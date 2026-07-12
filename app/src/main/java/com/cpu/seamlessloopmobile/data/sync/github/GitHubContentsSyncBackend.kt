package com.cpu.seamlessloopmobile.data.sync.github

import com.cpu.seamlessloopmobile.data.sync.GitHubSyncConfig
import com.cpu.seamlessloopmobile.data.sync.GitHubSnapshotRemote
import com.cpu.seamlessloopmobile.data.sync.GitHubTokenProvider
import com.cpu.seamlessloopmobile.data.sync.SyncBackend
import com.cpu.seamlessloopmobile.data.sync.SyncErrorCode
import com.cpu.seamlessloopmobile.data.sync.SyncReport
import com.cpu.seamlessloopmobile.data.sync.SyncResult
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshot
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSerializer
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSummary
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * 基于 GitHub Contents API 的同步后端实现。
 *
 * 使用单文件策略：将序列化后的 SyncSnapshot 以 base64 编码存储到
 * 仓库指定路径（[GitHubSyncConfig.path]），利用 GitHub 文件 SHA
 * 实现乐观锁冲突检测。
 */
class GitHubContentsSyncBackend(
    private val config: GitHubSyncConfig,
    private val tokenProvider: GitHubTokenProvider,
    private val serializer: SyncSnapshotSerializer = SyncSnapshotSerializer(),
    private val client: OkHttpClient = OkHttpClient()
) : SyncBackend, GitHubSnapshotRemote {

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val ERROR_MESSAGE_MAX_LENGTH = 200
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val contentUrl: String
        get() = "$GITHUB_API_BASE/repos/${config.owner}/${config.repo}/contents/${config.path}"

    // -------------------------------------------------------------------
    // SyncBackend 接口
    // -------------------------------------------------------------------

    override suspend fun uploadSnapshot(
        snapshot: SyncSnapshot,
        expectedRevision: String?
    ): SyncResult = withContext(Dispatchers.IO) {
        val token = tokenProvider.getToken()
            ?: return@withContext SyncResult.Failure(
                "GitHub token not configured",
                code = SyncErrorCode.NOT_CONFIGURED
            )

        try {
            val json = serializer.serialize(snapshot)
            val base64Content = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

            val bodyJson = buildPutBody(base64Content, expectedRevision)
            val requestBody = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(contentUrl)
                .put(requestBody)
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    in 200..299 -> parsePutResponse(responseBody)
                    401, 403 -> SyncResult.Failure(
                        "GitHub authentication failed (${response.code})",
                        code = SyncErrorCode.UNAUTHORIZED
                    )
                    404 -> SyncResult.Failure(
                        "Repository or path not found: ${config.owner}/${config.repo}/${config.path}",
                        code = SyncErrorCode.NOT_FOUND
                    )
                    409 -> SyncResult.Failure(
                        "Conflict: remote has changed since last sync",
                        code = SyncErrorCode.CONFLICT
                    )
                    422 -> SyncResult.Failure(
                        "Unprocessable entity: ${extractMessage(responseBody)}",
                        code = SyncErrorCode.CONFLICT
                    )
                    else -> SyncResult.Failure(
                        "GitHub API returned ${response.code}: ${extractMessage(responseBody)}",
                        code = SyncErrorCode.NETWORK
                    )
                }
            }
        } catch (e: IOException) {
            SyncResult.Failure("Network error during upload: ${e.message}", e, SyncErrorCode.NETWORK)
        }
    }

    override suspend fun downloadSnapshot(snapshotId: String?): SyncResult = withContext(Dispatchers.IO) {
        val token = tokenProvider.getToken()
            ?: return@withContext SyncResult.Failure(
                "GitHub token not configured",
                code = SyncErrorCode.NOT_CONFIGURED
            )

        try {
            val url = buildString {
                append(contentUrl)
                append("?ref=${config.branch}")
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    in 200..299 -> parseGetResponse(responseBody)
                    401, 403 -> SyncResult.Failure(
                        "GitHub authentication failed (${response.code})",
                        code = SyncErrorCode.UNAUTHORIZED
                    )
                    404 -> SyncResult.Failure(
                        "Snapshot file not found at ${config.path}",
                        code = SyncErrorCode.NOT_FOUND
                    )
                    else -> SyncResult.Failure(
                        "GitHub API returned ${response.code}: ${extractMessage(responseBody)}",
                        code = SyncErrorCode.NETWORK
                    )
                }
            }
        } catch (e: IOException) {
            SyncResult.Failure("Network error during download: ${e.message}", e, SyncErrorCode.NETWORK)
        }
    }

    override suspend fun listSnapshots(): SyncResult {
        val downloadResult = downloadSnapshot()
        return when (downloadResult) {
            is SyncResult.Success -> {
                val snapshot = downloadResult.snapshot
                    ?: return SyncResult.Success(SyncReport())
                val summary = SyncSnapshotSummary(
                    id = downloadResult.remoteRevision ?: "",
                    schemaVersion = snapshot.schemaVersion,
                    deviceId = snapshot.deviceId,
                    exportedAt = snapshot.exportedAt
                )
                SyncResult.Success(
                    report = SyncReport(),
                    snapshotSummaries = listOf(summary)
                )
            }
            is SyncResult.Failure -> {
                // 404 means no file yet → empty list
                if (downloadResult.code == SyncErrorCode.NOT_FOUND) {
                    SyncResult.Success(SyncReport())
                } else {
                    downloadResult
                }
            }
            is SyncResult.Cancelled -> downloadResult
        }
    }

    // -------------------------------------------------------------------
    // GitHub 专用操作
    // -------------------------------------------------------------------

    /**
     * 删除远程快照文件。
     * 先 GET 获取当前文件 SHA，再发送 DELETE 请求。
     * - 文件不存在 (404) → 视为幂等成功
     * - 成功 → 返回 Success，可能携带 commit SHA
     */
    override suspend fun deleteSnapshot(): SyncResult = withContext(Dispatchers.IO) {
        val token = tokenProvider.getToken()
            ?: return@withContext SyncResult.Failure(
                "GitHub token not configured",
                code = SyncErrorCode.NOT_CONFIGURED
            )
        try {
            // 1. GET current file to obtain SHA
            val getUrl = "$contentUrl?ref=${config.branch}"
            val getRequest = Request.Builder()
                .url(getUrl)
                .get()
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .build()

            client.newCall(getRequest).execute().use { getResponse ->
                val getBody = getResponse.body?.string() ?: ""
                when (getResponse.code) {
                    in 200..299 -> {
                        val sha = try {
                            JsonParser.parseString(getBody).asJsonObject.get("sha")?.asString
                        } catch (e: Exception) {
                            null
                        }
                        if (sha == null) {
                            return@withContext SyncResult.Failure(
                                "Missing SHA in GET response for delete",
                                code = SyncErrorCode.INVALID_REMOTE
                            )
                        }
                        // 2. DELETE with SHA
                        val deleteBody = JsonObject().apply {
                            addProperty("message", "Delete sync snapshot")
                            addProperty("sha", sha)
                            addProperty("branch", config.branch)
                        }
                        val requestBody = deleteBody.toString().toRequestBody(JSON_MEDIA_TYPE)
                        val deleteRequest = Request.Builder()
                            .url(contentUrl)
                            .delete(requestBody)
                            .header("Accept", "application/vnd.github+json")
                            .header("Authorization", "Bearer $token")
                            .build()

                        client.newCall(deleteRequest).execute().use { response ->
                            val respBody = response.body?.string()
                            when (response.code) {
                                in 200..299 -> {
                                    val revSha = respBody?.let { parseDeleteCommitSha(it) }
                                    SyncResult.Success(SyncReport(), remoteRevision = revSha)
                                }
                                401, 403 -> SyncResult.Failure(
                                    "GitHub auth failed (${response.code})",
                                    code = SyncErrorCode.UNAUTHORIZED
                                )
                                404 -> SyncResult.Success(SyncReport())
                                409, 422 -> SyncResult.Failure(
                                    "Conflict during delete: ${extractMessage(respBody ?: "")}",
                                    code = SyncErrorCode.CONFLICT
                                )
                                else -> SyncResult.Failure(
                                    "GitHub DELETE returned ${response.code}",
                                    code = SyncErrorCode.NETWORK
                                )
                            }
                        }
                    }
                    401, 403 -> SyncResult.Failure(
                        "GitHub auth failed (${getResponse.code})",
                        code = SyncErrorCode.UNAUTHORIZED
                    )
                    404 -> SyncResult.Success(SyncReport()) // 幂等删除
                    else -> SyncResult.Failure(
                        "GitHub GET returned ${getResponse.code}: ${extractMessage(getBody)}",
                        code = SyncErrorCode.NETWORK
                    )
                }
            }
        } catch (e: IOException) {
            SyncResult.Failure("Network error during delete: ${e.message}", e, SyncErrorCode.NETWORK)
        }
    }

    // -------------------------------------------------------------------
    // 内部辅助方法
    // -------------------------------------------------------------------

    /**
     * 构建 PUT 请求的 JSON body。
     */
    private fun buildPutBody(
        base64Content: String,
        expectedRevision: String?
    ): com.google.gson.JsonObject {
        val body = com.google.gson.JsonObject()
        body.addProperty("message", "Sync snapshot update")
        body.addProperty("content", base64Content)
        body.addProperty("branch", config.branch)
        expectedRevision?.let { body.addProperty("sha", it) }
        return body
    }

    /**
     * 解析 GET 成功响应，提取 base64 content、sha、decoded snapshot。
     */
    private fun parseGetResponse(responseBody: String): SyncResult {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject

            val sha = root.get("sha")?.asString
                ?: return SyncResult.Failure(
                    "Missing SHA in GitHub response",
                    code = SyncErrorCode.INVALID_REMOTE
                )

            val contentBase64 = root.get("content")?.asString
                ?: return SyncResult.Failure(
                    "Missing content in GitHub response",
                    code = SyncErrorCode.INVALID_REMOTE
                )

            val normalizedBase64 = contentBase64.replace("\n", "").replace("\r", "")
            val decodedBytes = Base64.getDecoder().decode(normalizedBase64)
            val decodedJson = decodedBytes.toString(Charsets.UTF_8)

            val snapshot = try {
                serializer.deserialize(decodedJson)
            } catch (e: Exception) {
                return SyncResult.Failure(
                    "Failed to parse snapshot from GitHub: ${e.message}",
                    e,
                    SyncErrorCode.INVALID_REMOTE
                )
            }

            SyncResult.Success(
                report = SyncReport(),
                snapshot = snapshot,
                remoteRevision = sha
            )
        } catch (e: Exception) {
            SyncResult.Failure(
                "Invalid JSON in GitHub response: ${e.message}",
                e,
                SyncErrorCode.INVALID_REMOTE
            )
        }
    }

    /**
     * 解析 PUT 成功响应，提取新 SHA。
     */
    private fun parsePutResponse(responseBody: String): SyncResult {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject

            // 优先从 content.sha 获取文件 SHA，回退到 commit.sha
            val sha = root.getAsJsonObject("content")?.get("sha")?.asString
                ?: root.getAsJsonObject("commit")?.get("sha")?.asString
                ?: return SyncResult.Failure(
                    "Missing content.sha and commit.sha in PUT response",
                    code = SyncErrorCode.INVALID_REMOTE
                )

            SyncResult.Success(
                report = SyncReport(),
                remoteRevision = sha
            )
        } catch (e: Exception) {
            SyncResult.Failure(
                "Invalid JSON in PUT response: ${e.message}",
                e,
                SyncErrorCode.INVALID_REMOTE
            )
        }
    }

    /**
     * 从 DELETE 成功响应中提取 commit SHA（可选 fallback）。
     */
    private fun parseDeleteCommitSha(responseBody: String): String? {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            root.getAsJsonObject("commit")?.get("sha")?.asString
        } catch (e: Exception) { null }
    }

    /**
     * 从 GitHub API 错误响应体中提取 message 字段。
     */
    private fun extractMessage(responseBody: String): String {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            root.get("message")?.asString ?: responseBody.take(ERROR_MESSAGE_MAX_LENGTH)
        } catch (e: Exception) {
            responseBody.take(ERROR_MESSAGE_MAX_LENGTH)
        }
    }
}

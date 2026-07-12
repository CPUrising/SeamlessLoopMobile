package com.cpu.seamlessloopmobile.data.sync

import android.content.Context
import java.util.UUID

/**
 * 自动同步开关存储接口。
 */
interface AutoSyncEnabledStore {
    /** 自动同步当前是否开启，默认 false。 */
    suspend fun isAutoSyncEnabled(): Boolean

    /** 设置自动同步开关状态。 */
    suspend fun setAutoSyncEnabled(enabled: Boolean)
}

/**
 * SharedPreferences 驱动的 GitHub token、配置及同步元数据存储。
 *
 * ⚠️ 当前使用明文存储 token（MODE_PRIVATE），仅作为 MVP/开发实现。
 * TODO: 替换为 EncryptedSharedPreferences 或 Android KeyStore。
 *      参见 https://developer.android.com/privacy-and-security/security-crypto
 */
class SharedPreferencesGitHubSyncStore(context: Context) :
    GitHubTokenStore,
    GitHubSyncConfigStore,
    SyncMetadataStore,
    AutoSyncEnabledStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------
    // GitHubTokenStore
    // -----------------------------------------------------------------------

    override suspend fun getToken(): String? =
        prefs.getString(KEY_TOKEN, null)

    override suspend fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override suspend fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    // -----------------------------------------------------------------------
    // GitHubSyncConfigStore
    // -----------------------------------------------------------------------

    override suspend fun getConfig(): GitHubSyncConfig? {
        val owner = prefs.getString(KEY_REPO_OWNER, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val repo = prefs.getString(KEY_REPO_NAME, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val branch = prefs.getString(KEY_BRANCH, null)?.takeIf { it.isNotBlank() }
            ?: GitHubSyncConfig.DEFAULT_BRANCH
        val path = prefs.getString(KEY_PATH, null)?.takeIf { it.isNotBlank() }
            ?: GitHubSyncConfig.DEFAULT_PATH
        return GitHubSyncConfig(
            owner = owner,
            repo = repo,
            branch = branch,
            path = path
        )
    }

    override suspend fun saveConfig(config: GitHubSyncConfig) {
        prefs.edit()
            .putString(KEY_REPO_OWNER, config.owner)
            .putString(KEY_REPO_NAME, config.repo)
            .putString(KEY_BRANCH, config.branch)
            .putString(KEY_PATH, config.path)
            .apply()
    }

    override suspend fun clearConfig() {
        prefs.edit()
            .remove(KEY_REPO_OWNER)
            .remove(KEY_REPO_NAME)
            .remove(KEY_BRANCH)
            .remove(KEY_PATH)
            .apply()
    }

    // -----------------------------------------------------------------------
    // 自动同步开关
    // -----------------------------------------------------------------------

    override suspend fun isAutoSyncEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)

    override suspend fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    // -----------------------------------------------------------------------
    // SyncMetadataStore
    // -----------------------------------------------------------------------

    override suspend fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    override suspend fun getLastSyncTime(): Long =
        prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    override suspend fun getLastRemoteRevision(): String? =
        prefs.getString(KEY_LAST_REMOTE_REVISION, null)

    override suspend fun getMutationVersion(): Int =
        prefs.getInt(KEY_MUTATION_VERSION, 0)

    override suspend fun markMutation() {
        val next = getMutationVersion() + 1
        prefs.edit().putInt(KEY_MUTATION_VERSION, next).apply()
    }

    override suspend fun saveSuccessfulSync(remoteRevision: String, syncTime: Long) {
        prefs.edit()
            .putString(KEY_LAST_REMOTE_REVISION, remoteRevision)
            .putLong(KEY_LAST_SYNC_TIME, syncTime)
            .apply()
    }

    override suspend fun clearSyncMetadata() {
        prefs.edit()
            .remove(KEY_LAST_REMOTE_REVISION)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "github_sync_prefs"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_REPO_OWNER = "repo_owner"
        private const val KEY_REPO_NAME = "repo_name"
        private const val KEY_BRANCH = "branch"
        private const val KEY_PATH = "path"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_REMOTE_REVISION = "last_remote_revision"
        private const val KEY_MUTATION_VERSION = "mutation_version"

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }
    }
}

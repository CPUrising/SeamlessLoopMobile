package com.cpu.seamlessloopmobile.data.sync

/**
 * GitHub Personal Access Token 提供者接口。
 * 实现类从安全存储（当前 MVP 使用 SharedPreferences）获取 token。
 */
interface GitHubTokenProvider {
    /** 获取当前 GitHub PAT，未配置时返回 null。 */
    suspend fun getToken(): String?
}

/**
 * GitHub Token 存储接口，扩展 [GitHubTokenProvider] 以支持写入/清除。
 */
interface GitHubTokenStore : GitHubTokenProvider {
    /** 持久化 token。 */
    suspend fun saveToken(token: String)

    /** 清除已存储的 token。 */
    suspend fun clearToken()
}

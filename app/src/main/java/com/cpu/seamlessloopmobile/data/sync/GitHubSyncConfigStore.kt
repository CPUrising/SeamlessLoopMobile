package com.cpu.seamlessloopmobile.data.sync

/**
 * GitHub 同步配置存储接口。
 *
 * UI 配置页后续会通过该接口保存 owner/repo/branch/path，后端实例化时读取它。
 */
interface GitHubSyncConfigStore {
    /** 获取当前 GitHub 同步配置；未配置时返回 null。 */
    suspend fun getConfig(): GitHubSyncConfig?

    /** 保存 GitHub 同步配置。 */
    suspend fun saveConfig(config: GitHubSyncConfig)

    /** 清除 GitHub 同步配置。 */
    suspend fun clearConfig()
}

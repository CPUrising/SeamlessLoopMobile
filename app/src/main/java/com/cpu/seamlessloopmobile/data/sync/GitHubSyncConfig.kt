package com.cpu.seamlessloopmobile.data.sync

/**
 * GitHub 同步配置。
 *
 * Token 不放在这里，由 [GitHubTokenProvider] 单独提供，避免配置对象在 UI 层传递时泄露凭据。
 */
data class GitHubSyncConfig(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    val path: String = "seamless-loop/sync.json"
) {
    companion object {
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_PATH = "seamless-loop/sync.json"
    }
}

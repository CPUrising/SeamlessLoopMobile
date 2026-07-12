package com.cpu.seamlessloopmobile.data.sync

/**
 * 结构化错误码，对应不同同步场景。
 */
enum class SyncErrorCode {
    /** GitHub token 未配置。 */
    NOT_CONFIGURED,
    /** 认证/授权失败（401/403）。 */
    UNAUTHORIZED,
    /** 远程资源不存在（404）。 */
    NOT_FOUND,
    /** 远程已有更新，需要重试（409/422）。 */
    CONFLICT,
    /** 远程数据格式异常，解析失败。 */
    INVALID_REMOTE,
    /** 网络 IO 错误。 */
    NETWORK,
    /** 未知错误。 */
    UNKNOWN
}

/**
 * 表示同步过程中检测到的一个具体冲突。
 * 可用于 UI 展示或日志记录。
 */
data class SyncConflict(
    val playlistName: String? = null,
    val songIdentity: SyncSongIdentity? = null,
    val field: String? = null,
    val remoteValue: String? = null,
    val localValue: String? = null,
    val resolution: String? = null
)

/**
 * 同步操作的详细报告。
 * 包含上传/下载数量和冲突详情。
 */
data class SyncReport(
    val playlistsUploaded: Int = 0,
    val playlistsDownloaded: Int = 0,
    val loopPointsUploaded: Int = 0,
    val loopPointsDownloaded: Int = 0,
    val ratingsUploaded: Int = 0,
    val ratingsDownloaded: Int = 0,
    val conflicts: List<SyncConflict> = emptyList()
)

/**
 * 同步操作的结果密封类。
 * - Success：操作成功，附带 SyncReport 及可能的 SyncSnapshot
 * - Failure：操作失败，附错误信息及结构化错误码
 * - Cancelled：操作被用户取消
 */
sealed class SyncResult {

    /**
     * @param report 同步报告
     * @param snapshot 下载操作成功时携带的快照数据
     * @param snapshotSummaries 列表操作成功时携带的远端快照摘要
     * @param remoteRevision 远程资源版本标识（GitHub SHA 等），用于后续乐观锁
     */
    data class Success(
        val report: SyncReport,
        val snapshot: SyncSnapshot? = null,
        val snapshotSummaries: List<SyncSnapshotSummary> = emptyList(),
        val remoteRevision: String? = null
    ) : SyncResult()

    /**
     * @param message 人类可读的错误信息
     * @param throwable 原始异常（可选）
     * @param code 结构化错误码
     */
    data class Failure(
        val message: String,
        val throwable: Throwable? = null,
        val code: SyncErrorCode = SyncErrorCode.UNKNOWN
    ) : SyncResult()

    data object Cancelled : SyncResult()
}

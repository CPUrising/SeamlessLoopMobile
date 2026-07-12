package com.cpu.seamlessloopmobile.data.sync

/**
 * 通用同步后端接口。
 * 实现类应负责与远程存储（GitHub、WebDAV 等）的通信。
 * 方法命名保持通用，不绑定任何特定后端。
 */
interface SyncBackend {

    /**
     * 将本地快照上传到远程后端。
     * @param snapshot 要上传的完整快照
     * @param expectedRevision 可选乐观锁参数（如 GitHub 文件 SHA），
     *        为 null 时由后端按自身语义创建新文件
     * @return 操作结果，成功时关联 SyncReport 及新 remoteRevision
     */
    suspend fun uploadSnapshot(
        snapshot: SyncSnapshot,
        expectedRevision: String? = null
    ): SyncResult

    /**
     * 从远程后端下载指定快照，未指定时下载最新快照。
     * @param snapshotId 可选的远端快照 ID
     * @return 操作结果，成功时携带 SyncSnapshot 及 remoteRevision
     */
    suspend fun downloadSnapshot(snapshotId: String? = null): SyncResult

    /**
     * 列出远程后端可用的快照摘要。
     * @return 操作结果，成功时携带快照列表
     */
    suspend fun listSnapshots(): SyncResult
}

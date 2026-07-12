package com.cpu.seamlessloopmobile.data.sync

/**
 * 本地快照导出/导入抽象。
 *
 * 实现类负责从本地数据仓库构建 [SyncSnapshot]，
 * 以及将合并后的快照写回本地存储。
 */
interface SyncSnapshotStore {

    /**
     * 导出当前本地数据为同步快照。
     * @param deviceId 设备标识符
     * @param now 导出时间戳（ms），便于测试固定时间
     * @return 包含播放列表、循环点、评分的完整快照
     */
    suspend fun exportSnapshot(
        deviceId: String,
        now: Long = System.currentTimeMillis()
    ): SyncSnapshot

    /**
     * 将合并后的快照写回本地存储。
     * @param snapshot 待应用的合并后快照
     * @param trackLocalMutation Whether this externally initiated apply should advance the
     * local mutation fence. Coordinators disable it for their own merged apply so concurrent
     * local writes remain distinguishable.
     * @return 应用操作的详细报告
     */
    suspend fun applySnapshot(snapshot: SyncSnapshot, trackLocalMutation: Boolean = true): SyncReport
}

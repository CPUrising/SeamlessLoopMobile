package com.cpu.seamlessloopmobile.data.sync

/**
 * 同步元数据持久化接口。
 * 追踪本地设备标识、上次同步时间、远程修订版本及本地变更版本号。
 */
interface SyncMetadataStore {

    /** 获取本地设备标识符（首次自动生成并持久化）。 */
    suspend fun getDeviceId(): String

    /** 上次成功同步的墙钟时间戳（ms）。 */
    suspend fun getLastSyncTime(): Long

    /** 上次成功同步后记录的远程资源修订版本（如 GitHub 文件的 SHA）。 */
    suspend fun getLastRemoteRevision(): String?

    /**
     * 当前本地数据的变更版本号。
     * 每次本地数据发生用户可见的修改（导入、评分、歌单变更等）时递增。
     * 用于在同步期间检测本地并发修改。
     */
    suspend fun getMutationVersion(): Int

    /** 标记一次本地数据变更（递增 mutationVersion）。 */
    suspend fun markMutation()

    /**
     * 记录一次成功的同步结果。
     * @param remoteRevision 同步成功后远程端的最新修订版本
     * @param syncTime 同步完成时的墙钟时间戳（ms）
     */
    suspend fun saveSuccessfulSync(remoteRevision: String, syncTime: Long)

    /**
     * 清除同步元数据（lastRemoteRevision 和 lastSyncTime）。
     * 不修改 deviceId、token、config、mutationVersion。
     */
    suspend fun clearSyncMetadata()
}

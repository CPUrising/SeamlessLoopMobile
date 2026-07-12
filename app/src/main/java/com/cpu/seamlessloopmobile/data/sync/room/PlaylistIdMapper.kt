package com.cpu.seamlessloopmobile.data.sync.room

/**
 * 歌单同步 ID 映射记录。
 * @property syncId 跨设备一致的 UUID 字符串
 * @property localId 本地 Room Playlist 表主键
 * @property modifiedAt 上次导出时的修改时间戳（ms）
 * @property fingerprint 歌单内容的哈希指纹
 */
data class PlaylistSyncRecord(
    val syncId: String,
    val localId: Int,
    val modifiedAt: Long,
    val fingerprint: String
)

/**
 * 歌单同步 ID 映射器接口。
 *
 * 负责在本地 Room 主键 (Int) 和跨设备可移植的 syncId (UUID string)
 * 之间建立双向映射，同时追踪歌单内容和修改时间。
 */
interface PlaylistIdMapper {

    /**
     * 获取或创建 syncId 用于导出。
     * - 如果 localId 已有映射且 fingerprint 一致 → 返回现有记录，modifiedAt 不变。
     * - 如果 fingerprint 变脏 → 更新 modifiedAt 为 now。
     * - 如果 localId 尚无映射 → 生成新的 UUID syncId，modifiedAt = now。
     */
    suspend fun getOrCreateSyncIdForExport(
        localId: Int,
        fingerprint: String,
        now: Long
    ): PlaylistSyncRecord

    /** 通过 syncId 查找本地主键；未找到时返回 null。 */
    suspend fun findLocalId(syncId: String): Int?

    /** 通过本地主键查找 syncId；未找到时返回 null。 */
    suspend fun findSyncId(localId: Int): String?

    /** 保存或更新一条映射记录。 */
    suspend fun saveMapping(
        syncId: String,
        localId: Int,
        modifiedAt: Long,
        fingerprint: String
    )

    /**
     * 清理已不存在的本地歌单对应映射记录。
     * @param validLocalIds 当前数据库中仍存在的所有本地歌单 ID 集合
     */
    suspend fun removeStaleMappings(validLocalIds: Set<Int>)

    /** 清除所有映射记录。 */
    suspend fun clearAllMappings()
}

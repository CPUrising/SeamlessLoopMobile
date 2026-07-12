package com.cpu.seamlessloopmobile.data.sync.room

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * SharedPreferences 驱动的 PlaylistIdMapper 实现。
 *
 * 将 [PlaylistMappingRecord] 列表以 JSON 格式存储在 SharedPreferences 中。
 */
class SharedPreferencesPlaylistIdMapper(
    context: Context,
    private val gson: Gson = Gson()
) : PlaylistIdMapper {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------
    // PlaylistIdMapper
    // -------------------------------------------------------------------

    override suspend fun getOrCreateSyncIdForExport(
        localId: Int,
        fingerprint: String,
        now: Long
    ): PlaylistSyncRecord {
        val records = loadRecords().toMutableList()
        val existing = records.find { it.localId == localId }

        return if (existing != null) {
            if (existing.fingerprint != fingerprint) {
                // 内容变化 → 更新 modifiedAt
                val updated = existing.copy(modifiedAt = now, fingerprint = fingerprint)
                records.removeAll { it.localId == localId }
                records.add(updated)
                saveRecords(records)
                PlaylistSyncRecord(
                    syncId = existing.syncId,
                    localId = localId,
                    modifiedAt = now,
                    fingerprint = fingerprint
                )
            } else {
                // 指纹一致 → 返回现有记录，modifiedAt 不变
                PlaylistSyncRecord(
                    syncId = existing.syncId,
                    localId = localId,
                    modifiedAt = existing.modifiedAt,
                    fingerprint = existing.fingerprint
                )
            }
        } else {
            // 新建映射
            val syncId = UUID.randomUUID().toString()
            val record = PlaylistMappingRecord(
                syncId = syncId,
                localId = localId,
                modifiedAt = now,
                fingerprint = fingerprint
            )
            records.add(record)
            saveRecords(records)
            PlaylistSyncRecord(
                syncId = syncId,
                localId = localId,
                modifiedAt = now,
                fingerprint = fingerprint
            )
        }
    }

    override suspend fun findLocalId(syncId: String): Int? {
        return loadRecords().find { it.syncId == syncId }?.localId
    }

    override suspend fun findSyncId(localId: Int): String? {
        return loadRecords().find { it.localId == localId }?.syncId
    }

    override suspend fun saveMapping(
        syncId: String,
        localId: Int,
        modifiedAt: Long,
        fingerprint: String
    ) {
        val records = loadRecords().toMutableList()
        val existing = records.find { it.syncId == syncId }
        if (existing != null) {
            records.remove(existing)
        }
        records.removeAll { it.localId == localId || it.syncId == syncId }
        records.add(PlaylistMappingRecord(syncId, localId, modifiedAt, fingerprint))
        saveRecords(records)
    }

    override suspend fun removeStaleMappings(validLocalIds: Set<Int>) {
        val records = loadRecords().filter { it.localId in validLocalIds }
        saveRecords(records)
    }

    override suspend fun clearAllMappings() {
        prefs.edit().remove(KEY_MAPPINGS).apply()
    }

    // -------------------------------------------------------------------
    // 内部存储
    // -------------------------------------------------------------------

    /** 内部 JSON 持久化记录。 */
    private data class PlaylistMappingRecord(
        val syncId: String,
        val localId: Int,
        val modifiedAt: Long,
        val fingerprint: String
    )

    private fun loadRecords(): List<PlaylistMappingRecord> {
        val json = prefs.getString(KEY_MAPPINGS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PlaylistMappingRecord>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveRecords(records: List<PlaylistMappingRecord>) {
        prefs.edit().putString(KEY_MAPPINGS, gson.toJson(records)).apply()
    }

    companion object {
        private const val PREFS_NAME = "playlist_sync_mappings"
        private const val KEY_MAPPINGS = "mappings"
    }
}

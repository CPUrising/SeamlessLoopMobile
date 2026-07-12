package com.cpu.seamlessloopmobile.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.cpu.seamlessloopmobile.jni.NativeAudio
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.SongMetadataUpdate
import com.cpu.seamlessloopmobile.model.LoopPoint
import com.cpu.seamlessloopmobile.model.UserRating
import com.cpu.seamlessloopmobile.model.Artist
import com.cpu.seamlessloopmobile.model.Album
import com.cpu.seamlessloopmobile.scanner.AudioScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** 扫描完成后给 UI 展示的摘要数据。 */
data class MusicScanResult(
    val songs: List<Song>,
    val addedCount: Int,
    val pathUpdatedCount: Int,
    val staleDeletedCount: Int
) {
    fun statusMessage(): String {
        val parts = mutableListOf<String>()
        if (addedCount > 0) parts.add("新增 ${addedCount} 首")
        if (pathUpdatedCount > 0) parts.add("更新路径 ${pathUpdatedCount} 首")
        if (staleDeletedCount > 0) parts.add("清理失效 ${staleDeletedCount} 首")
        return if (parts.isEmpty()) {
            "扫描完成：没有变化"
        } else {
            "扫描完成：${parts.joinToString(" ")}"
        }
    }
}

/**
 * 歌曲搜索雷达：专门负责扫描媒体库并进行 A-B 对检测喵！
 */
class MusicScannerRepository(private val songDao: SongDao) {

    private val totalSamplesEnrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    private companion object {
        const val TAG = "MusicScannerRepo"
        const val DURATION_MATCH_TOLERANCE_MS = 200L
        const val MEDIA_ID_VALIDATION_TOLERANCE_MS = 500L
        const val STALE_SCAN_THRESHOLD = 3
        const val STALE_PREFS_NAME = "music_scan_stale_counts"
        const val STALE_KEY_PREFIX = "song_"
        const val TOTAL_SAMPLE_ENRICH_BATCH_SIZE = 10
        const val TOTAL_SAMPLE_ENRICH_BATCH_DELAY_MS = 500L
    }

    /**
     * 历史接口保留。
     *
     * 不再在扫描前按 File.exists() 立即删除失效记录：移动/重命名文件需要先进入
     * getInitialScannedSongs() 完成匹配和路径迁移，否则会丢循环点、评分和歌单关联。
     * 持续缺失的记录现在由扫描后的 SharedPreferences 计数器延迟清理。
     */
    suspend fun cleanupStaleSongs(context: Context) = withContext(Dispatchers.IO) { 0 }

    /**
     * 初始同步扫描：极速批量版 (๑•̀ㅂ•́)و✧
     */
    suspend fun getInitialScannedSongs(context: Context): MusicScanResult = scanMutex.withLock {
        withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val scannedSongs = AudioScanner.scan(appContext)
        val scannedPathKeys = scannedSongs.mapNotNull { normalizePathKey(it.filePath) }.toSet()
        
        // 1. 加载包含 B 段在内的全量数据库记录喵！
        val allDbSongs = songDao.getAllSongsRaw()
        
        // 2. 只做“确定重复”的合并；不要在匹配前删除旧路径，否则移动/重命名会丢元数据。
        songDao.cleanDuplicateSongs(allDbSongs)
        
        // 3. 重新加载清理后的最新列表作为匹配基底
        val latestDbSongs = songDao.getAllSongsRaw()
        
        // 4. 构建多级强健匹配索引 Map 喵！(๑•̀ㅂ•́)و✧
        val dbSongsByPath = latestDbSongs.filter { it.filePath.isNotBlank() }
            .associateBy { normalizePathKey(it.filePath)!! }
        val dbSongsByMediaId = latestDbSongs.filter { it.mediaId != 0L }
            .groupBy { it.mediaId }
        val dbSongsBySamples = latestDbSongs.filter { it.totalSamples > 0L }
            .groupBy { samplesFingerprint(it.fileName, it.totalSamples) }
        val dbSongsBySamplesOnly = latestDbSongs.filter { it.totalSamples > 0L }
            .groupBy { it.totalSamples }
        val dbSongsByDuration = latestDbSongs.groupBy { durationFingerprint(it.fileName, it.duration) }
        val dbSongsByName = latestDbSongs.groupBy { normalizeNameKey(it.fileName) }
        
        // 5. 预处理：识别 AB 模式下的 B 段喵
        val abMarkedSongs = scannedSongs.map { song ->
            val isB = isLikelyAbPartB(song, scannedSongs)
            if (isB) song.copy(song = song.song.copy(isAbPartB = true)) else song
        }

        // === Artist/Album 批量预创建，构建 name→id 映射 (CPU 大人的 Zip 映射法) ===
        val ignoredNames = setOf("unknown artist", "unknown album", "<unknown>")
        val allArtistNames = abMarkedSongs
            .mapNotNull { it.artistEntity?.name }
            .filter { it.isNotBlank() && it.lowercase() !in ignoredNames }
            .toSet()
        val allAlbumNames = abMarkedSongs
            .mapNotNull { it.albumEntity?.name }
            .filter { it.isNotBlank() && it.lowercase() !in ignoredNames }
            .toSet()

        val artistMap = mutableMapOf<String, Long>()
        allArtistNames.forEach { name ->
            songDao.getArtistByName(name)?.let { artistMap[name.lowercase()] = it.id }
        }
        val missingArtists = allArtistNames.filter { artistMap[it.lowercase()] == null }
        if (missingArtists.isNotEmpty()) {
            val newIds = songDao.insertArtistsBatch(missingArtists.map { Artist(name = it) })
            missingArtists.zip(newIds).forEach { (name, id) -> artistMap[name.lowercase()] = id }
        }

        val albumMap = mutableMapOf<String, Long>()
        allAlbumNames.forEach { name ->
            songDao.getAlbumByName(name)?.let { albumMap[name.lowercase()] = it.id }
        }
        val missingAlbums = allAlbumNames.filter { albumMap[it.lowercase()] == null }
        if (missingAlbums.isNotEmpty()) {
            val newIds = songDao.insertAlbumsBatch(missingAlbums.map { Album(name = it) })
            missingAlbums.zip(newIds).forEach { (name, id) -> albumMap[name.lowercase()] = id }
        }
        // === 预创建结束 ===

        val insertList = mutableListOf<Song>()
        val updateList = mutableListOf<SongMetadataUpdate>()
        val result = mutableListOf<Song>()
        val seenResultPaths = mutableSetOf<String>()
        val seenInsertPaths = mutableSetOf<String>()
        val seenInsertFingerprints = mutableSetOf<String>()
        val matchedDbIds = mutableSetOf<Long>()
        val idsNeedingTotalSampleEnrichment = mutableSetOf<Long>()
        val exactSamplesByMediaId = mutableMapOf<Long, Long>()
        var pathUpdatedCount = 0

        fun queueInsert(song: Song) {
            val pathKey = normalizePathKey(song.filePath)
            val queued = if (pathKey != null) {
                seenInsertPaths.add(pathKey)
            } else {
                seenInsertFingerprints.add(durationFingerprint(song.fileName, song.duration))
            }
            if (queued) insertList.add(song)
        }

        abMarkedSongs.forEach { scanned ->
            var song = scanned
            val pathKey = normalizePathKey(song.filePath)
            if (pathKey != null && !seenResultPaths.add(pathKey)) return@forEach

            // 采用多级强健匹配：path → filename+samples → filename+duration → 弱 duration → mediaId 辅助。
            var dbSong: Song? = null
            if (pathKey != null) {
                dbSong = dbSongsByPath[pathKey]?.takeIf { it.id !in matchedDbIds }
                if (dbSong != null && closeDuration(dbSong.duration, song.duration) && song.totalSamples <= 0L && dbSong.totalSamples > 0L) {
                    song = song.copy(song = song.song.copy(totalSamples = dbSong.totalSamples))
                }
            }

            if (dbSong == null && song.totalSamples <= 0L) {
                val sameNameCandidates = dbSongsByName[normalizeNameKey(song.fileName)]
                    ?.filter { it.id !in matchedDbIds && it.totalSamples > 0L }
                val possibleRenamedMoveCandidates = if (sameNameCandidates.isNullOrEmpty()) {
                    latestDbSongs.filter { candidate ->
                        candidate.id !in matchedDbIds &&
                                candidate.totalSamples > 0L &&
                                closeDuration(candidate.duration, song.duration) &&
                                normalizePathKey(candidate.filePath)?.let { it in scannedPathKeys } != true
                    }
                } else {
                    emptyList()
                }
                if (!sameNameCandidates.isNullOrEmpty() || possibleRenamedMoveCandidates.isNotEmpty()) {
                    val exactSamples = readTotalSamplesForMedia(appContext, song.mediaId, exactSamplesByMediaId)
                    if (exactSamples > 0L) {
                        song = song.copy(song = song.song.copy(totalSamples = exactSamples))
                    }
                }
            }

            if (dbSong == null && song.totalSamples > 0L) {
                val candidates = dbSongsBySamples[samplesFingerprint(song.fileName, song.totalSamples)]
                    ?.filter { it.id !in matchedDbIds }
                if (candidates?.size == 1) dbSong = candidates.first()
            }

            // 文件被重命名并移动时，文件名维度失效；只在 exact samples 全库唯一且时长接近时迁移。
            if (dbSong == null && song.totalSamples > 0L) {
                val candidates = dbSongsBySamplesOnly[song.totalSamples]
                    ?.filter { it.id !in matchedDbIds && closeDuration(it.duration, song.duration) }
                if (candidates?.size == 1) dbSong = candidates.first()
            }

            if (dbSong == null) {
                val candidates = dbSongsByDuration[durationFingerprint(song.fileName, song.duration)]
                    ?.filter { it.id !in matchedDbIds }
                if (candidates?.size == 1) dbSong = candidates.first()
            }

            // 同名且时长小容差比对：只有唯一候选才合并，避免“第一条命中”误迁移。
            if (dbSong == null) {
                val candidates = dbSongsByName[normalizeNameKey(song.fileName)]
                    ?.filter { it.id !in matchedDbIds && closeDuration(it.duration, song.duration) }
                if (candidates?.size == 1) dbSong = candidates.first()
            }

            // mediaId 不再作为高优先级身份，只在最后用 filename / duration / samples 做二次校验。
            if (dbSong == null && song.mediaId != 0L) {
                val candidates = dbSongsByMediaId[song.mediaId]
                    ?.filter { it.id !in matchedDbIds }
                if (candidates?.size == 1) {
                    val candidate = candidates.first()
                    val nameMatches = candidate.fileName.equals(song.fileName, ignoreCase = true)
                    val durationClose = abs(candidate.duration - song.duration) <= MEDIA_ID_VALIDATION_TOLERANCE_MS
                    val samplesMatch = candidate.totalSamples > 0L && candidate.totalSamples == song.totalSamples
                    if (nameMatches || durationClose || samplesMatch) dbSong = candidate
                }
            }

            if (dbSong != null) {
                val oldPathKey = normalizePathKey(dbSong.filePath)
                val oldPathStillExists = oldPathKey != null && oldPathKey in scannedPathKeys
                val isSamePath = oldPathKey != null && oldPathKey == pathKey

                if (!isSamePath && oldPathStillExists) {
                    // 老路径仍在本次 MediaStore 快照里：这是复制，不是移动。保留旧歌，给新路径单独入库。
                    queueInsert(song)
                    return@forEach
                }

                // 情况 A：老面孔，准备批量元数据更新
                // 数据库已有的 artistId/albumId 优先（PC 同步数据），null 时用 MediaStore 扫描结果补充
                val resolvedArtistId = dbSong.song.artistId
                    ?: song.artistEntity?.name?.lowercase()?.let { artistMap[it] }
                val resolvedAlbumId = dbSong.song.albumId
                    ?: song.albumEntity?.name?.lowercase()?.let { albumMap[it] }
                val resolvedMediaId = if (song.mediaId != 0L) song.mediaId else dbSong.mediaId
                val resolvedTotalSamples = song.totalSamples.takeIf { it > 0L } ?: dbSong.totalSamples
                val resolvedCoverPath = song.coverPath ?: dbSong.coverPath
                val resolvedMimeType = song.mimeType ?: dbSong.mimeType
                val resolvedSampleRateHz = song.sampleRateHz ?: dbSong.sampleRateHz
                val resolvedBitrateKbps = song.bitrateKbps ?: dbSong.bitrateKbps

                matchedDbIds.add(dbSong.id)

                val needLocationUpdate = dbSong.fileName != song.fileName ||
                        dbSong.filePath != song.filePath ||
                        dbSong.mediaId != resolvedMediaId ||
                        dbSong.duration != song.duration ||
                        dbSong.totalSamples != resolvedTotalSamples ||
                        dbSong.coverPath != resolvedCoverPath ||
                        dbSong.mimeType != resolvedMimeType ||
                        dbSong.sampleRateHz != resolvedSampleRateHz ||
                        dbSong.bitrateKbps != resolvedBitrateKbps
                val pathOrNameChanged = dbSong.fileName != song.fileName || dbSong.filePath != song.filePath

                if (needLocationUpdate) {
                    songDao.updateSongLocationFields(
                        id = dbSong.id,
                        fileName = song.fileName,
                        filePath = song.filePath,
                        mediaId = resolvedMediaId,
                        duration = song.duration,
                        totalSamples = resolvedTotalSamples,
                        coverPath = resolvedCoverPath,
                        mimeType = resolvedMimeType,
                        sampleRateHz = resolvedSampleRateHz,
                        bitrateKbps = resolvedBitrateKbps,
                        lastModified = System.currentTimeMillis()
                    )
                    if (pathOrNameChanged) pathUpdatedCount++
                    Log.d(TAG, "迁移/刷新歌曲位置：${dbSong.filePath} -> ${song.filePath}")
                }

                if (resolvedTotalSamples <= 0L) {
                    idsNeedingTotalSampleEnrichment.add(dbSong.id)
                }

                // 性能极致优化看门狗：只有当歌手关联、专辑关联或 AB 段状态发生实际改变时，才刷写写盘更新喵！
                val needUpdate = dbSong.song.artistId != resolvedArtistId ||
                        dbSong.song.albumId != resolvedAlbumId ||
                        dbSong.coverPath != resolvedCoverPath ||
                        dbSong.mimeType != resolvedMimeType ||
                        dbSong.sampleRateHz != resolvedSampleRateHz ||
                        dbSong.bitrateKbps != resolvedBitrateKbps ||
                        dbSong.song.isAbPartB != song.isAbPartB

                if (needUpdate) {
                    updateList.add(SongMetadataUpdate(
                        songId = dbSong.id,
                        total = resolvedTotalSamples,
                        start = dbSong.loopStart,
                        end = dbSong.loopEnd,
                        rating = dbSong.rating,
                        artistId = resolvedArtistId,
                        albumId = resolvedAlbumId,
                        displayName = dbSong.displayName,
                        coverPath = resolvedCoverPath,
                        mimeType = resolvedMimeType,
                        sampleRateHz = resolvedSampleRateHz,
                        bitrateKbps = resolvedBitrateKbps,
                        isAbPartB = song.isAbPartB
                    ))
                }
                result.add(song.copy(song = song.song.copy(
                    id = dbSong.id,
                    mediaId = resolvedMediaId,
                    totalSamples = resolvedTotalSamples,
                    coverPath = resolvedCoverPath,
                    mimeType = resolvedMimeType,
                    sampleRateHz = resolvedSampleRateHz,
                    bitrateKbps = resolvedBitrateKbps,
                    artistId = resolvedArtistId,
                    albumId = resolvedAlbumId
                )))
            } else {
                // 情况 B：新朋友，同一路径只入库一次；不同路径的同名同长文件视作真实拷贝。
                queueInsert(song)
            }
        }

        // 2. 批量提交 (Atomic Batch)
        if (insertList.isNotEmpty()) {
            // 回填 artistId/albumId 到 SongEntity，确保入库时就有正确的关联喵！
            val entities = insertList.map { song ->
                val aId = song.artistEntity?.name?.lowercase()?.let { artistMap[it] }
                val alId = song.albumEntity?.name?.lowercase()?.let { albumMap[it] }
                song.song.copy(artistId = aId, albumId = alId)
            }
            val newIds = songDao.insertSongsBatch(entities)
            
            val newLoopPoints = mutableListOf<LoopPoint>()
            val newUserRatings = mutableListOf<UserRating>()
            
            insertList.zip(newIds).forEach { (song, id) ->
                newLoopPoints.add(LoopPoint(songId = id, loopStart = song.loopStart, loopEnd = song.loopEnd))
                newUserRatings.add(UserRating(songId = id, rating = song.rating))
                if (song.totalSamples <= 0L) idsNeedingTotalSampleEnrichment.add(id)
                result.add(song.copy(song = song.song.copy(id = id)))
            }
            
            songDao.insertLoopPointsBatch(newLoopPoints)
            songDao.insertUserRatingsBatch(newUserRatings)
        }
        
        if (updateList.isNotEmpty()) {
            songDao.updateSongsMetadataBatch(updateList)
        }

        val deletedStaleCount = updateStaleCountsAfterScan(appContext, latestDbSongs, matchedDbIds, scannedPathKeys)
        if (deletedStaleCount > 0) {
            Log.d(TAG, "删除持续缺失歌曲 $deletedStaleCount 首")
        }

        enrichTotalSamplesInBackground(appContext, idsNeedingTotalSampleEnrichment)
        
        MusicScanResult(
            songs = result,
            addedCount = insertList.size,
            pathUpdatedCount = pathUpdatedCount,
            staleDeletedCount = deletedStaleCount
        )
        }
    }

    private fun normalizePathKey(path: String?): String? = path
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()

    private fun normalizeNameKey(name: String): String = name.lowercase()

    private fun durationFingerprint(fileName: String, duration: Long): String = "${normalizeNameKey(fileName)}|$duration"

    private fun samplesFingerprint(fileName: String, totalSamples: Long): String = "${normalizeNameKey(fileName)}|$totalSamples"

    private fun closeDuration(left: Long, right: Long): Boolean = abs(left - right) <= DURATION_MATCH_TOLERANCE_MS

    private fun readTotalSamplesForMedia(context: Context, mediaId: Long, cache: MutableMap<Long, Long>): Long {
        if (mediaId <= 0L) return 0L
        cache[mediaId]?.let { return it }
        val samples = try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val actualLength = if (afd.declaredLength < 0L) afd.length else afd.declaredLength
                NativeAudio.getAudioFileDuration(afd.parcelFileDescriptor.fd, afd.startOffset, actualLength)
            } ?: 0L
        } catch (t: Throwable) {
            Log.w(TAG, "读取总采样数失败 mediaId=$mediaId: ${t.message}")
            0L
        }
        cache[mediaId] = samples
        return samples
    }

    private suspend fun updateStaleCountsAfterScan(
        context: Context,
        dbSongsBeforeScan: List<Song>,
        matchedDbIds: Set<Long>,
        scannedPathKeys: Set<String>
    ): Int {
        // 权限异常/MediaStore 暂时为空时不要推进删除计数，避免误删整库。
        if (scannedPathKeys.isEmpty()) {
            Log.w(TAG, "本次扫描没有返回任何路径，跳过 stale 计数推进")
            return 0
        }

        val prefs = context.getSharedPreferences(STALE_PREFS_NAME, Context.MODE_PRIVATE)
        val existingIds = dbSongsBeforeScan.map { it.id }.toSet()
        val idsToReset = dbSongsBeforeScan
            .filter { it.id in matchedDbIds || normalizePathKey(it.filePath)?.let { path -> path in scannedPathKeys } == true }
            .map { it.id }
            .toSet()
        val staleCandidates = dbSongsBeforeScan.filter { song ->
            song.filePath.isNotBlank() &&
                    song.id !in idsToReset &&
                    normalizePathKey(song.filePath)?.let { it in scannedPathKeys } != true
        }
        val idsToDelete = mutableListOf<Long>()
        val editor = prefs.edit()

        idsToReset.forEach { editor.remove(staleKey(it)) }
        staleCandidates.forEach { song ->
            val key = staleKey(song.id)
            val newCount = prefs.getInt(key, 0) + 1
            if (newCount >= STALE_SCAN_THRESHOLD) {
                idsToDelete.add(song.id)
                editor.remove(key)
            } else {
                editor.putInt(key, newCount)
            }
        }

        val survivingIds = existingIds - idsToDelete.toSet()
        prefs.all.keys.forEach { key ->
            val id = staleIdFromKey(key)
            if (id == null || id !in survivingIds || id in idsToReset) editor.remove(key)
        }
        editor.apply()

        idsToDelete.chunked(500).forEach { batch -> songDao.deleteSongsByIds(batch) }
        return idsToDelete.size
    }

    private fun staleKey(songId: Long): String = "$STALE_KEY_PREFIX$songId"

    private fun staleIdFromKey(key: String): Long? = key
        .takeIf { it.startsWith(STALE_KEY_PREFIX) }
        ?.removePrefix(STALE_KEY_PREFIX)
        ?.toLongOrNull()

    private fun enrichTotalSamplesInBackground(context: Context, songIds: Set<Long>) {
        if (songIds.isEmpty()) return
        val appContext = context.applicationContext
        val ids = songIds.distinct()
        totalSamplesEnrichmentScope.launch {
            ids.chunked(TOTAL_SAMPLE_ENRICH_BATCH_SIZE).forEach { batch ->
                batch.forEach { id ->
                    val song = try {
                        songDao.getSongById(id)
                    } catch (t: Throwable) {
                        Log.w(TAG, "后台总采样数补全读取歌曲失败 id=$id: ${t.message}")
                        null
                    } ?: return@forEach

                    if (song.totalSamples > 0L || song.mediaId <= 0L) return@forEach
                    val samples = readTotalSamplesForMedia(appContext, song.mediaId, mutableMapOf())
                    if (samples > 0L) {
                        try {
                            songDao.updateSongTotalSamples(
                                id = song.id,
                                totalSamples = samples,
                                lastModified = System.currentTimeMillis()
                            )
                        } catch (t: Throwable) {
                            Log.w(TAG, "后台总采样数补全写库失败 id=${song.id}: ${t.message}")
                        }
                    }
                }
                delay(TOTAL_SAMPLE_ENRICH_BATCH_DELAY_MS)
            }
        }
    }

    /**
     * 判断这首歌是不是传说中隐身的 B 段喵
     */
    private fun isLikelyAbPartB(song: Song, allSongs: List<Song>): Boolean {
        val fileName = song.fileName.substringBeforeLast(".")
        val bSuffixes = arrayOf("_B", "_b", "_loop", "_Loop")
        val aSuffixes = arrayOf("_A", "_a", "_intro", "_Intro")
        
        for (i in bSuffixes.indices) {
            if (fileName.endsWith(bSuffixes[i])) {
                val baseName = fileName.substring(0, fileName.length - bSuffixes[i].length)
                val targetAName = baseName + aSuffixes[i]
                
                // 如果同文件夹下真的有个 A 存在，那它就是小跟班 B 没跑了喵！
                val hasA = allSongs.any { 
                    it.fileName.substringBeforeLast(".") == targetAName &&
                    File(it.filePath).parent == File(song.filePath).parent
                }
                if (hasA) return true
            }
        }
        return false
    }

    /**
     * 发现简单的 A-B 对
     */
    fun findAbPair(song: Song, allScannedSongs: List<Song>): Pair<Song, Song>? {
        val fileName = song.fileName.substringBeforeLast(".")
        val aSuffixes = arrayOf("_A", "_a", "_intro", "_Intro")
        val bSuffixes = arrayOf("_B", "_b", "_loop", "_Loop")

        for (i in aSuffixes.indices) {
            if (fileName.endsWith(aSuffixes[i])) {
                val baseName = fileName.substring(0, fileName.length - aSuffixes[i].length)
                val targetBName = baseName + bSuffixes[i]
                
                val partB = allScannedSongs.find { 
                    it.fileName.substringBeforeLast(".") == targetBName &&
                    File(it.filePath).parent == File(song.filePath).parent
                }
                if (partB != null) return Pair(song, partB)
            }
        }
        return null
    }

    /**
     * 强大的、健壮的 A-B 对搜索（即使没同步数据库也能找回灵魂伴侣喵）
     */
    suspend fun findAbPairRobust(context: Context, song: Song): Pair<Song, Song>? = withContext(Dispatchers.IO) {
        val fileName = song.fileName.substringBeforeLast(".")
        val aSuffixes = arrayOf("_A", "_a", "_intro", "_Intro")
        val bSuffixes = arrayOf("_B", "_b", "_loop", "_Loop")

        var bNameWithoutExt: String? = null
        for (i in aSuffixes.indices) {
            if (fileName.endsWith(aSuffixes[i])) {
                val baseName = fileName.substring(0, fileName.length - aSuffixes[i].length)
                bNameWithoutExt = baseName + bSuffixes[i]
                break
            }
        }
        
        if (bNameWithoutExt == null) return@withContext null
        val parentDir = File(song.filePath).parent ?: return@withContext null

        // --- 核心修复：必须查阅“生死簿”的全卷喵！ ---
        // 1. 同步数据库中寻找 (使用 unfiltered 版本)
        val dbSongs = songDao.getAllSongsRaw()
        val pB = dbSongs.find { 
            it.fileName.substringBeforeLast(".") == bNameWithoutExt &&
            File(it.filePath).parent == parentDir
        }
        if (pB != null) return@withContext Pair(song, pB)

        // 2. 数据库没找到，直接去 MediaStore 搜
        var partB: Song? = null
        try {
            val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                android.provider.MediaStore.Audio.Media.DATA
            )
            val selection = "${android.provider.MediaStore.Audio.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("$parentDir/%")
            
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val path = cursor.getString(dataCol)
                    if (name.substringBeforeLast(".") == bNameWithoutExt && File(path).parent == parentDir) {
                        partB = Song(
                            mediaId = cursor.getLong(idCol),
                            fileName = name,
                            filePath = path,
                            totalSamples = 0,
                            displayName = name.substringBeforeLast("."),
                            loopStart = 0,
                            loopEnd = 0,
                            duration = 0,
                            id = 0
                        )
                        break
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        if (partB != null) Pair(song, partB!!) else null
    }
}

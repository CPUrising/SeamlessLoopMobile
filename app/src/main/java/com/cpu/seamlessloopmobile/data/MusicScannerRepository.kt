package com.cpu.seamlessloopmobile.data

import android.content.Context
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.SongMetadataUpdate
import com.cpu.seamlessloopmobile.model.LoopPoint
import com.cpu.seamlessloopmobile.model.UserRating
import com.cpu.seamlessloopmobile.model.Artist
import com.cpu.seamlessloopmobile.model.Album
import com.cpu.seamlessloopmobile.scanner.AudioScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌曲搜索雷达：专门负责扫描媒体库并进行 A-B 对检测喵！
 */
class MusicScannerRepository(private val songDao: SongDao) {

    /**
     * 大扫除：清理掉那些有路径但文件已消失的失效记录喵！
     * 注意：为了安全，如果没有路径（PC同步过来的）或者存储根目录不可达，莱芙会跳过它们喵。
     */
    suspend fun cleanupStaleSongs(context: Context) = withContext(Dispatchers.IO) {
        val allSongs = songDao.getAllSongsRaw()
        val staleIds = mutableListOf<Long>()
        
        allSongs.forEach { song ->
            if (song.filePath.isNotBlank()) {
                val file = File(song.filePath)
                // 只有当父目录存在（排除SD卡拔出的情况），且文件确实不见了，才标记为失效喵
                if (file.parentFile?.exists() == true && !file.exists()) {
                    staleIds.add(song.id)
                }
            }
        }
        
        if (staleIds.isNotEmpty()) {
            songDao.deleteSongsByIds(staleIds)
        }
        staleIds.size
    }

    /**
     * 初始同步扫描：极速批量版 (๑•̀ㅂ•́)و✧
     */
    suspend fun getInitialScannedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val scannedSongs = AudioScanner.scan(context)
        // 预热：一次性加载本地所有歌曲，按文件名+时长构建匹配索引
        val dbSongs = songDao.getAllSongs().associateBy { "${it.fileName.lowercase()}|${it.duration}" }
        
        // 1. 预处理：识别 AB 模式下的 B 段喵
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

        // 2. 计算 AB 式歌曲的合并采样数：A 段 totalSamples = A + B，匹配 PC 端数据喵！
        val abCombinedSamples = mutableMapOf<String, Long>()
        abMarkedSongs.forEach { song ->
            if (!song.isAbPartB) {
                val pair = findAbPair(song, abMarkedSongs)
                if (pair != null) {
                    val aSamples = if (song.totalSamples > 0L) song.totalSamples else AudioScanner.getApproximateSamples(context, song.mediaId, song.filePath, song.duration)
                    val bSamples = if (pair.second.totalSamples > 0L) pair.second.totalSamples else AudioScanner.getApproximateSamples(context, pair.second.mediaId, pair.second.filePath, pair.second.duration)
                    abCombinedSamples["${song.fileName.lowercase()}|${song.duration}"] = aSamples + bSamples
                }
            }
        }

        val insertList = mutableListOf<Song>()
        val updateList = mutableListOf<SongMetadataUpdate>()
        val result = mutableListOf<Song>()
        val seenFingerprints = mutableSetOf<String>()

        abMarkedSongs.forEach { song ->
            val fingerprint = "${song.fileName.lowercase()}|${song.duration}"
            val dbSong = dbSongs[fingerprint]

            if (dbSong != null) {
                // 情况 A：老面孔，准备批量元数据更新
                // 数据库已有的 artistId/albumId 优先（PC 同步数据），null 时用 MediaStore 扫描结果补充
                val resolvedArtistId = dbSong.song.artistId
                    ?: song.artistEntity?.name?.lowercase()?.let { artistMap[it] }
                val resolvedAlbumId = dbSong.song.albumId
                    ?: song.albumEntity?.name?.lowercase()?.let { albumMap[it] }

                val abTotal = abCombinedSamples[fingerprint]
                val approximateTotal = abTotal
                    ?: if (dbSong.totalSamples <= 0L) {
                        if (song.totalSamples > 0L) song.totalSamples else AudioScanner.getApproximateSamples(context, song.mediaId, song.filePath, song.duration)
                    } else dbSong.totalSamples

                updateList.add(SongMetadataUpdate(
                    songId = dbSong.id,
                    total = approximateTotal,
                    start = dbSong.loopStart,
                    end = dbSong.loopEnd,
                    rating = dbSong.rating,
                    artistId = resolvedArtistId,
                    albumId = resolvedAlbumId,
                    displayName = dbSong.displayName,
                    coverPath = dbSong.coverPath,
                    isAbPartB = song.isAbPartB
                ))
                result.add(song.copy(song = song.song.copy(id = dbSong.id)))
            } else if (!seenFingerprints.contains(fingerprint)) {
                // 情况 B：新朋友，且本次扫描中还没见过这个指纹，准备批量插入喵！
                insertList.add(song)
                seenFingerprints.add(fingerprint)
            }
        }

        // 2. 批量提交 (Atomic Batch)
        if (insertList.isNotEmpty()) {
            // 回填 artistId/albumId 到 SongEntity，确保入库时就有正确的关联喵！
            val entities = insertList.map { song ->
                val aId = song.artistEntity?.name?.lowercase()?.let { artistMap[it] }
                val alId = song.albumEntity?.name?.lowercase()?.let { albumMap[it] }
                val total = abCombinedSamples["${song.fileName.lowercase()}|${song.duration}"]
                    ?: if (song.totalSamples > 0L) song.totalSamples else AudioScanner.getApproximateSamples(context, song.mediaId, song.filePath, song.duration)
                song.song.copy(artistId = aId, albumId = alId, totalSamples = total)
            }
            val newIds = songDao.insertSongsBatch(entities)
            
            val newLoopPoints = mutableListOf<LoopPoint>()
            val newUserRatings = mutableListOf<UserRating>()
            
            insertList.zip(newIds).forEach { (song, id) ->
                newLoopPoints.add(LoopPoint(songId = id, loopStart = song.loopStart, loopEnd = song.loopEnd))
                newUserRatings.add(UserRating(songId = id, rating = song.rating))
                result.add(song.copy(song = song.song.copy(id = id)))
            }
            
            songDao.insertLoopPointsBatch(newLoopPoints)
            songDao.insertUserRatingsBatch(newUserRatings)
        }
        
        if (updateList.isNotEmpty()) {
            songDao.updateSongsMetadataBatch(updateList)
        }
        
        result
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

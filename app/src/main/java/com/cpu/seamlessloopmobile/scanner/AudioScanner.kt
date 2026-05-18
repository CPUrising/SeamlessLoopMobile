package com.cpu.seamlessloopmobile.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.cpu.seamlessloopmobile.model.Song
import java.io.File
import java.io.RandomAccessFile

/**
 * 极简音频扫描器
 * 基于 ContentResolver 从 Android 系统媒体库获取歌曲
 */
object AudioScanner {

    fun scan(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val contentResolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        
        // 动态检测是否支持 sample_rate 查询字段 (API 30+)
        val hasSampleRate = android.os.Build.VERSION.SDK_INT >= 30
        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            "album_artist", // ALBUM_ARTIST in newer Android versions
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        if (hasSampleRate) {
            projectionList.add("sample_rate")
        }
        val projection = projectionList.toTypedArray()

        // 过滤条件：只找音乐类文件（移除原本 10 秒的时长限制，确保所有短曲目也能出现喵）
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val cursor = contentResolver.query(uri, projection, selection, null, sortOrder)

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumArtistColumn = it.getColumnIndex("album_artist")
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sampleRateColumn = if (hasSampleRate) it.getColumnIndex("sample_rate") else -1

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val fileName = it.getString(nameColumn)
                val title = it.getString(titleColumn) ?: "Unknown"
                val artist = it.getString(artistColumn) ?: "Unknown Artist"
                val album = if (albumColumn != -1) it.getString(albumColumn) ?: "Unknown Album" else "Unknown Album"
                val albumArtist = if (albumArtistColumn != -1) it.getString(albumArtistColumn) ?: artist else artist
                val filePath = it.getString(pathColumn)
                val duration = it.getLong(durationColumn)
                
                val sampleRate = if (sampleRateColumn != -1) it.getInt(sampleRateColumn) else 0
                val computedTotalSamples = if (sampleRate > 0 && duration > 0) (duration * sampleRate) / 1000L else 0L

                // 物理文件校验
                val file = File(filePath)
                if (file.exists()) {
                    songs.add(
                        Song(
                            id = 0,
                            mediaId = id,
                            fileName = fileName,
                            filePath = filePath,
                            displayName = title,
                            artist = artist,
                            album = album,
                            albumArtist = albumArtist,
                            duration = duration,
                            totalSamples = computedTotalSamples, 
                            isLoopEnabled = false
                        )
                    )
                }
            }
        }
        return songs
    }

    /**
     * 快速估算！读文件头解析采样率，配合 duration 估算 totalSamples喵！
     * 采用了 CPU 大人的终极优化反转设计：优先物理路径直接读取（~1ms极速），异常时降级为 ContentResolver 读取喵！
     */
    fun getApproximateSamples(
        context: Context,
        mediaId: Long,
        filePath: String,
        durationMs: Long
    ): Long {
        val sampleRate = try {
            // 【第一主力选择】：直接用物理路径极其迅速地读取（经 FUSE 绕过 Java 层 Binder 跨进程开销，极速 ~1ms）
            val rate = detectSampleRateViaFile(filePath)
            if (rate > 0) rate else throw Exception("Direct file read failed, fallback to Resolver")
        } catch (e: Exception) {
            // 【最终后备方案】：如果物理路径报权限/丢失等错误，才调用慢速但安全的 ContentResolver
            try {
                if (mediaId > 0) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val header = ByteArray(8192)
                        val bytesRead = stream.read(header)
                        if (bytesRead > 0) detectSampleRate(header, bytesRead) else 0
                    } ?: 0
                } else 0
            } catch (inner: Exception) {
                0
            }
        }
        
        // 最终兜底：如果全部读取失败，则回退至 44100
        val finalRate = if (sampleRate > 0) sampleRate else 44100
        return if (finalRate > 0 && durationMs > 0) (durationMs * finalRate) / 1000L else 0L
    }

    /**
     * 向后兼容老版签名，方便单元测试和直接路径调用喵～
     */
    fun getApproximateSamples(filePath: String, durationMs: Long): Long {
        val rate = detectSampleRateViaFile(filePath)
        val finalRate = if (rate > 0) rate else 44100
        return if (finalRate > 0 && durationMs > 0) (durationMs * finalRate) / 1000L else 0L
    }

    private fun detectSampleRateViaFile(filePath: String): Int {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(8192)
                    val bytesRead = raf.read(header)
                    if (bytesRead > 0) detectSampleRate(header, bytesRead) else 0
                }
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun detectSampleRate(header: ByteArray, bytesRead: Int): Int {
        return try {
            if (bytesRead < 4) return 44100

            // FLAC: "fLaC" at offset 0, sample rate at bytes 18-20 (20-bit BE)
            if (bytesRead >= 42 && header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
                val sr = ((header[18].toInt() and 0xFF) shl 12) or
                          ((header[19].toInt() and 0xFF) shl 4) or
                          ((header[20].toInt() and 0xFF) shr 4)
                if (sr > 0) return sr
            }

            // WAV: "RIFF" at offset 0, sample rate at bytes 24-27 (32-bit LE)
            if (bytesRead >= 28 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) {
                val sr = (header[24].toInt() and 0xFF) or
                          ((header[25].toInt() and 0xFF) shl 8) or
                          ((header[26].toInt() and 0xFF) shl 16) or
                          ((header[27].toInt() and 0xFF) shl 24)
                if (sr > 0) return sr
            }

            // OGG: "OggS" at offset 0, direct from header memory (no extra I/O)
            if (bytesRead >= 42 && header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) {
                val segCount = header[26].toInt() and 0xFF
                val pktStart = 27 + segCount
                if (bytesRead < pktStart + 16) return 44100
                // Vorbis: packetType=1 + "vorbis"(6), sample_rate at offset 12-15
                if (header[pktStart] == 1.toByte() &&
                    header[pktStart + 1] == 0x76.toByte() &&
                    header[pktStart + 2] == 0x6F.toByte() &&
                    header[pktStart + 3] == 0x72.toByte() &&
                    header[pktStart + 4] == 0x62.toByte() &&
                    header[pktStart + 5] == 0x69.toByte() &&
                    header[pktStart + 6] == 0x73.toByte()) {
                    val sr = (header[pktStart + 12].toInt() and 0xFF) or
                             ((header[pktStart + 13].toInt() and 0xFF) shl 8) or
                             ((header[pktStart + 14].toInt() and 0xFF) shl 16) or
                             ((header[pktStart + 15].toInt() and 0xFF) shl 24)
                    if (sr > 0) return sr
                }
                // Opus: "OpusHead"(8), sample_rate at offset 12-15 LE
                if (bytesRead >= pktStart + 19 &&
                    header[pktStart] == 0x4F.toByte() &&
                    header[pktStart + 1] == 0x70.toByte() &&
                    header[pktStart + 2] == 0x75.toByte() &&
                    header[pktStart + 3] == 0x73.toByte() &&
                    header[pktStart + 4] == 0x48.toByte() &&
                    header[pktStart + 5] == 0x65.toByte() &&
                    header[pktStart + 6] == 0x61.toByte() &&
                    header[pktStart + 7] == 0x64.toByte()) {
                    val sr = (header[pktStart + 12].toInt() and 0xFF) or
                             ((header[pktStart + 13].toInt() and 0xFF) shl 8) or
                             ((header[pktStart + 14].toInt() and 0xFF) shl 16) or
                             ((header[pktStart + 15].toInt() and 0xFF) shl 24)
                    if (sr > 0) return sr
                }
                // FLAC in OGG: "fLaC"(4) + metadata-block-header(4) + STREAMINFO
                // sample_rate at STREAMINFO bytes 10-12 (20 bits): byte18-20 in packet
                if (bytesRead >= pktStart + 21 &&
                    header[pktStart] == 0x66.toByte() &&
                    header[pktStart + 1] == 0x4C.toByte() &&
                    header[pktStart + 2] == 0x61.toByte() &&
                    header[pktStart + 3] == 0x43.toByte()) {
                    val sr = ((header[pktStart + 18].toInt() and 0xFF) shl 12) or
                             ((header[pktStart + 19].toInt() and 0xFF) shl 4) or
                             ((header[pktStart + 20].toInt() and 0xFF) shr 4)
                    if (sr > 0) return sr
                }
            }

            // MP3: find sync word 0xFFE0-0xFFFE
            var offset = 0
            if (bytesRead >= 10 && header[0] == 0x49.toByte() && header[1] == 0x44.toByte() &&
                header[2] == 0x33.toByte()) {
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                              ((header[7].toInt() and 0x7F) shl 14) or
                              ((header[8].toInt() and 0x7F) shl 7) or
                              (header[9].toInt() and 0x7F)
                offset = 10 + tagSize
            }

            while (offset + 3 < bytesRead) {
                if ((header[offset].toInt() and 0xFF) == 0xFF &&
                    (header[offset + 1].toInt() and 0xE0) == 0xE0) {
                    val h = ((header[offset].toInt() and 0xFF) shl 24) or
                            ((header[offset + 1].toInt() and 0xFF) shl 16) or
                            ((header[offset + 2].toInt() and 0xFF) shl 8) or
                            (header[offset + 3].toInt() and 0xFF)
                    val srIndex = (h shr 10) and 0x03
                    val version = (h shr 19) and 0x03
                    return when (version) {
                        3 -> intArrayOf(44100, 48000, 32000, 0)[srIndex]
                        2 -> intArrayOf(22050, 24000, 16000, 0)[srIndex]
                        0 -> intArrayOf(11025, 12000, 8000, 0)[srIndex]
                        else -> 44100
                    }
                }
                offset++
            }
            44100
        } catch (e: Exception) {
            44100
        }
    }

    /**
     * 实地测量！利用 C++ 底层解码器拿到绝对准确的总采样数和采样率喵！
     */
    fun getAccurateMetadata(context: Context, mediaId: Long): Pair<Long, Int> {
        return try {
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
            context.contentResolver.openAssetFileDescriptor(contentUri, "r")?.use { afd ->
                val fd = afd.parcelFileDescriptor.fd
                val offset = afd.startOffset
                val length = if (afd.declaredLength < 0) afd.length else afd.declaredLength
                
                val frames = com.cpu.seamlessloopmobile.jni.NativeAudio.getAudioFileDuration(fd, offset, length)
                val sampleRate = com.cpu.seamlessloopmobile.jni.NativeAudio.getAudioFileSampleRate(fd, offset, length)
                Pair(frames, sampleRate)
            } ?: Pair(0L, 44100)
        } catch (e: Exception) {
            Pair(0L, 44100)
        }
    }

    /**
     * 旧接口兼容：获取准确的总采样数喵！
     */
    fun getAccurateSampleCount(context: Context, mediaId: Long): Long {
        return getAccurateMetadata(context, mediaId).first
    }
}

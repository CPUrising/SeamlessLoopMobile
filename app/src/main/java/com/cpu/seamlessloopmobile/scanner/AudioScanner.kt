package com.cpu.seamlessloopmobile.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import com.cpu.seamlessloopmobile.model.Song
import java.io.File

/**
 * 极简音频扫描器
 * 基于 ContentResolver 从 Android 系统媒体库获取歌曲
 */
object AudioScanner {

    private data class AudioFileDetails(
        val mimeType: String?,
        val sampleRateHz: Int?,
        val bitrateKbps: Int?
    )

    fun scan(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val contentResolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        
        // 查询字段：ID, 文件名, 标题, 艺术家, 路径, 时长
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            "album_artist", // ALBUM_ARTIST in newer Android versions
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

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
            val albumIdColumn = it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val albumArtistColumn = it.getColumnIndex("album_artist")
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val fileName = it.getString(nameColumn)
                val title = it.getString(titleColumn) ?: "Unknown"
                val artist = it.getString(artistColumn) ?: "Unknown Artist"
                val album = if (albumColumn != -1) it.getString(albumColumn) ?: "Unknown Album" else "Unknown Album"
                val albumId = if (albumIdColumn != -1 && !it.isNull(albumIdColumn)) it.getLong(albumIdColumn) else 0L
                val albumArtist = if (albumArtistColumn != -1) it.getString(albumArtistColumn) ?: artist else artist
                val filePath = it.getString(pathColumn)
                val duration = it.getLong(durationColumn)

                // 物理文件校验
                val file = File(filePath)
                if (file.exists()) {
                    val fileDetails = readAudioFileDetails(filePath)
                    // 先给个 0，让列表秒开喵！
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
                            coverPath = albumArtUri(albumId),
                            mimeType = fileDetails.mimeType,
                            sampleRateHz = fileDetails.sampleRateHz,
                            bitrateKbps = fileDetails.bitrateKbps,
                            duration = duration,
                            totalSamples = 0, 
                            isLoopEnabled = false 
                        )
                    )
                }
            }
        }
        return songs
    }

    private fun albumArtUri(albumId: Long): String? {
        if (albumId <= 0L) return null
        return ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        ).toString()
    }

    private fun readAudioFileDetails(filePath: String): AudioFileDetails {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mimeType = format.getString(MediaFormat.KEY_MIME)
                if (mimeType?.startsWith("audio/") != true) continue

                val sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                } else {
                    null
                }
                val bitrateKbps = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                        .takeIf { it > 0 }
                        ?.let { (it + 500) / 1000 }
                } else {
                    null
                }

                return AudioFileDetails(
                    mimeType = mimeType,
                    sampleRateHz = sampleRateHz,
                    bitrateKbps = bitrateKbps
                )
            }
            AudioFileDetails(null, null, null)
        } catch (_: Throwable) {
            AudioFileDetails(null, null, null)
        } finally {
            extractor.release()
        }
    }
}


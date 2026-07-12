package com.cpu.seamlessloopmobile.model

import androidx.room.*

/**
 * 歌曲视图模型 (POJO)
 * 通过 Room 的 @Relation 机制在内存中拼合 3NF 数据，保持与旧版 Song 类的接口兼容喵！
 */
data class Song(
    @Embedded val song: SongEntity,

    @Relation(
        parentColumn = "ArtistId",
        entityColumn = "Id"
    )
    val artistEntity: Artist? = null,

    @Relation(
        parentColumn = "AlbumId",
        entityColumn = "Id"
    )
    val albumEntity: Album? = null,

    @Relation(
        parentColumn = "Id",
        entityColumn = "SongId"
    )
    val loopPoint: LoopPoint? = null,

    @Relation(
        parentColumn = "Id",
        entityColumn = "SongId"
    )
    val userRating: UserRating? = null
) {
    // --- 兼容性构造函数，确保 Scanner 和旧逻辑能正常工作喵！ ---
    constructor(
        id: Long = 0,
        mediaId: Long = 0,
        fileName: String = "",
        filePath: String = "",
        totalSamples: Long = 0,
        displayName: String? = null,
        loopStart: Long = 0,
        loopEnd: Long = 0,
        lastModified: Long = System.currentTimeMillis(),
        duration: Long = 0,
        isLoopEnabled: Boolean = true,
        rating: Int = 0,
        coverPath: String? = null,
        mimeType: String? = null,
        sampleRateHz: Int? = null,
        bitrateKbps: Int? = null,
        isAbPartB: Boolean = false,
        loopCandidatesJson: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        artistId: Long? = null,
        albumId: Long? = null
    ) : this(
        song = SongEntity(
            id = id,
            mediaId = mediaId,
            fileName = fileName,
            filePath = filePath,
            totalSamples = totalSamples,
            displayName = displayName,
            lastModified = lastModified,
            coverPath = coverPath,
            mimeType = mimeType,
            sampleRateHz = sampleRateHz,
            bitrateKbps = bitrateKbps,
            artistId = artistId,
            albumId = albumId,
            duration = duration,
            isLoopEnabled = isLoopEnabled,
            isAbPartB = isAbPartB,
            loopCandidatesJson = loopCandidatesJson
        ),
        // 注意：在手动构造时，Relation 字段也可以被填充喵
        loopPoint = if (loopStart != 0L || (loopEnd != 0L && loopEnd != totalSamples)) LoopPoint(id, loopStart, loopEnd) else null,
        userRating = if (rating != 0) UserRating(id, rating) else null,
        artistEntity = if (artist != null) Artist(name = artist) else null,
        albumEntity = if (album != null) Album(name = album) else null
    )

    // --- 兼容性 Getter ---
    
    val id get() = song.id
    val mediaId get() = song.mediaId
    val fileName get() = song.fileName
    val filePath get() = song.filePath
    val totalSamples get() = song.totalSamples
    val displayName get() = song.displayName ?: song.fileName
    val lastModified get() = song.lastModified
    val coverPath get() = song.coverPath ?: albumEntity?.coverPath ?: artistEntity?.coverPath
    val mimeType get() = song.mimeType
    val sampleRateHz get() = song.sampleRateHz
    val bitrateKbps get() = song.bitrateKbps
    val duration get() = song.duration
    val isLoopEnabled get() = song.isLoopEnabled
    val isAbPartB get() = song.isAbPartB
    val loopCandidatesJson get() = song.loopCandidatesJson
    val artistId get() = song.artistId
    val albumId get() = song.albumId

    val loopStart get() = loopPoint?.loopStart ?: 0L
    val loopEnd get() = loopPoint?.loopEnd ?: 0L
    val rating get() = userRating?.rating ?: 0
    val artist get() = artistEntity?.name ?: "Unknown Artist"
    val album get() = albumEntity?.name ?: "Unknown Album"
    val albumArtist get() = artist

    // --- 兼容性方法 ---
    
    fun copy(
        id: Long = this.id,
        mediaId: Long = this.mediaId,
        fileName: String = this.fileName,
        filePath: String = this.filePath,
        totalSamples: Long = this.totalSamples,
        displayName: String? = this.displayName,
        loopStart: Long = this.loopStart,
        loopEnd: Long = this.loopEnd,
        lastModified: Long = this.lastModified,
        duration: Long = this.duration,
        isLoopEnabled: Boolean = this.isLoopEnabled,
        rating: Int = this.rating,
        coverPath: String? = this.coverPath,
        mimeType: String? = this.mimeType,
        sampleRateHz: Int? = this.sampleRateHz,
        bitrateKbps: Int? = this.bitrateKbps,
        isAbPartB: Boolean = this.isAbPartB,
        loopCandidatesJson: String? = this.loopCandidatesJson,
        artist: String? = this.artist,
        album: String? = this.album,
        artistId: Long? = this.artistId,
        albumId: Long? = this.albumId
    ): Song = Song(
        id = id,
        mediaId = mediaId,
        fileName = fileName,
        filePath = filePath,
        totalSamples = totalSamples,
        displayName = displayName,
        loopStart = loopStart,
        loopEnd = loopEnd,
        lastModified = lastModified,
        duration = duration,
        isLoopEnabled = isLoopEnabled,
        rating = rating,
        coverPath = coverPath,
        mimeType = mimeType,
        sampleRateHz = sampleRateHz,
        bitrateKbps = bitrateKbps,
        isAbPartB = isAbPartB,
        loopCandidatesJson = loopCandidatesJson,
        artist = artist,
        album = album,
        artistId = artistId,
        albumId = albumId
    )
}

/**
 * 极速同步专用的元数据更新包喵！
 * 只携带变动字段，减少内存和数据库开销。
 */
data class SongMetadataUpdate(
    val songId: Long,
    val total: Long,
    val start: Long,
    val end: Long,
    val rating: Int,
    val artistId: Long?,
    val albumId: Long?,
    val displayName: String?,
    val coverPath: String?,
    val mimeType: String? = null,
    val sampleRateHz: Int? = null,
    val bitrateKbps: Int? = null,
    val isAbPartB: Boolean = false,
    // PC 端把候选循环点存在 LoopPoints.LoopCandidatesJson；手机端存在 Songs.LoopCandidatesJson。
    // 默认为 null 表示“不更新缓存字段”，避免普通扫描批量更新时清空已有探测缓存喵。
    val loopCandidatesJson: String? = null
)

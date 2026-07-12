package com.cpu.seamlessloopmobile.model

import androidx.room.*

/**
 * 歌曲基础实体 (3NF 底层表)
 * 对应数据库中的 Songs 表
 */
@Entity(
    tableName = "Songs",
    indices = [
        Index(value = ["FilePath"]),
        Index(value = ["FileName", "duration"]),
        Index(value = ["FileName"]),
        Index(value = ["ArtistId"]),
        Index(value = ["AlbumId"]),
        Index(value = ["IsAbPartB"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = Artist::class,
            parentColumns = ["Id"],
            childColumns = ["ArtistId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Album::class,
            parentColumns = ["Id"],
            childColumns = ["AlbumId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "Id")
    val id: Long = 0,
    
    @ColumnInfo(name = "mediaId")
    val mediaId: Long = 0,

    @ColumnInfo(name = "FileName")
    val fileName: String,

    @ColumnInfo(name = "FilePath")
    val filePath: String,

    @ColumnInfo(name = "TotalSamples")
    val totalSamples: Long,

    @ColumnInfo(name = "DisplayName")
    val displayName: String? = null,

    @ColumnInfo(name = "LastModified")
    val lastModified: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "CoverPath")
    val coverPath: String? = null,

    @ColumnInfo(name = "MimeType")
    val mimeType: String? = null,

    @ColumnInfo(name = "SampleRateHz")
    val sampleRateHz: Int? = null,

    @ColumnInfo(name = "BitrateKbps")
    val bitrateKbps: Int? = null,

    @ColumnInfo(name = "ArtistId")
    val artistId: Long? = null,

    @ColumnInfo(name = "AlbumId")
    val albumId: Long? = null,

    @ColumnInfo(name = "duration")
    val duration: Long = 0,
    
    @ColumnInfo(name = "isLoopEnabled")
    val isLoopEnabled: Boolean = true,
    
    @ColumnInfo(name = "IsAbPartB")
    val isAbPartB: Boolean = false,
    
    @ColumnInfo(name = "LoopCandidatesJson")
    val loopCandidatesJson: String? = null
)

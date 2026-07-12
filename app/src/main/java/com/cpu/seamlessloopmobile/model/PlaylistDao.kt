package com.cpu.seamlessloopmobile.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    // --- 歌单管理 ---
    @Query("""
        SELECT p.*, (SELECT COUNT(*) FROM PlaylistItems JOIN Songs ON PlaylistItems.SongId = Songs.Id WHERE PlaylistItems.PlaylistId = p.Id AND Songs.IsAbPartB = 0) as songCount 
        FROM Playlists p 
        ORDER BY p.SortOrder ASC, p.CreatedAt DESC
    """)
    suspend fun getPlaylistsWithCounts(): List<PlaylistWithCount>

    @Query("""
        SELECT p.*, (SELECT COUNT(*) FROM PlaylistItems JOIN Songs ON PlaylistItems.SongId = Songs.Id WHERE PlaylistItems.PlaylistId = p.Id AND Songs.IsAbPartB = 0) as songCount 
        FROM Playlists p 
        ORDER BY p.SortOrder ASC, p.CreatedAt DESC
    """)
    fun getPlaylistsWithCountsFlow(): Flow<List<PlaylistWithCount>>

    data class PlaylistWithCount(
        @Embedded val playlist: Playlist,
        val songCount: Int
    )

    @Query("SELECT * FROM Playlists WHERE Id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Int): Playlist?

    @Query("SELECT * FROM Playlists WHERE Name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist): Int

    @Delete
    suspend fun deletePlaylist(playlist: Playlist): Int

    // --- 歌单项管理 ---
    
    @Query("""
        SELECT Songs.* FROM Songs
        JOIN PlaylistItems ON Songs.Id = PlaylistItems.SongId
        WHERE PlaylistItems.PlaylistId = :playlistId AND Songs.IsAbPartB = 0
        ORDER BY PlaylistItems.SortOrder ASC
    """)
    suspend fun getSongsInPlaylistRaw(playlistId: Int): List<Song>

    @Transaction
    suspend fun getSongsInPlaylist(playlistId: Int): List<Song> {
        return getSongsInPlaylistRaw(playlistId).distinctBy { it.id }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistItem(item: PlaylistItem): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistItemsBatch(items: List<PlaylistItem>)

    @Query("SELECT SongId FROM PlaylistItems WHERE PlaylistId = :playlistId")
    suspend fun getSongIdsInPlaylist(playlistId: Int): List<Long>

    @Transaction
    suspend fun addSongsToPlaylist(playlistId: Int, songIds: List<Long>): Int {
        val existingIds = getSongIdsInPlaylist(playlistId).toSet()
        val newSongIds = songIds.distinct().filter { it !in existingIds }
        
        if (newSongIds.isEmpty()) return 0

        val currentMaxOrder = getMaxSortOrder(playlistId) ?: 0
        newSongIds.forEachIndexed { index, songId ->
            insertPlaylistItem(PlaylistItem(
                playlistId = playlistId,
                songId = songId,
                sortOrder = currentMaxOrder + index + 1
            ))
        }
        return newSongIds.size
    }

    @Query("SELECT MAX(SortOrder) FROM PlaylistItems WHERE PlaylistId = :playlistId")
    suspend fun getMaxSortOrder(playlistId: Int): Int?

    @Query("DELETE FROM PlaylistItems WHERE PlaylistId = :playlistId AND SongId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Int, songId: Long): Int

    @Transaction
    suspend fun removeSongsFromPlaylist(playlistId: Int, songIds: List<Long>): Int {
        songIds.forEach { removeSongFromPlaylist(playlistId, it) }
        return songIds.size
    }

    @Query("SELECT * FROM PlaylistFolders WHERE PlaylistId = :playlistId")
    suspend fun getFoldersInPlaylist(playlistId: Int): List<PlaylistFolder>

    @Query("DELETE FROM PlaylistItems WHERE PlaylistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Int)

    @Transaction
    suspend fun clearAndSyncPlaylist(playlistId: Int, songIds: List<Long>) {
        clearPlaylist(playlistId)
        songIds.distinct().forEachIndexed { index, songId ->
            insertPlaylistItem(PlaylistItem(
                playlistId = playlistId,
                songId = songId,
                sortOrder = index + 1
            ))
        }
    }

    @Query("SELECT COUNT(*) FROM PlaylistItems JOIN Songs ON PlaylistItems.SongId = Songs.Id WHERE PlaylistItems.PlaylistId = :playlistId AND Songs.IsAbPartB = 0")
    suspend fun getSongCountInPlaylist(playlistId: Int): Int

    @Query("DELETE FROM Playlists")
    suspend fun deleteAllPlaylists(): Int
}

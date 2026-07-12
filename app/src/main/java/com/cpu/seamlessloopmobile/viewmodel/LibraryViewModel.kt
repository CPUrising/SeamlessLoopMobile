package com.cpu.seamlessloopmobile.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.cpu.seamlessloopmobile.data.MusicRepository
import com.cpu.seamlessloopmobile.model.Folder
import com.cpu.seamlessloopmobile.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import com.cpu.seamlessloopmobile.data.SettingsManager

/**
 * 乐库管理员喵！
 * 专门负责歌曲扫描、文件夹整理和数据库同步，把 MainViewModel 从繁重的 IO 工作中解放出来。
 */
class LibraryViewModel(
    private val repository: com.cpu.seamlessloopmobile.data.MusicRepository,
    private val coroutineScope: kotlinx.coroutines.CoroutineScope,
    private val settingsManager: SettingsManager? = null,
    private val onScanCompleted: suspend () -> Unit = {}
) {

    private val _syncStatus = MutableStateFlow<com.cpu.seamlessloopmobile.ui.state.DataUiState<String>>(com.cpu.seamlessloopmobile.ui.state.DataUiState.Success(""))
    val syncStatus: StateFlow<com.cpu.seamlessloopmobile.ui.state.DataUiState<String>> = _syncStatus

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders

    private val _albums = MutableStateFlow<List<Folder>>(emptyList())
    val albums: StateFlow<List<Folder>> = _albums

    private val _artists = MutableStateFlow<List<Folder>>(emptyList())
    val artists: StateFlow<List<Folder>> = _artists

    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites

    // --- 响应式数据流：APlayer 的瞬发秘籍喵！🚀 ---

    // 0. 快照统计流：大人一推门就能看到的“假象”（真相的前哨）喵！🚀
    private val _stats = MutableStateFlow(settingsManager?.lastLibraryStats ?: SettingsManager.LibraryStats())
    val stats: StateFlow<SettingsManager.LibraryStats> = _stats

    // 1. 全部歌曲（过滤掉 B 段）
    val allSongs: StateFlow<List<Song>> = repository.getAllSongsFlow()
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    // 2. 原始全量歌曲（包含 B 段）
    val allSongsRaw: StateFlow<List<Song>> = repository.getAllSongsRawFlow()
        .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    init {
        // APlayer 秘籍：统一指挥，全速前进喵！🚀
        coroutineScope.launch {
            allSongs.collect { songs ->
                if (songs.isNotEmpty()) {
                    android.util.Log.d("LibraryViewModel", "🚀 收到数据库水流，准备开始分类处理 (${songs.size} 首歌) 喵！")
                    val startTime = System.currentTimeMillis()
                    
                    // 继承 APlayer 意志：全并行分类，压榨每一核性能喵！🚀
                    withContext(Dispatchers.Default) {
                        val foldersDeferred = async { processFolders(songs) }
                        val albumsDeferred = async { processAlbums(songs) }
                        val artistsDeferred = async { processArtists(songs) }
                        val favoritesDeferred = async { songs.filter { it.rating > 0 }.sortedByDescending { it.rating } }
                        
                        val f = foldersDeferred.await()
                        val a = albumsDeferred.await()
                        val r = artistsDeferred.await()
                        val favs = favoritesDeferred.await()
                        
                        _folders.value = f
                        _albums.value = a
                        _artists.value = r
                        _favorites.value = favs

                        // 把这一刻的美好记在小本本上喵！🚀
                        val newStats = SettingsManager.LibraryStats(
                            songCount = songs.size,
                            albumCount = a.size,
                            artistCount = r.size,
                            folderCount = f.size
                        )
                        _stats.value = newStats
                        settingsManager?.lastLibraryStats = newStats
                        
                        val endTime = System.currentTimeMillis()
                        android.util.Log.d("LibraryViewModel", "✅ 分类处理完成，耗时 ${endTime - startTime}ms 喵！")
                    }
                }
            }
        }
    }


    /**
     * 核心扫描逻辑喵！
     */
    fun scanLibrary(context: Context) {
        coroutineScope.launch {
            _syncStatus.value = com.cpu.seamlessloopmobile.ui.state.DataUiState.Loading
            // 扫描完成后，数据库会发生变化，Room Flow 会自动通知到 UI 喵！🚀
            try {
                val scanResult = withContext(Dispatchers.IO) {
                    repository.getInitialScannedSongs(context)
                }
                onScanCompleted()
                val completionState = com.cpu.seamlessloopmobile.ui.state.DataUiState.Success(scanResult.statusMessage())
                _syncStatus.value = completionState
                delay(2000L)
                if (_syncStatus.value == completionState) {
                    _syncStatus.value = com.cpu.seamlessloopmobile.ui.state.DataUiState.Success("")
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "❌ 扫描出错: ${e.message}")
                _syncStatus.value = com.cpu.seamlessloopmobile.ui.state.DataUiState.Error(e.message ?: "未知错误")
            }
        }
    }

    private fun processFolders(songs: List<Song>): List<Folder> {
        val folderMap = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            val path = song.filePath
            val lastSlash = path.lastIndexOf(java.io.File.separator)
            val parent = if (lastSlash != -1) path.substring(0, lastSlash) else "未知目录"
            folderMap.getOrPut(parent) { mutableListOf() }.add(song)
        }
        return folderMap.map { 
            val path = it.key
            val lastSlash = path.lastIndexOf(java.io.File.separator)
            val name = if (lastSlash != -1) path.substring(lastSlash + 1) else path
            Folder(name, path, it.value.size, it.value) 
        }.sortedBy { it.name }
    }

    private fun processAlbums(songs: List<Song>): List<Folder> {
        val albumMap = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            val album = song.album ?: "未知专辑"
            albumMap.getOrPut(album) { mutableListOf() }.add(song)
        }
        return albumMap.map { Folder(it.key, "album_${it.key}", it.value.size, it.value) }.sortedBy { it.name }
    }

    private fun processArtists(songs: List<Song>): List<Folder> {
        val artistMap = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            val artist = song.artist ?: "未知歌手"
            artistMap.getOrPut(artist) { mutableListOf() }.add(song)
        }
        return artistMap.map { Folder(it.key, "artist_${it.key}", it.value.size, it.value) }.sortedBy { it.name }
    }


    fun updateSongLoopPoints(song: Song, start: Long, end: Long) {
        coroutineScope.launch {
            repository.updateSongLoopPoints(song, start, end)
            // 不需要再手动刷新了喵，真谛就在于此喵！🚀
        }
    }
}

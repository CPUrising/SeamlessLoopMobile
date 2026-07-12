package com.cpu.seamlessloopmobile.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.components.common.CategoryListItem
import com.cpu.seamlessloopmobile.viewmodel.LibraryViewModel
import com.cpu.seamlessloopmobile.viewmodel.MusicUiState
import com.cpu.seamlessloopmobile.viewmodel.PlaylistViewModel
import com.cpu.seamlessloopmobile.viewmodel.SelectionViewModel

/**
 * 物理隔离出来的一级 Tab 页 —— 歌单 Tab 屏幕喵！(๑•̀ㅂ•́)و✧
 * 局部自治管理状态，切断高频重组影响。
 */
@Composable
fun PlaylistTabScreen(
    playlistVM: PlaylistViewModel,
    libraryVM: LibraryViewModel,
    selectionVM: SelectionViewModel,
    onOpenSongList: (String, List<Song>, MusicUiState.ListType) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    val playlistsWithCounts by playlistVM.playlistsWithCounts.collectAsState()
    val allSongs by libraryVM.allSongs.collectAsState()
    val favorites by libraryVM.favorites.collectAsState()
    
    val isSelectionMode by selectionVM.isSelectionMode.observeAsState(false)
    val selectedPlaylists by selectionVM.selectedPlaylists.observeAsState(emptySet())
    
    val playlistPairs = playlistsWithCounts.map { it.playlist to it.songCount }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 176.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 1. 全部歌曲
        item {
            CategoryListItem(
                title = "全部歌曲",
                subtitle = "${allSongs.size} 首歌曲",
                icon = Icons.Default.MusicNote,
                isSelected = false,
                onClick = {
                    onOpenSongList("全部歌曲", allSongs, MusicUiState.ListType.ALL_SONGS)
                }
            )
        }
        
        // 2. 已评分
        item {
            CategoryListItem(
                title = "已评分",
                subtitle = "${favorites.size} 首歌曲",
                icon = Icons.Default.Star,
                isSelected = false,
                onClick = {
                    onOpenSongList("已评分", favorites, MusicUiState.ListType.FAVORITES)
                }
            )
        }

        // 3. 自定义歌单列表
        items(playlistPairs) { (playlist, count) ->
            val isSelected = selectedPlaylists.contains(playlist.id)
            CategoryListItem(
                title = playlist.name,
                subtitle = "${count}首" + if (playlist.isFolderLinked == 1) " · 联动" else "",
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                isSelected = isSelected,
                isSelectionMode = isSelectionMode,
                onClick = {
                    if (isSelectionMode) {
                        selectionVM.togglePlaylistSelection(playlist.id)
                    } else {
                        onOpenPlaylist(playlist)
                    }
                },
                onLongClick = { selectionVM.togglePlaylistSelection(playlist.id) }
            )
        }
    }
}

package com.cpu.seamlessloopmobile.ui.screen.songlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.components.common.SongListItem

/**
 * 歌曲大列表通用屏幕，现已完美兼职全屏搜索大页面喵！🚀
 * 架构最简、复用度最高，支持批量操作与完美的切歌循环喵！(๑•̀ㅂ•́)و✧
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    songs: List<Song>,
    currentPlayingSongPath: String?,
    isSelectionMode: Boolean,
    selectedItems: Set<String>,
    onPlaySong: (Song) -> Unit,
    onToggleSelection: (Song) -> Unit,
    onShowMoreOptions: (Song) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    // --- 极简复用搜索参数喵！🔍 ---
    isSearchType: Boolean = false,
    searchQuery: String = ""
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 核心 LazyColumn 歌曲列表
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isSearchType && searchQuery.isBlank()) {
                // 搜索空输入提示页
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "搜索歌曲、艺人或专辑",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "输入关键词即可开始搜索",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else if (isSearchType && songs.isEmpty()) {
                // 搜索无结果提示页
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "没有找到匹配的内容",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请尝试其他关键词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 正常的歌曲列表展现
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 176.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        val isPlaying = song.filePath == currentPlayingSongPath
                        val isSelected = selectedItems.contains(song.filePath)

                        SongListItem(
                            song = song,
                            isPlaying = isPlaying,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) onToggleSelection(song)
                                else onPlaySong(song)
                            },
                            onLongClick = { onToggleSelection(song) },
                            onMoreClick = { onShowMoreOptions(song) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 智能自治包装器 —— 专为 MainScreen 设计喵！(๑•̀ㅂ•́)و✧
 * 在内部直接、局部订阅子 ViewModel 的 LiveData/StateFlow，消灭顶层的频繁重组！
 */
@Composable
fun MainSongListScreen(
    state: com.cpu.seamlessloopmobile.viewmodel.MusicUiState.SongList,
    libraryVM: com.cpu.seamlessloopmobile.viewmodel.LibraryViewModel,
    selectionVM: com.cpu.seamlessloopmobile.viewmodel.SelectionViewModel,
    mainVM: com.cpu.seamlessloopmobile.viewmodel.MainViewModel,
    onPlaySong: (Song, List<Song>) -> Unit,
    onShowMoreOptions: (Song) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val allSongs by libraryVM.allSongs.collectAsState()
    val folders by libraryVM.folders.collectAsState()
    val albums by libraryVM.albums.collectAsState()
    val artists by libraryVM.artists.collectAsState()
    val favorites by libraryVM.favorites.collectAsState()

    val isSelectionMode by selectionVM.isSelectionMode.observeAsState(false)
    val selectedItems by selectionVM.selectedItems.observeAsState(emptySet())

    val currentPlaylist by mainVM.currentPlaylist.observeAsState(emptyList())
    val currentSongIndex by mainVM.currentSongIndex.observeAsState(-1)

    val currentPlayingSongPath = currentPlaylist.getOrNull(currentSongIndex)?.filePath

    // 用 derivedStateOf 过滤冗余的计算重组喵！(๑•̀ㅂ•́)و✧
    val songsToShow by remember(state, allSongs, folders, albums, artists, favorites) {
        derivedStateOf {
            when (state.type) {
                com.cpu.seamlessloopmobile.viewmodel.MusicUiState.ListType.ALL_SONGS -> allSongs
                com.cpu.seamlessloopmobile.viewmodel.MusicUiState.ListType.FOLDER -> folders.find { it.name == state.title }?.songs ?: state.songs
                com.cpu.seamlessloopmobile.viewmodel.MusicUiState.ListType.ALBUM -> albums.find { it.name == state.title }?.songs ?: state.songs
                com.cpu.seamlessloopmobile.viewmodel.MusicUiState.ListType.ARTIST -> artists.find { it.name == state.title }?.songs ?: state.songs
                com.cpu.seamlessloopmobile.viewmodel.MusicUiState.ListType.FAVORITES -> favorites
                else -> state.songs
            }
        }
    }

    Box(modifier = modifier) {
        SongListScreen(
            songs = songsToShow,
            currentPlayingSongPath = currentPlayingSongPath,
            isSelectionMode = isSelectionMode,
            selectedItems = selectedItems,
            onPlaySong = { song ->
                onPlaySong(song, songsToShow)
            },
            onToggleSelection = { song ->
                if (!isSelectionMode) selectionVM.setSelectionMode(true)
                selectionVM.toggleSelection(song.filePath)
            },
            onShowMoreOptions = onShowMoreOptions,
            listState = listState
        )
    }
}


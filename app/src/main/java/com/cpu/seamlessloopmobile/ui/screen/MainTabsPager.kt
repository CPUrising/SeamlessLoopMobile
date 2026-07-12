package com.cpu.seamlessloopmobile.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.model.Folder
import com.cpu.seamlessloopmobile.ui.screen.category.CategoryScreen
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.viewmodel.MusicUiState
import kotlinx.coroutines.launch

/**
 * 从 MainScreen 中完美抽取出来的一级 Tab 翻页展示组件喵！(๑•̀ㅂ•́)و✧
 * 让 MainScreen 变得极其轻盈、清爽！
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainTabsPager(
    viewModel: MainViewModel,
    isSelectionMode: Boolean,
    selectedFolders: Set<Folder>,
    categoryScrollStates: MutableMap<String, androidx.compose.foundation.lazy.LazyListState>,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    
    Column(modifier = modifier.fillMaxSize()) {
        val tabs = listOf("歌单", "专辑", "歌手", "文件夹")
        val pagerState = rememberPagerState { tabs.size }
        
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = pagerState.currentPage == index
                Tab(
                    selected = selected,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    modifier = Modifier.height(48.dp),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                )
            }
        }
        
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            when (page) {
                0 -> { // 歌单 Tab (全部歌曲 + 已评分 + 自定义歌单列表)
                    PlaylistTabScreen(
                        playlistVM = viewModel.playlist,
                        libraryVM = viewModel.library,
                        selectionVM = viewModel.selection,
                        onOpenSongList = remember(viewModel) {
                            { title, songs, type ->
                                viewModel.openSongList(title, songs, type)
                            }
                        },
                        onOpenPlaylist = remember(viewModel) {
                            { playlist ->
                                viewModel.openPlaylist(playlist)
                            }
                        }
                    )
                }
                else -> {
                    // 专辑、歌手、文件夹三者具备高度重合逻辑，在这里使用精美循环与局部收集大幅精简代码并消除重组！(๑•̀ㅂ•́)و✧
                    val (items, type, label) = when (page) {
                        1 -> {
                            val albums by viewModel.library.albums.collectAsState()
                            Triple(albums, MusicUiState.ListType.ALBUM, "专辑")
                        }
                        2 -> {
                            val artists by viewModel.library.artists.collectAsState()
                            Triple(artists, MusicUiState.ListType.ARTIST, "歌手")
                        }
                        else -> {
                            val folders by viewModel.library.folders.collectAsState()
                            Triple(folders, MusicUiState.ListType.FOLDER, "文件夹")
                        }
                    }

                    // 局部观察播放状态来支持当前高亮喵
                    val currentPlaylist by viewModel.currentPlaylist.observeAsState(emptyList())
                    val currentSongIndex by viewModel.currentSongIndex.observeAsState(-1)
                    val currentPlayingPath = currentPlaylist.getOrNull(currentSongIndex)?.filePath

                    CategoryScreen(
                        items = items,
                        currentPlayingPath = currentPlayingPath,
                        onOpenFolder = remember(viewModel, type, items) {
                            { folder ->
                                viewModel.openSongList(folder.name, folder.songs, type, items)
                            }
                        },
                        isSelectionMode = isSelectionMode,
                        selectedFolders = selectedFolders,
                        onToggleFolderSelection = remember(viewModel) {
                            { folder -> viewModel.toggleFolderSelection(folder) }
                        },
                        listState = categoryScrollStates.getOrPut(label) { 
                            androidx.compose.foundation.lazy.LazyListState() 
                        }
                    )
                }
            }
        }
    }
}

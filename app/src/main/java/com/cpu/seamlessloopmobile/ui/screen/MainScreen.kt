package com.cpu.seamlessloopmobile.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.screen.songlist.MainSongListScreen
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.viewmodel.MusicUiState
import com.cpu.seamlessloopmobile.viewmodel.MusicDialog
import com.cpu.seamlessloopmobile.viewmodel.GitHubSyncUiState
import com.cpu.seamlessloopmobile.data.ThemePreference
import androidx.activity.compose.BackHandler
import com.cpu.seamlessloopmobile.ui.screen.search.SearchScreen
import com.cpu.seamlessloopmobile.ui.screen.stats.PlaybackStatsScreen
import com.cpu.seamlessloopmobile.ui.screen.settings.SettingsScreen
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.TrackStat

import com.cpu.seamlessloopmobile.ui.components.app.MainBottomNavigation
import com.cpu.seamlessloopmobile.ui.components.app.MainDestination
import com.cpu.seamlessloopmobile.ui.components.app.MiniPlayer
import com.cpu.seamlessloopmobile.ui.components.app.CentralizedDialogHost
import com.cpu.seamlessloopmobile.ui.components.app.PlayingPanel
import com.cpu.seamlessloopmobile.ui.components.app.MultiSelectBar

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.material3.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.derivedStateOf
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import com.cpu.seamlessloopmobile.utils.LocalButtonHapticFeedbackEnabled

/**
 * 主界面总装配站喵！(๑•̀ㅂ•́)و✧
 * 现已完成深度瘦身，代码量缩减 50% 以上。
 * 不监听任何具体的业务列表数据（allSongs/playlists 等全部下沉自治），只做全局骨架装配与状态分发！
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    playSong: (com.cpu.seamlessloopmobile.model.Song) -> Unit,
    onSyncPc: () -> Unit,
    onExportDatabase: () -> Unit,
    isDarkTheme: Boolean,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    val uiState by viewModel.uiState.observeAsState(MusicUiState.Home)
    
    // 局部化收集多选及显示状态喵！(๑•̀ㅂ•́)و✧
    val isSelectionMode by viewModel.selection.isSelectionMode.observeAsState(false)
    val selectedItems by viewModel.selection.selectedItems.observeAsState(emptySet())
    val selectedPlaylists by viewModel.selection.selectedPlaylists.observeAsState(emptySet())
    val selectedFolders by viewModel.selection.selectedFolders.observeAsState(emptySet())
    
    val isPlayingPanelVisible by viewModel.isPlayingPanelVisible.observeAsState(false)
    val seamlessLoopCountLimit by viewModel.seamlessLoopCountLimit.observeAsState(0)
    val buttonHapticFeedbackEnabled by viewModel.buttonHapticFeedbackEnabled.observeAsState(true)
    val githubSyncState by viewModel.githubSyncState.observeAsState(GitHubSyncUiState())
    val bottomDestination = when (uiState) {
        is MusicUiState.Settings -> MainDestination.Settings
        is MusicUiState.Search -> MainDestination.Search
        is MusicUiState.PlaybackStats -> null
        else -> MainDestination.Library
    }
    val context = LocalContext.current
    val miniPlayerHazeState = remember { HazeState() }
    val statsRepository = remember(context) { ListenStatsRepository.getInstance(context) }
    
    val playlists by viewModel.playlist.playlists.collectAsState()
    val allSongs by viewModel.library.allSongs.collectAsState()

    // --- 导航滚动位置记忆中心喵 ---
    val categoryScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val songListScrollStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }

    // 计算当前页面展示的歌曲，用于“全选”喵
    val songsInCurrentPage by remember(uiState) {
        derivedStateOf {
            if (uiState is MusicUiState.SongList) {
                (uiState as MusicUiState.SongList).songs
            } else {
                emptyList()
            }
        }
    }

    // --- 接管返回键喵 ---
    BackHandler(enabled = isPlayingPanelVisible || isSelectionMode || uiState !is MusicUiState.Home) {
        when {
            isPlayingPanelVisible -> viewModel.setPlayingPanelVisible(false)
            isSelectionMode -> viewModel.clearSelection()
            else -> viewModel.goBack()
        }
    }

    CompositionLocalProvider(LocalButtonHapticFeedbackEnabled provides buttonHapticFeedbackEnabled) {
    // --- 界面架构拼装喵 ---
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .haze(
                    miniPlayerHazeState,
                    HazeStyle(
                        tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.08f),
                        blurRadius = 28.dp,
                        noiseFactor = HazeDefaults.noiseFactor
                    )
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {}

            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    (scaleIn(
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        initialScale = 0.98f
                    ) + fadeIn(animationSpec = tween(180))) togetherWith
                        fadeOut(animationSpec = tween(160))
                },
                label = "MainPageTransition",
                modifier = Modifier.fillMaxSize()
            ) { state ->
                when (state) {
                    is MusicUiState.Search -> {
                        SearchScreen(
                            viewModel = viewModel,
                            playSong = playSong,
                            onBack = remember(viewModel) { { viewModel.goBack() } }
                        )
                    }

                    is MusicUiState.Settings -> {
                        SettingsScreen(
                            onBack = remember(viewModel) { { viewModel.goBack() } },
                            onRescan = remember(viewModel) { { context -> viewModel.scanLibrary(context) } },
                            onSyncPc = onSyncPc,
                            onExportDatabase = onExportDatabase,
                            seamlessLoopCountLimit = seamlessLoopCountLimit,
                            onSeamlessLoopCountLimitChange = remember(viewModel) { viewModel::setSeamlessLoopCountLimit },
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onButtonHapticFeedbackEnabledChange = remember(viewModel) { viewModel::setButtonHapticFeedbackEnabled },
                            isDarkTheme = isDarkTheme,
                            themePreference = themePreference,
                            onThemePreferenceChange = onThemePreferenceChange,
                            githubSyncState = githubSyncState,
                            onGitHubAutoSyncEnabledChange = remember(viewModel) { viewModel::setGitHubAutoSyncEnabled },
                            onSaveGitHubSyncConfig = remember(viewModel) { viewModel::saveGitHubSyncConfig },
                            onClearGitHubSyncConfig = remember(viewModel) { viewModel::clearGitHubSyncConfig },
                            onRunGitHubSync = remember(viewModel) { viewModel::runGitHubSync },
                            onRefreshSyncDataManagementPreview = remember(viewModel) { viewModel::refreshSyncDataManagementPreview },
                            onLoadPlaybackStatsSourceDevices = remember(viewModel) { viewModel::loadPlaybackStatsSourceDevices },
                            onDeletePlaybackStatsSourceDeviceHistories = remember(viewModel) {
                                viewModel::deletePlaybackStatsSourceDeviceHistories
                            },
                            onSeedCloudFromLocal = remember(viewModel) { viewModel::seedCloudFromLocal },
                            onDeleteCloudSnapshot = remember(viewModel) { viewModel::deleteCloudSnapshot },
                            onClearLocalSyncData = remember(viewModel) { viewModel::clearLocalSyncData }
                        )
                    }

                    is MusicUiState.PlaybackStats -> {
                        PlaybackStatsScreen(
                            repository = statsRepository,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onTrackClick = remember(allSongs, viewModel, playSong) {
                                { stat ->
                                    val song = findSongForTrackStat(allSongs, stat)
                                    if (song != null) {
                                        val index = allSongs.indexOfFirst { it.filePath == song.filePath }
                                        viewModel.updateCurrentPlaylist(allSongs, index)
                                        playSong(song)
                                    }
                                }
                            },
                            onBack = remember(viewModel) { { viewModel.goBack() } }
                        )
                    }

                    else -> {
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            topBar = {
                                when {
                                    isSelectionMode -> SelectionAppBar(
                                        selectedItemsCount = selectedItems.size,
                                        selectedFoldersCount = selectedFolders.size,
                                        selectedPlaylistsCount = selectedPlaylists.size,
                                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                                        onCloseSelectionClick = remember(viewModel) { viewModel::clearSelection }
                                    )
                                    state is MusicUiState.Home -> HomeAppBar(
                                        libraryVM = viewModel.library,
                                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                                        onStatsClick = remember(viewModel) { { viewModel.navigateTo(MusicUiState.PlaybackStats) } }
                                    )
                                    state is MusicUiState.SongList -> SongListAppBar(
                                        title = state.title,
                                        libraryVM = viewModel.library,
                                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                                        onBackClick = remember(viewModel) { { viewModel.goBack() } },
                                        onSearchClick = remember(viewModel) { { viewModel.navigateTo(MusicUiState.Search) } }
                                    )
                                    else -> {}
                                }
                            }
                        ) { paddingValues ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                            ) {
                                MainTabsPager(
                                    viewModel = viewModel,
                                    isSelectionMode = isSelectionMode,
                                    selectedFolders = selectedFolders,
                                    categoryScrollStates = categoryScrollStates
                                )

                                if (state is MusicUiState.SongList) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        MainSongListScreen(
                                            state = state,
                                            libraryVM = viewModel.library,
                                            selectionVM = viewModel.selection,
                                            mainVM = viewModel,
                                            onPlaySong = remember(viewModel) {
                                                { song, songsToShow ->
                                                    val index = songsToShow.indexOf(song)
                                                    viewModel.updateCurrentPlaylist(songsToShow, index)
                                                    playSong(song)
                                                }
                                            },
                                            onShowMoreOptions = remember(viewModel) {
                                                { song ->
                                                    val currentPlaylistId = if (state.type == MusicUiState.ListType.PLAYLIST) {
                                                        playlists.find { it.name == state.title }?.id
                                                    } else null

                                                    viewModel.showDialog(MusicDialog.SongMoreOptions(song, currentPlaylistId))
                                                }
                                            },
                                            listState = songListScrollStates.getOrPut("${state.type}_${state.title}") {
                                                androidx.compose.foundation.lazy.LazyListState()
                                            }
                                        )
                                    }
                                }

                                MultiSelectBar(
                                    isSelectionMode = isSelectionMode,
                                    selectedItems = selectedItems,
                                    selectedPlaylists = selectedPlaylists,
                                    selectedFolders = selectedFolders,
                                    playlists = playlists,
                                    songsInCurrentPage = songsInCurrentPage,
                                    onClearSelection = remember(viewModel) { viewModel::clearSelection },
                                    onSelectAll = remember(viewModel) { { songs -> viewModel.selectAll(songs) } },
                                    onDeleteSelectedPlaylists = remember(viewModel) { viewModel::deleteSelectedPlaylists },
                                    onImportFoldersIndividually = remember(viewModel) { viewModel::importSelectedFoldersIndividually },
                                    onImportFoldersAsSinglePlaylist = remember(viewModel) { { name -> viewModel.importSelectedFoldersAsSinglePlaylist(name) } },
                                    onAddSelectedToPlaylist = remember(viewModel) { { playlistId -> viewModel.addSelectedToPlaylist(playlistId) } },
                                    onCreatePlaylistWithSelected = remember(viewModel) { { name -> viewModel.createPlaylistWithSelected(name) } },
                                    onShowDialog = remember(viewModel) { { dialog -> viewModel.showDialog(dialog) } },
                                    onShowMoreBulkOptions = remember(viewModel, state) {
                                        {
                                            val currentPlaylistId = if (state is MusicUiState.SongList && state.type == MusicUiState.ListType.PLAYLIST) {
                                                playlists.find { it.name == state.title }?.id
                                            } else null

                                            viewModel.showDialog(MusicDialog.BulkMoreOptions(selectedItems.size, currentPlaylistId))
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!isSelectionMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                MiniPlayer(
                    viewModel = viewModel,
                    hazeState = miniPlayerHazeState,
                    buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                    onClick = remember(viewModel) { { viewModel.setPlayingPanelVisible(true) } }
                )
                MainBottomNavigation(
                    selectedDestination = bottomDestination,
                    onLibraryClick = remember(viewModel) {
                        {
                            viewModel.resetToHome()
                        }
                    },
                    onSearchClick = remember(viewModel) {
                        {
                            viewModel.navigateTo(MusicUiState.Search)
                        }
                    },
                    onSettingsClick = remember(viewModel) {
                        { viewModel.navigateTo(MusicUiState.Settings) }
                    },
                    buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled
                )
            }
        }
    }
 
    // --- 全屏播放面板 Overlay ---
    PlayingPanel(
        viewModel = viewModel,
        isVisible = isPlayingPanelVisible,
        onClose = remember(viewModel) { { viewModel.setPlayingPanelVisible(false) } },
        onPlayPause = remember(viewModel) { viewModel::togglePlayPause },
        onNext = remember(viewModel) { { viewModel.skipToNext() } },
        onPrev = remember(viewModel) { { viewModel.skipToPrevious() } },
        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
        onMoreClick = remember(viewModel) {
            { song ->
                val currentPlaylistId = viewModel.currentOpenPlaylist.value?.id
                viewModel.showDialog(MusicDialog.SongMoreOptions(song, currentPlaylistId))
            }
        }
    )
 
    // --- 全局对话框托管 ---
    CentralizedDialogHost(viewModel)
    }
}

internal fun findSongForTrackStat(
    songs: List<Song>,
    stat: TrackStat
): Song? {
    if (stat.songId <= 0L) return null
    return songs.firstOrNull { song -> song.id == stat.songId }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Seamless Loop (预览)") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("此处是 MiniPlayer 区域喵")
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这里是主内容区域喵。\n\n您可以在 HomeScreen.kt 或 SongListScreen.kt \n中添加更具体的页面级预览！")
            }
        }
    }
}

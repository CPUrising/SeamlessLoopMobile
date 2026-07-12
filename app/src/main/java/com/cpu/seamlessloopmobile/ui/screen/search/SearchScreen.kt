package com.cpu.seamlessloopmobile.ui.screen.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.components.common.TopAppBarSearchBar
import com.cpu.seamlessloopmobile.ui.screen.songlist.SongListScreen
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.viewmodel.MusicDialog
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    playSong: (Song) -> Unit,
    onBack: () -> Unit
) {
    val allSongs by viewModel.library.allSongs.collectAsState()
    val isSelectionMode by viewModel.selection.isSelectionMode.observeAsState(false)
    val selectedItems by viewModel.selection.selectedItems.observeAsState(emptySet())
    val currentPlaylist by viewModel.currentPlaylist.observeAsState(emptyList())
    val currentSongIndex by viewModel.currentSongIndex.observeAsState(-1)

    var searchQuery by remember { mutableStateOf("") }
    var filteredSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val visibleSongs = if (searchQuery.isBlank()) allSongs else filteredSongs

    // --- 300ms 响应式搜索防抖过滤机制喵！🔍 ---
    LaunchedEffect(searchQuery, allSongs) {
        if (searchQuery.isBlank()) {
            filteredSongs = allSongs
        } else {
            delay(300)
            filteredSongs = allSongs.filter { song ->
                song.fileName.lowercase().contains(searchQuery.lowercase()) ||
                song.displayName.lowercase().contains(searchQuery.lowercase()) ||
                song.artist.lowercase().contains(searchQuery.lowercase()) ||
                song.album.lowercase().contains(searchQuery.lowercase())
            }
        }
    }

    val handleBack = remember(isSelectionMode, onBack) {
        {
            if (isSelectionMode) {
                viewModel.clearSelection()
            } else {
                onBack()
            }
        }
    }
    BackHandler(onBack = handleBack)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = {
                    TopAppBarSearchBar(
                        value = searchQuery,
                        onValueChange = { searchQuery = it }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val currentPlayingPath = currentPlaylist.getOrNull(currentSongIndex)?.filePath
            
            SongListScreen(
                songs = visibleSongs,
                currentPlayingSongPath = currentPlayingPath,
                isSelectionMode = isSelectionMode,
                selectedItems = selectedItems,
                onPlaySong = { song ->
                    val index = visibleSongs.indexOf(song)
                    viewModel.updateCurrentPlaylist(visibleSongs, index)
                    playSong(song)
                },
                onToggleSelection = { song ->
                    if (!isSelectionMode) viewModel.setSelectionMode(true)
                    viewModel.toggleSelection(song.filePath)
                },
                onShowMoreOptions = { song ->
                    viewModel.showDialog(MusicDialog.SongMoreOptions(song, null))
                },
                isSearchType = true,
                searchQuery = searchQuery
            )
        }
    }
}

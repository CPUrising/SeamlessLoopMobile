package com.cpu.seamlessloopmobile.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.ui.state.DataUiState
import com.cpu.seamlessloopmobile.utils.rememberHapticClick

@Composable
private fun SyncStatusSmallText(syncStatus: DataUiState<String>) {
    when (syncStatus) {
        is DataUiState.Loading -> {
            Text(
                "🔍 寻找新曲子中...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        is DataUiState.Error -> {
            Text(
                "❌ 扫描失败: ${syncStatus.message}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        is DataUiState.Success -> {
            if (syncStatus.data.isNotEmpty()) {
                Text(
                    syncStatus.data,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppBar(
    libraryVM: com.cpu.seamlessloopmobile.viewmodel.LibraryViewModel,
    buttonHapticFeedbackEnabled: Boolean = true,
    onStatsClick: () -> Unit
) {
    val syncStatus by libraryVM.syncStatus.collectAsState()
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("媒体库", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                SyncStatusSmallText(syncStatus = syncStatus)
            }
        },
        actions = {
            IconButton(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onStatsClick)) {
                Icon(Icons.Default.BarChart, contentDescription = "播放统计")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListAppBar(
    title: String,
    libraryVM: com.cpu.seamlessloopmobile.viewmodel.LibraryViewModel,
    buttonHapticFeedbackEnabled: Boolean = true,
    onBackClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val syncStatus by libraryVM.syncStatus.collectAsState()
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SyncStatusSmallText(syncStatus = syncStatus)
            }
        },
        navigationIcon = {
            IconButton(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onBackClick)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onSearchClick)) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    selectedItemsCount: Int,
    selectedFoldersCount: Int,
    selectedPlaylistsCount: Int,
    buttonHapticFeedbackEnabled: Boolean = true,
    onCloseSelectionClick: () -> Unit
) {
    val selectionTitle = when {
        selectedItemsCount > 0 -> "已选择 ${selectedItemsCount} 首歌曲"
        selectedFoldersCount > 0 -> "已选择 ${selectedFoldersCount} 个文件夹"
        selectedPlaylistsCount > 0 -> "已选择 ${selectedPlaylistsCount} 个歌单"
        else -> "已选择 ${selectedItemsCount + selectedFoldersCount + selectedPlaylistsCount} 项"
    }

    TopAppBar(
        title = {
            Text(selectionTitle, fontWeight = FontWeight.Bold)
        },
        navigationIcon = {
            IconButton(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onCloseSelectionClick)) {
                Icon(Icons.Default.Close, contentDescription = "取消选择")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}


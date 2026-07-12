package com.cpu.seamlessloopmobile.ui.screen.category

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.model.Folder
import com.cpu.seamlessloopmobile.ui.components.common.CategoryListItem

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState

@Composable
fun CategoryScreen(
    items: List<Folder>,
    currentPlayingPath: String?,
    onOpenFolder: (Folder) -> Unit,
    isSelectionMode: Boolean,
    selectedFolders: Set<Folder>,
    onToggleFolderSelection: (Folder) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 176.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(items) { folder ->
            val isSelected = selectedFolders.any { it.path == folder.path }
            val isPlaying = currentPlayingPath != null && folder.songs.any { it.filePath == currentPlayingPath }
            
            // 专辑、歌手、文件夹使用各自的合适图标喵！
            val icon = when {
                folder.path.startsWith("album_") -> Icons.Default.Album
                folder.path.startsWith("artist_") -> Icons.Default.Person
                else -> Icons.Default.Folder
            }

            CategoryListItem(
                title = folder.name,
                subtitle = "${folder.songs.size} 首歌曲",
                icon = icon,
                isSelected = isSelected,
                isPlaying = isPlaying,
                isSelectionMode = isSelectionMode,
                onClick = {
                    if (isSelectionMode) {
                        onToggleFolderSelection(folder)
                    } else {
                        onOpenFolder(folder)
                    }
                },
                onLongClick = { onToggleFolderSelection(folder) }
            )
        }
    }
}

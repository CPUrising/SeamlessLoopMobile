package com.cpu.seamlessloopmobile.ui.components.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.model.Folder
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.utils.rememberHapticClick
import com.cpu.seamlessloopmobile.viewmodel.MusicDialog

/**
 * 多选悬浮操作栏组件喵！(๑•̀ㅂ•́)و✧
 * 从 MainScreen 中完美抽离，专门用于批量删除歌单、合并导入文件夹或为歌曲归档。
 */
@Composable
fun MultiSelectBar(
    isSelectionMode: Boolean,
    selectedItems: Set<String>,
    selectedPlaylists: Set<Int>,
    selectedFolders: Set<Folder>,
    playlists: List<Playlist>,
    songsInCurrentPage: List<Song>,
    onClearSelection: () -> Unit,
    onSelectAll: (List<Song>) -> Unit,
    onDeleteSelectedPlaylists: () -> Unit,
    onImportFoldersIndividually: () -> Unit,
    onImportFoldersAsSinglePlaylist: (String) -> Unit,
    onAddSelectedToPlaylist: (Int) -> Unit,
    onCreatePlaylistWithSelected: (String) -> Unit,
    onShowDialog: (MusicDialog) -> Unit,
    onShowMoreBulkOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSelectionMode) return

    Box(
        modifier = modifier
            .padding(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedPlaylists.isNotEmpty()) {
                    Text(
                        text = "已选 ${selectedPlaylists.size} 个歌单",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = rememberHapticClick {
                        onShowDialog(
                            MusicDialog.ConfirmDeletePlaylist(
                                playlist = Playlist(name = "选中的 ${selectedPlaylists.size} 个歌单"),
                                onConfirm = onDeleteSelectedPlaylists
                            )
                        )
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除歌单")
                    }
                    IconButton(onClick = rememberHapticClick(onClick = onClearSelection)) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择")
                    }
                } else if (selectedFolders.isNotEmpty()) {
                    Text(
                        text = "已选 ${selectedFolders.size} 个文件夹",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = rememberHapticClick {
                        onShowDialog(
                            MusicDialog.ImportFoldersOptions(
                                count = selectedFolders.size,
                                onIndividual = onImportFoldersIndividually,
                                onMerge = { 
                                    onShowDialog(
                                        MusicDialog.MergeFoldersName { name ->
                                            onImportFoldersAsSinglePlaylist(name)
                                        }
                                    )
                                }
                            )
                        )
                    }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "导入文件夹")
                    }
                    IconButton(onClick = rememberHapticClick(onClick = onClearSelection)) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择")
                    }
                } else {
                    Text(
                        text = "已选 ${selectedItems.size} 首",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = rememberHapticClick {
                        val dialog = if (playlists.isNotEmpty()) {
                            MusicDialog.AddToPlaylist(
                                playlists = playlists,
                                onAdd = { p -> onAddSelectedToPlaylist(p.id) },
                                onCreateNew = {
                                    onShowDialog(
                                        MusicDialog.CreatePlaylist { name ->
                                            onCreatePlaylistWithSelected(name)
                                        }
                                    )
                                }
                            )
                        } else {
                            MusicDialog.CreatePlaylist { name ->
                                onCreatePlaylistWithSelected(name)
                            }
                        }
                        onShowDialog(dialog)
                    }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "添加到歌单")
                    }

                    val isAllSelected = songsInCurrentPage.isNotEmpty() && selectedItems.size >= songsInCurrentPage.size
                    IconButton(onClick = rememberHapticClick {
                        if (isAllSelected) {
                            onSelectAll(emptyList())
                        } else {
                            onSelectAll(songsInCurrentPage)
                        }
                    }) {
                        Icon(
                            imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = if (isAllSelected) "取消全选" else "全选"
                        )
                    }

                    IconButton(onClick = rememberHapticClick(onClick = onShowMoreBulkOptions)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    
                    IconButton(onClick = rememberHapticClick(onClick = onClearSelection)) {
                        Icon(Icons.Default.Close, contentDescription = "取消选择")
                    }
                }
            }
        }
    }
}

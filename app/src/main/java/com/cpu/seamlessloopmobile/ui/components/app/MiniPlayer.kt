package com.cpu.seamlessloopmobile.ui.components.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.ui.components.common.SongArtwork
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.utils.rememberHapticClick
import com.cpu.seamlessloopmobile.utils.TimeUtils
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay

/**
 * 底部迷你控制栏，已移动至 ui/components/app/ 包下，完全状态驱动喵！(๑•̀ㅂ•́)و✧
 */
@Composable
fun MiniPlayer(
    viewModel: MainViewModel,
    hazeState: HazeState? = null,
    buttonHapticFeedbackEnabled: Boolean = true,
    onClick: () -> Unit
) {
    val metadata by viewModel.metadata.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val totalDuration by viewModel.totalDuration.collectAsState()
    val audioPlayState by viewModel.audioPlayState.collectAsState()
    val currentSongIndex by viewModel.currentSongIndex.observeAsState(-1)
    val playlist by viewModel.currentPlaylist.observeAsState(emptyList())
    
    val isPlaying = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.PLAYING
    val isPreparing = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.PREPARING
    val isError = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.ERROR
    val playingSong = playlist.getOrNull(currentSongIndex)
    val title = playingSong?.displayName ?: metadata?.description?.title ?: "未在播放"
    val progress = (if (totalDuration > 0L) currentPosition.toFloat() / totalDuration.toFloat() else 0f)
        .coerceIn(0f, 1f)
    
    var showLoading by remember { mutableStateOf(false) }
    
    LaunchedEffect(isPreparing) {
        if (isPreparing) {
            delay(2000)
            showLoading = true
        } else {
            showLoading = false
        }
    }
    
    val sampleRate = 44100L
    val shape = RoundedCornerShape(24.dp)
    val enableHaze = hazeState != null
    val glassFillAlpha = if (enableHaze) 0.22f else 0.34f
    val surfaceAlpha = if (enableHaze) 0.46f else 0.9f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 0.dp)
            .clip(shape)
            .then(
                if (hazeState != null) Modifier.hazeChild(state = hazeState, shape = shape)
                else Modifier
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = surfaceAlpha),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = glassFillAlpha), shape)
        ) {
            GlassProgressOverlay(progress = progress, shape = shape)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .graphicsLayer { translationY = 0.5f }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(
                    coverPath = playingSong?.coverPath,
                    contentDescription = title.toString(),
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick)
                        ),
                    shape = RoundedCornerShape(14.dp),
                    iconSize = 24.dp,
                    backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    iconTint = MaterialTheme.colorScheme.primary
                )

                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = title.toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${TimeUtils.formatTime(currentPosition, sampleRate)} / ${TimeUtils.formatTime(totalDuration, sampleRate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) { viewModel.skipToPrevious() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    IconButton(
                        onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                            if (isError) {
                                Unit
                            } else if (isPlaying) viewModel.pause()
                            else viewModel.play()
                        },
                        enabled = !isPreparing,
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (showLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "播放/暂停",
                                modifier = Modifier.size(22.dp),
                                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) { viewModel.skipToNext() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GlassProgressOverlay(
    progress: Float,
    shape: Shape
) {
    if (progress <= 0f) return

    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(
                    Color.White.copy(alpha = 0.04f)
                )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MiniPlayerPreview() {
    MaterialTheme {
        Text("MiniPlayer 预览需要模拟 ViewModel 状态", modifier = Modifier.padding(16.dp))
    }
}

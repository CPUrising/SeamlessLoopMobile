package com.cpu.seamlessloopmobile.ui.components.app

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.ui.components.common.FineTunePage
import com.cpu.seamlessloopmobile.ui.components.common.MainInfoPage
import com.cpu.seamlessloopmobile.ui.components.common.PlaybackProgressBar
import com.cpu.seamlessloopmobile.ui.components.common.PlaybackControls
import com.cpu.seamlessloopmobile.jni.NativeAudio
import com.cpu.seamlessloopmobile.ui.theme.SeamlessLoopPlayerColors
import androidx.compose.ui.platform.LocalContext
import com.cpu.seamlessloopmobile.utils.rememberHapticClick

/**
 * 全屏音频播放核心面板，已移动至 ui/components/app/ 目录并融入 SeamlessLoopTheme 配色喵！(๑•̀ㅂ•́)و✧
 */
@Composable
fun PlayingPanel(
    viewModel: MainViewModel,
    isVisible: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    buttonHapticFeedbackEnabled: Boolean = true,
    onMoreClick: (com.cpu.seamlessloopmobile.model.Song) -> Unit
) {
    val context = LocalContext.current
    val currentSongIndex by viewModel.currentSongIndex.observeAsState(-1)
    val playlist by viewModel.currentPlaylist.observeAsState(emptyList())
    val audioPlayState by viewModel.audioPlayState.collectAsState()
    val mediaSessionPosition by viewModel.currentPosition.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val isPlaying = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.PLAYING
    val isPreparing = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.PREPARING
    val isError = audioPlayState == com.cpu.seamlessloopmobile.audio.AudioPlayState.ERROR
    val playMode by viewModel.playMode.observeAsState(com.cpu.seamlessloopmobile.viewmodel.PlayMode.SINGLE_LOOP)
    
    var showLoading by remember { mutableStateOf(false) }

    LaunchedEffect(isPreparing) {
        if (isPreparing) {
            kotlinx.coroutines.delay(2000)
            showLoading = true
        } else {
            showLoading = false
        }
    }
    
    val playingSong = if (currentSongIndex in playlist.indices) playlist[currentSongIndex] else null
    
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    
    var tempLoopStart by remember(playingSong?.id) { mutableStateOf(playingSong?.loopStart ?: 0L) }
    var tempLoopEnd by remember(playingSong?.id) { mutableStateOf(playingSong?.loopEnd ?: 0L) }

    // Candidate selection updates the Song first; mirror that back into the fine-tune controls.
    LaunchedEffect(playingSong?.id, playingSong?.loopStart, playingSong?.loopEnd) {
        tempLoopStart = playingSong?.loopStart ?: 0L
        tempLoopEnd = playingSong?.loopEnd ?: 0L
    }

    LaunchedEffect(playingSong?.id) {
        viewModel.clearDetectedLoopPoints()
    }

    AnimatedVisibility(
        visible = isVisible && playingSong != null,
        enter = slideInVertically(
            animationSpec = tween(280),
            initialOffsetY = { it / 8 }
        ) + fadeIn(animationSpec = tween(220)),
        exit = slideOutVertically(
            animationSpec = tween(220),
            targetOffsetY = { it / 10 }
        ) + fadeOut(animationSpec = tween(180))
    ) {
        val songItem = playingSong ?: return@AnimatedVisibility

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            SeamlessLoopPlayerColors.GradientStart,
                            SeamlessLoopPlayerColors.GradientEnd
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- 顶部控制 ---
                val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topPadding + 64.dp)
                        .padding(top = topPadding, start = 4.dp, end = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClose),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = SeamlessLoopPlayerColors.PrimaryText,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(2) { index ->
                            val selected = pagerState.currentPage == index
                            val indicatorWidth by animateDpAsState(
                                targetValue = if (selected) 18.dp else 6.dp,
                                animationSpec = tween(160),
                                label = "player_page_indicator"
                            )
                            Box(
                                modifier = Modifier
                                    .width(indicatorWidth)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (selected) {
                                            SeamlessLoopPlayerColors.Primary
                                        } else {
                                            SeamlessLoopPlayerColors.Inactive
                                        }
                                    )
                            )
                        }
                    }

                    IconButton(
                        onClick = rememberHapticClick(buttonHapticFeedbackEnabled) { onMoreClick(songItem) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = SeamlessLoopPlayerColors.PrimaryText,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // --- 分页内容 ---
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> MainInfoPage(
                            songItem = songItem,
                            isPlaying = isPlaying,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onRatingClick = { viewModel.cycleSongRating(songItem) }
                        )
                        1 -> {
                            val isDetecting by viewModel.isDetectingLoop.collectAsState()
                            
                            FineTunePage(
                                song = songItem,
                                tempLoopStart = tempLoopStart,
                                tempLoopEnd = tempLoopEnd,
                                isDetecting = isDetecting,
                                onStartValueChange = { tempLoopStart = it },
                                onEndValueChange = { tempLoopEnd = it },
                                onStartAdjustMs = { deltaMs ->
                                    val sampleRate = NativeAudio.getSampleRate()
                                    val deltaSamples = (sampleRate * deltaMs / 1000.0).toLong()
                                    val dur = NativeAudio.getDuration()
                                    val actualEnd = if (tempLoopEnd > 0) tempLoopEnd else dur
                                    tempLoopStart = (tempLoopStart + deltaSamples).coerceIn(0, actualEnd)
                                },
                                onEndAdjustMs = { deltaMs ->
                                    val sampleRate = NativeAudio.getSampleRate()
                                    val deltaSamples = (sampleRate * deltaMs / 1000.0).toLong()
                                    val dur = NativeAudio.getDuration()
                                    tempLoopEnd = (tempLoopEnd + deltaSamples).coerceIn(tempLoopStart, dur)
                                },
                                onEditClick = { isStart ->
                                    viewModel.showDialog(
                                        com.cpu.seamlessloopmobile.viewmodel.MusicDialog.LoopEdit(
                                            isStart = isStart,
                                            initialSamples = if(isStart) tempLoopStart else tempLoopEnd,
                                            onConfirm = { newValue ->
                                                if (isStart) tempLoopStart = newValue
                                                else tempLoopEnd = newValue
                                            }
                                        )
                                    )
                                },
                                onApplyAndListen = {
                                    viewModel.applyAndListenToLoop(songItem, tempLoopStart, tempLoopEnd)
                                },
                                onDetectClick = {
                                    viewModel.detectLoopPoints(context, songItem)
                                }
                            )
                        }
                    }
                }

                // --- 固定在底部的进度条与控制部分 ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val isSeamlessLoopEnabled by viewModel.isSeamlessLoopEnabled.observeAsState(true)

                    PlaybackProgressBar(
                        song = songItem,
                        fallbackPositionFrames = mediaSessionPosition.takeIf { it > 0L }
                            ?: playbackStatePositionFrames(playbackState),
                        onSeekComplete = { viewModel.refreshMediaSessionPosition() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    PlaybackControls(
                        playMode = playMode,
                        isSeamlessLoopEnabled = isSeamlessLoopEnabled,
                        isPlaying = isPlaying,
                        isPreparing = isPreparing,
                        isError = isError,
                        showLoading = showLoading,
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onTogglePlayMode = { viewModel.togglePlayMode() },
                        onToggleSeamlessLoop = { viewModel.setSeamlessLoopEnabled(!isSeamlessLoopEnabled) },
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext
                    )
                }
            }
        }
    }
}

private fun playbackStatePositionFrames(
    playbackState: android.support.v4.media.session.PlaybackStateCompat?
): Long {
    val positionMs = playbackState?.position ?: return 0L
    if (positionMs <= 0L) return 0L

    val sampleRate = runCatching { NativeAudio.getSampleRate() }
        .getOrDefault(44100)
        .takeIf { it > 0 }
        ?: 44100
    return positionMs * sampleRate / 1000L
}

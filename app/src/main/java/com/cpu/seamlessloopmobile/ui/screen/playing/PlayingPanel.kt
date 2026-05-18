package com.cpu.seamlessloopmobile.ui.screen.playing

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.utils.TimeUtils
import com.cpu.seamlessloopmobile.ui.components.FineTunePage
import com.cpu.seamlessloopmobile.ui.components.LoopEditDialog
import com.cpu.seamlessloopmobile.ui.components.MainInfoPage
import com.cpu.seamlessloopmobile.ui.components.PlaybackProgressBar
import com.cpu.seamlessloopmobile.ui.components.PlaybackControls
import com.cpu.seamlessloopmobile.jni.NativeAudio

@Composable
fun PlayingPanel(
    viewModel: MainViewModel,
    isVisible: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    val currentSongIndex by viewModel.currentSongIndex.observeAsState(-1)
    val playlist by viewModel.currentPlaylist.observeAsState(emptyList())
    val audioPlayState by viewModel.audioPlayState.collectAsState()
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
    
    // 状态管理：使用 VerticalPagerState 实现纵向滑动切换喵
    val pagerState = rememberPagerState(initialPage = 0) { 2 } // 0: 主页, 1: 调节页
    
    // --- 临时循环点状态 (仅在调节页显示时使用) ---
    var tempLoopStart by remember(playingSong?.id) { mutableStateOf(playingSong?.loopStart ?: 0L) }
    var tempLoopEnd by remember(playingSong?.id) { mutableStateOf(playingSong?.loopEnd ?: 0L) }

    AnimatedVisibility(
        visible = isVisible && playingSong != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val songItem = playingSong ?: return@AnimatedVisibility

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E1E2E),
                            Color(0xFF2E2E3E)
                        )
                    )
                )
                // 🛡️ 莱芙加的绝对防御防穿透护盾喵！拦截所有底部点击
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- 顶部控制 (关闭按钮 & 指示器) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起", tint = Color.White)
                    }
                    
                    // 页面指示器改为横向小点
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(2) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (pagerState.currentPage == index) 8.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(if (pagerState.currentPage == index) Color(0xFFBB86FC) else Color.Gray)
                            )
                        }
                    }
                }

                // --- 分页内容 (权重 1 占据剩余空间) ---
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> MainInfoPage(songItem, isPlaying, onRatingClick = { viewModel.cycleSongRating(songItem) }) // 主页只剩封面和歌曲信息
                        1 -> FineTunePage(
                            song = songItem,
                            tempLoopStart = tempLoopStart,
                            tempLoopEnd = tempLoopEnd,
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
                                        initialSamples = if(isStart) tempLoopStart else (if (tempLoopEnd == 0L) songItem.totalSamples else tempLoopEnd),
                                        onConfirm = { newValue ->
                                            if (isStart) tempLoopStart = newValue
                                            else tempLoopEnd = newValue
                                        }
                                    )
                                )
                            },
                            onApplyAndListen = {
                                viewModel.applyAndListenToLoop(songItem, tempLoopStart, tempLoopEnd)
                            }
                        )
                    }
                }

                // --- 固定在底部的进度条与控制部分 ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. 紧凑型进度条
                    PlaybackProgressBar(songItem)

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. 紧凑型播放控制栏
                    PlaybackControls(
                        playMode = playMode,
                        isPlaying = isPlaying,
                        isPreparing = isPreparing,
                        isError = isError,
                        showLoading = showLoading,
                        onTogglePlayMode = { viewModel.togglePlayMode() },
                        onPrev = onPrev,
                        onPlayPause = onPlayPause,
                        onNext = onNext
                    )
                }
            }
        }
    }
}

package com.cpu.seamlessloopmobile.ui.components.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cpu.seamlessloopmobile.jni.LoopPoint
import com.cpu.seamlessloopmobile.jni.NativeAudio
import com.cpu.seamlessloopmobile.ui.theme.SeamlessLoopPlayerColors
import com.cpu.seamlessloopmobile.utils.rememberHapticClick
import java.util.Locale

/**
 * 自动探测循环点候选列表弹窗喵！(๑•̀ㅂ•́)و✧
 * 展示所有候选循环点的得分、起点/终点位置，
 * 点击任一行会跳转到循环终点前 3 秒开始试听衔接效果。
 */
@Composable
fun LoopCandidatesDialog(
    candidates: List<LoopPoint>,
    sampleRate: Int,
    onSelect: (LoopPoint) -> Unit,
    onReanalyze: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(20.dp),
            color = SeamlessLoopPlayerColors.Panel,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- 顶栏 ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "循环点候选列表",
                        color = SeamlessLoopPlayerColors.PrimaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = rememberHapticClick(onClick = onDismiss)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = SeamlessLoopPlayerColors.Inactive
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = SeamlessLoopPlayerColors.PrimaryText.copy(alpha = 0.08f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // --- 候选列表 ---
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(candidates) { index, point ->
                        LoopCandidateRow(
                            index = index,
                            point = point,
                            sampleRate = sampleRate,
                            onClick = { onSelect(point) }
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = SeamlessLoopPlayerColors.PrimaryText.copy(alpha = 0.08f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                // --- 底部重新探测按钮 ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    OutlinedButton(
                        onClick = onReanalyze,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            SeamlessLoopPlayerColors.Primary.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = SeamlessLoopPlayerColors.Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "重新探测",
                            color = SeamlessLoopPlayerColors.Primary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopCandidateRow(
    index: Int,
    point: LoopPoint,
    sampleRate: Int,
    onClick: () -> Unit
) {
    val startSeconds = point.loopStart.toDouble() / sampleRate
    val endSeconds = point.loopEnd.toDouble() / sampleRate
    val durationSeconds = (point.loopEnd - point.loopStart).toDouble() / sampleRate
    val framesCount = point.loopEnd - point.loopStart
    val scorePercent = point.score * 100

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = SeamlessLoopPlayerColors.Control,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：播放图标 + 序号
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "试听",
                tint = SeamlessLoopPlayerColors.Primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            // 中间：循环点信息
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "候选 ${index + 1}",
                        color = SeamlessLoopPlayerColors.PrimaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = SeamlessLoopPlayerColors.Primary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.1f%%", scorePercent),
                            color = SeamlessLoopPlayerColors.Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "区间: %.3fs 至 %.3fs  (循环 %.3fs)",
                        startSeconds, endSeconds, durationSeconds
                    ),
                    color = SeamlessLoopPlayerColors.TertiaryText,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "采样: %d 至 %d  (共 %d 帧)",
                        point.loopStart, point.loopEnd, framesCount
                    ),
                    color = SeamlessLoopPlayerColors.TertiaryText,
                    fontSize = 12.sp
                )
            }
        }
    }
}

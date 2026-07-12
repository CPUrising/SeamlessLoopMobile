package com.cpu.seamlessloopmobile.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalInspectionMode
import com.cpu.seamlessloopmobile.jni.NativeAudio
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.theme.SeamlessLoopPlayerColors

/**
 * 循环微调面板，已完美融入 CPU 大人的 SeamlessLoopTheme 主题系统喵！(๑•̀ㅂ•́)و✧
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun FineTunePage(
    song: Song, 
    tempLoopStart: Long,
    tempLoopEnd: Long,
    isDetecting: Boolean = false,
    onStartValueChange: (Long) -> Unit,
    onEndValueChange: (Long) -> Unit,
    onStartAdjustMs: (Double) -> Unit,
    onEndAdjustMs: (Double) -> Unit,
    onEditClick: (Boolean) -> Unit,
    onApplyAndListen: () -> Unit,
    onDetectClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(2.dp))

        TuneSectionBox(
            label = "循环起点 (A)",
            samples = tempLoopStart,
            accentColor = SeamlessLoopPlayerColors.PointAccentA,
            onValueChange = onStartValueChange,
            onAdjustMs = onStartAdjustMs,
            onEditClick = { onEditClick(true) }
        )

        Spacer(modifier = Modifier.height(6.dp))

        TuneSectionBox(
            label = "循环终点 (B)",
            samples = tempLoopEnd,
            accentColor = SeamlessLoopPlayerColors.PointAccentB,
            onValueChange = onEndValueChange,
            onAdjustMs = onEndAdjustMs,
            onEditClick = { onEditClick(false) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- 自动检测区域喵 ---
        if (isDetecting) {
            Row(
                modifier = Modifier.height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = SeamlessLoopPlayerColors.Primary,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "正在探测循环点",
                    style = MaterialTheme.typography.labelLarge,
                    color = SeamlessLoopPlayerColors.SecondaryText
                )
            }
        } else {
            OutlinedButton(
                onClick = onDetectClick,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SeamlessLoopPlayerColors.Primary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SeamlessLoopPlayerColors.Primary, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "自动探测循环点",
                    style = MaterialTheme.typography.labelLarge,
                    color = SeamlessLoopPlayerColors.Primary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onApplyAndListen,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SeamlessLoopPlayerColors.Control),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Hearing, contentDescription = null, tint = SeamlessLoopPlayerColors.PrimaryText, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = "应用并试听",
                style = MaterialTheme.typography.labelLarge,
                color = SeamlessLoopPlayerColors.PrimaryText
            )
        }
    }
}

@Composable
fun TuneSectionBox(
    label: String,
    samples: Long,
    accentColor: Color,
    onValueChange: (Long) -> Unit,
    onAdjustMs: (Double) -> Unit,
    onEditClick: () -> Unit
) {
    val isPreview = LocalInspectionMode.current
    val sampleRate = if (isPreview) 44100 else NativeAudio.getSampleRate()
    val seconds = samples.toDouble() / sampleRate

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SeamlessLoopPlayerColors.Panel.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SeamlessLoopPlayerColors.PrimaryText.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = SeamlessLoopPlayerColors.SecondaryText,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable { onEditClick() },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = samples.toString(),
                    color = accentColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.3fs", seconds),
                    color = SeamlessLoopPlayerColors.TertiaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = accentColor.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(0.5f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 第一排: 最小/现/最大
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TuneGridButton("最小", modifier = Modifier.weight(1f)) { onValueChange(0) }
                TuneGridButton("现在", modifier = Modifier.weight(1f)) { if(!isPreview) onValueChange(NativeAudio.getCurrentPosition()) }
                TuneGridButton("最大", modifier = Modifier.weight(1f)) { if(!isPreview) onValueChange(NativeAudio.getDuration()) }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 第二排: 秒级调节
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TuneGridButton("-5s", modifier = Modifier.weight(1f)) { onAdjustMs(-5000.0) }
                TuneGridButton("-1s", modifier = Modifier.weight(1f)) { onAdjustMs(-1000.0) }
                TuneGridButton("+1s", modifier = Modifier.weight(1f)) { onAdjustMs(1000.0) }
                TuneGridButton("+5s", modifier = Modifier.weight(1f)) { onAdjustMs(5000.0) }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 第三排: 毫秒级调节
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TuneGridButton("-0.1s", modifier = Modifier.weight(1f)) { onAdjustMs(-100.0) }
                TuneGridButton("-0.01s", modifier = Modifier.weight(1f)) { onAdjustMs(-10.0) }
                TuneGridButton("+0.01s", modifier = Modifier.weight(1f)) { onAdjustMs(10.0) }
                TuneGridButton("+0.1s", modifier = Modifier.weight(1f)) { onAdjustMs(100.0) }
            }
        }
    }
}

@Composable
fun TuneGridButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            color = SeamlessLoopPlayerColors.Control,
            shape = RoundedCornerShape(7.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = SeamlessLoopPlayerColors.SecondaryText,
                fontWeight = FontWeight.Medium
            )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1E1E2E)
@Composable
@Suppress("SdCardPath")
fun FineTunePagePreview() {
    Box(modifier = Modifier.background(SeamlessLoopPlayerColors.GradientStart)) {
        FineTunePage(
            song = Song(
                id = 1L,
                fileName = "example.wav",
                filePath = "/sdcard/music/example.wav",
                totalSamples = 1000000L,
                displayName = "示例歌曲 - cpu 大人专用",
                artist = "莱芙",
                duration = 300000L,
                loopStart = 1000L,
                loopEnd = 5000L
            ),
            tempLoopStart = 1000L,
            tempLoopEnd = 5000L,
            onStartValueChange = {},
            onEndValueChange = {},
            onStartAdjustMs = {},
            onEndAdjustMs = {},
            onEditClick = {},
            onApplyAndListen = {}
        )
    }
}

package com.cpu.seamlessloopmobile.ui.screen.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsPeriod
import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.ui.components.common.SongArtwork
import com.cpu.seamlessloopmobile.utils.rememberHapticClick
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

private data class PeriodTrackStat(
    val stat: TrackStat,
    val listenMs: Long
)

internal fun canNavigateToPlaybackStat(stat: TrackStat): Boolean = stat.songId > 0L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackStatsScreen(
    repository: ListenStatsRepository,
    buttonHapticFeedbackEnabled: Boolean = true,
    onTrackClick: (TrackStat) -> Unit = {},
    onBack: () -> Unit
) {
    val allStats by repository.allStats.collectAsState()
    var selectedPeriod by remember { mutableStateOf(ListenStatsPeriod.ALL) }
    var today by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    LaunchedEffect(Unit) {
        while (true) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
            val delayMillis = Duration.between(now, nextMidnight)
                .toMillis()
                .coerceIn(1L, 15 * 60 * 1000L)
            delay(delayMillis)
            today = LocalDate.now(ZoneId.systemDefault())
        }
    }
    val sortedStats = remember(allStats, selectedPeriod, today) {
        allStats
            .mapNotNull { stat ->
                stat.listenMsFor(selectedPeriod, today)
                    .takeIf { it > 0L }
                    ?.let { listenMs -> PeriodTrackStat(stat, listenMs) }
            }
            .sortedByDescending { it.listenMs }
    }
    val totalListenMs = remember(sortedStats) {
        sortedStats.fold(0L) { total, periodStat ->
            val listenMs = periodStat.listenMs.coerceAtLeast(0L)
            if (total > Long.MAX_VALUE - listenMs) Long.MAX_VALUE else total + listenMs
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("播放统计") },
                navigationIcon = {
                    IconButton(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 176.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PeriodSelector(
                    selectedPeriod = selectedPeriod,
                    onPeriodSelected = { selectedPeriod = it }
                )
            }

            item {
                OverviewSection(totalListenMs = totalListenMs, trackedSongsCount = sortedStats.size)
            }

            if (sortedStats.isEmpty()) {
                item {
                    EmptyStatsState(selectedPeriod)
                }
            } else {
                item {
                    TopTracksBarChart(
                        stats = sortedStats.take(5)
                    )
                }

                item {
                    Text(
                        text = "最常听",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                itemsIndexed(
                    sortedStats,
                    key = { _, it ->
                        val stat = it.stat
                        "${stat.identityKey}|${stat.songId}|${stat.filePath}|${stat.fileName}"
                    }
                ) { index, periodStat ->
                    PlaybackStatRow(
                        rank = index + 1,
                        periodStat = periodStat,
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onClick = { onTrackClick(periodStat.stat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodSelector(
    selectedPeriod: ListenStatsPeriod,
    onPeriodSelected: (ListenStatsPeriod) -> Unit
) {
    val periods = remember {
        listOf(
            ListenStatsPeriod.DAY to "日",
            ListenStatsPeriod.WEEK to "周",
            ListenStatsPeriod.MONTH to "月",
            ListenStatsPeriod.YEAR to "年",
            ListenStatsPeriod.ALL to "总"
        )
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        periods.forEachIndexed { index, (period, label) ->
            SegmentedButton(
                modifier = Modifier.weight(1f),
                selected = period == selectedPeriod,
                onClick = { onPeriodSelected(period) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = periods.size),
                label = { Text(label, maxLines = 1) }
            )
        }
    }
}

@Composable
private fun OverviewSection(
    totalListenMs: Long,
    trackedSongsCount: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OverviewMetric(label = "收听时长", value = formatListenDuration(totalListenMs))
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            OverviewMetric(label = "已追踪", value = "$trackedSongsCount 首")
        }
    }
}

@Composable
private fun PlaybackStatRow(
    rank: Int,
    periodStat: PeriodTrackStat,
    buttonHapticFeedbackEnabled: Boolean,
    onClick: () -> Unit
) {
    val stat = periodStat.stat
    val canNavigate = canNavigateToPlaybackStat(stat)
    val isStale = remember(stat.songId, stat.filePath) {
        stat.songId <= 0L || stat.filePath.isBlank() || !File(stat.filePath).exists()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canNavigate) {
                        Modifier.clickable(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick))
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rank.toString(),
                modifier = Modifier.width(24.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SongArtwork(
                coverPath = stat.coverPath,
                contentDescription = stat.displayName,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                iconSize = 18.dp,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                iconTint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stat.displayName.ifBlank { stat.fileName.ifBlank { "未知歌曲" } },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stat.artist.ifBlank { "Unknown Artist" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = formatListenDuration(periodStat.listenMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isStale) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (stat.songId <= 0L) "未绑定" else "文件缺失",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopTracksBarChart(stats: List<PeriodTrackStat>) {
    if (stats.isEmpty()) return

    val maxListenMs = stats.maxOf { it.listenMs }.coerceAtLeast(1L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        stats.forEachIndexed { index, periodStat ->
            val stat = periodStat.stat
            val fraction = (periodStat.listenMs.toFloat() / maxListenMs.toFloat()).coerceIn(0f, 1f)
            val animatedFraction = animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(durationMillis = 500 + index * 90),
                label = "top_track_bar_$index"
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.width(18.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    SongArtwork(
                        coverPath = stat.coverPath,
                        contentDescription = stat.displayName,
                        modifier = Modifier.size(30.dp),
                        shape = RoundedCornerShape(10.dp),
                        iconSize = 14.dp,
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 8.dp)
                    ) {
                        Text(
                            text = stat.displayName.ifBlank { stat.fileName.ifBlank { "未知歌曲" } },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = formatListenDuration(periodStat.listenMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction.value)
                            .defaultMinSize(minWidth = 10.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.OverviewMetric(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyStatsState(selectedPeriod: ListenStatsPeriod) {
    val title = when (selectedPeriod) {
        ListenStatsPeriod.DAY -> "本日暂无收听记录"
        ListenStatsPeriod.WEEK -> "本周暂无收听记录"
        ListenStatsPeriod.MONTH -> "本月暂无收听记录"
        ListenStatsPeriod.YEAR -> "今年暂无收听记录"
        ListenStatsPeriod.ALL -> "还没有可显示的收听记录"
    }
    val description = if (selectedPeriod == ListenStatsPeriod.ALL) {
        "开始播放后会在这里累计收听时长。"
    } else {
        "选择其他时间范围查看收听时长。"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatListenDuration(totalListenMs: Long): String {
    if (totalListenMs <= 0L) return "0 秒"

    if (totalListenMs < 60_000L) {
        val seconds = (totalListenMs / 1000L).coerceAtLeast(1L)
        return "${seconds} 秒"
    }

    val totalMinutes = totalListenMs / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60

    return buildList {
        if (days > 0) add("${days} 天")
        if (hours > 0) add("${hours} 小时")
        if (minutes > 0 || isEmpty()) add("${minutes} 分钟")
    }.joinToString(" ")
}

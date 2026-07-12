package com.cpu.seamlessloopmobile.ui.screen.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpu.seamlessloopmobile.data.SettingsManager
import com.cpu.seamlessloopmobile.data.ThemePreference
import com.cpu.seamlessloopmobile.data.sync.LocalSyncDataSummary
import com.cpu.seamlessloopmobile.data.sync.PlaybackStatsSourceDeviceSummary
import com.cpu.seamlessloopmobile.utils.rememberHapticClick
import com.cpu.seamlessloopmobile.viewmodel.GitHubSyncUiState
import java.text.DateFormat
import java.util.Date

private enum class SettingsPage {
    Appearance,
    Playback,
    Data,
    Sync
}

internal data class PlaybackStatsSourceDevicePresentation(
    val activeSources: List<PlaybackStatsSourceDeviceSummary>,
    val historicalSourceCount: Int
)

internal fun playbackStatsSourceDevicePresentation(
    sources: List<PlaybackStatsSourceDeviceSummary>
): PlaybackStatsSourceDevicePresentation {
    val (historicalSources, activeSources) = sources.partition {
        it.allKnownGenerationsRemoved && !it.isCurrentDevice
    }
    return PlaybackStatsSourceDevicePresentation(
        activeSources = activeSources,
        historicalSourceCount = historicalSources.size
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRescan: (Context) -> Unit,
    onSyncPc: () -> Unit,
    onExportDatabase: () -> Unit,
    seamlessLoopCountLimit: Int,
    onSeamlessLoopCountLimitChange: (Int) -> Unit,
    buttonHapticFeedbackEnabled: Boolean,
    onButtonHapticFeedbackEnabledChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    githubSyncState: GitHubSyncUiState,
    onGitHubAutoSyncEnabledChange: (Boolean) -> Unit,
    onSaveGitHubSyncConfig: (token: String, owner: String, repo: String, branch: String, path: String) -> Unit,
    onClearGitHubSyncConfig: () -> Unit,
    onRunGitHubSync: () -> Unit,
    onRefreshSyncDataManagementPreview: () -> Unit,
    onLoadPlaybackStatsSourceDevices: () -> Unit,
    onDeletePlaybackStatsSourceDeviceHistories: (Set<String>) -> Unit,
    onSeedCloudFromLocal: () -> Unit,
    onDeleteCloudSnapshot: () -> Unit,
    onClearLocalSyncData: (
        clearPlaylists: Boolean,
        clearLoopPoints: Boolean,
        clearRatings: Boolean,
        clearListenStats: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var activePage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val languages = remember { listOf("简体中文") }
    var selectedLanguage by remember { mutableStateOf(languages[0]) }
    var loopLimitText by remember(seamlessLoopCountLimit) { mutableStateOf(seamlessLoopCountLimit.toString()) }
    var loopLimitSavedMessage by remember { mutableStateOf<String?>(null) }
    val maxLoopLimit = SettingsManager.MAX_SEAMLESS_LOOP_COUNT_LIMIT
    val parsedLoopLimit = remember(loopLimitText) { loopLimitText.toIntOrNull() }
    val loopLimitError = remember(loopLimitText, parsedLoopLimit, maxLoopLimit) {
        when {
            loopLimitText.isBlank() -> "请输入 0 到 $maxLoopLimit 之间的整数"
            !loopLimitText.all { it.isDigit() } -> "仅允许输入非负整数，不能包含符号、小数点或空格"
            parsedLoopLimit == null -> "数字过大，请输入 0 到 $maxLoopLimit 之间的整数"
            parsedLoopLimit > maxLoopLimit -> "循环次数不能超过 $maxLoopLimit"
            else -> null
        }
    }

    BackHandler(enabled = activePage != null) {
        activePage = null
    }

    val currentPage = activePage
    Box(modifier = modifier.fillMaxSize()) {
        if (currentPage == null) {
            SettingsHomePage(
                buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                onPageClick = { activePage = it }
            )
        } else {
            SettingsDetailPage(
                page = currentPage,
                onBack = { activePage = null },
                content = {
                    when (currentPage) {
                        SettingsPage.Appearance -> AppearanceSettings(
                            isDarkTheme = isDarkTheme,
                            themePreference = themePreference,
                            onThemePreferenceChange = onThemePreferenceChange,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onButtonHapticFeedbackEnabledChange = onButtonHapticFeedbackEnabledChange,
                            dropdownExpanded = dropdownExpanded,
                            onDropdownExpandedChange = { dropdownExpanded = it },
                            selectedLanguage = selectedLanguage,
                            languages = languages,
                            onLanguageSelected = { selectedLanguage = it }
                        )
                        SettingsPage.Playback -> PlaybackSettings(
                            loopLimitText = loopLimitText,
                            onLoopLimitTextChange = {
                                loopLimitText = it
                                loopLimitSavedMessage = null
                            },
                            loopLimitError = loopLimitError,
                            loopLimitSavedMessage = loopLimitSavedMessage,
                            maxLoopLimit = maxLoopLimit,
                            parsedLoopLimit = parsedLoopLimit,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onSaveLoopLimit = { value ->
                                onSeamlessLoopCountLimitChange(value)
                                loopLimitSavedMessage = if (value == 0) {
                                    "已保存：无限循环"
                                } else {
                                    "已保存：循环 $value 次后切换下一首"
                                }
                            }
                        )
                        SettingsPage.Data -> DataSettings(
                            onClosePage = onBack,
                            onRescan = onRescan,
                            onSyncPc = onSyncPc,
                            onExportDatabase = onExportDatabase,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled
                        )
                        SettingsPage.Sync -> GitHubSyncSettings(
                            githubSyncState = githubSyncState,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onGitHubAutoSyncEnabledChange = onGitHubAutoSyncEnabledChange,
                            onSaveGitHubSyncConfig = onSaveGitHubSyncConfig,
                            onClearGitHubSyncConfig = onClearGitHubSyncConfig,
                            onRunGitHubSync = onRunGitHubSync,
                            onRefreshSyncDataManagementPreview = onRefreshSyncDataManagementPreview,
                            onLoadPlaybackStatsSourceDevices = onLoadPlaybackStatsSourceDevices,
                            onDeletePlaybackStatsSourceDeviceHistories = onDeletePlaybackStatsSourceDeviceHistories,
                            onSeedCloudFromLocal = onSeedCloudFromLocal,
                            onDeleteCloudSnapshot = onDeleteCloudSnapshot,
                            onClearLocalSyncData = onClearLocalSyncData
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomePage(
    buttonHapticFeedbackEnabled: Boolean,
    onPageClick: (SettingsPage) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SettingsGroupCard(
                    title = "界面",
                    pages = listOf(SettingsPage.Appearance),
                    buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                    onPageClick = onPageClick
                )
            }
            item {
                SettingsGroupCard(
                    title = "播放与数据",
                    pages = listOf(SettingsPage.Playback, SettingsPage.Data, SettingsPage.Sync),
                    buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                    onPageClick = onPageClick
                )
            }
            item {
                FooterInfo()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailPage(
    page: SettingsPage,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(page.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { content() }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    pages: List<SettingsPage>,
    buttonHapticFeedbackEnabled: Boolean,
    onPageClick: (SettingsPage) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
        ) {
            pages.forEachIndexed { index, page ->
                SettingsNavigationRow(
                    page = page,
                    buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                    onClick = { onPageClick(page) }
                )
                if (index < pages.lastIndex) {
                    Divider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    page: SettingsPage,
    buttonHapticFeedbackEnabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBox(icon = page.icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = page.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettings(
    isDarkTheme: Boolean,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    buttonHapticFeedbackEnabled: Boolean,
    onButtonHapticFeedbackEnabledChange: (Boolean) -> Unit,
    dropdownExpanded: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit,
    selectedLanguage: String,
    languages: List<String>,
    onLanguageSelected: (String) -> Unit
) {
    SettingsSectionCard {
        ThemePreferenceSelector(
            themePreference = themePreference,
            isDarkTheme = isDarkTheme,
            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
            onThemePreferenceChange = onThemePreferenceChange
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    HapticFeedbackSettings(
        enabled = buttonHapticFeedbackEnabled,
        onEnabledChange = onButtonHapticFeedbackEnabledChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.Language)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "语言",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { onDropdownExpandedChange(!dropdownExpanded) }
        ) {
            OutlinedTextField(
                value = selectedLanguage,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { onDropdownExpandedChange(false) }
            ) {
                languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language) },
                        onClick = {
                            onLanguageSelected(language)
                            onDropdownExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HapticFeedbackSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    SettingsSectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    onClick = rememberHapticClick(enabled) {
                        onEnabledChange(!enabled)
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconBox(icon = Icons.Default.Vibration)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "点击触感反馈",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "播放器和设置页按钮点击时提供轻微反馈",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null
            )
        }
    }
}

@Composable
private fun ThemePreferenceSelector(
    themePreference: ThemePreference,
    isDarkTheme: Boolean,
    buttonHapticFeedbackEnabled: Boolean,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsIconBox(icon = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "显示模式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = when (themePreference) {
                    ThemePreference.SYSTEM -> if (isDarkTheme) "跟随系统，当前为深色" else "跟随系统，当前为浅色"
                    ThemePreference.LIGHT -> "固定使用浅色"
                    ThemePreference.DARK -> "固定使用深色"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemePreferenceButton(
            text = "跟随系统",
            selected = themePreference == ThemePreference.SYSTEM,
            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
            onClick = { onThemePreferenceChange(ThemePreference.SYSTEM) },
            modifier = Modifier.weight(1f)
        )
        ThemePreferenceButton(
            text = "浅色",
            selected = themePreference == ThemePreference.LIGHT,
            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
            onClick = { onThemePreferenceChange(ThemePreference.LIGHT) },
            modifier = Modifier.weight(1f)
        )
        ThemePreferenceButton(
            text = "深色",
            selected = themePreference == ThemePreference.DARK,
            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
            onClick = { onThemePreferenceChange(ThemePreference.DARK) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ThemePreferenceButton(
    text: String,
    selected: Boolean,
    buttonHapticFeedbackEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onClick),
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaybackSettings(
    loopLimitText: String,
    onLoopLimitTextChange: (String) -> Unit,
    loopLimitError: String?,
    loopLimitSavedMessage: String?,
    maxLoopLimit: Int,
    parsedLoopLimit: Int?,
    buttonHapticFeedbackEnabled: Boolean,
    onSaveLoopLimit: (Int) -> Unit
) {
    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.RepeatOne)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "无缝循环行为",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "控制单曲循环达到指定次数后的调度方式",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = loopLimitText,
            onValueChange = onLoopLimitTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("单曲无缝循环次数上限") },
            singleLine = true,
            isError = loopLimitError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    text = loopLimitError
                        ?: loopLimitSavedMessage
                        ?: "0 表示无限循环；最大值：$maxLoopLimit",
                    color = when {
                        loopLimitError != null -> MaterialTheme.colorScheme.error
                        loopLimitSavedMessage != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                errorBorderColor = MaterialTheme.colorScheme.error
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) { parsedLoopLimit?.let(onSaveLoopLimit) },
            enabled = loopLimitError == null && parsedLoopLimit != null,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存循环次数设置", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DataSettings(
    onClosePage: () -> Unit,
    onRescan: (Context) -> Unit,
    onSyncPc: () -> Unit,
    onExportDatabase: () -> Unit,
    buttonHapticFeedbackEnabled: Boolean
) {
    val context = LocalContext.current

    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.Sync)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "数据同步与管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "扫描媒体库，导入或导出 PC 端数据库",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                onClosePage()
                onRescan(context)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("重新扫描库音乐", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                onClosePage()
                onSyncPc()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("导入 PC 端数据库", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                onClosePage()
                onExportDatabase()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("导出 PC 端数据库", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GitHubSyncSettings(
    githubSyncState: GitHubSyncUiState,
    buttonHapticFeedbackEnabled: Boolean,
    onGitHubAutoSyncEnabledChange: (Boolean) -> Unit,
    onSaveGitHubSyncConfig: (token: String, owner: String, repo: String, branch: String, path: String) -> Unit,
    onClearGitHubSyncConfig: () -> Unit,
    onRunGitHubSync: () -> Unit,
    onRefreshSyncDataManagementPreview: () -> Unit,
    onLoadPlaybackStatsSourceDevices: () -> Unit,
    onDeletePlaybackStatsSourceDeviceHistories: (Set<String>) -> Unit,
    onSeedCloudFromLocal: () -> Unit,
    onDeleteCloudSnapshot: () -> Unit,
    onClearLocalSyncData: (
        clearPlaylists: Boolean,
        clearLoopPoints: Boolean,
        clearRatings: Boolean,
        clearListenStats: Boolean
    ) -> Unit
) {
    var token by rememberSaveable(githubSyncState.isConfigured, githubSyncState.hasToken) { mutableStateOf("") }
    var owner by rememberSaveable(githubSyncState.owner) { mutableStateOf(githubSyncState.owner) }
    var repo by rememberSaveable(githubSyncState.repo) { mutableStateOf(githubSyncState.repo) }
    var branch by rememberSaveable(githubSyncState.branch) { mutableStateOf(githubSyncState.branch) }
    var path by rememberSaveable(githubSyncState.path) { mutableStateOf(githubSyncState.path) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showSeedCloudDialog by remember { mutableStateOf(false) }
    var showDeleteCloudDialog by remember { mutableStateOf(false) }
    var showClearLocalDialog by remember { mutableStateOf(false) }
    var showDeleteSourceDevicesDialog by remember { mutableStateOf(false) }
    var clearLocalPlaylists by remember { mutableStateOf(false) }
    var clearLocalLoopPoints by remember { mutableStateOf(false) }
    var clearLocalRatings by remember { mutableStateOf(false) }
    var clearLocalListenStats by remember { mutableStateOf(false) }
    var selectedSourceDeviceIds by remember { mutableStateOf(setOf<String>()) }

    val missingConfigFields = owner.isBlank() || repo.isBlank() || branch.isBlank() || path.isBlank()
    val saveEnabled = !missingConfigFields && (githubSyncState.hasToken || token.isNotBlank())
    val managementActionEnabled = githubSyncState.isConfigured &&
        githubSyncState.hasToken &&
        !githubSyncState.isManagementLoading &&
        !githubSyncState.isManagementOperationRunning
    val clearLocalActionEnabled = !githubSyncState.isManagementLoading &&
        !githubSyncState.isManagementOperationRunning
    val clearLocalSelectionValid =
        clearLocalPlaylists || clearLocalLoopPoints || clearLocalRatings || clearLocalListenStats
    val autoSyncToggleEnabled = githubSyncState.canEnableAutoSync || githubSyncState.isAutoSyncEnabled
    val lastSyncText = remember(githubSyncState.lastSyncTime) {
        if (githubSyncState.lastSyncTime > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(githubSyncState.lastSyncTime))
        } else {
            null
        }
    }
    val report = githubSyncState.lastReport
    val managementPreview = githubSyncState.managementPreview
    val cloudPreview = managementPreview?.cloud
    val sourceDevicePresentation = remember(githubSyncState.playbackStatsSourceDevices) {
        playbackStatsSourceDevicePresentation(githubSyncState.playbackStatsSourceDevices)
    }
    val activePlaybackStatsSourceDevices = sourceDevicePresentation.activeSources
    fun resetClearLocalSelections() {
        clearLocalPlaylists = false
        clearLocalLoopPoints = false
        clearLocalRatings = false
        clearLocalListenStats = false
    }
    fun resetSourceDeviceSelections() {
        selectedSourceDeviceIds = emptySet()
    }
    val cloudExportedAtText = remember(cloudPreview?.exportedAt) {
        val exportedAt = cloudPreview?.exportedAt ?: 0L
        if (exportedAt > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(exportedAt))
        } else {
            null
        }
    }

    LaunchedEffect(Unit) {
        onLoadPlaybackStatsSourceDevices()
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除 GitHub 配置") },
            text = { Text("这会移除当前仓库配置和已保存的 Token。") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onClearGitHubSyncConfig()
                        token = ""
                        showClearDialog = false
                    }
                ) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        showClearDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showSeedCloudDialog) {
        AlertDialog(
            onDismissRequest = { showSeedCloudDialog = false },
            title = { Text("初始化云端同步") },
            text = { Text("仅当云端同步文件不存在时，才会用当前本机数据创建它。云端已有文件时请使用普通同步，或先删除云端文件。") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onSeedCloudFromLocal()
                        showSeedCloudDialog = false
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        showSeedCloudDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteCloudDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteCloudDialog = false },
            title = { Text("删除云端同步文件") },
            text = { Text("这会删除 GitHub 上的同步文件，本机数据会保留。") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onDeleteCloudSnapshot()
                        showDeleteCloudDialog = false
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        showDeleteCloudDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearLocalDialog) {
        AlertDialog(
            onDismissRequest = {
                showClearLocalDialog = false
                resetClearLocalSelections()
            },
            title = { Text("清理本机数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("所选内容将从本机清除。歌单、循环点和评分可能在后续同步中恢复；播放统计会同步永久删除标记，不会恢复已清理的代际。")
                    LocalDataOptionRow(
                        checked = clearLocalPlaylists,
                        label = "歌单",
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onCheckedChange = { clearLocalPlaylists = it }
                    )
                    LocalDataOptionRow(
                        checked = clearLocalLoopPoints,
                        label = "循环点",
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onCheckedChange = { clearLocalLoopPoints = it }
                    )
                    LocalDataOptionRow(
                        checked = clearLocalRatings,
                        label = "评分",
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onCheckedChange = { clearLocalRatings = it }
                    )
                    LocalDataOptionRow(
                        checked = clearLocalListenStats,
                        label = "播放统计",
                        buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                        onCheckedChange = { clearLocalListenStats = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onClearLocalSyncData(
                            clearLocalPlaylists,
                            clearLocalLoopPoints,
                            clearLocalRatings,
                            clearLocalListenStats
                        )
                        showClearLocalDialog = false
                        resetClearLocalSelections()
                    },
                    enabled = clearLocalSelectionValid
                ) {
                    Text("清理", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        showClearLocalDialog = false
                        resetClearLocalSelections()
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteSourceDevicesDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteSourceDevicesDialog = false
                resetSourceDeviceSelections()
            },
            title = { Text("删除来源设备历史") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("所选设备的播放统计历史将通过 tombstone 标记失效；当前设备会切换到新的空 generation。")
                    activePlaybackStatsSourceDevices.forEach { source ->
                        SourceDeviceOptionRow(
                            checked = source.deviceId in selectedSourceDeviceIds,
                            source = source,
                            buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                            onCheckedChange = { checked ->
                                selectedSourceDeviceIds = if (checked) {
                                    selectedSourceDeviceIds + source.deviceId
                                } else {
                                    selectedSourceDeviceIds - source.deviceId
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onDeletePlaybackStatsSourceDeviceHistories(selectedSourceDeviceIds)
                        showDeleteSourceDevicesDialog = false
                        resetSourceDeviceSelections()
                    },
                    enabled = selectedSourceDeviceIds.isNotEmpty()
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        showDeleteSourceDevicesDialog = false
                        resetSourceDeviceSelections()
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.CloudUpload)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (githubSyncState.isConfigured) "已连接 GitHub 仓库" else "尚未配置 GitHub 同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        githubSyncState.isSyncing -> "正在同步歌单、循环点与评分"
                        githubSyncState.isConfigured -> "${githubSyncState.owner}/${githubSyncState.repo}"
                        else -> "保存仓库信息后可手动同步"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (lastSyncText != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "上次同步：$lastSyncText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (githubSyncState.statusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = githubSyncState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (githubSyncState.errorMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = githubSyncState.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.Sync)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "仓库配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (githubSyncState.hasToken) "Token 留空时保留当前已保存值" else "首次保存需要填写 Token",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("GitHub Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            supportingText = {
                Text(if (githubSyncState.hasToken) "留空则保持已保存 Token" else "未检测到已保存 Token")
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Owner") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Repository") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Branch") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Path") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                onSaveGitHubSyncConfig(
                    token.trim(),
                    owner.trim(),
                    repo.trim(),
                    branch.trim(),
                    path.trim()
                )
                token = ""
            },
            enabled = saveEnabled,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存同步配置", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(
                    enabled = autoSyncToggleEnabled,
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        onGitHubAutoSyncEnabledChange(!githubSyncState.isAutoSyncEnabled)
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自动同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        githubSyncState.isAutoSyncEnabled && githubSyncState.canEnableAutoSync -> "网络可用时在后台约每小时同步一次"
                        githubSyncState.isAutoSyncEnabled -> "配置不完整，自动同步已暂停"
                        !githubSyncState.canEnableAutoSync -> "配置 Token 和仓库后可开启"
                        else -> "关闭时仅手动同步"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = githubSyncState.isAutoSyncEnabled,
                onCheckedChange = if (autoSyncToggleEnabled) onGitHubAutoSyncEnabledChange else null,
                enabled = autoSyncToggleEnabled
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onRunGitHubSync),
            enabled = githubSyncState.isConfigured && !githubSyncState.isSyncing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("立即同步", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                showClearDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("清除配置", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    SettingsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon = Icons.Default.CloudDownload)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "数据管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        githubSyncState.isManagementOperationRunning -> "正在处理同步数据"
                        githubSyncState.isManagementLoading -> "正在刷新数据预览"
                        else -> "查看本机与云端同步数据摘要"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = onRefreshSyncDataManagementPreview),
            enabled = managementActionEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("刷新数据预览", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        managementPreview?.let { preview ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "本机",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            SyncDataSummaryText(summary = preview.local)

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "云端",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                preview.cloud == null -> {
                    Text(
                        text = "尚未获取云端摘要",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                !preview.cloud.exists -> {
                    Text(
                        text = "云端同步文件不存在",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                else -> {
                    cloudExportedAtText?.let {
                        Text(
                            text = "导出时间：$it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = "歌单 ${preview.cloud.playlists}，项目 ${preview.cloud.playlistItems}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "循环点 ${preview.cloud.loopPointCount}，评分 ${preview.cloud.ratingCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "本机已匹配 ${preview.cloud.matchedSongReferenceCount} 首云端引用歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "本机未找到 ${preview.cloud.missingSongReferenceCount} 首云端引用歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "播放统计来源",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (activePlaybackStatsSourceDevices.isEmpty() && sourceDevicePresentation.historicalSourceCount == 0) {
            Text(
                text = "暂无来源设备记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            activePlaybackStatsSourceDevices.forEach { source ->
                PlaybackStatsSourceDeviceRow(source = source)
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (sourceDevicePresentation.historicalSourceCount > 0) {
                if (activePlaybackStatsSourceDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                DeletedPlaybackStatsSourcesRow(
                    count = sourceDevicePresentation.historicalSourceCount
                )
            }
            if (activePlaybackStatsSourceDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                        resetSourceDeviceSelections()
                        showDeleteSourceDevicesDialog = true
                    },
                    enabled = clearLocalActionEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("删除来源设备历史", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (githubSyncState.managementStatusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = githubSyncState.managementStatusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (githubSyncState.managementErrorMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = githubSyncState.managementErrorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                showSeedCloudDialog = true
            },
            enabled = managementActionEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("用本机数据初始化云端", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                showDeleteCloudDialog = true
            },
            enabled = managementActionEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("删除云端同步文件", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                resetClearLocalSelections()
                showClearLocalDialog = true
            },
            enabled = clearLocalActionEnabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("清理本机数据", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    if (report != null) {
        Spacer(modifier = Modifier.height(16.dp))

        SettingsSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsIconBox(icon = Icons.Default.CloudDownload)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "最近一次同步结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "冲突 ${report.conflicts.size} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "歌单 ${report.playlistsUploaded} 上传 / ${report.playlistsDownloaded} 下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "循环点 ${report.loopPointsUploaded} 上传 / ${report.loopPointsDownloaded} 下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "评分 ${report.ratingsUploaded} 上传 / ${report.ratingsDownloaded} 下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PlaybackStatsSourceDeviceRow(source: PlaybackStatsSourceDeviceSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconBox(icon = Icons.Default.Smartphone)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = source.fallbackLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildString {
                        append(source.platform)
                        source.currentGeneration?.let { append("  Generation $it") }
                        if (source.isCurrentDevice) append("  当前设备")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = formatPlaybackStatsSourceDuration(source.contributedListenMs),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DeletedPlaybackStatsSourcesRow(count: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconBox(icon = Icons.Default.Delete)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "已删除的来源（${count} 台）",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SourceDeviceOptionRow(
    checked: Boolean,
    source: PlaybackStatsSourceDeviceSummary,
    buttonHapticFeedbackEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                    onCheckedChange(!checked)
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = source.fallbackLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append(source.platform)
                    source.currentGeneration?.let { append("  Generation $it") }
                    if (source.isCurrentDevice) append("  当前设备")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatPlaybackStatsSourceDuration(source.contributedListenMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatPlaybackStatsSourceDuration(listenMs: Long): String {
    val totalMinutes = (listenMs.coerceAtLeast(0L) / 60_000L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun SyncDataSummaryText(summary: LocalSyncDataSummary) {
    Text(
        text = "歌曲 ${summary.songCount}，歌单 ${summary.playlistCount}，项目 ${summary.playlistItemCount}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "循环点 ${summary.loopPointCount}，评分 ${summary.ratingCount}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun LocalDataOptionRow(
    checked: Boolean,
    label: String,
    buttonHapticFeedbackEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                onClick = rememberHapticClick(buttonHapticFeedbackEnabled) {
                    onCheckedChange(!checked)
                }
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsSectionCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsIconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun FooterInfo() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Seamless Loop Mobile v0.2.0",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "莱芙・泽诺为您倾情服务喵 (´w｀)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            lineHeight = 16.sp
        )
    }
}

private val SettingsPage.title: String
    get() = when (this) {
        SettingsPage.Appearance -> "外观"
        SettingsPage.Playback -> "播放"
        SettingsPage.Data -> "数据"
        SettingsPage.Sync -> "GitHub 同步"
    }

private val SettingsPage.description: String
    get() = when (this) {
        SettingsPage.Appearance -> "语言、昼夜模式与触感反馈"
        SettingsPage.Playback -> "无缝循环次数与播放行为"
        SettingsPage.Data -> "扫描、导入和导出音乐数据"
        SettingsPage.Sync -> "同步歌单、循环点与评分"
    }

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.Appearance -> Icons.Default.DarkMode
        SettingsPage.Playback -> Icons.Default.RepeatOne
        SettingsPage.Data -> Icons.Default.Sync
        SettingsPage.Sync -> Icons.Default.CloudUpload
    }

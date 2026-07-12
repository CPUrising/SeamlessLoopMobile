package com.cpu.seamlessloopmobile.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpu.seamlessloopmobile.model.Folder
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.data.MusicRepository
import com.cpu.seamlessloopmobile.data.SettingsManager
import com.cpu.seamlessloopmobile.data.ThemePreference
import com.cpu.seamlessloopmobile.data.sync.ClearLocalSyncDataSelection
import com.cpu.seamlessloopmobile.data.sync.GitHubAutoSyncScheduler
import com.cpu.seamlessloopmobile.data.sync.GitHubSyncConfig
import com.cpu.seamlessloopmobile.data.sync.GitHubSyncCoordinator
import com.cpu.seamlessloopmobile.data.sync.SharedPreferencesGitHubSyncStore
import com.cpu.seamlessloopmobile.data.sync.SyncDataManagementRepository
import com.cpu.seamlessloopmobile.data.sync.SyncDataManagementResult
import com.cpu.seamlessloopmobile.data.sync.SyncOutcome
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSerializer
import com.cpu.seamlessloopmobile.data.sync.github.GitHubContentsSyncBackend
import com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStore
import com.cpu.seamlessloopmobile.data.sync.room.SharedPreferencesPlaylistIdMapper
import com.cpu.seamlessloopmobile.jni.LoopPoint
import com.cpu.seamlessloopmobile.jni.NativeAudio
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.FileInputStream
import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.net.toUri

enum class PlayMode {
    LIST_LOOP,    // 列表循环
    SINGLE_LOOP,  // 单曲循环
    SHUFFLE       // 随机播放
}

/**
 * 界面导航的大地图喵！
 */
sealed class MusicUiState {
    object Home : MusicUiState()
    object Search : MusicUiState()
    object Settings : MusicUiState()
    object PlaybackStats : MusicUiState()
    
    // 一级分类展开
    @Deprecated("Use Tab navigation instead")
    data class CategoryFolders(val title: String, val items: List<Folder>) : MusicUiState()
    
    // 二级歌曲列表
    data class SongList(
        val title: String, 
        val songs: List<Song>, 
        val type: ListType,
        val originalItems: List<Folder>? = null 
    ) : MusicUiState()
    
    enum class ListType { PLAYLIST, FOLDER, ALL_SONGS, ALBUM, ARTIST, FAVORITES, SEARCH }
}


/**
 * 主界面调度员喵！
 * 莱芙现在变聪明了，把具体的脏活累活都分给了 Library, Selection 和 Playlist 这三位管家，
 * 现在的 MainViewModel 只负责整体架构的指挥和 UI 状态的同步。
 */
class MainViewModel(
    private val repository: MusicRepository,
    private val mediaControlManager: com.cpu.seamlessloopmobile.audio.MediaControlManager
) : ViewModel() {
    lateinit var library: LibraryViewModel
    lateinit var selection: SelectionViewModel
    lateinit var playlist: PlaylistViewModel
    lateinit var loopDetection: LoopDetectionViewModel

    // GitHub 同步基础设施（由工厂注入）
    lateinit var githubSyncStore: SharedPreferencesGitHubSyncStore
    lateinit var playlistIdMapper: SharedPreferencesPlaylistIdMapper
    lateinit var roomSyncSnapshotStore: RoomSyncSnapshotStore
    lateinit var localSyncDataManagementRepository: SyncDataManagementRepository

    /** 数据管理仓库工厂 —— 由 [MainViewModelFactory] 注入，每次调用传入最新 [GitHubSyncConfig]。 */
    lateinit var syncDataManagementRepositoryFactory: (GitHubSyncConfig) -> SyncDataManagementRepository

    /** 自动同步调度器（由工厂注入）。 */
    lateinit var githubAutoSyncScheduler: GitHubAutoSyncScheduler

    private var settingsManager: SettingsManager? = null

    init {
        // 莱芙现在变聪明了，不在构造函数里乱连天线了喵！
    }

    // ===================================================================
    // GitHub 同步 UI 状态 & 操作
    // ===================================================================

    private val _githubSyncState = MutableLiveData(GitHubSyncUiState())
    val githubSyncState: LiveData<GitHubSyncUiState> = _githubSyncState

    private fun updateGitHubSyncState(
        transform: (GitHubSyncUiState) -> GitHubSyncUiState
    ) {
        val current = _githubSyncState.value ?: GitHubSyncUiState()
        _githubSyncState.value = transform(current)
    }

    /**
     * 从持久化存储加载 GitHub 同步配置状态。
     * 被 [MainViewModelFactory] 在初始化后立即调用。
     */
    fun loadGitHubSyncState() {
        viewModelScope.launch {
            val config = githubSyncStore.getConfig()
            val token = githubSyncStore.getToken()
            val hasUsableToken = !token.isNullOrBlank()
            val lastSyncTime = githubSyncStore.getLastSyncTime()
            val isAutoSyncEnabled = githubSyncStore.isAutoSyncEnabled()
            val current = _githubSyncState.value ?: GitHubSyncUiState()
            _githubSyncState.postValue(current.copy(
                isConfigured = config != null,
                hasToken = hasUsableToken,
                owner = config?.owner ?: "",
                repo = config?.repo ?: "",
                branch = config?.branch ?: "main",
                path = config?.path ?: "seamless-loop/sync.json",
                lastSyncTime = lastSyncTime,
                isAutoSyncEnabled = isAutoSyncEnabled
            ))
            // 根据已持久化的状态同步 WorkManager 调度
            githubAutoSyncScheduler.reconcile(isAutoSyncEnabled && config != null && hasUsableToken)
        }
    }

    /**
     * 保存 GitHub 同步配置及 token。
     * 允许 token 留空，此时保留已存储的 token（如有）。
     * @param token 新 token；空字符串表示不更改
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param branch 分支名
     * @param path 文件路径
     */
    fun saveGitHubSyncConfig(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        path: String
    ) {
        viewModelScope.launch {
            val normalizedOwner = owner.trim()
            val normalizedRepo = repo.trim()
            val normalizedBranch = branch.trim()
            val normalizedPath = path.trim()

            if (normalizedOwner.isBlank() ||
                normalizedRepo.isBlank() ||
                normalizedBranch.isBlank() ||
                normalizedPath.isBlank()
            ) {
                _githubSyncState.postValue(
                    _githubSyncState.value?.copy(errorMessage = "请填写完整的仓库信息")
                )
                return@launch
            }

            // token 为空时尝试保留现有 token
            val effectiveToken = if (token.isBlank()) {
                githubSyncStore.getToken()
            } else {
                token.trim()
            }

            if (effectiveToken == null) {
                _githubSyncState.postValue(
                    _githubSyncState.value?.copy(errorMessage = "请先填写 GitHub Token")
                )
                return@launch
            }

            githubSyncStore.saveToken(effectiveToken)
            githubSyncStore.saveConfig(
                GitHubSyncConfig(
                    owner = normalizedOwner,
                    repo = normalizedRepo,
                    branch = normalizedBranch,
                    path = normalizedPath
                )
            )
            _githubSyncState.postValue(
                _githubSyncState.value?.copy(
                    statusMessage = "GitHub 同步配置已保存",
                    errorMessage = ""
                )
            )
            loadGitHubSyncState()
            // 如果自动同步之前已开启，确保调度器在新配置下继续运行
            if (githubSyncStore.isAutoSyncEnabled()) {
                githubAutoSyncScheduler.reconcile(true)
            }
        }
    }

    /** 清除 GitHub 同步配置和 token，同时关闭自动同步并取消调度。 */
    fun clearGitHubSyncConfig() {
        viewModelScope.launch {
            githubSyncStore.clearToken()
            githubSyncStore.clearConfig()
            githubSyncStore.setAutoSyncEnabled(false)
            githubAutoSyncScheduler.cancel()
            _githubSyncState.postValue(
                GitHubSyncUiState(statusMessage = "GitHub 同步配置已清除")
            )
        }
    }

    /**
     * 设置自动同步开关。
     *
     * 开启时会检查配置和 token 是否就绪，不满足条件则拒绝开启并设置错误信息。
     * 关闭时取消 WorkManager 调度。
     */
    fun setGitHubAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // 开启前校验配置和 token 是否就绪
                val config = githubSyncStore.getConfig()
                val token = githubSyncStore.getToken()
                if (config == null || token.isNullOrBlank()) {
                    _githubSyncState.postValue(
                        _githubSyncState.value?.copy(
                            isAutoSyncEnabled = false,
                            errorMessage = "请先配置 GitHub Token 和仓库"
                        )
                    )
                    return@launch
                }
            }

            githubSyncStore.setAutoSyncEnabled(enabled)
            githubAutoSyncScheduler.reconcile(enabled)
            _githubSyncState.postValue(
                _githubSyncState.value?.copy(isAutoSyncEnabled = enabled, errorMessage = "")
            )
        }
    }

    /** 执行一次 GitHub 同步。 */
    fun runGitHubSync() {
        viewModelScope.launch {
            _githubSyncState.postValue(
                _githubSyncState.value?.copy(
                    isSyncing = true,
                    statusMessage = "正在同步 GitHub 数据...",
                    errorMessage = ""
                )
            )

            val config = githubSyncStore.getConfig()
            val token = githubSyncStore.getToken()

            if (config == null || token == null) {
                _githubSyncState.postValue(
                    _githubSyncState.value?.copy(
                        isSyncing = false,
                        errorMessage = "请先配置 GitHub Token 和仓库"
                    )
                )
                return@launch
            }

            val backend = GitHubContentsSyncBackend(
                config = config,
                tokenProvider = githubSyncStore,
                serializer = SyncSnapshotSerializer()
            )

            val coordinator = GitHubSyncCoordinator(
                backend = backend,
                snapshotStore = roomSyncSnapshotStore,
                metadataStore = githubSyncStore
            )

            val outcome = try {
                coordinator.syncNow()
            } catch (e: Exception) {
                _githubSyncState.postValue(
                    _githubSyncState.value?.copy(
                        isSyncing = false,
                        errorMessage = "GitHub 同步失败：${e.message ?: "未知错误"}"
                    )
                )
                return@launch
            }

            when (outcome) {
                is SyncOutcome.Success -> {
                    val lastSyncTime = githubSyncStore.getLastSyncTime()
                    _githubSyncState.postValue(
                        _githubSyncState.value?.copy(
                            isSyncing = false,
                            statusMessage = "GitHub 同步完成",
                            errorMessage = "",
                            lastSyncTime = lastSyncTime,
                            lastReport = outcome.report
                        )
                    )
                }
                is SyncOutcome.Failure -> {
                    _githubSyncState.postValue(
                        _githubSyncState.value?.copy(
                            isSyncing = false,
                            errorMessage = outcome.message
                        )
                    )
                }
                is SyncOutcome.LocalMutationDuringSync -> {
                    _githubSyncState.postValue(
                        _githubSyncState.value?.copy(
                            isSyncing = false,
                            errorMessage = "同步期间本地数据发生变化，请重试"
                        )
                    )
                }
                is SyncOutcome.Cancelled -> {
                    _githubSyncState.postValue(
                        _githubSyncState.value?.copy(
                            isSyncing = false,
                            statusMessage = "GitHub 同步已取消"
                        )
                    )
                }
            }
        }
    }

    // ===================================================================
    // 同步数据管理操作
    // ===================================================================

    /**
     * 构建 [SyncDataManagementRepository] 实例。
     * 如果 GitHub 未配置（无 config 或 token），
     * 设置 managementErrorMessage 并返回 null。
     */
    private suspend fun buildManagementRepository(): SyncDataManagementRepository? {
        val config = githubSyncStore.getConfig()
        val token = githubSyncStore.getToken()
        if (config == null || token == null) {
            updateGitHubSyncState {
                it.copy(managementErrorMessage = "请先配置 GitHub Token 和仓库")
            }
            return null
        }
        return try {
            syncDataManagementRepositoryFactory(config)
        } catch (e: Exception) {
            updateGitHubSyncState {
                it.copy(managementErrorMessage = "创建仓库实例失败：${e.message ?: "未知错误"}")
            }
            null
        }
    }

    /** 刷新数据管理预览。 */
    fun refreshSyncDataManagementPreview() {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementLoading = true,
                    isManagementOperationRunning = false,
                    managementErrorMessage = ""
                )
            }

            val repo = buildManagementRepository() ?: run {
                updateGitHubSyncState { it.copy(isManagementLoading = false) }
                return@launch
            }

            try {
                when (val result = repo.preview()) {
                    is SyncDataManagementResult.Success -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementLoading = false,
                                isManagementOperationRunning = false,
                                managementPreview = result.data,
                                managementStatusMessage = "数据预览已更新",
                                managementErrorMessage = ""
                            )
                        }
                    }
                    is SyncDataManagementResult.Failure -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementLoading = false,
                                isManagementOperationRunning = false,
                                managementErrorMessage = result.message
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateGitHubSyncState {
                    it.copy(
                        isManagementLoading = false,
                        isManagementOperationRunning = false,
                        managementErrorMessage = "预览失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 仅在云端同步文件不存在时，用本机数据创建初始云端快照。 */
    fun seedCloudFromLocal() {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementOperationRunning = true,
                    isManagementLoading = false,
                    managementErrorMessage = ""
                )
            }

            val repo = buildManagementRepository() ?: run {
                updateGitHubSyncState { it.copy(isManagementOperationRunning = false) }
                return@launch
            }

            try {
                when (val result = repo.seedCloudFromLocal()) {
                    is SyncDataManagementResult.Success -> {
                        val lastSyncTime = githubSyncStore.getLastSyncTime()
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementStatusMessage = "已用本机数据创建云端同步文件，可刷新数据预览",
                                statusMessage = "已创建初始云端同步文件",
                                lastSyncTime = lastSyncTime,
                                lastReport = result.data,
                                managementErrorMessage = ""
                            )
                        }
                    }
                    is SyncDataManagementResult.Failure -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementErrorMessage = result.message
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateGitHubSyncState {
                    it.copy(
                        isManagementOperationRunning = false,
                        isManagementLoading = false,
                        managementErrorMessage = "初始化云端失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** 删除云端同步快照文件。 */
    fun deleteCloudSnapshot() {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementOperationRunning = true,
                    isManagementLoading = false,
                    managementErrorMessage = ""
                )
            }

            val repo = buildManagementRepository() ?: run {
                updateGitHubSyncState { it.copy(isManagementOperationRunning = false) }
                return@launch
            }

            try {
                when (val result = repo.deleteCloudSnapshot()) {
                    is SyncDataManagementResult.Success -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementStatusMessage = "云端同步文件已删除，可刷新数据预览",
                                statusMessage = "云端同步文件已删除",
                                lastReport = null,
                                lastSyncTime = 0L,
                                managementPreview = it.managementPreview?.copy(
                                    cloud = com.cpu.seamlessloopmobile.data.sync.CloudSyncDataPreview(exists = false)
                                ),
                                managementErrorMessage = ""
                            )
                        }
                    }
                    is SyncDataManagementResult.Failure -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementErrorMessage = result.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                updateGitHubSyncState {
                    it.copy(
                        isManagementOperationRunning = false,
                        isManagementLoading = false,
                        managementErrorMessage = "删除云端快照失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /**
     * 清除本机同步相关数据。
     * @param clearPlaylists 是否清除歌单
     * @param clearLoopPoints 是否清除循环点
     * @param clearRatings 是否清除评分
     * @param clearListenStats 是否清除播放统计
     */
    fun clearLocalSyncData(
        clearPlaylists: Boolean,
        clearLoopPoints: Boolean,
        clearRatings: Boolean,
        clearListenStats: Boolean
    ) {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementOperationRunning = true,
                    isManagementLoading = false,
                    managementErrorMessage = ""
                )
            }

            val selection = ClearLocalSyncDataSelection(
                clearPlaylists = clearPlaylists,
                clearLoopPoints = clearLoopPoints,
                clearRatings = clearRatings,
                clearListenStats = clearListenStats
            )

            try {
                when (val result = localSyncDataManagementRepository.clearLocalSyncData(selection)) {
                    is SyncDataManagementResult.Success -> {
                        val currentPreview = _githubSyncState.value?.managementPreview
                        val updatedPreview = currentPreview?.copy(local = result.data)
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementStatusMessage = "所选本机数据已清除",
                                managementPreview = updatedPreview,
                                managementErrorMessage = ""
                            )
                        }
                    }
                    is SyncDataManagementResult.Failure -> {
                        updateGitHubSyncState {
                            it.copy(
                                isManagementOperationRunning = false,
                                isManagementLoading = false,
                                managementErrorMessage = result.message
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateGitHubSyncState {
                    it.copy(
                        isManagementOperationRunning = false,
                        isManagementLoading = false,
                        managementErrorMessage = "清除失败：${e.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    /** Loads local playback-stat source device data for the data-management UI. */
    fun loadPlaybackStatsSourceDevices() {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementLoading = true,
                    isManagementOperationRunning = false,
                    managementStatusMessage = "",
                    managementErrorMessage = ""
                )
            }
            when (val result = localSyncDataManagementRepository.getLocalPlaybackStatsSourceDevices()) {
                is SyncDataManagementResult.Success -> updateGitHubSyncState {
                    it.copy(
                        isManagementLoading = false,
                        playbackStatsSourceDevices = result.data,
                        managementStatusMessage = "播放统计来源已更新",
                        managementErrorMessage = ""
                    )
                }
                is SyncDataManagementResult.Failure -> updateGitHubSyncState {
                    it.copy(
                        isManagementLoading = false,
                        managementStatusMessage = "",
                        managementErrorMessage = result.message
                    )
                }
            }
        }
    }

    /** Deletes selected local playback-stat source histories using generation tombstones. */
    fun deletePlaybackStatsSourceDeviceHistories(deviceIds: Set<String>) {
        viewModelScope.launch {
            updateGitHubSyncState {
                it.copy(
                    isManagementOperationRunning = true,
                    isManagementLoading = false,
                    managementStatusMessage = "",
                    managementErrorMessage = ""
                )
            }
            when (val result = localSyncDataManagementRepository
                .deleteLocalPlaybackStatsSourceDeviceHistories(deviceIds)) {
                is SyncDataManagementResult.Success -> updateGitHubSyncState {
                    it.copy(
                        isManagementOperationRunning = false,
                        playbackStatsSourceDevices = result.data,
                        managementStatusMessage = "所选播放统计来源记录已删除",
                        managementErrorMessage = ""
                    )
                }
                is SyncDataManagementResult.Failure -> updateGitHubSyncState {
                    it.copy(
                        isManagementOperationRunning = false,
                        managementStatusMessage = "",
                        managementErrorMessage = result.message
                    )
                }
            }
        }
    }

    fun startObservation() {
        viewModelScope.launch {
            mediaControlManager.metadata.collect { meta ->
                val mediaId = meta?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                val mediaUri = meta?.getString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_URI)
                
                val list = _currentPlaylist.value ?: emptyList()
                var index = -1
                
                if (mediaId != null) {
                    index = list.indexOfFirst { it.mediaId.toString() == mediaId }
                }
                
                if (index == -1 && mediaUri != null) {
                    index = list.indexOfFirst { it.filePath == mediaUri }
                }

                if (index != -1 && index != _currentSongIndex.value) {
                    _currentSongIndex.postValue(index)
                }
            }
        }
    }

    // --- 播放引擎状态流代理喵 ---
    val playbackState = mediaControlManager.playbackState
    val metadata = mediaControlManager.metadata
    val currentPosition = mediaControlManager.currentPosition
    val totalDuration = mediaControlManager.totalDuration
    @Suppress("unused")
    val isConnected = mediaControlManager.isConnected
    val audioPlayState = mediaControlManager.audioPlayState

    fun initSettings(manager: SettingsManager) {
        this.settingsManager = manager
        
        // --- 修复：启动时把存在小本本里的播放模式读出来给 UI 喵！ ---
        val savedMode = manager.playMode
        _playMode.value = savedMode
        isSeamlessLoopEnabled.value = manager.isSeamlessLoopEnabled
        _seamlessLoopCountLimit.value = manager.seamlessLoopCountLimit
        _themePreference.value = manager.themePreference
        _buttonHapticFeedbackEnabled.value = manager.buttonHapticFeedbackEnabled
        
        // 给底层发个信号，告诉它当前的初始模式，也把状态机的模式同步好喵！
        val bundle = android.os.Bundle().apply {
            putInt("play_mode", savedMode.ordinal)
        }
        mediaControlManager.sendCustomAction("SET_PLAY_MODE", bundle)

        // 莱芙顺便帮 UI 界面把名单也请回来喵！
        restorePlaybackSession()
    }

    fun setSeamlessLoopEnabled(enabled: Boolean) {
        isSeamlessLoopEnabled.value = enabled
        settingsManager?.isSeamlessLoopEnabled = enabled
        val bundle = android.os.Bundle().apply {
            putBoolean("is_seamless_loop_enabled", enabled)
        }
        mediaControlManager.sendCustomAction("SET_SEAMLESS_LOOP_ENABLED", bundle)
    }

    private val _seamlessLoopCountLimit = MutableLiveData(0)
    val seamlessLoopCountLimit: LiveData<Int> = _seamlessLoopCountLimit

    private val _themePreference = MutableLiveData(ThemePreference.SYSTEM)
    val themePreference: LiveData<ThemePreference> = _themePreference

    private val _buttonHapticFeedbackEnabled = MutableLiveData(true)
    val buttonHapticFeedbackEnabled: LiveData<Boolean> = _buttonHapticFeedbackEnabled

    fun setSeamlessLoopCountLimit(limit: Int) {
        val safeLimit = limit.coerceIn(0, SettingsManager.MAX_SEAMLESS_LOOP_COUNT_LIMIT)
        _seamlessLoopCountLimit.value = safeLimit
        settingsManager?.seamlessLoopCountLimit = safeLimit
    }

    fun setThemePreference(preference: ThemePreference) {
        _themePreference.value = preference
        settingsManager?.themePreference = preference
    }

    fun setButtonHapticFeedbackEnabled(enabled: Boolean) {
        _buttonHapticFeedbackEnabled.value = enabled
        settingsManager?.buttonHapticFeedbackEnabled = enabled
    }

    private fun restorePlaybackSession() {
        val manager = settingsManager ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val songs = repository.getPlayQueueSongs()
            if (songs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _currentPlaylist.value = songs
                    _currentSongIndex.value = manager.currentSongIndex
                    android.util.Log.d("MainViewModel", "🏠 UI 层记忆由数据库恢复：已载入 ${songs.size} 首歌，当前序号 ${manager.currentSongIndex} 喵！")
                }
            }
        }
    }


    private val _playMode = MutableLiveData<PlayMode>(PlayMode.LIST_LOOP)
    val playMode: LiveData<PlayMode> = _playMode

    val isSeamlessLoopEnabled = MutableLiveData<Boolean>(true)

    private val _currentPlaylist = MutableLiveData<List<Song>>(emptyList())
    val currentPlaylist: LiveData<List<Song>> = _currentPlaylist

    private val _currentSongIndex = MutableLiveData<Int>(-1)
    val currentSongIndex: LiveData<Int> = _currentSongIndex

    // --- 页面状态喵 ---
    private val _currentOpenPlaylist = MutableLiveData<Playlist?>(null)
    @Suppress("unused")
    val currentOpenPlaylist: LiveData<Playlist?> = _currentOpenPlaylist

    private val _uiState = MutableLiveData<MusicUiState>(MusicUiState.Home)
    val uiState: LiveData<MusicUiState> = _uiState

    private val navigationStack = mutableListOf<MusicUiState>(MusicUiState.Home)

    // --- 播放器面板显示控制 ---
    private val _isPlayingPanelVisible = MutableLiveData(false)
    val isPlayingPanelVisible: LiveData<Boolean> = _isPlayingPanelVisible

    // --- 侧滑面板可见性控制喵！🚀 ---
    private val _isSearchPanelVisible = MutableLiveData(false)
    val isSearchPanelVisible: LiveData<Boolean> = _isSearchPanelVisible

    fun setSearchPanelVisible(visible: Boolean) {
        _isSearchPanelVisible.value = visible
    }

    // --- 循环检测状态只读流代理喵 ---
    val detectedLoopPoints: StateFlow<List<LoopPoint>?> get() = loopDetection.detectedLoopPoints
    val isDetectingLoop: StateFlow<Boolean> get() = loopDetection.isDetectingLoop

    fun detectLoopPoints(context: Context, song: Song, forceReanalyze: Boolean = false) {
        loopDetection.detectLoopPoints(
            context = context,
            song = song,
            forceReanalyze = forceReanalyze,
            onFinished = { updatedSong, candidates, sampleRate ->
                updateSongInPlaylist(updatedSong)
                showLoopCandidatesDialog(context, updatedSong, candidates, sampleRate)
            },
            onError = { message ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * 弹出循环点候选列表弹窗喵！
     */
    private fun showLoopCandidatesDialog(context: Context, song: Song, candidates: List<LoopPoint>, sampleRate: Int) {
        showDialog(MusicDialog.LoopCandidates(
            song = song,
            candidates = candidates,
            sampleRate = sampleRate,
            onSelect = { point ->
                loopDetection.applyAndListenToLoopFromEnd(
                    song = song,
                    point = point,
                    onPlaySongRequired = { s, startPos ->
                        playSong(s, startPosition = startPos)
                    },
                    onSongUpdated = { updatedSong ->
                        updateSongInPlaylist(updatedSong)
                    }
                )
            },
            onReanalyze = {
                dismissDialog()
                detectLoopPoints(context, song, forceReanalyze = true)
            }
        ))
    }

    /**
     * 更新本地 currentPlaylist 中指定歌曲的数据副本喵！
     */
    private fun updateSongInPlaylist(updatedSong: Song) {
        val currentList = _currentPlaylist.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == updatedSong.id }
        if (index != -1) {
            currentList[index] = updatedSong
            _currentPlaylist.postValue(currentList)
        }
    }

    fun clearDetectedLoopPoints() {
        loopDetection.clearDetectedLoopPoints()
    }

    // --- 对话框中台控制喵 ---
    private val _currentDialog = MutableLiveData<MusicDialog?>(null)
    val currentDialog: LiveData<MusicDialog?> = _currentDialog

    fun showDialog(dialog: MusicDialog) {
        _currentDialog.value = dialog
    }

    fun dismissDialog() {
        _currentDialog.value = null
    }

    // --- 核心业务接口转发喵 ---

    // --- 核心业务接口转发喵 ---
    // (APlayer 重构：这些现在都是自动响应的，不需要手动拉水闸了喵！🚀)

    fun scanLibrary(context: Context) = library.scanLibrary(context)

    fun setSelectionMode(enabled: Boolean) = selection.setSelectionMode(enabled)
    fun toggleSelection(id: String) = selection.toggleSelection(id)
    fun togglePlaylistSelection(id: Int) = selection.togglePlaylistSelection(id)
    fun toggleFolderSelection(f: Folder) = selection.toggleFolderSelection(f)
    fun clearSelection() = selection.clearSelection()
    fun selectAll(songs: List<Song>) = selection.selectAll(songs)

    fun navigateTo(state: MusicUiState) {
        if (navigationStack.lastOrNull() == state) return
        navigationStack.add(state)
        _uiState.value = state
    }

    fun goBack(): Boolean {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.size - 1)
            _uiState.value = navigationStack.last()
            return true
        }
        return false
    }

    fun resetToHome() {
        navigationStack.clear()
        navigationStack.add(MusicUiState.Home)
        _uiState.value = MusicUiState.Home
    }

    fun openHome() = resetToHome()

    // --- 组合业务逻辑喵 (MainViewModel 负责跨模块协调) ---

    fun playSong(song: Song, startPosition: Long = 0, startPaused: Boolean = false) {
        // 智能找索引：如果歌曲已经在现在的播放队列里，就别乱换队列喵！
        val currentList = _currentPlaylist.value ?: emptyList()
        var index = currentList.indexOfFirst { it.filePath == song.filePath }
        var listToUse = currentList

        if (index == -1) {
            // 不在现有的列表里？那才去翻翻全家桶喵
            val allList = library.allSongs.value
            index = allList.indexOfFirst { it.filePath == song.filePath }
            if (index != -1) {
                listToUse = allList
                _currentPlaylist.value = allList
            }
        }

        if (index != -1) {
            _currentSongIndex.value = index
        }

        val bundle = android.os.Bundle().apply {
            putLong("start_pos", startPosition)
            putBoolean("start_paused", startPaused)
            putStringArray("playlist_paths", listToUse.map { it.filePath }.toTypedArray())
        }
        mediaControlManager.playFromMediaId(song.mediaId.toString(), bundle)
    }

    fun updateCurrentPlaylist(songs: List<Song>, index: Int = -1) {
        _currentPlaylist.value = songs
        if (index != -1) _currentSongIndex.value = index
    }

    @Suppress("unused")
    fun togglePlayPause() {
        if (audioPlayState.value == com.cpu.seamlessloopmobile.audio.AudioPlayState.PLAYING) {
            mediaControlManager.pause()
        } else {
            mediaControlManager.play()
        }
    }

    fun play() = mediaControlManager.play()
    fun pause() = mediaControlManager.pause()

    fun skipToNext() = mediaControlManager.skipToNext()
    fun skipToPrevious() = mediaControlManager.skipToPrevious()
    fun seekTo(pos: Long) = mediaControlManager.seekTo(pos)
    fun refreshMediaSessionPosition() = mediaControlManager.refreshPosition()

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        settingsManager?.playMode = mode
        val bundle = android.os.Bundle().apply {
            putInt("play_mode", mode.ordinal)
        }
        mediaControlManager.sendCustomAction("SET_PLAY_MODE", bundle)
    }

    fun togglePlayMode() {
        val next = when (_playMode.value) {
            PlayMode.LIST_LOOP -> PlayMode.SINGLE_LOOP
            PlayMode.SINGLE_LOOP -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.LIST_LOOP
            null -> PlayMode.LIST_LOOP
        }
        setPlayMode(next)
    }

    fun addSelectedToPlaylist(playlistId: Int) {
        val selectedPaths = selection.selectedItems.value ?: return
        viewModelScope.launch {
            val songs = library.allSongs.value
            val selectedSongIds = songs.filter { it.filePath in selectedPaths }.map { it.id }
            playlist.addSongsToPlaylist(playlistId, selectedSongIds) {
                selection.clearSelection()
            }
        }
    }

    fun createPlaylistWithSelected(name: String) {
        val selectedPaths = selection.selectedItems.value ?: return
        viewModelScope.launch {
            val songs = library.allSongs.value
            val selectedSongIds = songs.filter { it.filePath in selectedPaths }.map { it.id }
            playlist.createPlaylist(name, selectedSongIds) {
                selection.clearSelection()
            }
        }
    }

    @Suppress("unused")
    fun deletePlaylist(p: Playlist) {
        playlist.deletePlaylist(p)
        val currentState = _uiState.value
        if (currentState is MusicUiState.SongList && currentState.type == MusicUiState.ListType.PLAYLIST && currentState.title == p.name) {
            goBack()
        }
    }

    fun deleteSelectedPlaylists() {
        val ids = selection.selectedPlaylists.value ?: return
        playlist.deleteMultiplePlaylists(ids) {
            selection.clearSelection()
        }
    }

    fun importSelectedFoldersIndividually() {
        val folders = selection.selectedFolders.value ?: return
        viewModelScope.launch {
            playlist.importFoldersIndividually(folders.toList())
            selection.clearSelection()
        }
    }

    fun importSelectedFoldersAsSinglePlaylist(name: String) {
        val folders = selection.selectedFolders.value ?: return
        viewModelScope.launch {
            playlist.importFoldersAsSinglePlaylist(name, folders.toList())
            selection.clearSelection()
        }
    }

    fun openPlaylist(playlistObj: Playlist) {
        viewModelScope.launch {
            _currentOpenPlaylist.value = playlistObj
            val songs = withContext(Dispatchers.IO) { repository.getSongsInPlaylist(playlistObj.id) }
            navigateTo(MusicUiState.SongList(playlistObj.name, songs, MusicUiState.ListType.PLAYLIST))
        }
    }

    @Deprecated("Use Tab navigation instead")
    @Suppress("DEPRECATION")
    fun openCategory(title: String, items: List<Folder>) = navigateTo(MusicUiState.CategoryFolders(title, items))
    fun openSongList(title: String, songs: List<Song>, type: MusicUiState.ListType, original: List<Folder>? = null) 
        = navigateTo(MusicUiState.SongList(title, songs, type, original))

    fun setPlayingPanelVisible(visible: Boolean) { _isPlayingPanelVisible.value = visible }
    fun connectMedia() = mediaControlManager.connect()
    fun disconnectMedia() = mediaControlManager.disconnect()

    fun updateSongLoopPoints(song: Song, start: Long, end: Long) {
        library.updateSongLoopPoints(song, start, end)
        
        // 确保本地 UI 状态 (currentPlaylist) 里的曲目数据也立刻更新，否则在详情页的循环调节数字不会动喵！
        val currentList = _currentPlaylist.value?.toMutableList() ?: return
        val index = currentList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(loopStart = start, loopEnd = end)
            _currentPlaylist.postValue(currentList)
        }
    }
    
    fun applyAndListenToLoop(song: Song, start: Long, end: Long) {
        // 先同步更新本地和数据库的数据喵
        updateSongLoopPoints(song, start, end)
        
        // （已修复）不再顺水推舟强制切换全局的 UI 播放模式，保持用户的“列表循环/随机播放”等设置不去污染它喵！
        
        // 通过专线向系统服务下达“试听应用指令”，一切由后台状态机调度
        val bundle = android.os.Bundle().apply {
            putLong("start_pos", start)
            putLong("end_pos", end)
        }
        mediaControlManager.sendCustomAction("APPLY_LOOP_POINTS", bundle)
    }
    
    @Suppress("unused")
    fun findAbPair(song: Song): Pair<Song, Song>? {
        // UI 层现在即使在 library 里找不到也没关系，因为我们要实现“点 A 放全曲”喵！
        // 我们改用一个同步包裹的探测（或者引导大人在真正播放时才去加载）
        // 为了保持 UI 响应，我们在 viewModelScope 之外建议直接调用 repository 的原始逻辑
        
        // 修正：我们直接利用库里的 findAbPairRobust 逻辑，但为了方便 UI 同步显示图标，
        // 我们给 library 增加一个全局感知能力，或者在这里直接搜全家桶喵
        
        // 方案：让 library 提供一个未过滤的版本，或者干脆在这里通过文件名推断！
        return repository.findAbPair(song, library.allSongsRaw.value)
    }

    fun cycleSongRating(song: Song) {
        val nextRating = (song.rating + 1) % 6
        viewModelScope.launch {
            repository.updateSongRating(song, nextRating)
            
            // 确保本地 UI 状态 (currentPlaylist) 里的曲目数据也立刻更新喵！
            val currentList = _currentPlaylist.value?.toMutableList() ?: return@launch
            val index = currentList.indexOfFirst { it.id == song.id }
            if (index != -1) {
                currentList[index] = currentList[index].copy(rating = nextRating)
                _currentPlaylist.postValue(currentList)
            }
        }
    }

    fun addSongToPlaylist(playlistId: Int, song: Song) {
        viewModelScope.launch {
            playlist.addSongsToPlaylist(playlistId, listOf(song.id)) {
                // 静默完成
            }
        }
    }

    fun createPlaylistWithSong(name: String, song: Song) {
        viewModelScope.launch {
            playlist.createPlaylist(name, listOf(song.id)) {
                // 静默完成
            }
        }
    }

    fun removeSongFromPlaylist(playlistId: Int, song: Song) {
        playlist.removeSongsFromPlaylist(playlistId, listOf(song.id)) {
            val currentState = _uiState.value
            if (currentState is MusicUiState.SongList && currentState.type == MusicUiState.ListType.PLAYLIST) {
                val updatedSongs = currentState.songs.filter { it.id != song.id }
                _uiState.value = currentState.copy(songs = updatedSongs)
            }
            
            // 同步更新当前播放列表队列
            val currentList = _currentPlaylist.value?.toMutableList() ?: return@removeSongsFromPlaylist
            val idx = currentList.indexOfFirst { it.id == song.id }
            if (idx != -1) {
                currentList.removeAt(idx)
                _currentPlaylist.postValue(currentList)
                
                val curIdx = _currentSongIndex.value ?: -1
                if (curIdx == idx) {
                    if (currentList.isEmpty()) {
                        _currentSongIndex.postValue(-1)
                        mediaControlManager.pause()
                    } else {
                        val nextIdx = if (idx >= currentList.size) 0 else idx
                        _currentSongIndex.postValue(nextIdx)
                        playSong(currentList[nextIdx])
                    }
                } else if (curIdx > idx) {
                    _currentSongIndex.postValue(curIdx - 1)
                }
            }
        }
    }

    fun makeSongsFavorite(songPaths: Set<String>) {
        viewModelScope.launch {
            val songs = library.allSongs.value.filter { it.filePath in songPaths }
            val currentList = _currentPlaylist.value?.toMutableList() ?: return@launch
            var updated = false
            songs.forEach { song ->
                val currentRating = song.rating
                if (currentRating < 5) {
                    val nextRating = currentRating + 1
                    repository.updateSongRating(song, nextRating)
                    
                    val index = currentList.indexOfFirst { it.id == song.id }
                    if (index != -1) {
                        currentList[index] = currentList[index].copy(rating = nextRating)
                        updated = true
                    }
                }
            }
            if (updated) {
                _currentPlaylist.postValue(currentList)
            }
        }
    }

    fun detectLoopPointsBulk(context: Context, songPaths: Set<String>) {
        viewModelScope.launch {
            val songs = library.allSongs.value.filter { it.filePath in songPaths }
            if (songs.isNotEmpty()) {
                android.widget.Toast.makeText(context, "开始后台批量分析 ${songs.size} 首歌曲喵...", android.widget.Toast.LENGTH_SHORT).show()
                // 串行执行，避免瞬间挤占太多内存/计算资源
                songs.forEach { song ->
                    try {
                        loopDetection.detectLoopPoints(
                            context = context,
                            song = song,
                            forceReanalyze = false,
                            onFinished = { updatedSong, candidates, _ ->
                                updateSongInPlaylist(updatedSong)
                                if (candidates.isNotEmpty()) {
                                    val best = candidates.first()
                                    loopDetection.applyAndListenToLoopFromEnd(updatedSong, best) { finalSong ->
                                        updateSongInPlaylist(finalSong)
                                    }
                                }
                            },
                            onError = { /* 静默失败 */ }
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                android.widget.Toast.makeText(context, "批量分析完成喵！", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun removeSongsFromPlaylistBulk(playlistId: Int, songPaths: Set<String>) {
        viewModelScope.launch {
            val songsToRemove = library.allSongs.value.filter { it.filePath in songPaths }
            val songIds = songsToRemove.map { it.id }
            playlist.removeSongsFromPlaylist(playlistId, songIds) {
                val currentState = _uiState.value
                if (currentState is MusicUiState.SongList && currentState.type == MusicUiState.ListType.PLAYLIST) {
                    val updatedSongs = currentState.songs.filter { it.id !in songIds }
                    _uiState.value = currentState.copy(songs = updatedSongs)
                }
                
                val currentList = _currentPlaylist.value?.toMutableList() ?: return@removeSongsFromPlaylist
                val curIdx = _currentSongIndex.value ?: -1
                val curSong = if (curIdx in currentList.indices) currentList[curIdx] else null
                
                val idsSet = songIds.toSet()
                val updatedList = currentList.filter { it.id !in idsSet }
                _currentPlaylist.postValue(updatedList)
                
                if (curSong != null && curSong.id in idsSet) {
                    if (updatedList.isEmpty()) {
                        _currentSongIndex.postValue(-1)
                        mediaControlManager.pause()
                    } else {
                        val newIdx = curIdx.coerceAtMost(updatedList.size - 1)
                        _currentSongIndex.postValue(newIdx)
                        playSong(updatedList[newIdx])
                    }
                } else if (curSong != null) {
                    val newIdx = updatedList.indexOfFirst { it.id == curSong.id }
                    _currentSongIndex.postValue(newIdx)
                }
                selection.clearSelection()
            }
        }
    }
}

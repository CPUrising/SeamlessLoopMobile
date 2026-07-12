package com.cpu.seamlessloopmobile.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.PlayQueueDao

class MainViewModelFactory(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val playQueueDao: PlayQueueDao,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val repository = com.cpu.seamlessloopmobile.data.MusicRepository(songDao, playlistDao, playQueueDao)
            val mediaControl = com.cpu.seamlessloopmobile.audio.MediaControlManager(context.applicationContext)
            
            @Suppress("UNCHECKED_CAST")
            val viewModel = MainViewModel(repository, mediaControl)
            
            // 莱芙帮大家找了个统一的大伞（Scope）喵！
            val scope = (viewModel as ViewModel).viewModelScope
            
            val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(context)
            val selectionVM = SelectionViewModel()
            val playlistVM = PlaylistViewModel(repository, scope, settingsManager)
            val loopDetectionRepo = com.cpu.seamlessloopmobile.data.LoopDetectionRepository(repository, context.applicationContext)
            val loopDetectionVM = LoopDetectionViewModel(loopDetectionRepo, mediaControl, scope)
            
            // GitHub 同步基础设施
            val database = com.cpu.seamlessloopmobile.db.AppDatabase.getDatabase(context)
            val listenStatsRepository = com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
                .getInstance(context.applicationContext)
            val githubSyncStore = com.cpu.seamlessloopmobile.data.sync.SharedPreferencesGitHubSyncStore(context.applicationContext)
            val playlistIdMapper = com.cpu.seamlessloopmobile.data.sync.room.SharedPreferencesPlaylistIdMapper(context.applicationContext)
            val roomSyncSnapshotStore = com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStore(
                database = database,
                songDao = songDao,
                playlistDao = playlistDao,
                playlistIdMapper = playlistIdMapper,
                listenStatsRepository = listenStatsRepository
            )

            val libraryVM = LibraryViewModel(
                repository = repository,
                coroutineScope = scope,
                settingsManager = settingsManager,
                onScanCompleted = roomSyncSnapshotStore::rebindPlaybackStats
            )

            viewModel.githubSyncStore = githubSyncStore
            viewModel.playlistIdMapper = playlistIdMapper
            viewModel.roomSyncSnapshotStore = roomSyncSnapshotStore
            viewModel.localSyncDataManagementRepository = com.cpu.seamlessloopmobile.data.sync.SyncDataManagementRepository(
                database = database,
                songDao = songDao,
                playlistDao = playlistDao,
                snapshotStore = roomSyncSnapshotStore,
                metadataStore = githubSyncStore,
                playlistIdMapper = playlistIdMapper,
                listenStatsRepository = listenStatsRepository
            )

            // 自动同步调度器
            viewModel.githubAutoSyncScheduler = com.cpu.seamlessloopmobile.data.sync.GitHubAutoSyncScheduler(
                workManager = WorkManager.getInstance(context.applicationContext)
            )

            // 数据管理仓库工厂 —— 每次调用时使用最新配置构建后端
            viewModel.syncDataManagementRepositoryFactory = { config ->
                val mgmtBackend = com.cpu.seamlessloopmobile.data.sync.github.GitHubContentsSyncBackend(
                    config = config,
                    tokenProvider = githubSyncStore,
                    serializer = com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSerializer()
                )
                com.cpu.seamlessloopmobile.data.sync.SyncDataManagementRepository(
                    database = database,
                    songDao = songDao,
                    playlistDao = playlistDao,
                    snapshotStore = roomSyncSnapshotStore,
                    backend = mgmtBackend,
                    metadataStore = githubSyncStore,
                    playlistIdMapper = playlistIdMapper,
                    listenStatsRepository = listenStatsRepository
                )
            }

            viewModel.loadGitHubSyncState()

            // 设置子管家引用
            viewModel.library = libraryVM
            viewModel.selection = selectionVM
            viewModel.playlist = playlistVM
            viewModel.loopDetection = loopDetectionVM
            
            return viewModel as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

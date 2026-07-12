package com.cpu.seamlessloopmobile.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cpu.seamlessloopmobile.data.sync.github.GitHubContentsSyncBackend
import com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStore
import com.cpu.seamlessloopmobile.data.sync.room.SharedPreferencesPlaylistIdMapper
import com.cpu.seamlessloopmobile.db.AppDatabase
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import kotlinx.coroutines.CancellationException

/**
 * WorkManager 驱动的后台自动同步 Worker。
 *
 * 在 [doWork] 中自行构建完整同步链，不依赖 ViewModel 或外部注入，
 * 通过 [applicationContext] 获取全部依赖。
 */
class GitHubAutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "GitHubAutoSyncWorker"
    }

    override suspend fun doWork(): Result {
        val outcome = try {
            // 1. 检查自动同步开关 —— 关闭则静默退出
            val syncStore = SharedPreferencesGitHubSyncStore(applicationContext)
            if (!syncStore.isAutoSyncEnabled()) {
                Log.d(TAG, "Auto sync disabled, skipping work")
                return Result.success()
            }

            // 2. 检查配置和 token
            val config = syncStore.getConfig()
            val token = syncStore.getToken()
            if (config == null || token.isNullOrBlank()) {
                Log.d(TAG, "Config or token missing, skipping work")
                return Result.success()
            }

            // 3. 构建同步链
            val database = AppDatabase.getDatabase(applicationContext)
            val playlistIdMapper = SharedPreferencesPlaylistIdMapper(applicationContext)
            val songDao = database.songDao()
            val playlistDao = database.playlistDao()
            val roomSyncSnapshotStore = RoomSyncSnapshotStore(
                database = database,
                songDao = songDao,
                playlistDao = playlistDao,
                playlistIdMapper = playlistIdMapper,
                listenStatsRepository = ListenStatsRepository.getInstance(applicationContext)
            )
            val backend = GitHubContentsSyncBackend(
                config = config,
                tokenProvider = syncStore,
                serializer = SyncSnapshotSerializer()
            )
            // Worker 与手动同步各自构建 coordinator；跨入口并发依赖远端修订检查与下次周期重试收敛。
            val coordinator = GitHubSyncCoordinator(
                backend = backend,
                snapshotStore = roomSyncSnapshotStore,
                metadataStore = syncStore
            )

            // 4. 执行同步
            coordinator.syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed with unexpected exception", e)
            return Result.retry()
        }

        // 5. 处理结果
        return when (outcome) {
            is SyncOutcome.Success -> {
                Log.i(TAG, "Auto sync succeeded, revision: ${outcome.remoteRevision}")
                Result.success()
            }
            is SyncOutcome.Cancelled -> {
                Log.d(TAG, "Auto sync cancelled")
                Result.success()
            }
            is SyncOutcome.LocalMutationDuringSync -> {
                Log.w(TAG, "Auto sync: local mutation during sync, will retry on next cycle")
                Result.success()
            }
            is SyncOutcome.Failure -> {
                Log.w(TAG, "Auto sync failed: ${outcome.code} - ${outcome.message}")
                when (outcome.code) {
                    SyncErrorCode.NETWORK,
                    SyncErrorCode.CONFLICT,
                    SyncErrorCode.UNKNOWN -> Result.retry()
                    SyncErrorCode.NOT_CONFIGURED,
                    SyncErrorCode.UNAUTHORIZED,
                    SyncErrorCode.INVALID_REMOTE,
                    SyncErrorCode.NOT_FOUND -> {
                        Log.e(TAG, "Non-retryable sync failure: ${outcome.code}")
                        Result.success()
                    }
                }
            }
        }
    }
}

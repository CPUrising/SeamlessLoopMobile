package com.cpu.seamlessloopmobile.data.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 驱动的自动同步调度器。
 *
 * 负责注册/取消后台定时同步任务，本身不持有业务逻辑。
 * 通过 [reconcile] 方法统一根据开关状态决定调度或取消。
 *
 * @param workManager WorkManager 实例，通常来自 [android.content.Context]
 */
class GitHubAutoSyncScheduler(private val workManager: WorkManager) {

    companion object {
        /**
         * 完全限定的唯一 WorkManager 工作名称，避免命名冲突。
         */
        const val UNIQUE_WORK_NAME = "com.cpu.seamlessloopmobile.GITHUB_AUTO_SYNC_PERIODIC"
    }

    /**
     * 注册周期同步任务。
     *
     * 使用 [ExistingPeriodicWorkPolicy.KEEP] 保留已有任务，
     * 避免反复调用时意外重置调度周期。
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<GitHubAutoSyncWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * 取消周期同步任务。
     */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    /**
     * 根据开关状态统一调度或取消。
     *
     * @param enabled true 时调度，false 时取消
     */
    fun reconcile(enabled: Boolean) {
        if (enabled) {
            schedule()
        } else {
            cancel()
        }
    }
}

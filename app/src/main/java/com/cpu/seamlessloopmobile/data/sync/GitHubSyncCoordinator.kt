package com.cpu.seamlessloopmobile.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同步操作的最终结果密封类。
 */
sealed class SyncOutcome {
    /**
     * 同步成功。
     * @param report 合并与应用报告
     * @param remoteRevision 同步完成后远程端的最新修订版本
     */
    data class Success(
        val report: SyncReport,
        val remoteRevision: String
    ) : SyncOutcome()

    /**
     * 同步失败。
     * @param code 错误码
     * @param message 人类可读的错误描述
     * @param throwable 原始异常（可选）
     */
    data class Failure(
        val code: SyncErrorCode,
        val message: String,
        val throwable: Throwable? = null
    ) : SyncOutcome()

    /**
     * 同步期间本地数据发生变更，中止应用/上传。
     * 调用方可选择重新开始同步。
     */
    data object LocalMutationDuringSync : SyncOutcome()

    /** 同步被取消。 */
    data object Cancelled : SyncOutcome()
}

/**
 * GitHub 同步协调器。
 *
 * 编排同步全流程：导出本地 → 下载远端 → 合并 → 应用 → 上传，
 * 利用 [SyncBackend] 的 expectedRevision 机制和 [SyncMetadataStore]
 * 的 mutationVersion 实现安全同步。
 *
 * 使用 [Mutex] 保证同一时刻只有一个同步操作在执行。
 *
 * @param backend 后端通信接口
 * @param snapshotStore 本地快照导出/应用接口
 * @param metadataStore 同步元数据持久化接口
 */
class GitHubSyncCoordinator(
    private val backend: SyncBackend,
    private val snapshotStore: SyncSnapshotStore,
    private val metadataStore: SyncMetadataStore
) {
    private val mutex = Mutex()
    private val mergeEngine = SyncMergeEngine()

    /**
     * 执行一次完整的同步流程。
     *
     * 流程：
     * 1. 记录当前 mutationVersion
     * 2. 导出本地快照
     * 3. 下载远端快照
     * 4. 若 NOT_FOUND → 直接上传本地快照（初次同步）
     * 5. 若非 NOT_FOUND 的下载失败 → 返回 Failure
     * 6. 合并远端与本地
     * 7. 检查 mutationVersion 是否改变 → 若变则返回 LocalMutationDuringSync
     * 8. 应用合并后快照到本地
     * 9. 上传合并后快照，携带远端修订版本用于乐观锁
     * 10. 若 CONFLICT → 重试（最多 maxConflictRetries 次）
     * 11. 成功 → 记录同步元数据，返回 Success
     *
     * @param maxConflictRetries 遇到 CONFLICT 时的最大重试次数
     * @return 同步结果
     */
    suspend fun syncNow(maxConflictRetries: Int = 3): SyncOutcome {
        return mutex.withLock {
            syncInternal(maxConflictRetries)
        }
    }

    private suspend fun syncInternal(maxConflictRetries: Int): SyncOutcome {
        // 1. 记录初始 mutationVersion
        val initialMutationVersion = metadataStore.getMutationVersion()
        val deviceId = metadataStore.getDeviceId()

        // 2. 导出本地快照
        val localSnapshot = snapshotStore.exportSnapshot(deviceId)

        // 3. 下载远端快照
        val downloadResult = backend.downloadSnapshot()

        if (downloadResult is SyncResult.Failure) {
            // 4. NOT_FOUND → 初次上传
            if (downloadResult.code == SyncErrorCode.NOT_FOUND) {
                return initialUpload(localSnapshot, initialMutationVersion)
            }
            // 5. 其他错误
            return SyncOutcome.Failure(
                code = downloadResult.code,
                message = "Download failed: ${downloadResult.message}",
                throwable = downloadResult.throwable
            )
        }

        if (downloadResult is SyncResult.Cancelled) {
            return SyncOutcome.Cancelled
        }

        val (remoteSnapshot, remoteRevision) = when (downloadResult) {
            is SyncResult.Success -> downloadResult.snapshot to downloadResult.remoteRevision
            else -> return SyncOutcome.Failure(
                SyncErrorCode.UNKNOWN, "Unexpected download result: $downloadResult"
            )
        }
        if (remoteSnapshot != null && remoteSnapshot.schemaVersion != SYNC_SCHEMA_VERSION_V2) {
            return SyncOutcome.Failure(
                SyncErrorCode.INVALID_REMOTE,
                "Unsupported remote schema version ${remoteSnapshot.schemaVersion}"
            )
        }

        // 6. 合并
        var retriesRemaining = maxConflictRetries
        var currentRemoteRevision = remoteRevision
        var currentRemoteSnapshot = remoteSnapshot

        while (true) {
            val mergeResult = mergeEngine.merge(currentRemoteSnapshot, localSnapshot)
            var report = mergeResult.report

            // 7. 检查并发修改
            if (metadataStore.getMutationVersion() != initialMutationVersion) {
                return SyncOutcome.LocalMutationDuringSync
            }

            // 8. 应用合并后快照到本地
            val applyReport = snapshotStore.applySnapshot(
                mergeResult.snapshot,
                trackLocalMutation = false
            )
            // 合并 applyReport 中的冲突信息
            report = report.copy(
                conflicts = report.conflicts + applyReport.conflicts
            )

            if (metadataStore.getMutationVersion() != initialMutationVersion) {
                return SyncOutcome.LocalMutationDuringSync
            }

            // 9. 上传合并后快照
            val preparedSnapshot = mergeResult.snapshot.prepareV2Egress()
            if (metadataStore.getMutationVersion() != initialMutationVersion) {
                return SyncOutcome.LocalMutationDuringSync
            }
            val uploadResult = backend.uploadSnapshot(
                snapshot = preparedSnapshot,
                expectedRevision = currentRemoteRevision
            )

            when (uploadResult) {
                is SyncResult.Success -> {
                    val newRevision = uploadResult.remoteRevision
                        ?: return SyncOutcome.Failure(
                            SyncErrorCode.INVALID_REMOTE,
                            "Upload succeeded but no remote revision returned"
                        )

                    // 11. 记录成功同步
                    metadataStore.saveSuccessfulSync(
                        remoteRevision = newRevision,
                        syncTime = System.currentTimeMillis()
                    )

                    return SyncOutcome.Success(
                        report = report,
                        remoteRevision = newRevision
                    )
                }

                is SyncResult.Failure -> {
                    if (uploadResult.code == SyncErrorCode.CONFLICT && retriesRemaining > 0) {
                        retriesRemaining--
                        // 10. CONFLICT → 重新下载远端快照后重试
                        val reDownload = backend.downloadSnapshot()
                        when (reDownload) {
                            is SyncResult.Success -> {
                                if (reDownload.snapshot != null &&
                                    reDownload.snapshot.schemaVersion != SYNC_SCHEMA_VERSION_V2
                                ) {
                                    return SyncOutcome.Failure(
                                        SyncErrorCode.INVALID_REMOTE,
                                        "Unsupported remote schema version ${reDownload.snapshot.schemaVersion}"
                                    )
                                }
                                currentRemoteSnapshot = reDownload.snapshot
                                currentRemoteRevision = reDownload.remoteRevision
                                // 继续循环
                            }
                            is SyncResult.Cancelled -> return SyncOutcome.Cancelled
                            is SyncResult.Failure -> return SyncOutcome.Failure(
                                code = reDownload.code,
                                message = "Re-download after conflict failed: ${reDownload.message}",
                                throwable = reDownload.throwable
                            )
                        }
                    } else {
                        return SyncOutcome.Failure(
                            code = uploadResult.code,
                            message = "Upload failed: ${uploadResult.message}",
                            throwable = uploadResult.throwable
                        )
                    }
                }

                is SyncResult.Cancelled -> return SyncOutcome.Cancelled
            }
        }
    }

    /**
     * 初次同步：远端不存在文件，直接上传本地快照。
     */
    private suspend fun initialUpload(
        localSnapshot: SyncSnapshot,
        initialMutationVersion: Int
    ): SyncOutcome {
        if (metadataStore.getMutationVersion() != initialMutationVersion) {
            return SyncOutcome.LocalMutationDuringSync
        }

        val preparedSnapshot = localSnapshot.prepareV2Egress()
        val uploadResult = backend.uploadSnapshot(
            snapshot = preparedSnapshot,
            expectedRevision = null
        )

        return when (uploadResult) {
            is SyncResult.Success -> {
                val newRevision = uploadResult.remoteRevision
                    ?: return SyncOutcome.Failure(
                        SyncErrorCode.INVALID_REMOTE,
                        "Initial upload succeeded but no remote revision returned"
                    )

                metadataStore.saveSuccessfulSync(
                    remoteRevision = newRevision,
                    syncTime = System.currentTimeMillis()
                )

                SyncOutcome.Success(
                    report = SyncReport(
                        playlistsUploaded = localSnapshot.playlists.size,
                        loopPointsUploaded = localSnapshot.loopPoints.size,
                        ratingsUploaded = localSnapshot.ratings.size
                    ),
                    remoteRevision = newRevision
                )
            }
            is SyncResult.Failure -> SyncOutcome.Failure(
                code = uploadResult.code,
                message = "Initial upload failed: ${uploadResult.message}",
                throwable = uploadResult.throwable
            )
            is SyncResult.Cancelled -> SyncOutcome.Cancelled
        }
    }
}

package com.cpu.seamlessloopmobile.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GitHubSyncCoordinator 单元测试。
 *
 * 验证协调器的同步流程：初始上传、冲突重试、本地并发修改中止。
 * 使用纯 Kotlin fake 实现，不依赖 Android 框架。
 */
class GitHubSyncCoordinatorTest {

    private lateinit var backend: FakeSyncBackend
    private lateinit var snapshotStore: FakeSyncSnapshotStore
    private lateinit var metadataStore: FakeSyncMetadataStore
    private lateinit var coordinator: GitHubSyncCoordinator

    @Before
    fun setUp() {
        backend = FakeSyncBackend()
        snapshotStore = FakeSyncSnapshotStore()
        metadataStore = FakeSyncMetadataStore()
        coordinator = GitHubSyncCoordinator(
            backend = backend,
            snapshotStore = snapshotStore,
            metadataStore = metadataStore
        )
    }

    // -------------------------------------------------------------------
    // Initial upload on NOT_FOUND
    // -------------------------------------------------------------------

    @Test
    fun `initial sync uploads local when remote not found`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)
        snapshotStore.exportResult = SyncSnapshot(
            deviceId = "local", exportedAt = 1L, playbackStats = SyncPlaybackStats()
        )
        backend.uploadResult = SyncResult.Success(
            report = SyncReport(),
            remoteRevision = "sha-initial"
        )

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Success", outcome is SyncOutcome.Success)
        val success = outcome as SyncOutcome.Success
        assertEquals("sha-initial", success.remoteRevision)

        // Verify upload was called with local snapshot
        assertEquals(1, backend.uploadCallCount)
        assertEquals(1, backend.downloadCallCount)
        assertEquals(SYNC_SCHEMA_VERSION_V2, backend.lastUploadedSnapshot?.schemaVersion)
        assertNotNull(backend.lastUploadedSnapshot?.playbackStats)

        // Verify metadata was saved
        assertEquals("sha-initial", metadataStore.lastRemoteRevision)
        assertTrue(metadataStore.lastSyncTime > 0)
    }

    @Test
    fun `initial sync uploads schema 2 snapshot with playback statistics`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)

        val outcome = coordinator.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(SYNC_SCHEMA_VERSION_V2, backend.lastUploadedSnapshot?.schemaVersion)
        assertTrue(backend.lastUploadedSnapshot?.playbackStats != null)
    }

    @Test
    fun `initial upload failure returns failure outcome`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)
        backend.uploadResult = SyncResult.Failure(
            "Token expired", code = SyncErrorCode.UNAUTHORIZED
        )

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Failure", outcome is SyncOutcome.Failure)
        val failure = outcome as SyncOutcome.Failure
        assertEquals(SyncErrorCode.UNAUTHORIZED, failure.code)
    }

    // -------------------------------------------------------------------
    // Download failure (non-NOT_FOUND)
    // -------------------------------------------------------------------

    @Test
    fun `non not-found download failure returns failure outcome`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Failure("Network error", code = SyncErrorCode.NETWORK)

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Failure", outcome is SyncOutcome.Failure)
        assertEquals(SyncErrorCode.NETWORK, (outcome as SyncOutcome.Failure).code)
        assertEquals(0, backend.uploadCallCount)
    }

    @Test
    fun `unsupported remote schema is rejected before merge or upload`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Success(
            SyncReport(),
            SyncSnapshot(schemaVersion = 999, deviceId = "remote", exportedAt = 1L),
            remoteRevision = "remote-sha"
        )

        val outcome = coordinator.syncNow()

        assertTrue(outcome is SyncOutcome.Failure)
        assertEquals(SyncErrorCode.INVALID_REMOTE, (outcome as SyncOutcome.Failure).code)
        assertEquals(0, backend.uploadCallCount)
    }

    // -------------------------------------------------------------------
    // Normal merge upload
    // -------------------------------------------------------------------

    @Test
    fun `normal merge upload cycle succeeds`() = kotlinx.coroutines.runBlocking {
        // Remote exists
        backend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = SyncSnapshot(
                deviceId = "remote",
                exportedAt = 200L,
                playlists = listOf(
                    SyncPlaylist("p1", "Remote Playlist", 100L, 200L)
                )
            ),
            remoteRevision = "remote-sha"
        )
        backend.uploadResult = SyncResult.Success(
            report = SyncReport(),
            remoteRevision = "new-sha-after-merge"
        )

        // Local has data
        snapshotStore.exportResult = SyncSnapshot(
            deviceId = "local-device",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p2", "Local Playlist", 300L, 400L)
            )
        )

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Success", outcome is SyncOutcome.Success)
        val success = outcome as SyncOutcome.Success
        assertEquals("new-sha-after-merge", success.remoteRevision)

        // Verify merge: merged snapshot should have both playlists
        assertEquals(1, backend.uploadCallCount)
        val uploadedSnapshot = backend.lastUploadedSnapshot
        assertNotNull(uploadedSnapshot)
        assertEquals(2, uploadedSnapshot?.playlists?.size)
        assertEquals(SYNC_SCHEMA_VERSION_V2, uploadedSnapshot?.schemaVersion)
        assertNotNull(uploadedSnapshot?.playbackStats)

        // Verify metadata saved
        assertEquals("new-sha-after-merge", metadataStore.lastRemoteRevision)
    }

    @Test
    fun `normal merge upload emits schema 2 snapshot with playback statistics`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Success(
            SyncReport(), SyncSnapshot(deviceId = "remote", exportedAt = 200L), remoteRevision = "remote-sha"
        )

        val outcome = coordinator.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(SYNC_SCHEMA_VERSION_V2, backend.lastUploadedSnapshot?.schemaVersion)
        assertTrue(backend.lastUploadedSnapshot?.playbackStats != null)
    }

    // -------------------------------------------------------------------
    // Conflict retry
    // -------------------------------------------------------------------

    @Test
    fun `conflict triggers re-download and retry`() = kotlinx.coroutines.runBlocking {
        // Remote exists initially
        backend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = SyncSnapshot(
                deviceId = "remote",
                exportedAt = 200L,
                playlists = listOf(SyncPlaylist("p1", "Remote", 100L, 200L))
            ),
            remoteRevision = "remote-sha"
        )

        // First upload fails with CONFLICT
        backend.uploadResults = mutableListOf(
            SyncResult.Failure("Conflict", code = SyncErrorCode.CONFLICT),
            SyncResult.Success(report = SyncReport(), remoteRevision = "final-sha")
        )

        // Re-download returns updated remote
        backend.downloadResults = mutableListOf(
            // first call from syncNow
            SyncResult.Success(
                report = SyncReport(),
                snapshot = SyncSnapshot(
                    deviceId = "remote",
                    exportedAt = 200L,
                    playlists = listOf(SyncPlaylist("p1", "Remote", 100L, 200L))
                ),
                remoteRevision = "remote-sha"
            ),
            // second call from re-download after conflict
            SyncResult.Success(
                report = SyncReport(),
                snapshot = SyncSnapshot(
                    deviceId = "remote",
                    exportedAt = 300L,
                    playlists = listOf(
                        SyncPlaylist("p1", "Remote Updated", 100L, 500L)
                    )
                ),
                remoteRevision = "updated-remote-sha"
            )
        )

        snapshotStore.exportResult = SyncSnapshot(
            deviceId = "local-device",
            exportedAt = 100L,
            playlists = listOf(SyncPlaylist("p1", "Local", 100L, 300L))
        )

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Success", outcome is SyncOutcome.Success)
        val success = outcome as SyncOutcome.Success
        assertEquals("final-sha", success.remoteRevision)

        // Should have attempted upload twice (conflict + retry)
        assertEquals(2, backend.uploadCallCount)
        // Should have downloaded twice (initial + re-download)
        assertEquals(2, backend.downloadCallCount)
        assertTrue(backend.uploadedSnapshots.all {
            it.schemaVersion == SYNC_SCHEMA_VERSION_V2 && it.playbackStats != null
        })

        // Verify metadata saved
        assertEquals("final-sha", metadataStore.lastRemoteRevision)
    }

    @Test
    fun `conflict retry emits schema 2 for every upload`() = kotlinx.coroutines.runBlocking {
        backend.downloadResults = mutableListOf(
            SyncResult.Success(
                SyncReport(), SyncSnapshot(deviceId = "remote", exportedAt = 1L), remoteRevision = "sha-1"
            ),
            SyncResult.Success(
                SyncReport(), SyncSnapshot(deviceId = "remote", exportedAt = 2L), remoteRevision = "sha-2"
            )
        )
        backend.uploadResults = mutableListOf(
            SyncResult.Failure("Conflict", code = SyncErrorCode.CONFLICT),
            SyncResult.Success(SyncReport(), remoteRevision = "sha-3")
        )

        val outcome = coordinator.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(2, backend.uploadedSnapshots.size)
        assertTrue(backend.uploadedSnapshots.all {
            it.schemaVersion == SYNC_SCHEMA_VERSION_V2 && it.playbackStats != null
        })
    }

    @Test
    fun `conflict retries exhausted returns failure`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = SyncSnapshot(
                deviceId = "remote", exportedAt = 200L,
                playlists = listOf(SyncPlaylist("p1", "Remote", 100L, 200L))
            ),
            remoteRevision = "remote-sha"
        )

        // All uploads fail with CONFLICT
        backend.uploadResult = SyncResult.Failure("Conflict", code = SyncErrorCode.CONFLICT)

        // Re-download always returns same data
        backend.downloadResults = mutableListOf(
            SyncResult.Success(
                SyncReport(),
                SyncSnapshot(deviceId = "remote", exportedAt = 200L),
                remoteRevision = "remote-sha"
            ),
            SyncResult.Success(
                SyncReport(),
                SyncSnapshot(deviceId = "remote", exportedAt = 200L),
                remoteRevision = "remote-sha"
            ),
            SyncResult.Success(
                SyncReport(),
                SyncSnapshot(deviceId = "remote", exportedAt = 200L),
                remoteRevision = "remote-sha"
            ),
            SyncResult.Success(
                SyncReport(),
                SyncSnapshot(deviceId = "remote", exportedAt = 200L),
                remoteRevision = "remote-sha"
            )
        )

        snapshotStore.exportResult = SyncSnapshot(deviceId = "local-device", exportedAt = 100L)

        // maxConflictRetries = 2 → 3 total attempts (1 initial + 2 retries)
        val outcome = coordinator.syncNow(maxConflictRetries = 2)

        assertTrue("Expected SyncOutcome.Failure", outcome is SyncOutcome.Failure)
        assertEquals(SyncErrorCode.CONFLICT, (outcome as SyncOutcome.Failure).code)
        assertEquals(3, backend.uploadCallCount) // 1 initial + 2 retries
        assertTrue(backend.uploadedSnapshots.all {
            it.schemaVersion == SYNC_SCHEMA_VERSION_V2 && it.playbackStats != null
        })
    }

    // -------------------------------------------------------------------
    // Local mutation during sync
    // -------------------------------------------------------------------

    @Test
    fun `local mutation during sync aborts with LocalMutationDuringSync`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = SyncSnapshot(
                deviceId = "remote", exportedAt = 200L,
                playlists = listOf(SyncPlaylist("p1", "Remote", 100L, 200L))
            ),
            remoteRevision = "remote-sha"
        )

        snapshotStore.exportResult = SyncSnapshot(
            deviceId = "local-device", exportedAt = 100L,
            playlists = listOf(SyncPlaylist("p1", "Local", 100L, 300L))
        )

        // Simulate mutation occurring during merge
        metadataStore.mutationVersionAfterCheck = metadataStore.mutationVersion + 1

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.LocalMutationDuringSync",
            outcome is SyncOutcome.LocalMutationDuringSync)

        // Verify no upload occurred
        assertEquals(0, backend.uploadCallCount)
        // Verify no snapshot was applied
        assertEquals(null, snapshotStore.applyCalledCount)
    }

    @Test
    fun `playback stats delta during sync aborts before apply and upload`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = SyncSnapshot(deviceId = "remote", exportedAt = 200L),
            remoteRevision = "remote-sha"
        )
        snapshotStore.exportResult = SyncSnapshot(
            deviceId = "local-device",
            exportedAt = 100L,
            playbackStats = SyncPlaybackStats()
        )
        snapshotStore.onExport = { metadataStore.markMutation() }

        val outcome = coordinator.syncNow()

        assertTrue(outcome is SyncOutcome.LocalMutationDuringSync)
        assertEquals(0, backend.uploadCallCount)
        assertEquals(null, snapshotStore.applyCalledCount)
    }

    // -------------------------------------------------------------------
    // Cancelled
    // -------------------------------------------------------------------

    @Test
    fun `cancelled download returns cancelled outcome`() = kotlinx.coroutines.runBlocking {
        backend.downloadResult = SyncResult.Cancelled

        val outcome = coordinator.syncNow()

        assertTrue("Expected SyncOutcome.Cancelled", outcome is SyncOutcome.Cancelled)
    }

    // -------------------------------------------------------------------
    // Fake implementations
    // -------------------------------------------------------------------

    /**
     * Fake [SyncBackend] with controllable results per call.
     */
    class FakeSyncBackend : SyncBackend {
        /** Result returned for all download calls (unless [downloadResults] is set). */
        var downloadResult: SyncResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)

        /** Ordered queue of download results (takes precedence over [downloadResult]). */
        var downloadResults: MutableList<SyncResult> = mutableListOf()

        /** Result returned for all upload calls (unless [uploadResults] is set). */
        var uploadResult: SyncResult = SyncResult.Success(SyncReport(), remoteRevision = "fake-sha")

        /** Ordered queue of upload results (takes precedence over [uploadResult]). */
        var uploadResults: MutableList<SyncResult> = mutableListOf()

        var downloadCallCount = 0
        var uploadCallCount = 0
        var lastUploadedSnapshot: SyncSnapshot? = null
        val uploadedSnapshots = mutableListOf<SyncSnapshot>()
        var lastExpectedRevision: String? = null

        override suspend fun downloadSnapshot(snapshotId: String?): SyncResult {
            downloadCallCount++
            return when {
                downloadResults.isNotEmpty() -> downloadResults.removeAt(0)
                else -> downloadResult
            }
        }

        override suspend fun uploadSnapshot(
            snapshot: SyncSnapshot,
            expectedRevision: String?
        ): SyncResult {
            uploadCallCount++
            lastUploadedSnapshot = snapshot
            uploadedSnapshots += snapshot
            lastExpectedRevision = expectedRevision
            return when {
                uploadResults.isNotEmpty() -> uploadResults.removeAt(0)
                else -> uploadResult
            }
        }

        override suspend fun listSnapshots(): SyncResult {
            return SyncResult.Success(SyncReport())
        }
    }

    /**
     * Fake [SyncSnapshotStore] with controllable export result.
     */
    class FakeSyncSnapshotStore : SyncSnapshotStore {
        var exportResult: SyncSnapshot = SyncSnapshot(
            deviceId = "fake-device",
            exportedAt = 1000L
        )
        var applyCalledCount: Int? = null
        var lastAppliedSnapshot: SyncSnapshot? = null
        var applyReport: SyncReport = SyncReport()
        var onExport: (suspend () -> Unit)? = null

        override suspend fun exportSnapshot(deviceId: String, now: Long): SyncSnapshot {
            onExport?.invoke()
            return exportResult.copy(deviceId = deviceId, exportedAt = now)
        }

        override suspend fun applySnapshot(
            snapshot: SyncSnapshot,
            trackLocalMutation: Boolean
        ): SyncReport {
            applyCalledCount = (applyCalledCount ?: 0) + 1
            lastAppliedSnapshot = snapshot
            return applyReport
        }
    }

    /**
     * Fake [SyncMetadataStore] with controllable mutation version.
     */
    class FakeSyncMetadataStore : SyncMetadataStore {
        var deviceId: String = "test-device-fake"
        var lastSyncTime: Long = 0L
        var lastRemoteRevision: String? = null
        var mutationVersion: Int = 0

        /** If non-null, this value is returned during the mutation check after merge. */
        var mutationVersionAfterCheck: Int? = null
        private var checkCount = 0

        override suspend fun getDeviceId(): String = deviceId
        override suspend fun getLastSyncTime(): Long = lastSyncTime
        override suspend fun getLastRemoteRevision(): String? = lastRemoteRevision

        override suspend fun getMutationVersion(): Int {
            checkCount++
            // Return the "after check" value on the second getMutationVersion call
            // (which happens after merge in the coordinator)
            val afterCheck = mutationVersionAfterCheck
            return if (checkCount >= 2 && afterCheck != null) {
                afterCheck
            } else {
                mutationVersion
            }
        }

        override suspend fun markMutation() { mutationVersion++ }
        override suspend fun saveSuccessfulSync(remoteRevision: String, syncTime: Long) {
            lastRemoteRevision = remoteRevision
            lastSyncTime = syncTime
        }

        override suspend fun clearSyncMetadata() {
            lastRemoteRevision = null
            lastSyncTime = 0L
        }
    }
}

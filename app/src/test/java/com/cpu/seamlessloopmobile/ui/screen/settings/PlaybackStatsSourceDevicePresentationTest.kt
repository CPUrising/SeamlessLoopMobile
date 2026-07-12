package com.cpu.seamlessloopmobile.ui.screen.settings

import com.cpu.seamlessloopmobile.data.sync.PlaybackStatsSourceDeviceSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsSourceDevicePresentationTest {

    @Test
    fun groupsOnlyNonCurrentFullyRemovedSourcesAndKeepsActiveSources() {
        val currentRemoved = source(deviceId = "current", isCurrentDevice = true, removed = true, listenMs = 60_000L)
        val active = source(deviceId = "active", removed = false, listenMs = 120_000L)
        val deletedOne = source(deviceId = "deleted-1", removed = true, listenMs = 180_000L)
        val deletedTwo = source(deviceId = "deleted-2", removed = true, listenMs = 240_000L)

        val presentation = playbackStatsSourceDevicePresentation(
            listOf(currentRemoved, active, deletedOne, deletedTwo)
        )

        assertEquals(listOf("current", "active"), presentation.activeSources.map { it.deviceId })
        assertEquals(2, presentation.historicalSourceCount)
    }

    private fun source(
        deviceId: String,
        isCurrentDevice: Boolean = false,
        removed: Boolean,
        listenMs: Long
    ) = PlaybackStatsSourceDeviceSummary(
        deviceId = deviceId,
        displayName = deviceId,
        fallbackLabel = deviceId,
        platform = "Android",
        currentGeneration = 1L,
        isCurrentDevice = isCurrentDevice,
        contributedListenMs = listenMs,
        hasEffectiveContributions = !removed,
        allKnownGenerationsRemoved = removed
    )
}

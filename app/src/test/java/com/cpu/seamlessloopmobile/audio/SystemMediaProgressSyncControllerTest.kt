package com.cpu.seamlessloopmobile.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class SystemMediaProgressSyncControllerTest {
    private val testDispatcher = newSingleThreadContext("system-progress-sync-test")

    @After
    fun tearDown() {
        testDispatcher.close()
    }

    @Test
    fun syncJobRunsOnlyWhilePlaybackIsPlaying() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val tickCount = AtomicInteger(0)
        val controller = SystemMediaProgressSyncController(
            scope = scope,
            intervalMs = 20L
        ) {
            tickCount.incrementAndGet()
        }

        controller.onPlaybackStateChanged(AudioPlayState.PAUSED)
        withContext(testDispatcher) {}
        assertFalse(controller.isRunning)
        assertEquals(0, tickCount.get())

        controller.onPlaybackStateChanged(AudioPlayState.PLAYING)
        waitUntil { tickCount.get() == 1 }
        assertTrue(controller.isRunning)
        assertEquals(1, tickCount.get())

        controller.onPlaybackStateChanged(AudioPlayState.PLAYING)
        withContext(testDispatcher) {}
        assertTrue(controller.isRunning)
        assertEquals("PLAYING twice must not start a duplicate sync loop", 1, tickCount.get())

        waitUntil { tickCount.get() >= 2 }

        controller.onPlaybackStateChanged(AudioPlayState.ERROR)
        withContext(testDispatcher) {}
        assertFalse(controller.isRunning)
        val stoppedAt = tickCount.get()

        kotlinx.coroutines.delay(60L)
        assertEquals("ERROR must stop future system progress ticks", stoppedAt, tickCount.get())
        scope.cancel()
    }

    @Test
    fun disposeStopsFutureTicks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val tickCount = AtomicInteger(0)
        val controller = SystemMediaProgressSyncController(
            scope = scope,
            intervalMs = 20L
        ) {
            tickCount.incrementAndGet()
        }

        controller.onPlaybackStateChanged(AudioPlayState.PLAYING)
        waitUntil { tickCount.get() == 1 }
        assertEquals(1, tickCount.get())

        controller.dispose()
        withContext(testDispatcher) {}
        assertFalse(controller.isRunning)

        kotlinx.coroutines.delay(60L)
        assertEquals("Disposed sync loop must not leak ticks", 1, tickCount.get())
        scope.cancel()
    }

    @Test
    fun dispatchPredicateCanSuppressPeriodicTicksWithoutBreakingImmediateSync() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val tickCount = AtomicInteger(0)
        val controller = SystemMediaProgressSyncController(
            scope = scope,
            intervalMs = 20L,
            shouldDispatch = { false }
        ) {
            tickCount.incrementAndGet()
        }

        controller.onPlaybackStateChanged(AudioPlayState.PLAYING)
        waitUntil { tickCount.get() == 1 }
        assertEquals(1, tickCount.get())

        kotlinx.coroutines.delay(60L)
        assertEquals("Predicate should suppress periodic ticks after the immediate sync", 1, tickCount.get())
        controller.dispose()
        scope.cancel()
    }

    @Test
    fun convertsFramesToPlaybackMillisecondsUsingSampleRateAtSampleTime() {
        assertEquals(1000L, framesToPlaybackPositionMs(positionFrames = 44100L, sampleRate = 44100))
        assertEquals(1500L, framesToPlaybackPositionMs(positionFrames = 72000L, sampleRate = 48000))
        assertEquals(250L, framesToPlaybackPositionMs(positionFrames = 11025L, sampleRate = 0))
    }

    @Test
    fun loopedProgressUsesSampledNativePositionRatherThanSyntheticEventPosition() = runBlocking {
        val sampledNativePositions = Collections.synchronizedList(mutableListOf(44050L, 120L))
        val publishedPositionsMs = Collections.synchronizedList(mutableListOf<Long>())
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val controller = SystemMediaProgressSyncController(
            scope = scope,
            intervalMs = 20L
        ) {
            val framesAtTick = sampledNativePositions.removeFirst()
            publishedPositionsMs += framesToPlaybackPositionMs(framesAtTick, sampleRate = 44100)
        }

        controller.onPlaybackStateChanged(AudioPlayState.PLAYING)
        waitUntil { publishedPositionsMs.size == 1 }
        assertEquals(listOf(998L), publishedPositionsMs.toList())

        waitUntil { publishedPositionsMs.size == 2 }
        controller.dispose()
        assertEquals(listOf(998L, 2L), publishedPositionsMs.toList())
        scope.cancel()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            kotlinx.coroutines.delay(5L)
        }
        assertTrue("Condition was not met before timeout", predicate())
    }
}

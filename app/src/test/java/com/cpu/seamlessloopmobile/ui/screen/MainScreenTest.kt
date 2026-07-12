package com.cpu.seamlessloopmobile.ui.screen

import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.ui.screen.stats.canNavigateToPlaybackStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenTest {

    @Test
    fun boundStatsSelectTheExactLocalSongId() {
        val target = Song(id = 42L, fileName = "target.mp3", filePath = "/music/target.mp3")
        val samePathDifferentId = Song(id = 7L, fileName = "other.mp3", filePath = target.filePath)
        val stat = TrackStat(songId = target.id, filePath = target.filePath)

        assertEquals(target, findSongForTrackStat(listOf(samePathDifferentId, target), stat))
    }

    @Test
    fun unboundStatsDoNotUseWireOrPathFallback() {
        val song = Song(id = 42L, fileName = "target.mp3", filePath = "/music/target.mp3")
        val stat = TrackStat(
            songId = 0L,
            fileName = song.fileName,
            filePath = song.filePath,
            identityKey = "target.mp3|0"
        )

        assertNull(findSongForTrackStat(listOf(song), stat))
    }

    @Test
    fun boundStatsRemainNavigableWhenTheirFileIsMissing() {
        assertEquals(true, canNavigateToPlaybackStat(TrackStat(songId = 42L, filePath = "/missing.mp3")))
        assertEquals(false, canNavigateToPlaybackStat(TrackStat(songId = 0L, filePath = "/present.mp3")))
    }
}

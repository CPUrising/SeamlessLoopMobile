package com.cpu.seamlessloopmobile.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 1 合并策略单元测试。
 * 验证默认合并策略的正确性。
 */
class SyncMergePolicyTest {

    // -----------------------------------------------------------------------
    // DefaultPlaylistMergePolicy
    // -----------------------------------------------------------------------

    @Test
    fun `playlist merge uses remote when remote is newer`() {
        val local = SyncPlaylist(
            id = "p1",
            name = "Local Playlist",
            createdAt = 1000L,
            modifiedAt = 2000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 10000L), 0)
            )
        )
        val remote = SyncPlaylist(
            id = "p1",
            name = "Remote Playlist",
            createdAt = 1000L,
            modifiedAt = 3000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 10000L), 0),
                SyncPlaylistItem(SyncSongIdentity("b.mp3", 20000L), 1)
            )
        )
        val result = DefaultPlaylistMergePolicy.resolve(remote, local)
        assertEquals("Remote Playlist", result.name)
        assertEquals(2, result.items.size)
    }

    @Test
    fun `playlist merge uses local when local is newer`() {
        val local = SyncPlaylist(
            id = "p2",
            name = "Local Playlist",
            createdAt = 1000L,
            modifiedAt = 3000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 10000L), 0)
            )
        )
        val remote = SyncPlaylist(
            id = "p2",
            name = "Remote Playlist",
            createdAt = 1000L,
            modifiedAt = 2000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("b.mp3", 20000L), 1)
            )
        )
        val result = DefaultPlaylistMergePolicy.resolve(remote, local)
        assertEquals("Local Playlist", result.name)
        // local has a.mp3, remote has b.mp3: both should be present after merge
        val identities = result.items.map { it.song }
        assertEquals(2, identities.size)
        assertEquals(SyncSongIdentity("a.mp3", 10000L), identities[0])
        assertEquals(SyncSongIdentity("b.mp3", 20000L), identities[1])
    }

    @Test
    fun `playlist merge deduplicates items by song identity`() {
        val local = SyncPlaylist(
            id = "p3",
            name = "Playlist",
            createdAt = 1000L,
            modifiedAt = 2000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 10000L), 0),
                SyncPlaylistItem(SyncSongIdentity("b.mp3", 20000L), 1)
            )
        )
        val remote = SyncPlaylist(
            id = "p3",
            name = "Playlist",
            createdAt = 1000L,
            modifiedAt = 3000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 10000L), 5),
                SyncPlaylistItem(SyncSongIdentity("c.mp3", 30000L), 2)
            )
        )
        val result = DefaultPlaylistMergePolicy.resolve(remote, local)
        // remote wins (newer), but we keep remote's sortOrder for a.mp3 (5)
        // and add b.mp3 from local since it's missing in remote
        assertEquals(3, result.items.size)
        assertEquals(5, result.items.find { it.song == SyncSongIdentity("a.mp3", 10000L) }?.sortOrder)
        assertEquals(1, result.items.find { it.song == SyncSongIdentity("b.mp3", 20000L) }?.sortOrder)
        assertEquals(2, result.items.find { it.song == SyncSongIdentity("c.mp3", 30000L) }?.sortOrder)
    }

    @Test
    fun `playlist merge keeps items sorted by sortOrder`() {
        val remote = SyncPlaylist(
            id = "p4",
            name = "Sorted",
            createdAt = 1000L,
            modifiedAt = 2000L,
            items = listOf(
                SyncPlaylistItem(SyncSongIdentity("z.mp3", 5000L), 10),
                SyncPlaylistItem(SyncSongIdentity("a.mp3", 5000L), 1)
            )
        )
        val local = SyncPlaylist(
            id = "p4",
            name = "Sorted",
            createdAt = 1000L,
            modifiedAt = 1000L,
            items = emptyList()
        )
        val result = DefaultPlaylistMergePolicy.resolve(remote, local)
        assertEquals(2, result.items.size)
        assertEquals("a.mp3", result.items[0].song.fileName)
        assertEquals("z.mp3", result.items[1].song.fileName)
    }

    @Test
    fun `playlist merge same timestamp prefers remote`() {
        val now = 1000L
        val local = SyncPlaylist("p5", "Local", now, now, emptyList())
        val remote = SyncPlaylist("p5", "Remote", now, now, emptyList())
        val result = DefaultPlaylistMergePolicy.resolve(remote, local)
        assertEquals("Remote", result.name) // >= picks remote
    }

    // -----------------------------------------------------------------------
    // DefaultLoopPointMergePolicy
    // -----------------------------------------------------------------------

    @Test
    fun `loop point both null returns null`() {
        assertNull(DefaultLoopPointMergePolicy.resolve(null, null))
    }

    @Test
    fun `loop point remote null returns local`() {
        val local = SyncLoopPoint(100L, 200L, 1000L)
        assertEquals(local, DefaultLoopPointMergePolicy.resolve(null, local))
    }

    @Test
    fun `loop point local null returns remote`() {
        val remote = SyncLoopPoint(300L, 400L, 2000L)
        assertEquals(remote, DefaultLoopPointMergePolicy.resolve(remote, null))
    }

    @Test
    fun `loop point remote newer returns remote`() {
        val local = SyncLoopPoint(100L, 200L, 1000L)
        val remote = SyncLoopPoint(150L, 250L, 2000L)
        assertEquals(remote, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    @Test
    fun `loop point local newer returns local`() {
        val local = SyncLoopPoint(100L, 200L, 2000L)
        val remote = SyncLoopPoint(150L, 250L, 1000L)
        assertEquals(local, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    @Test
    fun `loop point same timestamp prefers remote`() {
        val now = 5000L
        val local = SyncLoopPoint(100L, 200L, now)
        val remote = SyncLoopPoint(150L, 250L, now)
        assertEquals(remote, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    // -----------------------------------------------------------------------
    // DefaultRatingMergePolicy
    // -----------------------------------------------------------------------

    @Test
    fun `rating both null returns null`() {
        assertNull(DefaultRatingMergePolicy.resolve(null, null))
    }

    @Test
    fun `rating remote null returns local`() {
        val local = SyncRating(5, 1000L)
        assertEquals(local, DefaultRatingMergePolicy.resolve(null, local))
    }

    @Test
    fun `rating local null returns remote`() {
        val remote = SyncRating(3, 2000L)
        assertEquals(remote, DefaultRatingMergePolicy.resolve(remote, null))
    }

    @Test
    fun `rating remote newer returns remote`() {
        val local = SyncRating(5, 1000L)
        val remote = SyncRating(3, 2000L)
        assertEquals(remote, DefaultRatingMergePolicy.resolve(remote, local))
    }

    @Test
    fun `rating local newer returns local`() {
        val local = SyncRating(5, 2000L)
        val remote = SyncRating(3, 1000L)
        assertEquals(local, DefaultRatingMergePolicy.resolve(remote, local))
    }

    @Test
    fun `rating same timestamp prefers remote`() {
        val now = 5000L
        val local = SyncRating(5, now)
        val remote = SyncRating(3, now)
        assertEquals(remote, DefaultRatingMergePolicy.resolve(remote, local))
    }

    // -----------------------------------------------------------------------
    // 保护规则：循环点零值保护
    // -----------------------------------------------------------------------

    @Test
    fun `loop point zero values protected - remote unset keeps local substantive`() {
        val local = SyncLoopPoint(100L, 200L, 3000L)   // substantive
        val remote = SyncLoopPoint(0L, 0L, 4000L)       // unset, newer
        // remote is newer but unset → keep local
        assertEquals(local, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    @Test
    fun `loop point zero values protected - local unset keeps remote substantive`() {
        val local = SyncLoopPoint(0L, 0L, 4000L)        // unset, newer
        val remote = SyncLoopPoint(100L, 200L, 3000L)   // substantive
        // local is newer but unset → keep remote
        assertEquals(remote, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    @Test
    fun `loop point both unset picks newer`() {
        val local = SyncLoopPoint(0L, 0L, 1000L)
        val remote = SyncLoopPoint(0L, 0L, 2000L)
        // both unset, remote newer → remote
        assertEquals(remote, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    @Test
    fun `loop point both substantive normal LWW`() {
        val local = SyncLoopPoint(100L, 200L, 2000L)
        val remote = SyncLoopPoint(300L, 400L, 1000L)
        // both substantive, local newer → local
        assertEquals(local, DefaultLoopPointMergePolicy.resolve(remote, local))
    }

    // -----------------------------------------------------------------------
    // 保护规则：评分零值保护
    // -----------------------------------------------------------------------

    @Test
    fun `rating zero protected - remote unset keeps local substantive`() {
        val local = SyncRating(4, 2000L)    // substantive
        val remote = SyncRating(0, 3000L)   // unset, newer
        // remote is newer but 0 → keep local
        assertEquals(local, DefaultRatingMergePolicy.resolve(remote, local))
    }

    @Test
    fun `rating zero protected - local unset keeps remote substantive`() {
        val local = SyncRating(0, 3000L)    // unset, newer
        val remote = SyncRating(4, 2000L)   // substantive
        // local is newer but 0 → keep remote
        assertEquals(remote, DefaultRatingMergePolicy.resolve(remote, local))
    }

    @Test
    fun `rating both zero picks newer`() {
        val local = SyncRating(0, 1000L)
        val remote = SyncRating(0, 2000L)
        assertEquals(remote, DefaultRatingMergePolicy.resolve(remote, local))
    }

    @Test
    fun `rating both nonzero normal LWW`() {
        val local = SyncRating(3, 2000L)
        val remote = SyncRating(5, 1000L)
        assertEquals(local, DefaultRatingMergePolicy.resolve(remote, local))
    }
}

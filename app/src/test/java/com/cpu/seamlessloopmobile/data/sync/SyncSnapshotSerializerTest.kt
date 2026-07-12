package com.cpu.seamlessloopmobile.data.sync

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SyncSnapshotSerializer 单元测试。
 * 验证 Gson 驱动的序列化/反序列化正确性，包括 schema 校验和异常处理。
 */
class SyncSnapshotSerializerTest {

    private val serializer = SyncSnapshotSerializer()
    private val gson = GsonBuilder().create()

    // -------------------------------------------------------------------
    // Round-trip
    // -------------------------------------------------------------------

    @Test
    fun `round trip produces identical snapshot`() {
        val original = SyncSnapshot(
            deviceId = "device-123",
            exportedAt = 1000000L,
            playlists = listOf(
                SyncPlaylist(
                    id = "pl-1",
                    name = "Favorites",
                    createdAt = 100L,
                    modifiedAt = 200L,
                    items = listOf(
                        SyncPlaylistItem(
                            song = SyncSongIdentity("song1.mp3", 30000L),
                            sortOrder = 0
                        )
                    )
                )
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(
                    song = SyncSongIdentity("song1.mp3", 30000L),
                    loopPoint = SyncLoopPoint(1000L, 5000L, 300L)
                )
            ),
            ratings = listOf(
                SyncRatingEntry(
                    song = SyncSongIdentity("song1.mp3", 30000L),
                    rating = SyncRating(5, 400L)
                )
            )
        )

        val json = serializer.serialize(original)
        val restored = serializer.deserialize(json)

        assertEquals(original, restored)
    }

    @Test
    fun `round trip with minimal snapshot`() {
        val original = SyncSnapshot(
            deviceId = "device-456",
            exportedAt = 2000000L
        )

        val json = serializer.serialize(original)
        val restored = serializer.deserialize(json)

        assertEquals(original, restored)
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects schema one input`() {
        serializer.deserialize("""{
            "schemaVersion": 1, "deviceId": "legacy", "exportedAt": 10,
            "playbackStatistics": {}
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects missing canonical playback statistics`() {
        serializer.deserialize("""{
            "schemaVersion": 2, "deviceId": "v2", "exportedAt": 10
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects null canonical playback statistics`() {
        serializer.deserialize("""{
            "schemaVersion": 2, "deviceId": "v2", "exportedAt": 10,
            "playbackStatistics": null
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects playback stats alias`() {
        serializer.deserialize("""{
            "schemaVersion": 2, "deviceId": "v2", "exportedAt": 10,
            "playbackStats": {}
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects both playback statistics fields`() {
        serializer.deserialize("""{
            "schemaVersion": 2, "deviceId": "v2", "exportedAt": 10,
            "playbackStatistics": {}, "playbackStats": {}
        }""")
    }

    @Test
    fun `v2 round trip sorts playback stats deterministically`() {
        val original = SyncSnapshot(
            schemaVersion = SYNC_SCHEMA_VERSION_V2,
            deviceId = "v2",
            exportedAt = 10L,
            playbackStats = SyncPlaybackStats(
                devices = listOf(
                    SyncPlaybackStatsDevice("b", "B", 2L, 3L, 1L, "android", 2L),
                    SyncPlaybackStatsDevice("a", "A", 1L, 4L, 0L, "android", 1L)
                ),
                songs = listOf(
                    SyncPlaybackStatsSong(
                        SyncSongIdentity("Z.mp3", 20L),
                        listOf(SyncPlaybackStatsContribution("b", 1L, mapOf("2026-01-02" to 5L)))
                    ),
                    SyncPlaybackStatsSong(
                        SyncSongIdentity("a.mp3", 10L),
                        listOf(SyncPlaybackStatsContribution("a", 0L, mapOf("2026-01-01" to 1L)))
                    )
                ),
                tombstones = listOf(SyncPlaybackStatsTombstone("b", 1L, 5L))
            )
        )

        val restored = serializer.deserialize(serializer.serialize(original))

        assertEquals(listOf("a", "b"), restored.playbackStats!!.devices.map { it.deviceId })
        assertEquals(listOf("a.mp3", "z.mp3"), restored.playbackStats.songs.map { it.song.normalizedFileName })
    }

    @Test
    fun `v2 serialization emits an empty playback statistics object and round trips`() {
        val original = SyncSnapshot(
            schemaVersion = SYNC_SCHEMA_VERSION_V2,
            deviceId = "v2",
            exportedAt = 1L,
            playbackStats = SyncPlaybackStats()
        )

        val json = serializer.serialize(original)

        assertTrue(JsonParser.parseString(json).asJsonObject.get("playbackStatistics").isJsonObject)
        assertEquals(SyncPlaybackStats(), serializer.deserialize(json).playbackStats)
    }

    @Test(expected = SyncSerializationException::class)
    fun `v2 serialization rejects invalid outbound device metadata`() {
        serializer.serialize(
            SyncSnapshot(
                schemaVersion = SYNC_SCHEMA_VERSION_V2,
                deviceId = "v2",
                exportedAt = 1L,
                playbackStats = SyncPlaybackStats(
                    devices = listOf(SyncPlaybackStatsDevice("device", "", 1L, 1L))
                )
            )
        )
    }

    @Test
    fun `v2 serialization is canonical and fixture round trips deterministically`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "sync/playback_stats_v2_android_golden.json"
        )).bufferedReader().use { it.readText() }
        val snapshot = serializer.deserialize(fixture)

        val serialized = serializer.serialize(snapshot)

        assertTrue(serialized.contains("\"playbackStatistics\""))
        assertTrue(!serialized.contains("\"playbackStats\""))
        assertEquals(snapshot, serializer.deserialize(serialized))
        assertEquals(serialized, serializer.serialize(serializer.deserialize(serialized)))
    }

    @Test
    fun `v2 playback statistics fixtures strictly deserialize`() {
        val fixtures = listOf(
            "sync/playback_stats_v2_android_golden.json",
            "sync/playback_stats_v2_tombstone_collision.json",
            "sync/playback_stats_v2_wpf_canonical.json"
        )

        fixtures.forEach { fixturePath ->
            val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(fixturePath))
                .bufferedReader().use { it.readText() }

            val snapshot = serializer.deserialize(fixture)

            assertEquals(SYNC_SCHEMA_VERSION_V2, snapshot.schemaVersion)
            assertNotNull(snapshot.playbackStats)
        }
    }

    @Test
    fun `wpf canonical playback statistics match android golden apart from provenance device id`() {
        val wpf = deserializeFixture("sync/playback_stats_v2_wpf_canonical.json")
        val android = deserializeFixture("sync/playback_stats_v2_android_golden.json")

        assertEquals(android.playbackStats, wpf.playbackStats)
        assertTrue(wpf.deviceId != android.deviceId)
    }

    @Test
    fun `v2 canonical playback statistics round trip uses canonical field`() {
        val snapshot = SyncSnapshot(
            schemaVersion = SYNC_SCHEMA_VERSION_V2,
            deviceId = "v2",
            exportedAt = 1L,
            playbackStats = SyncPlaybackStats(
                devices = listOf(SyncPlaybackStatsDevice("d", "Phone", 1L, 2L, 0L, "android", 1L))
            )
        )

        val json = serializer.serialize(snapshot)
        val restored = serializer.deserialize(json)

        assertTrue(json.contains("\"playbackStatistics\""))
        assertTrue(!json.contains("\"playbackStats\""))
        assertEquals(snapshot.playbackStats, restored.playbackStats)
    }

    @Test
    fun `playback stats song semantic validator rejects invalid dates and negative values`() {
        val invalidDate = SyncPlaybackStatsSong(
            SyncSongIdentity("a.mp3", 1L),
            listOf(SyncPlaybackStatsContribution("d", 0L, mapOf("not-a-date" to 0L)))
        )
        val negativeValues = SyncPlaybackStatsSong(
            SyncSongIdentity("a.mp3", 1L),
            listOf(SyncPlaybackStatsContribution("d", -1L, undatedListenMs = -1L))
        )

        assertTrue(!invalidDate.isSemanticallyValid())
        assertTrue(!negativeValues.isSemanticallyValid())
    }

    @Test
    fun `playback stats song semantic validator rejects mismatched normalized file name`() {
        val song = SyncPlaybackStatsSong(
            SyncSongIdentity(
                fileName = "first-song.mp3",
                durationMs = 1L,
                normalizedFileName = "other-song.mp3"
            )
        )

        assertTrue(!song.isSemanticallyValid())
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects v2 playback statistics song with mismatched normalized file name`() {
        serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
          "playbackStatistics": { "dateBucketBasis": "sourceLocal", "songs": [{
            "song": { "fileName": "first-song.mp3", "normalizedFileName": "other-song.mp3", "durationMs": 1 },
            "contributions": []
          }] }
        }""")
    }

    @Test
    fun `accepts v2 playback statistics song with canonical Unicode normalized file name`() {
        val snapshot = serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
          "playbackStatistics": { "dateBucketBasis": "sourceLocal", "songs": [{
            "song": { "fileName": "  CAFÉ.MP3  ", "normalizedFileName": "café.mp3", "durationMs": 1 },
            "contributions": []
          }] }
        }""")

        assertEquals("café.mp3", snapshot.playbackStats!!.songs.single().song.normalizedFileName)
    }

    @Test
    fun `backfills missing generic normalized file names and reserializes canonically`() {
        val json = """{
          "schemaVersion": 2, "deviceId": "wpf", "exportedAt": 1,
          "playlists": [{
            "id": "p1", "name": "Favorites", "createdAt": 1, "modifiedAt": 2,
            "items": [{
              "song": { "fileName": "  Track.MP3  ", "durationMs": 10, "totalSamples": 20 },
              "sortOrder": 0
            }]
          }],
          "loopPoints": [{
            "song": { "fileName": "Loop.FLAC", "durationMs": 11, "totalSamples": 21 },
            "loopPoint": { "loopStart": 1, "loopEnd": 2, "lastModified": 3 }
          }],
          "playbackStatistics": { "dateBucketBasis": "sourceLocal" }
        }"""

        val snapshot = serializer.deserialize(json)
        val playlistSong = snapshot.playlists.single().items.single().song
        val loopSong = snapshot.loopPoints.single().song

        assertEquals("track.mp3", playlistSong.normalizedFileName)
        assertEquals("loop.flac", loopSong.normalizedFileName)
        val serialized = serializer.serialize(snapshot)
        val root = JsonParser.parseString(serialized).asJsonObject
        assertEquals(
            "track.mp3",
            root.getAsJsonArray("playlists").single()
                .asJsonObject.getAsJsonArray("items").single().asJsonObject
                .getAsJsonObject("song").get("normalizedFileName").asString
        )
        assertEquals(
            "loop.flac",
            root.getAsJsonArray("loopPoints").single().asJsonObject
                .getAsJsonObject("song").get("normalizedFileName").asString
        )
    }

    @Test
    fun `backfills null or blank generic normalized file names`() {
        val snapshot = serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "wpf", "exportedAt": 1,
          "ratings": [
            { "song": { "fileName": "Rating.MP3", "durationMs": 12, "normalizedFileName": null },
              "rating": { "rating": 4, "lastModified": 3 } },
            { "song": { "fileName": "Blank.MP3", "durationMs": 13, "normalizedFileName": "   " },
              "rating": { "rating": 3, "lastModified": 4 } }
          ],
          "playbackStatistics": { "dateBucketBasis": "sourceLocal" }
        }""")

        assertEquals(
            listOf("rating.mp3", "blank.mp3"),
            snapshot.ratings.map { it.song.normalizedFileName }
        )
    }

    @Test
    fun `rejects inconsistent generic normalized file name`() {
        val exception = try {
            serializer.deserialize("""{
              "schemaVersion": 2, "deviceId": "wpf", "exportedAt": 1,
              "loopPoints": [{
                "song": { "fileName": "Loop.MP3", "durationMs": 1, "normalizedFileName": "other.mp3" },
                "loopPoint": { "loopStart": 1, "loopEnd": 2, "lastModified": 3 }
              }],
              "playbackStatistics": { "dateBucketBasis": "sourceLocal" }
            }""")
            throw AssertionError("Expected SyncSerializationException")
        } catch (e: SyncSerializationException) {
            e
        }

        assertTrue(exception.message!!.contains("loopPoints[0].song.normalizedFileName"))
    }

    @Test
    fun `rejects missing or null generic file name with a field-specific exception`() {
        listOf(
            "{ \"durationMs\": 1 }",
            "{ \"fileName\": null, \"durationMs\": 1 }"
        ).forEach { songJson ->
            val exception = try {
                serializer.deserialize("""{
                  "schemaVersion": 2, "deviceId": "wpf", "exportedAt": 1,
                  "ratings": [{
                    "song": $songJson,
                    "rating": { "rating": 4, "lastModified": 3 }
                  }],
                  "playbackStatistics": { "dateBucketBasis": "sourceLocal" }
                }""")
                throw AssertionError("Expected SyncSerializationException")
            } catch (e: SyncSerializationException) {
                e
            }

            assertTrue(exception.message!!.contains("ratings[0].song.fileName"))
        }
    }

    @Test
    fun `playback statistics song still requires normalized file name`() {
        val exception = try {
            serializer.deserialize("""{
              "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
              "playbackStatistics": { "dateBucketBasis": "sourceLocal", "songs": [{
                "song": { "fileName": "track.mp3", "durationMs": 1 },
                "contributions": []
              }] }
            }""")
            throw AssertionError("Expected SyncSerializationException")
        } catch (e: SyncSerializationException) {
            e
        }

        assertTrue(exception.message!!.contains("normalizedFileName"))
    }

    @Test
    fun `playback statistics requires explicit date bucket basis`() {
        val exception = try {
            serializer.deserialize("""{
              "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
              "playbackStatistics": { "devices": [], "songs": [], "tombstones": [] }
            }""")
            throw AssertionError("Expected SyncSerializationException")
        } catch (e: SyncSerializationException) {
            e
        }

        assertTrue(exception.message!!.contains("dateBucketBasis"))
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects duplicate v2 contribution and invalid date`() {
        serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
          "playbackStatistics": { "dateBucketBasis": "sourceLocal", "songs": [{
            "song": { "fileName": "a.mp3", "normalizedFileName": "a.mp3", "durationMs": 1 },
            "contributions": [
              { "deviceId": "d", "generation": 0, "datedListenMs": { "not-a-date": 1 } },
              { "deviceId": "d", "generation": 0, "datedListenMs": {} }
            ]
          }] }
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects invalid canonical playback statistics audit fields`() {
        serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
          "playbackStatistics": { "dateBucketBasis": "sourceLocal", "devices": [{
            "deviceId": "d", "displayName": "Phone", "platform": "android",
            "firstSeenAtUtcMs": 1, "lastSeenAtUtcMs": 2,
            "currentGeneration": -1, "displayNameUpdatedAtUtcMs": 1
          }] }
        }""")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects duplicate canonical playback statistics contribution`() {
        serializer.deserialize("""{
          "schemaVersion": 2, "deviceId": "v2", "exportedAt": 1,
          "playbackStatistics": { "dateBucketBasis": "sourceLocal", "songs": [{
            "song": { "fileName": "a.mp3", "normalizedFileName": "a.mp3", "durationMs": 1 },
            "contributions": [
              { "deviceId": "d", "generation": 0, "datedListenMs": {}, "firstPlayedAtUtcMs": 1, "lastPlayedAtUtcMs": 1, "updatedAtUtcMs": 1 },
              { "deviceId": "d", "generation": 0, "datedListenMs": {}, "firstPlayedAtUtcMs": 1, "lastPlayedAtUtcMs": 1, "updatedAtUtcMs": 1 }
            ]
          }] }
        }""")
    }

    @Test
    fun `round trip preserves all fields`() {
        val original = SyncSnapshot(
            schemaVersion = SYNC_SCHEMA_VERSION_V2,
            deviceId = "test-device",
            exportedAt = 5000L,
            playlists = listOf(
                SyncPlaylist("p1", "Test", 100L, 200L)
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(
                    SyncSongIdentity("a.mp3", 10000L),
                    SyncLoopPoint(0L, 0L, 300L)
                )
            ),
            ratings = emptyList()
        )

        val json = serializer.serialize(original)
        val restored = serializer.deserialize(json)

        assertEquals(original.schemaVersion, restored.schemaVersion)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.playlists.size, restored.playlists.size)
        assertEquals(original.loopPoints.size, restored.loopPoints.size)
        assertEquals(original.ratings.size, restored.ratings.size)
        assertEquals(original, restored)
    }

    // -------------------------------------------------------------------
    // Schema version validation
    // -------------------------------------------------------------------

    @Test(expected = SyncSerializationException::class)
    fun `rejects unsupported schema version - too high`() {
        val json = gson.toJson(
            mapOf(
                "schemaVersion" to 999,
                "deviceId" to "test",
                "exportedAt" to 1000L
            )
        )
        serializer.deserialize(json)
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects unsupported schema version - too low`() {
        val json = gson.toJson(
            mapOf(
                "schemaVersion" to 0,
                "deviceId" to "test",
                "exportedAt" to 1000L
            )
        )
        serializer.deserialize(json)
    }

    // -------------------------------------------------------------------
    // Malformed / empty JSON
    // -------------------------------------------------------------------

    @Test(expected = SyncSerializationException::class)
    fun `rejects empty string`() {
        serializer.deserialize("")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects blank string`() {
        serializer.deserialize("   ")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects malformed JSON`() {
        serializer.deserialize("{{{{broken json}///")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects null literal`() {
        serializer.deserialize("null")
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects missing device id`() {
        val json = """{
            "schemaVersion": 2,
            "exportedAt": 1000,
            "playbackStatistics": {}
        }"""
        serializer.deserialize(json)
    }

    @Test(expected = SyncSerializationException::class)
    fun `rejects negative exported at`() {
        val json = """{
            "schemaVersion": 2,
            "deviceId": "device",
            "exportedAt": -1,
            "playbackStatistics": {}
        }"""
        serializer.deserialize(json)
    }

    @Test
    fun `normalizes null lists to empty lists`() {
        val json = """{
            "schemaVersion": 2,
            "deviceId": "device",
            "exportedAt": 1000,
            "playlists": null,
            "loopPoints": null,
            "ratings": null,
            "playbackStatistics": { "dateBucketBasis": "sourceLocal" }
        }"""

        val snapshot = serializer.deserialize(json)

        assertEquals(emptyList<SyncPlaylist>(), snapshot.playlists)
        assertEquals(emptyList<SyncLoopPointEntry>(), snapshot.loopPoints)
        assertEquals(emptyList<SyncRatingEntry>(), snapshot.ratings)
    }

    // -------------------------------------------------------------------
    // Unknown fields ignored
    // -------------------------------------------------------------------

    @Test
    fun `ignores unknown fields during deserialization`() {
        val json = """{
            "schemaVersion": 2,
            "deviceId": "test-device",
            "exportedAt": 1000,
            "playbackStatistics": { "dateBucketBasis": "sourceLocal" },
            "unknownField": "should be ignored",
            "anotherUnknown": 42
        }"""
        val snapshot = serializer.deserialize(json)
        assertNotNull(snapshot)
        assertEquals("test-device", snapshot.deviceId)
        assertEquals(1000L, snapshot.exportedAt)
        assertEquals(2, snapshot.schemaVersion)
    }

    // -------------------------------------------------------------------
    // Serialize
    // -------------------------------------------------------------------

    @Test
    fun `serialize produces valid JSON`() {
        val snapshot = SyncSnapshot(deviceId = "d1", exportedAt = 100L)
        val json = serializer.serialize(snapshot)
        // Should be parseable back via Gson
        val parsed = gson.fromJson(json, Map::class.java)
        assertNotNull(parsed)
        assertEquals("d1", (parsed as Map<*, *>)["deviceId"])
    }

    // -------------------------------------------------------------------
    // Custom Gson instance
    // -------------------------------------------------------------------

    @Test
    fun `uses custom gson instance`() {
        val customGson = GsonBuilder().setPrettyPrinting().create()
        val customSerializer = SyncSnapshotSerializer(customGson)
        val snapshot = SyncSnapshot(deviceId = "custom", exportedAt = 200L)
        val json = customSerializer.serialize(snapshot)
        // Pretty-printed JSON should still be valid
        val restored = customSerializer.deserialize(json)
        assertEquals(snapshot, restored)
    }

    private fun deserializeFixture(path: String): SyncSnapshot {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
            .bufferedReader().use { it.readText() }
        return serializer.deserialize(fixture)
    }
}

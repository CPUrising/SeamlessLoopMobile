package com.cpu.seamlessloopmobile.data.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.time.LocalDate

/**
 * Gson 驱动的 SyncSnapshot 序列化/反序列化工具。
 *
 * - 忽略未知字段（Gson 默认行为）
 * - 校验 schemaVersion
 * - 拒绝空/格式错误的 JSON
 */
class SyncSnapshotSerializer(
    private val gson: Gson = GsonBuilder().create()
) {

    /**
     * 序列化快照为 JSON 字符串。
     */
    fun serialize(snapshot: SyncSnapshot): String {
        if (snapshot.schemaVersion != SYNC_SCHEMA_VERSION_V2) {
            throw SyncSerializationException("Unsupported schema version ${snapshot.schemaVersion}")
        }
        val canonical = try {
            snapshot.canonicalized()
        } catch (e: NullPointerException) {
            throw SyncSerializationException("Snapshot playbackStatistics is null", e)
        }
        checkNotNull(canonical.playbackStats) { "Snapshot playbackStatistics is null" }
        val json = gson.toJson(canonical)
        deserialize(json)
        return json
    }

    /**
     * 反序列化 JSON 字符串为 SyncSnapshot。
     *
     * @param json 待解析字符串
     * @return 校验通过后的 SyncSnapshot
     * @throws SyncSerializationException 当 JSON 为空、格式错误或 schemaVersion 不匹配时
     */
    fun deserialize(json: String): SyncSnapshot {
        if (json.isBlank()) {
            throw SyncSerializationException(
                "Snapshot JSON is blank or empty"
            )
        }

        val root: JsonElement = try {
            JsonParser.parseString(json)
        } catch (e: JsonParseException) {
            throw SyncSerializationException("Malformed snapshot JSON: ${e.message}", e)
        }
        if (!root.isJsonObject) throw SyncSerializationException("Snapshot JSON must be an object")

        val schemaVersion = root.asJsonObject.get("schemaVersion")?.asInt
            ?: throw SyncSerializationException("Snapshot schemaVersion is missing")
        if (schemaVersion != SYNC_SCHEMA_VERSION_V2) {
            throw SyncSerializationException("Unsupported schema version $schemaVersion")
        }
        normalizeGenericSongIdentities(root.asJsonObject)
        validateV2Payload(root.asJsonObject)

        val snapshot: SyncSnapshot = try {
            gson.fromJson(root, SyncSnapshot::class.java)
                ?: throw SyncSerializationException(
                    "JSON parsed to null (expected a valid snapshot object)"
                )
        } catch (e: SyncSerializationException) {
            throw e
        } catch (e: Exception) {
            throw SyncSerializationException(
                "Malformed snapshot JSON: ${e.message}",
                e
            )
        }

        if (snapshot.deviceId.isNullOrBlank()) {
            throw SyncSerializationException("Snapshot deviceId is blank")
        }

        if (snapshot.exportedAt < 0L) {
            throw SyncSerializationException("Snapshot exportedAt is negative")
        }
        if (snapshot.playbackStats == null) {
            throw SyncSerializationException("Snapshot playbackStatistics is null")
        }

        @Suppress("USELESS_CAST")
        return snapshot.copy(
            playlists = ((snapshot.playlists as List<SyncPlaylist>?) ?: emptyList()),
            loopPoints = ((snapshot.loopPoints as List<SyncLoopPointEntry>?) ?: emptyList()),
            ratings = ((snapshot.ratings as List<SyncRatingEntry>?) ?: emptyList())
        ).canonicalized()
    }

    private fun validateV2Payload(root: com.google.gson.JsonObject) {
        val canonicalStats = root.get("playbackStatistics")
        if (root.has("playbackStats")) fail("Snapshot playbackStats alias is not supported")
        if (canonicalStats == null || canonicalStats.isJsonNull) {
            fail("Snapshot playbackStatistics is required")
        }
        if (!canonicalStats.isJsonObject) fail("playbackStatistics must be an object")
        val payload = canonicalStats.asJsonObject
        val dateBucketBasis = payload.get("dateBucketBasis")
            ?.takeUnless { it.isJsonNull }
            ?: fail("playbackStatistics dateBucketBasis is required")
        if (!dateBucketBasis.isJsonPrimitive || !dateBucketBasis.asJsonPrimitive.isString) {
            fail("playbackStatistics dateBucketBasis must be a string")
        }
        if (dateBucketBasis.asString != "sourceLocal") fail("Invalid dateBucketBasis")

        val devices = payload.array("devices")
        unique(devices, "device") { it.objectValue("deviceId") }
        devices.forEach { device ->
            device.objectValue("deviceId").requireNotBlank("deviceId")
            device.objectValue("displayName").requireNotBlank("displayName")
            device.objectValue("platform").requireNotBlank("platform")
            nonNegative(device.objectLong("firstSeenAtUtcMs"), "firstSeenAtUtcMs")
            nonNegative(device.objectLong("lastSeenAtUtcMs"), "lastSeenAtUtcMs")
            nonNegative(device.objectLong("currentGeneration"), "currentGeneration")
            nonNegative(device.objectLong("displayNameUpdatedAtUtcMs"), "displayNameUpdatedAtUtcMs")
        }
        val songs = payload.array("songs")
        unique(songs, "song") { song ->
            val identity = song.asJsonObject.getAsJsonObject("song") ?: fail("song identity is missing")
            "${identity.objectValue("normalizedFileName")}:${identity.objectLong("durationMs")}"
        }
        songs.forEach { song ->
            val identity = song.asJsonObject.getAsJsonObject("song") ?: fail("song identity is missing")
            identity.objectValue("fileName").requireNotBlank("song fileName")
            val fileName = identity.objectValue("fileName")
            val normalizedFileName = identity.objectValue("normalizedFileName")
            normalizedFileName.requireNotBlank("song normalizedFileName")
            if (normalizedFileName != normalizeSyncFileName(fileName)) {
                fail("song normalizedFileName does not match fileName")
            }
            nonNegative(identity.objectLong("durationMs"), "song durationMs")
            identity.get("totalSamples")?.takeUnless { it.isJsonNull }?.asLong?.let { nonNegative(it, "song totalSamples") }
            val contributions = song.asJsonObject.array("contributions")
            unique(contributions, "contribution") { "${it.objectValue("deviceId")}:${it.objectLong("generation")}" }
            contributions.forEach { contribution ->
                contribution.objectValue("deviceId").requireNotBlank("contribution deviceId")
                nonNegative(contribution.objectLong("generation"), "generation")
                nonNegative(contribution.objectLong("undatedListenMs", default = 0), "undatedListenMs")
                nonNegative(contribution.objectLong("firstPlayedAtUtcMs"), "firstPlayedAtUtcMs")
                nonNegative(contribution.objectLong("lastPlayedAtUtcMs"), "lastPlayedAtUtcMs")
                nonNegative(contribution.objectLong("updatedAtUtcMs"), "updatedAtUtcMs")
                val dates = contribution.asJsonObject.arrayOrObject("datedListenMs")
                dates.entrySet().forEach { (date, value) ->
                    try { LocalDate.parse(date) } catch (_: Exception) { fail("Invalid date bucket $date") }
                    nonNegative(value.asLong, "datedListenMs")
                }
            }
        }
        val tombstones = payload.array("tombstones")
        unique(tombstones, "tombstone") { "${it.objectValue("deviceId")}:${it.objectLong("generation")}" }
        tombstones.forEach {
            it.objectValue("deviceId").requireNotBlank("tombstone deviceId")
            nonNegative(it.objectLong("generation"), "tombstone generation")
            if (it.objectValue("scope") != "deviceGeneration") fail("Invalid tombstone scope")
            it.objectValue("tombstonedByDeviceId").requireNotBlank("tombstonedByDeviceId")
            it.objectValue("reason").requireNotBlank("tombstone reason")
            nonNegative(it.objectLong("tombstonedAtUtcMs"), "tombstonedAtUtcMs")
        }
    }

    /**
     * WPF's generic song identity payloads predate normalizedFileName. Normalize them
     * before Gson sees the JSON so Kotlin non-null fields are never populated with null.
     * Playback-statistics identities are intentionally handled only by the strict validator
     * above and are not backfilled here.
     */
    private fun normalizeGenericSongIdentities(root: com.google.gson.JsonObject) {
        root.genericArray("playlists")?.forEachIndexed { playlistIndex, playlistElement ->
            val playlistPath = "playlists[$playlistIndex]"
            val playlist = playlistElement.requireObject(playlistPath)
            playlist.get("items")?.let { itemsElement ->
                if (itemsElement.isJsonNull) fail("$playlistPath.items is null")
                if (!itemsElement.isJsonArray) fail("$playlistPath.items must be an array")
                itemsElement.asJsonArray.forEachIndexed { itemIndex, itemElement ->
                    val itemPath = "$playlistPath.items[$itemIndex]"
                    val item = itemElement.requireObject(itemPath)
                    normalizeGenericSongIdentity(item.get("song"), "$itemPath.song")
                }
            }
        }

        root.genericArray("loopPoints")?.forEachIndexed { index, entryElement ->
            val entryPath = "loopPoints[$index]"
            val entry = entryElement.requireObject(entryPath)
            normalizeGenericSongIdentity(entry.get("song"), "$entryPath.song")
            entry.get("loopPoint").requireObject("$entryPath.loopPoint")
        }

        root.genericArray("ratings")?.forEachIndexed { index, entryElement ->
            val entryPath = "ratings[$index]"
            val entry = entryElement.requireObject(entryPath)
            normalizeGenericSongIdentity(entry.get("song"), "$entryPath.song")
            entry.get("rating").requireObject("$entryPath.rating")
        }
    }

    private fun normalizeGenericSongIdentity(
        songElement: com.google.gson.JsonElement?,
        path: String
    ) {
        val song = songElement.requireObject(path)
        val fileName = song.stringValue("fileName", path).also {
            if (it.isBlank()) fail("$path.fileName is blank")
        }
        val canonicalNormalizedFileName = normalizeSyncFileName(fileName)
        song.nonNegativeLong("durationMs", path)
        song.optionalNonNegativeLong("totalSamples", path)

        val normalizedElement = song.get("normalizedFileName")
        if (normalizedElement == null || normalizedElement.isJsonNull) {
            song.addProperty("normalizedFileName", canonicalNormalizedFileName)
        } else {
            val normalizedFileName = normalizedElement.stringValue("$path.normalizedFileName")
            if (normalizedFileName.isBlank()) {
                song.addProperty("normalizedFileName", canonicalNormalizedFileName)
            } else if (normalizedFileName != canonicalNormalizedFileName) {
                fail("$path.normalizedFileName does not match fileName")
            }
        }
    }

    private fun com.google.gson.JsonObject.genericArray(name: String): com.google.gson.JsonArray? =
        get(name)?.let {
            if (it.isJsonNull) return null
            if (!it.isJsonArray) fail("$name must be an array")
            it.asJsonArray
        }

    private fun com.google.gson.JsonElement?.requireObject(path: String): com.google.gson.JsonObject {
        if (this == null || isJsonNull || !isJsonObject) fail("$path must be an object")
        return asJsonObject
    }

    private fun com.google.gson.JsonObject.stringValue(name: String, path: String): String {
        val element = get(name)
        if (element == null || element.isJsonNull) fail("$path.$name is missing")
        return element.stringValue("$path.$name")
    }

    private fun com.google.gson.JsonElement.stringValue(path: String): String {
        if (!isJsonPrimitive || !asJsonPrimitive.isString) fail("$path must be a string")
        return asString
    }

    private fun com.google.gson.JsonObject.nonNegativeLong(name: String, path: String): Long {
        val element = get(name)
        if (element == null || element.isJsonNull) fail("$path.$name is missing")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            fail("$path.$name must be a number")
        }
        return element.asLong.also { nonNegative(it, "$path.$name") }
    }

    private fun com.google.gson.JsonObject.optionalNonNegativeLong(name: String, path: String) {
        val element = get(name) ?: return
        if (element.isJsonNull) return
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            fail("$path.$name must be a number")
        }
        nonNegative(element.asLong, "$path.$name")
    }

    private fun com.google.gson.JsonObject.array(name: String) = get(name)?.let {
        if (!it.isJsonArray) fail("$name must be an array")
        it.asJsonArray
    } ?: com.google.gson.JsonArray()
    private fun com.google.gson.JsonObject.arrayOrObject(name: String) = get(name)?.let {
        if (!it.isJsonObject) fail("$name must be an object")
        it.asJsonObject
    } ?: com.google.gson.JsonObject()
    private fun com.google.gson.JsonElement.objectValue(name: String): String =
        asJsonObject.get(name)?.takeUnless { it.isJsonNull }?.asString ?: fail("$name is missing")
    private fun com.google.gson.JsonElement.objectLong(name: String, default: Long? = null): Long =
        asJsonObject.get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default ?: fail("$name is missing")
    private fun unique(values: Iterable<com.google.gson.JsonElement>, label: String, key: (com.google.gson.JsonElement) -> String) {
        if (values.map(key).toSet().size != values.count()) fail("Duplicate $label key")
    }
    private fun String.requireNotBlank(label: String) { if (isBlank()) fail("$label is blank") }
    private fun nonNegative(value: Long, label: String) { if (value < 0L) fail("$label is negative") }
    private fun fail(message: String): Nothing = throw SyncSerializationException(message)
}

/**
 * 快照序列化/反序列化过程中的异常。
 */
class SyncSerializationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

package com.cpu.seamlessloopmobile.data.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Immutable snapshot of a single song's listening statistics.
 *
 * This is a presentation snapshot rebuilt from the persisted contribution
 * store. Playback recording uses the exact wire identity derived from the
 * current song; bound presentation rows may instead use a `bound-song:<id>`
 * identity after aggregation.
 *
 * @property songId       Room SongEntity id (0 if unknown).
 * @property displayName  Human-readable song title.
 * @property fileName     Raw file name.
 * @property artist       Artist display name.
 * @property album        Album display name.
 * @property coverPath    Optional cover art path.
 * @property durationMs   Song duration in milliseconds.
 * @property totalListenMs Cumulative wall-clock milliseconds spent listening.
 * @property dailyListenMs Listened milliseconds keyed by ISO local date. This
 *                         only includes deltas recorded after this field was added.
 * @property lastPlayedAt Epoch millis of the most recent listen session end.
 * @property firstPlayedAt Epoch millis of the first-ever listen session end.
 * @property filePath     Absolute file path on disk.
 * @property identityKey  Presentation identity. Exact recording identities
 *                        use `"$fileName|$durationMs"` when [durationMs] > 0,
 *                        otherwise [filePath]. Aggregated bound rows use
 *                        `"bound-song:<songId>"`.
 */
data class TrackStat(
    val songId: Long = 0L,
    val displayName: String = "",
    val fileName: String = "",
    val artist: String = "",
    val album: String = "",
    val coverPath: String? = null,
    val durationMs: Long = 0L,
    val totalListenMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val filePath: String = "",
    val identityKey: String = "",
    val dailyListenMs: Map<String, Long> = emptyMap()
) {
    /** Returns listened milliseconds for [period] relative to [today]. */
    fun listenMsFor(period: ListenStatsPeriod, today: LocalDate): Long {
        if (period == ListenStatsPeriod.ALL) return totalListenMs

        val startDate = when (period) {
            ListenStatsPeriod.DAY -> today
            ListenStatsPeriod.WEEK -> today.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            )
            ListenStatsPeriod.MONTH -> today.withDayOfMonth(1)
            ListenStatsPeriod.YEAR -> today.withDayOfYear(1)
            ListenStatsPeriod.ALL -> error("Handled above")
        }
        val endDate = when (period) {
            ListenStatsPeriod.DAY -> today
            ListenStatsPeriod.WEEK -> startDate.plusDays(6)
            ListenStatsPeriod.MONTH -> today.withDayOfMonth(today.lengthOfMonth())
            ListenStatsPeriod.YEAR -> today.withDayOfYear(today.lengthOfYear())
            ListenStatsPeriod.ALL -> error("Handled above")
        }

        return dailyListenMs.entries.fold(0L) { total, (dateKey, listenedMs) ->
            val date = runCatching { LocalDate.parse(dateKey) }.getOrNull()
            if (date != null && date in startDate..endDate && listenedMs > 0L) {
                if (total > Long.MAX_VALUE - listenedMs) Long.MAX_VALUE else total + listenedMs
            } else {
                total
            }
        }
    }

    companion object {
        private const val BOUND_PRESENTATION_ID_PREFIX = "bound-song:"

        /**
         * Builds the exact recording identity for [fileName] and [durationMs].
         * The recording identity is the normalized filename and duration when
         * the duration is positive. Falls back to [filePath] otherwise.
         */
        fun identityKey(fileName: String, durationMs: Long, filePath: String): String {
            return if (durationMs > 0L) {
                "${normalizedStatsFileName(fileName)}|$durationMs"
            } else {
                filePath
            }
        }

        /** Returns the explicit identity used for an aggregated bound row. */
        fun boundPresentationIdentityKey(songId: Long): String =
            "$BOUND_PRESENTATION_ID_PREFIX$songId"
    }
}

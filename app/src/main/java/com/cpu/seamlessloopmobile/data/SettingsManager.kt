package com.cpu.seamlessloopmobile.data

import android.content.Context
import android.content.SharedPreferences
import com.cpu.seamlessloopmobile.viewmodel.PlayMode
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 莱芙的私人小本本 📒
 * 负责记录 cpu 大人的所有偏好和上次的播放状态，哪怕 app 睡着了莱芙也能记得喵！
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "seamless_loop_settings"
        private const val KEY_LAST_SONG_PATH = "last_song_path"
        private const val KEY_LAST_POSITION = "last_position"
        private const val KEY_PLAY_MODE = "play_mode"
        private const val KEY_IS_AB_MODE = "is_ab_mode"
        private const val KEY_CURRENT_SONG_INDEX = "current_song_index"
        private const val KEY_LIBRARY_STATS = "library_stats"
        private const val KEY_IS_SEAMLESS_LOOP_ENABLED = "is_seamless_loop_enabled"
        private const val KEY_SEAMLESS_LOOP_COUNT_LIMIT = "seamless_loop_count_limit"
        private const val KEY_THEME_PREFERENCE = "theme_preference"
        private const val KEY_DARK_THEME_OVERRIDE = "dark_theme_override"
        private const val KEY_BUTTON_HAPTIC_FEEDBACK_ENABLED = "button_haptic_feedback_enabled"
        const val MAX_SEAMLESS_LOOP_COUNT_LIMIT = 9999
        
        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // --- 基础状态读写喵 ---

    var isSeamlessLoopEnabled: Boolean
        get() = prefs.getBoolean(KEY_IS_SEAMLESS_LOOP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_SEAMLESS_LOOP_ENABLED, value).apply()

    var seamlessLoopCountLimit: Int
        get() = prefs.getInt(KEY_SEAMLESS_LOOP_COUNT_LIMIT, 0).coerceIn(0, MAX_SEAMLESS_LOOP_COUNT_LIMIT)
        set(value) = prefs.edit().putInt(KEY_SEAMLESS_LOOP_COUNT_LIMIT, value.coerceIn(0, MAX_SEAMLESS_LOOP_COUNT_LIMIT)).apply()

    var themePreference: ThemePreference
        get() {
            val saved = prefs.getString(KEY_THEME_PREFERENCE, null)
            if (saved != null) {
                return runCatching { ThemePreference.valueOf(saved) }.getOrDefault(ThemePreference.SYSTEM)
            }
            return when (darkThemeOverride) {
                true -> ThemePreference.DARK
                false -> ThemePreference.LIGHT
                null -> ThemePreference.SYSTEM
            }
        }
        set(value) = prefs.edit().putString(KEY_THEME_PREFERENCE, value.name).apply()

    var buttonHapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUTTON_HAPTIC_FEEDBACK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BUTTON_HAPTIC_FEEDBACK_ENABLED, value).apply()

    var darkThemeOverride: Boolean?
        get() = if (prefs.contains(KEY_DARK_THEME_OVERRIDE)) {
            prefs.getBoolean(KEY_DARK_THEME_OVERRIDE, false)
        } else {
            null
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DARK_THEME_OVERRIDE) else putBoolean(KEY_DARK_THEME_OVERRIDE, value)
                apply()
            }
        }

    var lastSongPath: String?
        get() = prefs.getString(KEY_LAST_SONG_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_SONG_PATH, value).apply()

    var lastPosition: Long
        get() = prefs.getLong(KEY_LAST_POSITION, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_POSITION, value).apply()

    var playMode: PlayMode
        get() {
            val modeName = prefs.getString(KEY_PLAY_MODE, PlayMode.LIST_LOOP.name)
            return try { PlayMode.valueOf(modeName!!) } catch (e: Exception) { PlayMode.LIST_LOOP }
        }
        set(value) = prefs.edit().putString(KEY_PLAY_MODE, value.name).apply()

    var isAbMode: Boolean
        get() = prefs.getBoolean(KEY_IS_AB_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_AB_MODE, value).apply()

    var currentSongIndex: Int
        get() = prefs.getInt(KEY_CURRENT_SONG_INDEX, -1)
        set(value) = prefs.edit().putInt(KEY_CURRENT_SONG_INDEX, value).apply()

    var lastLibraryStats: LibraryStats?
        get() {
            val json = prefs.getString(KEY_LIBRARY_STATS, null) ?: return null
            return try { gson.fromJson(json, LibraryStats::class.java) } catch (e: Exception) { null }
        }
        set(value) = prefs.edit().putString(KEY_LIBRARY_STATS, gson.toJson(value)).apply()

    /**
     * 一键记录所有关键状态喵！
     */
    fun saveFullState(
        songPath: String?,
        position: Long,
        mode: PlayMode,
        isAb: Boolean,
        playlist: List<String>,
        index: Int
    ) {
        prefs.edit().apply {
            putString(KEY_LAST_SONG_PATH, songPath)
            putLong(KEY_LAST_POSITION, position)
            putString(KEY_PLAY_MODE, mode.name)
            putBoolean(KEY_IS_AB_MODE, isAb)
            putInt(KEY_CURRENT_SONG_INDEX, index)
            apply()
        }
    }

    /**
     * 图书馆统计快照，用于“抢跑”显示喵！🚀
     */
    data class LibraryStats(
        val songCount: Int = 0,
        val albumCount: Int = 0,
        val artistCount: Int = 0,
        val folderCount: Int = 0,
        val playlistNamesWithCounts: Map<String, Int> = emptyMap()
    )
}

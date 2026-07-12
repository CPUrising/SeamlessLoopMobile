package com.cpu.seamlessloopmobile.audio

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.data.MusicRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.audio.stats.PlaybackStatsTracker
import com.cpu.seamlessloopmobile.db.AppDatabase
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.content.Intent

/**
 * 影子司令部：正式升级为官方“明星架构”——媒体浏览器服务喵！
 * 负责在后台默默维持音频引擎的生命线，并为系统和其他应用提供标准的控制接口。
 */
class PlaybackService : MediaBrowserServiceCompat() {

    private val binder = PlaybackBinder()
    private val serviceScope = MainScope()
    private lateinit var repository: MusicRepository
    var playbackManager: PlaybackManager? = null
    var mediaControlManager: MediaControlManager? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notify: Notify? = null
    private val queueManager = QueueManager()
    private lateinit var systemProgressSyncController: SystemMediaProgressSyncController
    private lateinit var listenStatsRepository: ListenStatsRepository
    private lateinit var playbackStatsTracker: PlaybackStatsTracker
    private var statsFlushJob: Job? = null

    inner class PlaybackBinder : android.os.Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private lateinit var audioFocusManager: AudioFocusManager
    private var wasPlayingBeforeUnplug = false
    private lateinit var headsetPlugReceiver: HeadsetPlugReceiver
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val audioFocusCallbacks = object : AudioFocusManager.Callbacks {
        override fun onFocusGained() {
            playbackManager?.resume()
        }

        override fun onFocusLost() {
            playbackManager?.pause()
        }

        override fun onFocusLostTransient() {
            playbackManager?.pause()
        }

        override fun onFocusDuck() {
            // 暂时不调小声音，直接暂停喵
            playbackManager?.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        audioFocusManager = AudioFocusManager(this).apply {
            setCallbacks(audioFocusCallbacks)
        }
        
        headsetPlugReceiver = HeadsetPlugReceiver(object : HeadsetPlugReceiver.Callbacks {
            override fun onHeadsetPlugged() {
                // 🎧 插入耳机设备切换，我们要采取和拔出一样的措施：暂停播放防惊吓喵！
                if (playbackManager?.isPlaying == true) {
                    wasPlayingBeforeUnplug = true
                    playbackManager?.pause()
                }
            }

            override fun onHeadsetUnplugged() {
                // 🔌 耳机拔出，记住状态并暂停
                // 修复：不要直接赋值 wasPlayingBeforeUnplug = x，防止被 BecomingNoisy 抢先导致状态变成 false
                if (playbackManager?.isPlaying == true) {
                    wasPlayingBeforeUnplug = true
                    playbackManager?.pause()
                }
            }

            override fun onBecomingNoisy() {
                // ⚠️ 即将外放，强制暂停并记录状态
                if (playbackManager?.isPlaying == true) {
                    wasPlayingBeforeUnplug = true
                    playbackManager?.pause()
                }
            }
        })
        headsetPlugReceiver.register(this)

        val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SeamlessLoop:PlaybackWakeLock")

        val database = AppDatabase.getDatabase(this)
        repository = MusicRepository(database.songDao(), database.playlistDao(), database.playQueueDao())

        // 初始化媒体会话喵！
        mediaSession = MediaSessionCompat(this, "SeamlessLoopService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        
        setSessionToken(mediaSession?.sessionToken)

        notify = NotifyImpl(
            context = this,
            mediaSession = mediaSession!!,
            notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        )

        systemProgressSyncController = SystemMediaProgressSyncController(serviceScope) {
            // Keep notification/lock-screen progress sourced from the native position sampled
            // on the service thread. This avoids treating decoder loop handover as audible rewind.
            playbackManager?.updatePosition()
        }

        listenStatsRepository = ListenStatsRepository.getInstance(this)
        playbackStatsTracker = PlaybackStatsTracker(listenStatsRepository)
        serviceScope.launch {
            listenStatsRepository.clearEvents.collect {
                playbackStatsTracker.onStatsCleared()
            }
        }

        playbackManager = PlaybackManager(
            context = this,
            coroutineScope = serviceScope,
            repository = repository,
            mediaSession = mediaSession!!
        ).apply {
            onPlaybackStatusChanged = { isPlaying, song ->
                if (song != null) {
                    handlePlaybackStateChange(isPlaying, song)
                }
                // Track real listened time via wall-clock while PLAYING.
                if (song != null) {
                    playbackStatsTracker.onSongChanged(song)
                    playbackStatsTracker.onPlayingChanged(isPlaying)
                }
            }
            onSongCompleted = {
                val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this@PlaybackService)
                val modeOrdinal = mediaSession?.controller?.playbackState?.extras?.getInt("play_mode") ?: settingsManager.playMode.ordinal
                val mode = com.cpu.seamlessloopmobile.viewmodel.PlayMode.values().getOrNull(modeOrdinal) ?: com.cpu.seamlessloopmobile.viewmodel.PlayMode.LIST_LOOP
                
                if (mode == com.cpu.seamlessloopmobile.viewmodel.PlayMode.SINGLE_LOOP) {
                    android.util.Log.d("PlaybackService", "🔁 单曲循环模式下抵达物理结尾，自动重新播放当前歌曲喵！")
                    playbackManager?.currentSong?.let { song ->
                        playbackManager?.playSong(song, 0L, false)
                    }
                } else {
                    android.util.Log.d("PlaybackService", "⏭️ 列表/随机模式下抵达物理结尾，切入下一首歌曲喵！")
                    mediaSession?.controller?.transportControls?.skipToNext()
                }
            }
            onSeamlessLoopLimitReached = {
                val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this@PlaybackService)
                val modeOrdinal = mediaSession?.controller?.playbackState?.extras?.getInt("play_mode") ?: settingsManager.playMode.ordinal
                val mode = com.cpu.seamlessloopmobile.viewmodel.PlayMode.values().getOrNull(modeOrdinal) ?: com.cpu.seamlessloopmobile.viewmodel.PlayMode.LIST_LOOP

                if (mode == com.cpu.seamlessloopmobile.viewmodel.PlayMode.SINGLE_LOOP) {
                    android.util.Log.d("PlaybackService", "🔁 单曲循环模式下达到无缝循环次数上限，重新播放当前歌曲")
                    playbackManager?.currentSong?.let { song ->
                        playbackManager?.playSong(song, 0L, false)
                    }
                } else {
                    android.util.Log.d("PlaybackService", "🔁 无缝循环次数上限已达到，切换下一首")
                    mediaSession?.controller?.transportControls?.skipToNext()
                }
            }
        }

        // 莱芙帮大人找回上次的记忆喵！
        val manager = playbackManager!!
        serviceScope.launch {
            // The service owns system progress sync, so background and lock-screen playback keep
            // updating even when no Activity/MediaControlManager is connected.
            manager.state.collect { state ->
                systemProgressSyncController.onPlaybackStateChanged(state)

                // Manage periodic stats flush (~15s while playing).
                if (state == AudioPlayState.PLAYING) {
                    if (statsFlushJob?.isActive != true && playbackStatsTracker.shouldFlushPeriodically()) {
                        statsFlushJob = serviceScope.launch {
                            while (isActive) {
                                delay(15_000L)
                                playbackStatsTracker.flushPeriodic()
                            }
                        }
                    }
                } else {
                    statsFlushJob?.cancel()
                    statsFlushJob = null
                }
            }
        }

        restoreLastSession()

        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            private var clickCount = 0
            private var clickTimerJob: kotlinx.coroutines.Job? = null

            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                val keyEvent = mediaButtonEvent?.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null) {
                    val keyCode = keyEvent.keyCode
                    if (keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK || 
                        keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (keyEvent.action == android.view.KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                            clickCount++
                            clickTimerJob?.cancel()
                            clickTimerJob = serviceScope.launch {
                                kotlinx.coroutines.delay(400) // 400ms 的连击等待时间喵
                                when (clickCount) {
                                    1 -> if (playbackManager?.isPlaying == true) onPause() else onPlay()
                                    2 -> onSkipToNext()
                                    3 -> onSkipToPrevious()
                                    else -> if (clickCount > 3) onSkipToPrevious()
                                }
                                clickCount = 0
                            }
                        }
                        return true // 必须整个吞下这个按键事件喵！
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent)
            }

            override fun onPlay() { resumeOrRestorePlayback() }
            override fun onPause() { playbackManager?.pause() }
            override fun onSkipToNext() {
                val mode = mediaSession?.controller?.playbackState?.extras?.getInt("play_mode") ?: 0
                queueManager.playMode = mode
                queueManager.getNextSong()?.let { 
                    playbackManager?.play(it, 0, false)
                    // 别忘了帮大人偷偷改一下小本本喵！
                    com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this@PlaybackService).currentSongIndex = queueManager.currentIndex
                }
            }
            override fun onSkipToPrevious() {
                val mode = mediaSession?.controller?.playbackState?.extras?.getInt("play_mode") ?: 0
                queueManager.playMode = mode
                queueManager.getPreviousSong()?.let { 
                    playbackManager?.play(it, 0, false)
                    // 这里也要同步记下来喵！
                    com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this@PlaybackService).currentSongIndex = queueManager.currentIndex
                }
            }
            override fun onSeekTo(pos: Long) { playbackManager?.seekTo(pos) }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                val idLong = mediaId?.toLongOrNull() ?: -1L
                val startPos = extras?.getLong("start_pos") ?: 0L
                val startPaused = extras?.getBoolean("start_paused") ?: false
                val isSingleLoop = extras?.getBoolean("is_single_loop") ?: true
                val playlistPaths = extras?.getStringArray("playlist_paths")

                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    if (playlistPaths != null) {
                        val songs = playlistPaths.mapNotNull { repository.getSongByPath(it) }
                        val index = songs.indexOfFirst { it.mediaId == idLong }
                        queueManager.updatePlaylist(songs, if (index != -1) index else 0)
                    }

                    val songToPlay = queueManager.currentSong ?: repository.getAllSongs().find { it.mediaId == idLong }
                    
                    if (songToPlay != null) {
                        val index = queueManager.currentSong?.let { queueManager.currentIndex } ?: 0
                        if (queueManager.currentSong == null) {
                            queueManager.updatePlaylist(listOf(songToPlay), 0)
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            playbackManager?.playSong(songToPlay, startPos, startPaused, isSingleLoop)
                        }
                    }
                    
                    // 记在小本本和新库上，下次重开就不怕忘了喵！
                    val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this@PlaybackService)
                    settingsManager.currentSongIndex = queueManager.currentIndex
                    repository.replacePlayQueue(queueManager.currentPlaylistSnapshot.map { it.id })
                }
            }

            override fun onCustomAction(action: String?, extras: Bundle?) {
                if (action == "SET_PLAY_MODE") {
                    val mode = extras?.getInt("play_mode") ?: 0
                    val state = mediaSession?.controller?.playbackState
                    val builder = if (state != null) {
                        android.support.v4.media.session.PlaybackStateCompat.Builder(state)
                    } else {
                        android.support.v4.media.session.PlaybackStateCompat.Builder()
                    }
                    val newState = builder.setExtras(android.os.Bundle().apply { 
                            putInt("play_mode", mode)
                        })
                        .build()
                    mediaSession?.setPlaybackState(newState)
                    // 🍓 完全解耦：切换曲目调度模式不需要动态更改 NDK 的 setLooping！它在物理播放到末尾触发 EOS 后由 onSongCompleted 接管喵！
                } else if (action == "SET_SEAMLESS_LOOP_ENABLED") {
                    val enabled = extras?.getBoolean("is_seamless_loop_enabled") ?: true
                    val song = playbackManager?.currentSong
                    if (song != null) {
                        val start = song.loopStart
                        val end = song.loopEnd
                        val hasCustomPoints = end > start
                        
                        if (enabled && hasCustomPoints) {
                            com.cpu.seamlessloopmobile.jni.NativeAudio.setLoopPoints(start, end)
                            playbackManager?.setLooping(true)
                        } else {
                            playbackManager?.setLooping(false)
                        }
                    }
                } else if (action == "APPLY_LOOP_POINTS") {
                    val startPos = extras?.getLong("start_pos") ?: 0L
                    val endPos = extras?.getLong("end_pos") ?: 0L
                    
                    val isAb = mediaSession?.controller?.playbackState?.extras?.getBoolean("is_ab_mode") == true
                    val sampleRate = com.cpu.seamlessloopmobile.jni.NativeAudio.getSampleRate().toLong()
                    val totalDur = com.cpu.seamlessloopmobile.jni.NativeAudio.getDuration()
                    
                    val sr = if (sampleRate > 0) sampleRate else 44100L
                    if (isAb) {
                        // 🍓 AB 模式下，直接跳到总长度（A+B）前 3 秒以聆听无缝跳转喵！
                        val seekPos = (totalDur - (sr * 3)).coerceIn(0, totalDur)
                        val seekPosMs = seekPos * 1000L / sr
                        playbackManager?.seekTo(seekPosMs)
                    } else {
                        // 普通模式下，直接通知底层修改内部临时循环区间喵！
                        com.cpu.seamlessloopmobile.jni.NativeAudio.setLoopPoints(startPos, endPos)
                        
                        val actualEnd = if (endPos > 0) endPos else totalDur
                        val seekPos = (actualEnd - (sr * 3)).coerceIn(0, actualEnd)
                        val seekPosMs = seekPos * 1000L / sr
                        playbackManager?.seekTo(seekPosMs)
                    }
                }
            }
            override fun onStop() { stopForegroundCompletely() }
        })
    }

    // --- MediaBrowserServiceCompat 必须实现的接口喵 ---

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(null)
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        return if (intent?.action == SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mediaSession?.let { session ->
            MediaButtonReceiver.handleIntent(session, intent)
        }
        return START_STICKY
    }

    private fun handlePlaybackStateChange(isPlaying: Boolean, song: Song) {
        if (isPlaying) {
            // 索要音频焦点喵！
            val hasFocus = audioFocusManager.requestFocus()

            if (hasFocus) {
                if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L /* 10 mins fallback */)
                updateNotification(song, true)
            }
        } else {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            updateNotification(song, false)
        }
    }

    fun updateNotification(song: Song, isPlaying: Boolean) {
        val notification = notify?.createNotification(song, isPlaying)
        if (notification != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        }
        
        // 只有在暂停且应用没被销毁时，才允许设置为非前台（可划掉通知）
        if (!isPlaying) {
            stopForeground(false)
        }
    }

    fun stopForegroundCompletely() {
        statsFlushJob?.cancel()
        statsFlushJob = null
        playbackStatsTracker.flushFinal()
        systemProgressSyncController.onPlaybackStateChanged(AudioPlayState.IDLE)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        audioFocusManager.abandonFocus()
        playbackManager?.stop()
        stopForeground(true)
        stopSelf()
    }

    private fun resumeOrRestorePlayback() {
        val manager = playbackManager ?: return
        if (manager.currentSong != null && manager.state.value != AudioPlayState.IDLE) {
            manager.resume()
            return
        }

        val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this)
        val lastPath = settingsManager.lastSongPath
        if (lastPath == null) {
            android.util.Log.w("PlaybackService", "播放请求被忽略：没有可恢复的上一首歌曲")
            return
        }

        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val songToRestore = queueManager.currentSong?.takeIf { it.filePath == lastPath }
                ?: repository.getSongByPath(lastPath)
            if (songToRestore == null) {
                android.util.Log.w("PlaybackService", "播放请求被忽略：找不到上一首歌曲 $lastPath")
                return@launch
            }

            val maxKnownPosition = if (settingsManager.isAbMode) {
                // AB 模式使用 A+B 虚拟时间轴；A 段歌曲自身的 totalSamples 不是播放总长度。
                val loopSong = repository.findAbPairRobust(this@PlaybackService, songToRestore)?.second
                val introFrames = songToRestore.totalSamples
                val loopFrames = loopSong?.totalSamples ?: 0L
                if (introFrames > 0L && loopFrames > 0L) introFrames + loopFrames else 0L
            } else {
                songToRestore.totalSamples.takeIf { it > 0L } ?: 0L
            }
            // lastPosition 由 NativeAudio.getCurrentPosition() 保存，单位是帧；playSong 也接收帧。
            val startPosition = settingsManager.lastPosition.coerceIn(0L, maxKnownPosition)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                playbackManager?.playSong(songToRestore, startPosition, startPaused = false)
            }
        }
    }

    /**
     * 去小本本上找找上次还没放完的歌和列表喵！
     */
    private fun restoreLastSession() {
        val settingsManager = com.cpu.seamlessloopmobile.data.SettingsManager.getInstance(this)
        val lastPath = settingsManager.lastSongPath ?: return
        // 进度就算了喵，大人说下次播放从头开始！
        val lastIndex = settingsManager.currentSongIndex

        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // 1. 恢复播放队列喵
            val songs = repository.getPlayQueueSongs()
            if (songs.isNotEmpty()) {
                queueManager.updatePlaylist(songs, if (lastIndex in songs.indices) lastIndex else 0)
                android.util.Log.d("PlaybackService", "📚 列表已从数据库找回，共 ${songs.size} 首歌喵！")
            }

            // 2. 把上次正放着的那首歌找出来候命喵
            val songToPrepare = repository.getSongByPath(lastPath)
            if (songToPrepare != null) {
                // 如果是 AB 模式，我们也尽量记一下（不过目前 AB 初始化还有点小纠结，先保持单曲加载喵）
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.util.Log.d("PlaybackService", "📖 记忆恢复：将 ${songToPrepare.fileName} 加载到引擎中，进度清零从头开始喵！")
                    playbackManager?.playSong(songToPrepare, 0L, startPaused = true)
                }
            }
        }
    }

    override fun onDestroy() {
        statsFlushJob?.cancel()
        playbackStatsTracker.flushFinal()
        systemProgressSyncController.dispose()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        headsetPlugReceiver.unregister(this)
        mediaSession?.release()
        super.onDestroy()
        serviceScope.cancel() 
    }
}

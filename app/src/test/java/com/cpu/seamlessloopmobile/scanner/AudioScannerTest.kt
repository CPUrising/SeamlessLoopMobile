package com.cpu.seamlessloopmobile.scanner

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.cpu.seamlessloopmobile.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * 黑科技测试：Robolectric 模拟安卓环境喵！
 * 专门对付像 AudioScanner 这种爱跟系统打交道的“坏孩子”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testScanSongs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val contentResolver = context.contentResolver
        val shadowContentResolver = Shadows.shadowOf(contentResolver)

        // 1. 准备一个真的临时文件，因为扫描器会校验 File().exists() 喵！
        val songFile = tempFolder.newFile("test_song.mp3")
        val filePath = songFile.absolutePath

        // 2. 模拟扫描器查询的列名喵
        val roboCursor = org.robolectric.fakes.RoboCursor()
        roboCursor.setColumnNames(listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        ))

        // 3. 往这个“假表格”里写一行标准答案喵
        roboCursor.setResults(arrayOf(
            arrayOf(
                1L,                     // ID
                "test_song.mp3",        // DISPLAY_NAME
                "测试金曲",              // TITLE
                "莱芙之歌",              // ARTIST
                filePath,               // DATA (这条最关键，必须真实存在喵)
                20000L                  // DURATION
            )
        ))

        // 4. 重磅魔法：告诉 Robolectric，凡是查 MediaStore 的，都把这个表格给他喵！
        shadowContentResolver.setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, roboCursor)

        // 5. 执行扫描喵！
        val results = AudioScanner.scan(context)

        // 6. 验证奇迹喵
        assertTrue("竟然没扫描到歌曲，莱芙真的要哭晕在厕所了喵！", results.isNotEmpty())
        val scannedSong = results.find { it.fileName == "test_song.mp3" }
        
        assertEquals("标题对不上喵！", "测试金曲", scannedSong?.displayName)
        assertEquals("艺术家对不上喵！", "莱芙之歌", scannedSong?.artist)
        assertEquals("路径对不上喵！", filePath, scannedSong?.filePath)
    }

    @Test
    fun testActualAudioScannerSamples() {
        var dir = File("music_test")
        if (!dir.exists()) {
            dir = File("../music_test")
        }
        if (!dir.exists()) {
            println("music_test directory not found!")
            return
        }
        val files = dir.listFiles { _, name -> name.lowercase().endsWith(".ogg") } ?: emptyArray()
        println("=== Testing Actual AudioScanner.getApproximateSamples on OGG files ===")
        for (oggFile in files) {
            val samples = AudioScanner.getApproximateSamples(oggFile.absolutePath, 1000L)
            println("  File: ${oggFile.name} -> samples (for 1000ms) = $samples")
            if (oggFile.name.startsWith("BGM_") || oggFile.name.startsWith("m0")) {
                assertEquals("OGG 48kHz sample rate should be correct喵！", 48000L, samples)
            } else if (oggFile.name.startsWith("0000")) {
                assertEquals("OGG 44.1kHz sample rate should be correct喵！", 44100L, samples)
            }
        }
    }
}


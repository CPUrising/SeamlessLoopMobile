package com.cpu.seamlessloopmobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.cpu.seamlessloopmobile.viewmodel.MainViewModel
import com.cpu.seamlessloopmobile.viewmodel.MainViewModelFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.cpu.seamlessloopmobile.ui.screen.MainScreen
import com.cpu.seamlessloopmobile.data.SettingsManager
import com.cpu.seamlessloopmobile.data.ThemePreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 精简后的 MainActivity 喵！
 * 莱芙把它从 600 多行瘦身成功啦，现在它只负责：
 * 1. 权限申请 
 * 2. 媒体连接生命周期
 * 3. 开启 Compose 大门
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    
    // PC 数据库文件选择器喵
    private val dbPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromPcDatabase(it) }
    }

    // PC 兼容数据库导出保存器喵：交给系统文件选择器决定导出位置，不额外索要写存储权限。
    private val dbExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { exportPcDatabaseToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化
        val database = com.cpu.seamlessloopmobile.db.AppDatabase.getDatabase(this)
        val factory = MainViewModelFactory(database.songDao(), database.playlistDao(), database.playQueueDao(), this)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        val settingsManager = SettingsManager.getInstance(this)
        viewModel.initSettings(settingsManager)

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()
            val themePreference by viewModel.themePreference.observeAsState(settingsManager.themePreference)
            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> systemDarkTheme
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            com.cpu.seamlessloopmobile.ui.theme.SeamlessLoopTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        playSong = { song -> viewModel.playSong(song) },
                        onSyncPc = { dbPickerLauncher.launch("application/octet-stream") },
                        onExportDatabase = { dbExportLauncher.launch(createDatabaseExportFileName()) },
                        isDarkTheme = darkTheme,
                        themePreference = themePreference,
                        onThemePreferenceChange = viewModel::setThemePreference
                    )
                }
            }
        }

        checkPermissionsAndLoadHome()
        viewModel.connectMedia()
        viewModel.startObservation()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnectMedia()
    }

    private fun checkPermissionsAndLoadHome() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val permissions = mutableListOf(permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.openHome()
            // APlayer 风格：子模块 init 已经帮我们抢跑了喵，这里直接等扫描就行喵
            
            // --- 优化：扫描工作稍后再悄悄开始喵 ---
            lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(800) // 让数据库里的老数据先露个脸，再轻轻启动扫描喵
                viewModel.scanLibrary(this@MainActivity) 
            }
        } else {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissionsAndLoadHome()
        } else if (requestCode == 1001) {
            Toast.makeText(this, "未获得权限喵，可能无法为您扫描音乐(>_<)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromPcDatabase(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            com.cpu.seamlessloopmobile.db.PcDatabaseImporter.importFromPcDatabase(
                context = this@MainActivity,
                uri = uri,
                songDao = com.cpu.seamlessloopmobile.db.AppDatabase.getDatabase(this@MainActivity).songDao(),
                playlistDao = com.cpu.seamlessloopmobile.db.AppDatabase.getDatabase(this@MainActivity).playlistDao(),
                callback = object : com.cpu.seamlessloopmobile.db.PcDatabaseImporter.ImportCallback {
                    override fun onSuccess(syncCount: Int) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "同步完成喵！成功找回 $syncCount 条循环数据", Toast.LENGTH_LONG).show()
                            viewModel.scanLibrary(this@MainActivity)
                        }
                    }
                    override fun onError(message: String) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "同步失败了(>_<): $message", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun createDatabaseExportFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "seamless_loop_pc_export_$timestamp.db"
    }

    private fun exportPcDatabaseToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val database = com.cpu.seamlessloopmobile.db.AppDatabase.getDatabase(this@MainActivity)
                val result = com.cpu.seamlessloopmobile.db.PcDatabaseExporter.exportToPcDatabase(
                    context = this@MainActivity,
                    uri = uri,
                    songDao = database.songDao(),
                    playlistDao = database.playlistDao(),
                    playQueueDao = database.playQueueDao()
                )

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "PC 端数据库导出完成喵！${result.trackCount} 首歌、${result.playlistCount} 个歌单，大小 ${formatBytes(result.bytes)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "PC database export failed", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "PC 端数据库导出失败了(>_<): ${e.message}。若正在扫描，请稍后重试喵",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

package com.cpu.seamlessloopmobile.viewmodel

import com.cpu.seamlessloopmobile.data.sync.SyncDataManagementPreview
import com.cpu.seamlessloopmobile.data.sync.SyncReport
import com.cpu.seamlessloopmobile.data.sync.PlaybackStatsSourceDeviceSummary

/**
 * GitHub 同步 UI 状态。
 * 不由 Sealed Class 封装，方便设置页各字段独立双向绑定。
 */
data class GitHubSyncUiState(
    val isConfigured: Boolean = false,
    val hasToken: Boolean = false,
    val isSyncing: Boolean = false,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val path: String = "seamless-loop/sync.json",
    val lastSyncTime: Long = 0L,
    val statusMessage: String = "",
    val errorMessage: String = "",
    val lastReport: SyncReport? = null,
    // --- 自动同步 ---
    val isAutoSyncEnabled: Boolean = false,
    // --- 数据管理状态 ---
    val isManagementLoading: Boolean = false,
    val isManagementOperationRunning: Boolean = false,
    val managementPreview: SyncDataManagementPreview? = null,
    val playbackStatsSourceDevices: List<PlaybackStatsSourceDeviceSummary> = emptyList(),
    val managementStatusMessage: String = "",
    val managementErrorMessage: String = ""
) {
    val canEnableAutoSync: Boolean
        get() = isConfigured && hasToken
}

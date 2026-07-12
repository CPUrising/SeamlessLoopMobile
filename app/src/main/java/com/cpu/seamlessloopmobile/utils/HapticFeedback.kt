package com.cpu.seamlessloopmobile.utils

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

val LocalButtonHapticFeedbackEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberHapticClick(
    enabled: Boolean = LocalButtonHapticFeedbackEnabled.current,
    hapticFeedbackType: Int = HapticFeedbackConstants.VIRTUAL_KEY,
    onClick: () -> Unit
): () -> Unit {
    val view = LocalView.current
    val latestOnClick by rememberUpdatedState(onClick)

    return remember(view, enabled, hapticFeedbackType) {
        {
            if (enabled) {
                view.performHapticFeedback(hapticFeedbackType)
            }
            latestOnClick()
        }
    }
}

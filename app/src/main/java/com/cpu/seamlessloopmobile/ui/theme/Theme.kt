package com.cpu.seamlessloopmobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SeamlessLoopMaterialColors.Dark.Primary,
    onPrimary = SeamlessLoopMaterialColors.Dark.OnPrimary,
    primaryContainer = SeamlessLoopMaterialColors.Dark.PrimaryContainer,
    onPrimaryContainer = SeamlessLoopMaterialColors.Dark.OnPrimaryContainer,
    secondary = SeamlessLoopMaterialColors.Dark.Secondary,
    onSecondary = SeamlessLoopMaterialColors.Dark.OnSecondary,
    secondaryContainer = SeamlessLoopMaterialColors.Dark.SecondaryContainer,
    onSecondaryContainer = SeamlessLoopMaterialColors.Dark.OnSecondaryContainer,
    background = SeamlessLoopMaterialColors.Dark.Background,
    onBackground = SeamlessLoopMaterialColors.Dark.OnBackground,
    surface = SeamlessLoopMaterialColors.Dark.Surface,
    onSurface = SeamlessLoopMaterialColors.Dark.OnSurface,
    surfaceVariant = SeamlessLoopMaterialColors.Dark.SurfaceVariant,
    onSurfaceVariant = SeamlessLoopMaterialColors.Dark.OnSurfaceVariant,
    outlineVariant = SeamlessLoopMaterialColors.Dark.OutlineVariant,
    error = SeamlessLoopMaterialColors.Dark.Error,
    onError = SeamlessLoopMaterialColors.Dark.OnError
)

private val LightColorScheme = lightColorScheme(
    primary = SeamlessLoopMaterialColors.Light.Primary,
    onPrimary = SeamlessLoopMaterialColors.Light.OnPrimary,
    primaryContainer = SeamlessLoopMaterialColors.Light.PrimaryContainer,
    onPrimaryContainer = SeamlessLoopMaterialColors.Light.OnPrimaryContainer,
    secondary = SeamlessLoopMaterialColors.Light.Secondary,
    onSecondary = SeamlessLoopMaterialColors.Light.OnSecondary,
    secondaryContainer = SeamlessLoopMaterialColors.Light.SecondaryContainer,
    onSecondaryContainer = SeamlessLoopMaterialColors.Light.OnSecondaryContainer,
    background = SeamlessLoopMaterialColors.Light.Background,
    onBackground = SeamlessLoopMaterialColors.Light.OnBackground,
    surface = SeamlessLoopMaterialColors.Light.Surface,
    onSurface = SeamlessLoopMaterialColors.Light.OnSurface,
    surfaceVariant = SeamlessLoopMaterialColors.Light.SurfaceVariant,
    onSurfaceVariant = SeamlessLoopMaterialColors.Light.OnSurfaceVariant,
    outlineVariant = SeamlessLoopMaterialColors.Light.OutlineVariant,
    error = SeamlessLoopMaterialColors.Light.Error,
    onError = SeamlessLoopMaterialColors.Light.OnError
)

@Composable
fun SeamlessLoopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()

            val windowInsetsController = WindowCompat.getInsetsController(window, view)
            windowInsetsController.isAppearanceLightStatusBars = !darkTheme
            windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SeamlessLoopTypography,
        content = content
    )
}

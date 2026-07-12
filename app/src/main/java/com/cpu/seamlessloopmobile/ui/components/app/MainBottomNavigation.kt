package com.cpu.seamlessloopmobile.ui.components.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cpu.seamlessloopmobile.utils.rememberHapticClick

enum class MainDestination {
    Library,
    Search,
    Settings
}

@Composable
fun MainBottomNavigation(
    selectedDestination: MainDestination?,
    onLibraryClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    buttonHapticFeedbackEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        BottomNavItem(MainDestination.Library, "媒体库", Icons.Default.LibraryMusic, onLibraryClick),
        BottomNavItem(MainDestination.Search, "搜索", Icons.Default.Search, onSearchClick),
        BottomNavItem(MainDestination.Settings, "设置", Icons.Default.Settings, onSettingsClick)
    )

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 0.dp,
    ) {
        items.forEach { item ->
            BottomNavButton(
                item = item,
                selected = item.destination == selectedDestination,
                buttonHapticFeedbackEnabled = buttonHapticFeedbackEnabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomNavButton(
    item: BottomNavItem,
    selected: Boolean,
    buttonHapticFeedbackEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val iconColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
        },
        animationSpec = tween(durationMillis = 140),
        label = "bottom_nav_color"
    )
    val indicatorColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            hovered -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 140),
        label = "bottom_nav_indicator_color"
    )
    val rippleColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)
    }

    Column(
        modifier = modifier
            .height(64.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = rememberHapticClick(buttonHapticFeedbackEnabled, onClick = item.onClick)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(indicatorColor)
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(
                        bounded = true,
                        radius = 29.dp,
                        color = rippleColor
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = item.label,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class BottomNavItem(
    val destination: MainDestination,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

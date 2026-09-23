package io.github.srqingchen.qingzhou.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = QzPrimaryLight,
    onPrimary = QzOnPrimaryLight,
    primaryContainer = QzPrimaryContainerLight,
    onPrimaryContainer = QzOnPrimaryContainerLight,
    secondary = QzSecondaryLight,
    secondaryContainer = QzSecondaryContainerLight,
    background = QzBackgroundLight,
    surface = QzSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = QzPrimaryDark,
    onPrimary = QzOnPrimaryDark,
    primaryContainer = QzPrimaryContainerDark,
    onPrimaryContainer = QzOnPrimaryContainerDark,
    secondary = QzSecondaryDark,
    secondaryContainer = QzSecondaryContainerDark,
    background = QzBackgroundDark,
    surface = QzSurfaceDark,
)

@Composable
fun QzTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = QzTypography,
        content = content,
    )
}

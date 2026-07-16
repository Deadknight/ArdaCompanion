package com.github.deadknight.ardacompanion

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF285DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF52627A),
    background = Color(0xFFF6F8FC),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE1E7F0),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF44474E),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF0B478A),
    onPrimaryContainer = Color(0xFFD8E6FF),
    secondary = Color(0xFFBAC6DC),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474E),
    onSurface = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFFC4C6CF),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ArdaCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

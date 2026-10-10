package com.example.applestylenotes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val IosGray = Color(0xFF8E8E93)

@Composable
fun editorBackground(): Color = if (isSystemInDarkTheme()) Color.Black else Color.White

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFFFD60A), onPrimary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color(0xFF1C1C1E), onSurface = Color.White,
            surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = IosGray,
            outlineVariant = Color(0xFF38383A),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFE0A800), onPrimary = Color.White,
            background = Color(0xFFF2F2F7), onBackground = Color.Black,
            surface = Color.White, onSurface = Color.Black,
            surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = IosGray,
            outlineVariant = Color(0xFFD1D1D6),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

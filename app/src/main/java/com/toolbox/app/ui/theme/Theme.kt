package com.toolbox.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Lavender, onPrimary = OnLavender,
    primaryContainer = Color(0xFF3A2F6B), onPrimaryContainer = Lavender,
    background = Ink0, onBackground = TextHi,
    surface = Ink1, onSurface = TextHi,
    surfaceVariant = Ink2, onSurfaceVariant = TextMid,
    outline = Ink3, outlineVariant = Stroke,
)

private val ToolBoxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ToolBoxTheme(arabic: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides if (arabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        MaterialTheme(colorScheme = Scheme, typography = ToolBoxTypography,
            shapes = ToolBoxShapes, content = content)
    }
}

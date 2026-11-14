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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.compositeOver

private fun scheme() = darkColorScheme(
    primary = Lavender, onPrimary = OnLavender,
    primaryContainer = LavenderDeep.copy(alpha = 0.35f).compositeOver(Ink1Solid), onPrimaryContainer = Lavender,
    background = Ink0, onBackground = TextHi,
    surface = Ink1Solid, onSurface = TextHi,
    surfaceVariant = Ink2Solid, surfaceContainerHighest = Ink2, onSurfaceVariant = TextMid,
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
        MaterialTheme(colorScheme = scheme(), typography = if (TB.bg.isNotEmpty()) ToolBoxTypography.shadowed() else ToolBoxTypography,
            shapes = ToolBoxShapes, content = content)
    }
}

private fun Typography.shadowed(): Typography {
    val sh = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 6f)
    fun TextStyle.s() = copy(shadow = sh)
    return copy(
        displayLarge = displayLarge.s(), displayMedium = displayMedium.s(), displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s(), headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(), labelMedium = labelMedium.s(), labelSmall = labelSmall.s()
    )
}

package com.toolbox.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Surfaces: layered, slightly violet-tinted ink ──
val Ink0 = Color(0xFF0C0B12)      // app background
val Ink1Solid = Color(0xFF14131C)
val Ink2Solid = Color(0xFF1C1B27)
val Ink1: Color get() = Ink1Solid.copy(alpha = TB.cardAlpha)   // cards (glass-aware)
val Ink2: Color get() = Ink2Solid.copy(alpha = TB.cardAlpha)   // raised / pressed
val Ink3 = Color(0xFF272636)      // borders, tracks
val Stroke = Color(0x1AFFFFFF)    // 10% white hairline
val GlassStroke: Color get() = if (TB.glass > 0) Color.White.copy(alpha = 0.20f) else Stroke

// ── Text ──
val TextHi = Color(0xFFF3F1FA)
val TextMid = Color(0xFFB4B0C8)
val TextLow = Color(0xFF7D7A92)

// ── Brand lavender ──
val Lavender: Color get() = Accents[TB.accent].main
val LavenderDeep: Color get() = Accents[TB.accent].deep
val OnLavender: Color get() = Accents[TB.accent].on

// ── Per-tool accents (icon tint + soft glow) ──
val AccCleaner = Color(0xFF7CE0B5)
val AccFiles = Color(0xFFFFC46B)
val AccTransfer = Color(0xFF7FB6FF)
val AccVideo = Color(0xFFFF8FA3)
val AccAudio = Color(0xFFD59BFF)
val AccImages = Color(0xFF6FE3E0)
val AccEditor = Color(0xFFFFA77A)
val AccApk = Color(0xFFA6E36F)
val AccDownload = Color(0xFF8FA8FF)
val AccNotes = Color(0xFFF2D56B)
val AccInfo = Color(0xFF9FD0FF)
val AccSettings = Color(0xFFC4C0DA)

// ── Storage bar segments ──
val SegApps = Color(0xFF5B8DEF)
val SegImages = Color(0xFF34D399)
val SegVideo = Color(0xFFF5A742)
val SegAudio = Color(0xFFB45CE0)
val SegOther = Color(0xFF8A889C)

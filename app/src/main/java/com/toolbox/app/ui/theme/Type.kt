package com.toolbox.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.toolbox.app.R

val AqeeqSans = FontFamily(
    Font(R.font.aqeeq_sans_pro_thin, FontWeight.Thin),
    Font(R.font.aqeeq_sans_pro_extralight, FontWeight.ExtraLight),
    Font(R.font.aqeeq_sans_pro_light, FontWeight.Light),
    Font(R.font.aqeeq_sans_pro_regular, FontWeight.Normal),
    Font(R.font.aqeeq_sans_pro_medium, FontWeight.Medium),
    Font(R.font.aqeeq_sans_pro_semibold, FontWeight.SemiBold),
    Font(R.font.aqeeq_sans_pro_bold, FontWeight.Bold),
    Font(R.font.aqeeq_sans_pro_extrabold, FontWeight.ExtraBold),
)

private fun s(w: FontWeight, size: Int, lh: Int, ls: Double = 0.0) =
    TextStyle(fontFamily = AqeeqSans, fontWeight = w, fontSize = size.sp,
        lineHeight = lh.sp, letterSpacing = ls.sp)

// Arabic needs generous line-height; titles stay heavy, body stays calm.
val ToolBoxTypography = Typography(
    displaySmall   = s(FontWeight.ExtraBold, 34, 46),
    headlineMedium = s(FontWeight.ExtraBold, 28, 40),
    headlineSmall  = s(FontWeight.Bold, 24, 34),
    titleLarge     = s(FontWeight.Bold, 21, 30),
    titleMedium    = s(FontWeight.SemiBold, 17, 26),
    titleSmall     = s(FontWeight.SemiBold, 15, 22),
    bodyLarge      = s(FontWeight.Medium, 16, 26),
    bodyMedium     = s(FontWeight.Normal, 14, 22),
    bodySmall      = s(FontWeight.Normal, 12, 18),
    labelLarge     = s(FontWeight.SemiBold, 15, 20),
    labelMedium    = s(FontWeight.Medium, 13, 18),
    labelSmall     = s(FontWeight.Medium, 11, 16),
)

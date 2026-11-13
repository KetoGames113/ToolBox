package com.toolbox.app.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.toolbox.app.App
import java.io.File

class Accent(val ar: String, val en: String, val main: Color, val deep: Color, val on: Color)

val Accents = listOf(
    Accent("لافندر", "Lavender", Color(0xFFCDBEFF), Color(0xFF8E75F2), Color(0xFF241553)),
    Accent("وردي", "Rose", Color(0xFFFFB4C8), Color(0xFFF0628F), Color(0xFF4A0F25)),
    Accent("نعناع", "Mint", Color(0xFF9DF0CF), Color(0xFF2FC795), Color(0xFF06382A)),
    Accent("سماوي", "Sky", Color(0xFFA9D1FF), Color(0xFF4C9BFF), Color(0xFF0A2B57)),
    Accent("عنبري", "Amber", Color(0xFFFFD98A), Color(0xFFF2A93B), Color(0xFF4A2F00)),
    Accent("مرجاني", "Coral", Color(0xFFFFB59C), Color(0xFFFF7048), Color(0xFF521A07)),
)

object TB {
    private fun p() = App.i.prefs()
    var accent by mutableIntStateOf(p().getInt("th_acc", 0).coerceIn(0, Accents.lastIndex)); private set
    var bg by mutableStateOf(p().getString("th_bg", "") ?: ""); private set
    var blur by mutableIntStateOf(p().getInt("th_blur", 12)); private set
    var dim by mutableIntStateOf(p().getInt("th_dim", 45)); private set
    var lyricsOnline by mutableStateOf(p().getBoolean("lyr_online", true)); private set

    fun pickAccent(i: Int) { accent = i; p().edit().putInt("th_acc", i).apply() }
    fun pickBlur(v: Int) { blur = v; p().edit().putInt("th_blur", v).apply() }
    fun pickDim(v: Int) { dim = v; p().edit().putInt("th_dim", v).apply() }
    fun pickLyrics(v: Boolean) { lyricsOnline = v; p().edit().putBoolean("lyr_online", v).apply() }
    fun pickBg(path: String) {
        val old = bg
        bg = path; p().edit().putString("th_bg", path).apply()
        if (old.isNotEmpty() && old != path) try { File(old).delete() } catch (_: Throwable) {}
    }
}

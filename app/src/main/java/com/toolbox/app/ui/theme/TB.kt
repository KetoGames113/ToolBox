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
    var glass by mutableIntStateOf(p().getInt("th_glass", 0)); private set
    var bareIcons by mutableStateOf(p().getBoolean("th_bare", false)); private set
    val cardAlpha: Float get() = 1f - glass / 100f
    var plTheme by mutableIntStateOf(p().getInt("pl_theme", 0)); private set
    var plBg by mutableIntStateOf(p().getInt("pl_bg", 0)); private set
    var plBlur by mutableIntStateOf(p().getInt("pl_blur", 18)); private set
    var plDim by mutableIntStateOf(p().getInt("pl_dim", 35)); private set
    var plDisc by mutableStateOf(p().getBoolean("pl_disc", true)); private set
    var plLyr by mutableIntStateOf(p().getInt("pl_lyr", 100)); private set
    var ntStyle by mutableIntStateOf(p().getInt("nt_style", 0)); private set
    var ntSpin by mutableStateOf(p().getBoolean("nt_spin", true)); private set
    var ntRate by mutableIntStateOf(p().getInt("nt_rate", 1)); private set
    var ntColor by mutableStateOf(p().getBoolean("nt_color", true)); private set
    var ntHq by mutableStateOf(p().getBoolean("nt_hq", true)); private set
    var ntStop by mutableStateOf(p().getBoolean("nt_stop", true)); private set
    var plSpeed by mutableIntStateOf(p().getInt("pl_speed", 1)); private set
    var plArm by mutableStateOf(p().getBoolean("pl_arm", true)); private set
    var plStyle by mutableIntStateOf(p().getInt("pl_style", 0)); private set
    var lyricsOnline by mutableStateOf(p().getBoolean("lyr_online", true)); private set

    fun pickAccent(i: Int) { accent = i; p().edit().putInt("th_acc", i).apply() }
    fun pickBlur(v: Int) { blur = v; p().edit().putInt("th_blur", v).apply() }
    fun pickDim(v: Int) { dim = v; p().edit().putInt("th_dim", v).apply() }
    fun pickGlass(v: Int) { glass = v; p().edit().putInt("th_glass", v).apply() }
    fun pickBare(v: Boolean) { bareIcons = v; p().edit().putBoolean("th_bare", v).apply() }
    fun pickPlTheme(v: Int) { plTheme = v; p().edit().putInt("pl_theme", v).apply() }
    fun pickPlBg(v: Int) { plBg = v; p().edit().putInt("pl_bg", v).apply() }
    fun pickPlBlur(v: Int) { plBlur = v; p().edit().putInt("pl_blur", v).apply() }
    fun pickPlDim(v: Int) { plDim = v; p().edit().putInt("pl_dim", v).apply() }
    fun pickPlDisc(v: Boolean) { plDisc = v; p().edit().putBoolean("pl_disc", v).apply() }
    fun pickPlLyr(v: Int) { plLyr = v; p().edit().putInt("pl_lyr", v).apply() }
    fun pickNtStyle(v: Int) { ntStyle = v; p().edit().putInt("nt_style", v).apply() }
    fun pickNtSpin(v: Boolean) { ntSpin = v; p().edit().putBoolean("nt_spin", v).apply() }
    fun pickNtRate(v: Int) { ntRate = v; p().edit().putInt("nt_rate", v).apply() }
    fun pickNtColor(v: Boolean) { ntColor = v; p().edit().putBoolean("nt_color", v).apply() }
    fun pickNtHq(v: Boolean) { ntHq = v; p().edit().putBoolean("nt_hq", v).apply() }
    fun pickNtStop(v: Boolean) { ntStop = v; p().edit().putBoolean("nt_stop", v).apply() }
    fun pickPlSpeed(v: Int) { plSpeed = v; p().edit().putInt("pl_speed", v).apply() }
    fun pickPlArm(v: Boolean) { plArm = v; p().edit().putBoolean("pl_arm", v).apply() }
    fun pickPlStyle(v: Int) { plStyle = v; p().edit().putInt("pl_style", v).apply() }
    fun pickLyrics(v: Boolean) { lyricsOnline = v; p().edit().putBoolean("lyr_online", v).apply() }
    fun pickBg(path: String) {
        val old = bg
        bg = path; p().edit().putString("th_bg", path).apply()
        if (path.isNotEmpty() && glass == 0) pickGlass(40)
        if (old.isNotEmpty() && old != path) try { File(old).delete() } catch (_: Throwable) {}
    }
}

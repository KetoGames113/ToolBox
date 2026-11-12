@file:OptIn(ExperimentalMaterial3Api::class)
package com.toolbox.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.ColorMatrixColorFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Canvas as ACanvas
import android.graphics.ColorMatrix as AColorMatrix

fun decodeBmp(p: String): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(p, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > 2600) s *= 2
    val b = BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = s; inMutable = true }) ?: return null
    val ori = try { ExifInterface(p).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: Throwable) { 1 }
    val m = Matrix()
    when (ori) { 6 -> m.postRotate(90f); 3 -> m.postRotate(180f); 8 -> m.postRotate(270f); 2 -> m.postScale(-1f, 1f); 4 -> m.postScale(1f, -1f) }
    return if (m.isIdentity) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

fun adjMatrix(br: Float, ct: Float, sa: Float): AColorMatrix {
    val m = AColorMatrix(); m.setSaturation(sa)
    val t = (-0.5f * ct + 0.5f) * 255f + br
    m.postConcat(AColorMatrix(floatArrayOf(ct, 0f, 0f, 0f, t, 0f, ct, 0f, 0f, t, 0f, 0f, ct, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    return m
}

fun applyCM(b: Bitmap, m: AColorMatrix): Bitmap {
    val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
    ACanvas(o).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    return o
}

@Composable
fun EditorScreen(path: String) {
    val c = LocalContext.current
    var src by remember { mutableStateOf(path) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val undo = remember { mutableStateListOf<Bitmap>() }
    var tool by remember { mutableStateOf("") }
    var br by remember { mutableFloatStateOf(0f) }
    var ct by remember { mutableFloatStateOf(1f) }
    var sa by remember { mutableFloatStateOf(1f) }
    var cl by remember { mutableFloatStateOf(0f) }
    var cr by remember { mutableFloatStateOf(0f) }
    var cu by remember { mutableFloatStateOf(0f) }
    var cd by remember { mutableFloatStateOf(0f) }
    var color by remember { mutableStateOf(Color.Red) }
    var size by remember { mutableFloatStateOf(14f) }
    var text by remember { mutableStateOf("ToolBox") }
    var cur by remember { mutableStateOf(listOf<Offset>()) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) src = copyToCache(c, u).path }
    LaunchedEffect(src) { if (src.isNotEmpty()) { bmp = withContext(Dispatchers.IO) { decodeBmp(src) }; undo.clear() } }

    fun commit(nb: Bitmap) { bmp?.let { undo.add(it); if (undo.size > 8) undo.removeAt(0) }; bmp = nb }
    fun resetAdj() { br = 0f; ct = 1f; sa = 1f; cl = 0f; cr = 0f; cu = 0f; cd = 0f }
    val b = bmp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pick.launch("image/*") }) { Text(tr("فتح", "Open")) }
            TextButton(enabled = undo.isNotEmpty(), onClick = { bmp = undo.removeAt(undo.lastIndex) }) { Text(tr("تراجع", "Undo")) }
            TextButton(enabled = b != null, onClick = {
                val bb = bmp ?: return@TextButton
                val f = uniqueFile(File(ROOT, "Pictures/ToolBox").apply { mkdirs() }, "edit_${System.currentTimeMillis()}.jpg")
                f.outputStream().use { bb.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                scan(f.path); toast(tr("تم الحفظ: ", "Saved: ") + f.path)
            }) { Text(tr("حفظ", "Save")) }
            TextButton(enabled = b != null, onClick = {
                val bb = bmp ?: return@TextButton
                val f = File(c.cacheDir, "share_${System.currentTimeMillis()}.jpg"); f.outputStream().use { bb.compress(Bitmap.CompressFormat.JPEG, 92, it) }; shareFiles(c, listOf(f))
            }) { Text(tr("مشاركة", "Share")) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020)).onSizeChanged { box = it }) {
            if (b != null && box.width > 0) {
                val sc = minOf(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
                val ox = (box.width - b.width * sc) / 2; val oy = (box.height - b.height * sc) / 2
                fun toBmp(p: Offset) = Offset((p.x - ox) / sc, (p.y - oy) / sc)
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    colorFilter = if (tool == "adjust") ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(adjMatrix(br, ct, sa).array)) else null)
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(tool, color, size) {
                        if (tool == "draw") detectDragGestures(
                            onDragStart = { cur = listOf(it) },
                            onDrag = { ch, _ -> cur = cur + ch.position },
                            onDragEnd = {
                                val pts = cur.map { toBmp(it) }; cur = emptyList()
                                if (pts.size > 1) {
                                    val nb = b.copy(Bitmap.Config.ARGB_8888, true)
                                    val p = android.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                                    ACanvas(nb).drawPath(p, Paint().apply {
                                        this.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                                        strokeWidth = size / sc; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
                                    })
                                    commit(nb)
                                }
                            })
                    }
                    .pointerInput(tool, text, color, size) {
                        if (tool == "text") detectTapGestures { o ->
                            val p = toBmp(o)
                            val nb = b.copy(Bitmap.Config.ARGB_8888, true)
                            ACanvas(nb).drawText(text, p.x, p.y, Paint().apply {
                                this.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                                textSize = size * 3 / sc; isAntiAlias = true; isFakeBoldText = true
                            })
                            commit(nb)
                        }
                    }
                ) {
                    if (cur.size > 1) drawPath(Path().apply { moveTo(cur[0].x, cur[0].y); cur.drop(1).forEach { lineTo(it.x, it.y) } }, color, style = Stroke(size, cap = StrokeCap.Round))
                    if (tool == "crop") drawRect(Color.White, Offset(ox + b.width * sc * cl, oy + b.height * sc * cu),
                        Size(b.width * sc * (1 - cl - cr), b.height * sc * (1 - cu - cd)), style = Stroke(3f))
                }
            } else if (b == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        // خيارات الأداة
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            when (tool) {
                "adjust" -> {
                    Text(tr("السطوع", "Brightness")); Slider(br, { br = it }, valueRange = -100f..100f)
                    Text(tr("التباين", "Contrast")); Slider(ct, { ct = it }, valueRange = 0.5f..2f)
                    Text(tr("التشبّع", "Saturation")); Slider(sa, { sa = it }, valueRange = 0f..2f)
                    Button(onClick = { b?.let { commit(applyCM(it, adjMatrix(br, ct, sa))) }; resetAdj(); tool = "" }) { Text(tr("تطبيق", "Apply")) }
                }
                "crop" -> {
                    Row { Slider(cl, { cl = it }, Modifier.weight(1f), valueRange = 0f..0.45f); Slider(cr, { cr = it }, Modifier.weight(1f), valueRange = 0f..0.45f) }
                    Row { Slider(cu, { cu = it }, Modifier.weight(1f), valueRange = 0f..0.45f); Slider(cd, { cd = it }, Modifier.weight(1f), valueRange = 0f..0.45f) }
                    Button(onClick = {
                        b?.let { bb ->
                            val x = (bb.width * cl).toInt(); val y = (bb.height * cu).toInt()
                            val w = (bb.width * (1 - cl - cr)).toInt().coerceAtLeast(1); val h = (bb.height * (1 - cu - cd)).toInt().coerceAtLeast(1)
                            commit(Bitmap.createBitmap(bb, x, y, w.coerceAtMost(bb.width - x), h.coerceAtMost(bb.height - y)))
                        }; resetAdj(); tool = ""
                    }) { Text(tr("قص", "Crop")) }
                }
                "draw", "text" -> {
                    if (tool == "text") OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Blue, Color.White, Color.Black, Color.Magenta).forEach {
                            Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(it).clickable { color = it })
                        }
                    }
                    Slider(size, { size = it }, valueRange = 4f..60f)
                    Text(if (tool == "text") tr("المس الصورة لوضع النص", "Tap the image to place text") else tr("ارسم بإصبعك", "Draw with your finger"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            fun rot(deg: Float) { b?.let { val m = Matrix(); m.postRotate(deg); commit(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true)) } }
            fun flip(h: Boolean) { b?.let { val m = Matrix(); if (h) m.postScale(-1f, 1f) else m.postScale(1f, -1f); commit(Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true)) } }
            fun filt(m: AColorMatrix) { b?.let { commit(applyCM(it, m)) } }
            AssistChip(onClick = { rot(90f) }, label = { Text("⟳ 90°") })
            AssistChip(onClick = { flip(true) }, label = { Text(tr("قلب أفقي", "Flip H")) })
            AssistChip(onClick = { flip(false) }, label = { Text(tr("قلب رأسي", "Flip V")) })
            AssistChip(onClick = { resetAdj(); tool = "crop" }, label = { Text(tr("قص", "Crop")) })
            AssistChip(onClick = { resetAdj(); tool = "adjust" }, label = { Text(tr("ضبط", "Adjust")) })
            AssistChip(onClick = { filt(AColorMatrix().apply { setSaturation(0f) }) }, label = { Text(tr("أبيض/أسود", "B&W")) })
            AssistChip(onClick = { filt(AColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }, label = { Text("Sepia") })
            AssistChip(onClick = { filt(AColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))) }, label = { Text(tr("عكس", "Invert")) })
            AssistChip(onClick = { tool = "draw" }, label = { Text(tr("رسم", "Draw")) })
            AssistChip(onClick = { tool = "text" }, label = { Text(tr("نص", "Text")) })
        }
    }
}

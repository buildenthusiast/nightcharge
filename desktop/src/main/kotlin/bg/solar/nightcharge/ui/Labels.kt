package bg.solar.nightcharge.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint

/** Текст, центриран по X около cx, с базова линия baseline. Замества Android Paint.drawText. */
internal fun Canvas.centeredText(text: String, cx: Float, baseline: Float, sizePx: Float, color: Any) {
    val font = Font(null, sizePx)
    val paint = Paint().apply { isAntiAlias = true; this.color = (color as? Color)?.toArgb() ?: (color as Int) }
    drawString(text, cx - font.measureTextWidth(text) / 2f, baseline, font, paint)
}

/** Отваря адрес в браузъра по подразбиране. */
internal fun openUri(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

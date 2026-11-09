package bg.solar.nightcharge.ui


import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bg.solar.nightcharge.core.EnergyFlow
import bg.solar.nightcharge.core.pick
import bg.solar.nightcharge.inverter.Live
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class Glyph { SOLAR, GRID, BATTERY, HOUSE, EV, INVERTER }

/**
 * flow: 1 = енергията тече от елемента към инвертора, -1 = от инвертора към елемента, 0 = няма поток.
 */
private class FlowNode(
    val fx: Float,
    val fy: Float,
    val title: String,
    val value: String,
    val status: String?,
    val glyph: Glyph,
    val color: Color,
    val flow: Int,
)

private fun watts(v: Double?): String = v?.let { "${it.roundToInt()} W" } ?: "–"

/**
 * Схема на системата в стила на Deye Cloud: карти със символи, пунктирани линии със стрелки
 * и движещи се точки по посоката на потока. Посоката следва баланса (EnergyFlow).
 */
@Composable
fun FlowDiagram(d: Live?) {
    val pv = d?.pvW
    val grid = d?.gridW
    val battery = d?.batteryW
    val load = d?.loadW
    val cardColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val centerColor = MaterialTheme.colorScheme.secondary

    // Посоката е по баланса (EnergyFlow). Ако Deye не върне мрежата, тя се смята и се бележи с ≈.
    val dirs = EnergyFlow.directions(pv, load, battery, grid)
    val gridShown = grid ?: if (pv != null && load != null && battery != null) EnergyFlow.gridFromBalance(pv, load, battery) else null
    val gridApprox = grid == null && gridShown != null
    val soc = (d?.soc ?: 0) / 100f

    val batteryTitle = when {
        battery == null -> pick("Батерия", "Battery")
        battery > 0 -> pick("Разрежда", "Discharging")
        battery < 0 -> pick("Зарежда", "Charging")
        else -> pick("Батерия", "Battery")
    }

    val nodes = listOf(
        FlowNode(0.2f, 0.22f, pick("Слънце", "Solar"), watts(pv), null, Glyph.SOLAR, DeyeOrange, dirs.pv),
        FlowNode(
            0.8f, 0.22f, pick("Мрежа", "Grid"), (if (gridApprox) "≈ " else "") + watts(gridShown),
            if (grid != null) "On grid" else null, Glyph.GRID, DeyeBlue, dirs.grid,
        ),
        FlowNode(0.2f, 0.72f, batteryTitle, watts(battery), d?.let { "SOC ${it.soc}%" }, Glyph.BATTERY, DeyeGreen, dirs.battery),
        FlowNode(0.8f, 0.5f, pick("Къща", "House"), watts(load), null, Glyph.HOUSE, DeyeRed, dirs.house),
        FlowNode(0.8f, 0.72f, pick("EV зарядно", "EV charger"), "–", pick("няма данни", "no data"), Glyph.EV, mutedColor, 0),
    )
    val inverterLabel = pick("Инвертор", "Inverter")

    // Фаза 0..1 за движещите се точки по линиите.
    val flowPhase by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1600, easing = LinearEasing)),
        label = "flowPhase",
    )

    Canvas(Modifier.fillMaxWidth().height(330.dp)) {
        fun pos(fx: Float, fy: Float) = Offset(size.width * fx, size.height * fy)
        val center = pos(0.5f, 0.5f)
        val card = 54.dp.toPx()
        val dashed = Stroke(
            width = 1.5f.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f.dp.toPx(), 6f.dp.toPx())),
        )
        val lineColor = mutedColor.copy(alpha = 0.7f)

        // Пунктирните линии от всяка карта към инвертора.
        nodes.forEach { n ->
            val p = pos(n.fx, n.fy)
            val path = Path().apply {
                moveTo(p.x, p.y)
                lineTo(center.x, center.y)
            }
            drawPath(path, lineColor, style = dashed)
        }

        // Движещи се точки в цвета на елемента, по посоката на потока (flow != 0).
        val period = 14.dp.toPx()
        nodes.forEach { n ->
            if (n.flow == 0) return@forEach
            val p = pos(n.fx, n.fy)
            val path = Path().apply {
                moveTo(p.x, p.y)
                lineTo(center.x, center.y)
            }
            // Фазата на пунктира се измества към началото на пътя, затова за движение към инвертора е отрицателна.
            val shift = if (n.flow > 0) -flowPhase * period else flowPhase * period
            drawPath(
                path,
                n.color,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 12.dp.toPx()), shift),
                ),
            )
        }

        // Синята точка по средата и стрелката ближе до инвертора, по посоката на потока.
        nodes.forEach { n ->
            val p = pos(n.fx, n.fy)
            val mid = Offset((p.x + center.x) / 2, (p.y + center.y) / 2)
            drawCircle(cardColor, 5.dp.toPx(), mid)
            drawCircle(DeyeBlue, 3.5f.dp.toPx(), mid)

            if (n.flow == 0) return@forEach
            val from = if (n.flow > 0) p else center
            val to = if (n.flow > 0) center else p
            val len = hypot(to.x - from.x, to.y - from.y)
            if (len == 0f) return@forEach
            val ux = (to.x - from.x) / len
            val uy = (to.y - from.y) / len
            val px = -uy
            val py = ux
            val at = if (n.flow > 0) Offset(from.x + (to.x - from.x) * 0.7f, from.y + (to.y - from.y) * 0.7f)
            else Offset(from.x + (to.x - from.x) * 0.3f, from.y + (to.y - from.y) * 0.3f)
            val tip = 8.dp.toPx()
            val back = 5.dp.toPx()
            val half = 5.dp.toPx()
            val arrow = Path().apply {
                moveTo(at.x + ux * tip, at.y + uy * tip)
                lineTo(at.x - ux * back + px * half, at.y - uy * back + py * half)
                lineTo(at.x - ux * back - px * half, at.y - uy * back - py * half)
                close()
            }
            drawPath(arrow, mutedColor)
        }

        // Инверторът: карта със синя рамка.
        val centerSize = card * 0.9f
        drawRoundRect(
            cardColor,
            topLeft = Offset(center.x - centerSize / 2, center.y - centerSize / 2),
            size = Size(centerSize, centerSize),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        drawRoundRect(
            DeyeBlue,
            topLeft = Offset(center.x - centerSize / 2, center.y - centerSize / 2),
            size = Size(centerSize, centerSize),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )
        drawGlyph(Glyph.INVERTER, center, centerSize * 0.55f, centerColor, soc)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.centeredText(inverterLabel, center.x, center.y + centerSize / 2 + 14.dp.toPx(), 11.sp.toPx(), centerColor.toArgb())

        nodes.forEach { n ->
            val p = pos(n.fx, n.fy)
            // Карта, символ и стойност под нея, както в Deye Cloud.
            drawRoundRect(
                cardColor,
                topLeft = Offset(p.x - card / 2, p.y - card / 2),
                size = Size(card, card),
                cornerRadius = CornerRadius(12.dp.toPx()),
            )
            drawRoundRect(
                borderColor,
                topLeft = Offset(p.x - card / 2, p.y - card / 2),
                size = Size(card, card),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(1.dp.toPx()),
            )
            drawGlyph(n.glyph, p, card * 0.55f, n.color, if (n.glyph == Glyph.BATTERY) soc else 0f)
            canvas.centeredText(n.value, p.x, p.y + card / 2 + 16.dp.toPx(), 12.sp.toPx(), textColor.toArgb())
            canvas.centeredText(n.title, p.x, p.y + card / 2 + 30.dp.toPx(), 10.sp.toPx(), mutedColor.toArgb())

            n.status?.let {
                canvas.centeredText(it, p.x, p.y + card / 2 + 42.dp.toPx(), 10.sp.toPx(), DeyeGreen.toArgb())
            }
        }
    }
}

/** Символи в линеен стил. c е центърът, s е размерът на квадрата. */
private fun DrawScope.drawGlyph(glyph: Glyph, c: Offset, s: Float, color: Color, fill: Float) {
    val stroke = Stroke(1.6f.dp.toPx())
    val l = c.x - s / 2
    val t = c.y - s / 2
    val r = c.x + s / 2
    val b = c.y + s / 2
    when (glyph) {
        Glyph.SOLAR -> {
            val h = s * 0.7f
            drawRect(color, Offset(l, t), Size(s, h), style = stroke)
            for (i in 1..2) {
                drawLine(color, Offset(l + s * i / 3, t), Offset(l + s * i / 3, t + h), strokeWidth = 1.2f.dp.toPx())
            }
            drawLine(color, Offset(l, t + h / 2), Offset(r, t + h / 2), strokeWidth = 1.2f.dp.toPx())
            drawLine(color, Offset(c.x, t + h), Offset(c.x, b), strokeWidth = 1.6f.dp.toPx())
        }
        Glyph.GRID -> {
            val path = Path().apply {
                moveTo(c.x, t)
                lineTo(r, b)
                lineTo(l, b)
                close()
            }
            drawPath(path, color, style = stroke)
            drawLine(color, Offset(l + s * 0.2f, t + s * 0.55f), Offset(r - s * 0.2f, t + s * 0.55f), strokeWidth = 1.2f.dp.toPx())
            drawLine(color, Offset(c.x, t), Offset(c.x, b), strokeWidth = 1.2f.dp.toPx())
        }
        Glyph.BATTERY -> {
            val bodyH = s * 0.55f
            val top = c.y - bodyH / 2
            drawRoundRect(color, Offset(l, top), Size(s * 0.85f, bodyH), CornerRadius(3.dp.toPx()), style = stroke)
            drawRect(color, Offset(l + s * 0.85f, c.y - bodyH / 4), Size(s * 0.08f, bodyH / 2))
            val inner = (s * 0.85f - 6.dp.toPx()) * fill.coerceIn(0f, 1f)
            drawRect(color, Offset(l + 3.dp.toPx(), top + 3.dp.toPx()), Size(inner, bodyH - 6.dp.toPx()))
        }
        Glyph.HOUSE -> {
            val path = Path().apply {
                moveTo(c.x, t)
                lineTo(r, t + s * 0.42f)
                lineTo(r - s * 0.12f, t + s * 0.42f)
                lineTo(r - s * 0.12f, b)
                lineTo(l + s * 0.12f, b)
                lineTo(l + s * 0.12f, t + s * 0.42f)
                lineTo(l, t + s * 0.42f)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.EV -> {
            drawRoundRect(color, Offset(l + s * 0.1f, t + s * 0.1f), Size(s * 0.8f, s * 0.8f), CornerRadius(4.dp.toPx()), style = stroke)
            val bolt = Path().apply {
                moveTo(c.x + s * 0.05f, t + s * 0.2f)
                lineTo(c.x - s * 0.12f, c.y + s * 0.05f)
                lineTo(c.x + s * 0.02f, c.y + s * 0.05f)
                lineTo(c.x - s * 0.05f, b - s * 0.2f)
                lineTo(c.x + s * 0.12f, c.y - s * 0.05f)
                lineTo(c.x - s * 0.02f, c.y - s * 0.05f)
                close()
            }
            drawPath(bolt, color)
        }
        Glyph.INVERTER -> {
            drawRoundRect(color, Offset(l, t), Size(s, s), CornerRadius(4.dp.toPx()), style = stroke)
            drawCircle(color, s * 0.22f, c, style = stroke)
            drawLine(color, Offset(c.x, t + s * 0.1f), Offset(c.x, t + s * 0.2f), strokeWidth = 1.2f.dp.toPx())
        }
    }
}

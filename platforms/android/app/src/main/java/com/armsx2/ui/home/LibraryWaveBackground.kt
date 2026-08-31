package com.armsx2.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.armsx2.ui.theme.LibraryBackgroundColorPreferences
import kotlin.math.PI
import kotlin.math.sin

/**
 * Library background for devices where the GLES3 [XmbGlView] can't run — older Mali without
 * float-texture filtering, or any EGL failure. It used to fall back to a fixed looping GIF
 * (R.raw.library_fallback) that ignored the colour picker entirely, so Mali users had a background
 * they couldn't recolour. This is a PPSSPP-style procedural background instead: a soft vertical
 * gradient, four wave sheets and a few PlayStation glyphs, drawn with the hardware 2D Canvas (Skia)
 * rather than GLES3 — so it runs on ANY GPU, and it reads the same
 * [LibraryBackgroundColorPreferences] the GL wave does, so the colour picker works on a Mali device.
 * The readability scrim is applied by HomeScreen on top, same as for the GL wave.
 *
 * ## It does not animate, and that is the point (TASK-0063)
 *
 * It used to. "Deliberately cheap" was the intent; the measurement said otherwise. On a Galaxy A12
 * (Mali-G52) the animated version cost **~0.94 of a core, continuously, on a screen where the user
 * is only picking a game** — `RenderThread` 41%, main 25%, hwui 10+10%, mali 8% — at 34–46 ms per
 * frame, most of it rasterising four alpha-blended bands that each cover ~72% of the panel. Caching
 * every object that did not change bought 3 of those points; the remaining ~69 are fill rate, and
 * fill rate is only paid because a new frame gets drawn.
 *
 * So no new frame gets drawn. Nothing here writes to state after the first composition, so nothing
 * invalidates the draw tree, so HWUI records the display list once and stops asking the choreographer
 * for frames. The idle cost of this screen goes to roughly nothing — and the picture is *identical*:
 * same gradient, same waves, same glyphs, same colours. Just still.
 *
 * That is a product decision, not an optimisation: performance is not worth trading for decoration
 * on the weak devices this path exists to serve.
 *
 * One honest consequence: [LibraryBackgroundColorPreferences.rgbCycle] sweeps the hue wheel over
 * ~28 s, which is an animation by definition. A still scene cannot sweep, so on this path that
 * switch now yields a fixed colour instead of a cycle.
 */
@Composable
fun LibraryWaveBackground(modifier: Modifier = Modifier) {
    // Colour picker + RGB toggle, read as Compose state so a live change recolours immediately —
    // a recomposition redraws the still scene once, which is exactly what it should cost.
    val colorArgb by LibraryBackgroundColorPreferences.color
    val rgbCycle by LibraryBackgroundColorPreferences.rgbCycle

    Canvas(modifier) {
        // Base colour: the RGB toggle picks a fixed point on the hue wheel (it can no longer sweep
        // — see the KDoc); otherwise the chosen colour, or the built-in royal blue when unset.
        val base: Color = when {
            rgbCycle -> Color.hsv(RGB_STILL_HUE, 0.72f, 0.96f)
            colorArgb == 0 -> DEFAULT_WAVE
            else -> Color(colorArgb)
        }
        drawWaveScene(base)
    }
}

/** Cor embutida da onda. Casa com XmbGlView.BG_BOT e com
 *  LibraryBackgroundColorPreferences.DefaultDisplayColor -- os tres tem de andar juntos, senao o
 *  mesmo app mostra fundos diferentes conforme o aparelho tenha ou nao GLES3. */
private val DEFAULT_WAVE = Color(0xFF16243D)

private const val WAVE_LAYERS = 4
private const val SAMPLES = 64

/**
 * The instant of the old animation this scene is frozen at.
 *
 * Not zero: at t = 0 every layer's phase is exactly `layer * 2.2f`, which stacks the crests into a
 * regular ladder. A little way in gives the layers the offset spread the wave was designed to show.
 * Pure aesthetics — every value draws for the same price.
 */
private const val FROZEN_T = 7.5f

/** Fixed hue for the RGB-cycle setting, which cannot cycle in a still scene.
 *
 *  218.5 is the hue of [DEFAULT_WAVE] (0xFF16243D converts to H 218.5, S 0.64, V 0.24), so turning
 *  the switch on keeps the family of colour the library already had instead of jumping somewhere
 *  unrelated. Saturation and value stay at the cycle's own 0.72/0.96 — the scene darkens them
 *  itself through scaleRgb. */
private const val RGB_STILL_HUE = 218.5f

private fun DrawScope.drawWaveScene(base: Color) {
    val w = size.width
    val h = size.height
    val t = FROZEN_T

    // 1) Vertical gradient — near-black anchor at the top (where the content/grid sits), deepening
    //    to the chosen colour at the bottom. Same 0.20 top/bottom ratio the GL path uses.
    drawRect(
        Brush.verticalGradient(
            0.0f to base.scaleRgb(0.10f),
            0.55f to base.scaleRgb(0.35f),
            1.0f to base.scaleRgb(0.85f),
        ),
    )

    // 2) Soft wave BANDS — filled translucent sheets that glow just under the crest and fade
    //    downward, layered back-to-front. Reads as flowing light, not thin squiggly lines.
    val step = w / SAMPLES
    val twoPi = 2f * PI.toFloat()
    for (layer in 0 until WAVE_LAYERS) {
        val f = layer.toFloat() / (WAVE_LAYERS - 1)
        val baseY = h * (0.40f + 0.16f * f)                 // deeper layers sit lower
        val amp = h * (0.05f + 0.028f * (1f - f))           // gentle undulation
        val len = 1.05f + 0.5f * f
        val speed = 0.26f + 0.14f * f
        val phase = t * speed + layer * 2.2f
        fun waveY(nx: Float): Float =
            baseY + amp * sin(nx * len * twoPi + phase) +
                amp * 0.34f * sin(nx * len * 2.1f * twoPi - phase * 1.35f + layer)
        // Filled sheet from the crest curve down past the bottom edge.
        val body = Path().apply {
            moveTo(0f, h + 2f)
            var i = 0
            while (i <= SAMPLES) { lineTo(i * step, waveY(i.toFloat() / SAMPLES)); i++ }
            lineTo(w, h + 2f)
            close()
        }
        val tint = base.lighten(0.30f + 0.22f * f)
        drawPath(
            path = body,
            brush = Brush.verticalGradient(
                0.00f to tint.copy(alpha = 0f),
                0.05f to tint.copy(alpha = 0.10f + 0.05f * f),   // soft glow under the crest
                0.55f to base.scaleRgb(1.06f).copy(alpha = 0.04f + 0.03f * f),
                1.00f to base.scaleRgb(0.75f).copy(alpha = 0f),
                startY = baseY - amp * 1.6f,
                endY = h,
            ),
        )
        // Faint, wide, soft crest — enough to define the wave without reading as a hard line.
        val crest = Path().apply {
            var i = 0
            while (i <= SAMPLES) {
                val x = i * step; val y = waveY(i.toFloat() / SAMPLES)
                if (i == 0) moveTo(x, y) else lineTo(x, y); i++
            }
        }
        drawPath(
            path = crest,
            color = base.lighten(0.62f).copy(alpha = 0.07f + 0.07f * f),
            style = Stroke(width = size.minDimension * (0.008f + 0.004f * f), cap = StrokeCap.Round),
        )
    }

    // 3) PlayStation glyphs — a faint parallax layer, the PPSSPP "floating symbols" flavour. They
    //    used to drift upward and loop; now they sit wherever the drift had them at FROZEN_T.
    val glyphColor = base.lighten(0.6f).copy(alpha = 0.06f)
    for (i in GLYPH_SPOTS.indices) {
        val (sx, sy, kind, scale) = GLYPH_SPOTS[i]
        val drift = (t * (0.012f + 0.006f * (i % 3))) + sy
        val y = h * (1.1f - (drift % 1.2f))
        val x = w * ((sx + 0.02f * sin(t * 0.2f + i)) % 1f)
        val r = size.minDimension * 0.04f * scale
        drawGlyph(kind, Offset(x, y), r, glyphColor, t + i)
    }
}

/** (xFrac, yPhase, kind 0..3 = △○✕□, scale). */
private val GLYPH_SPOTS: List<Glyph> = listOf(
    Glyph(0.10f, 0.05f, 0, 1.1f), Glyph(0.24f, 0.55f, 1, 0.8f), Glyph(0.38f, 0.30f, 2, 1.0f),
    Glyph(0.52f, 0.80f, 3, 0.9f), Glyph(0.63f, 0.15f, 1, 1.2f), Glyph(0.71f, 0.62f, 0, 0.75f),
    Glyph(0.82f, 0.40f, 3, 1.05f), Glyph(0.90f, 0.90f, 2, 0.85f), Glyph(0.46f, 0.05f, 0, 0.7f),
    Glyph(0.16f, 0.72f, 3, 0.95f),
)

private data class Glyph(val x: Float, val y: Float, val kind: Int, val scale: Float)

private fun DrawScope.drawGlyph(kind: Int, c: Offset, r: Float, color: Color, spin: Float) {
    val stroke = Stroke(width = r * 0.14f, cap = StrokeCap.Round)
    when (kind) {
        1 -> drawCircle(color, radius = r * 0.82f, center = c, style = stroke)      // ○
        3 -> rotate(spin * 6f, pivot = c) {                                          // □
            val s = r * 1.35f
            drawRect(color, topLeft = Offset(c.x - s / 2, c.y - s / 2),
                size = androidx.compose.ui.geometry.Size(s, s), style = stroke)
        }
        2 -> {                                                                       // ✕
            val s = r * 0.7f
            drawLine(color, Offset(c.x - s, c.y - s), Offset(c.x + s, c.y + s), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
            drawLine(color, Offset(c.x - s, c.y + s), Offset(c.x + s, c.y - s), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
        }
        else -> {                                                                    // △
            val p = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r * 0.87f, c.y + r * 0.5f)
                lineTo(c.x - r * 0.87f, c.y + r * 0.5f)
                close()
            }
            drawPath(p, color, style = stroke)
        }
    }
}

// ---- small colour helpers -------------------------------------------------
/** Scale RGB by [v] (toward black for v<1, a touch brighter for v>1), clamped to valid range. */
private fun Color.scaleRgb(v: Float) = Color(
    (red * v).coerceIn(0f, 1f), (green * v).coerceIn(0f, 1f), (blue * v).coerceIn(0f, 1f), alpha,
)
/** Blend toward white by [amount] — the ribbon/glyph highlight tint. */
private fun Color.lighten(amount: Float) = Color(
    red + (1f - red) * amount, green + (1f - green) * amount, blue + (1f - blue) * amount, alpha,
)

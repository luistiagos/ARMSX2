package com.armsx2.ui.home

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
 * Animated library background for devices where the GLES3 [XmbGlView] can't run — older Mali
 * without float-texture filtering, or any EGL failure. It used to fall back to a fixed looping GIF
 * (R.raw.library_fallback) that ignored the colour picker entirely, so Mali users had a background
 * they couldn't recolour. This is a PPSSPP-style procedural background instead: soft flowing waves
 * plus a few drifting PlayStation glyphs, drawn with the hardware 2D Canvas (Skia) rather than GLES3
 * — so it runs on ANY GPU, and it reads the SAME [LibraryBackgroundColorPreferences] the GL wave
 * does, so the colour swatches and the RGB hue-cycle finally do something on a Mali device.
 *
 * Deliberately cheap: one vertical gradient, four stroked sine paths, and ten small glyphs per
 * frame — a few hundred segments, nothing a weak tiler struggles with. No textures, no blur, no
 * offscreen passes. The readability scrim is applied by HomeScreen on top, same as for the GL wave.
 *
 * "Cheap" was the intent, not the measurement. On a Galaxy A12 (Mali-G52), the library screen sat
 * at 34–46 ms per frame (gfxinfo p50 = 38 ms) with `Number Slow issue draw commands` on 100% of
 * frames, and burned ~0.85 of a core standing still. Everything the scene draws that does NOT move
 * is now built once and reused — see [WaveScratch].
 */
@Composable
fun LibraryWaveBackground(modifier: Modifier = Modifier) {
    // Colour picker + RGB toggle, read as Compose state so a live change recolours immediately.
    val colorArgb by LibraryBackgroundColorPreferences.color
    val rgbCycle by LibraryBackgroundColorPreferences.rgbCycle

    // Elapsed seconds, ticked once per frame. Driven off the animation clock (not a recomposition
    // loop) so only the Canvas draw re-runs each frame, not the whole tree.
    //
    // Held to ~30 fps, matching the GL sibling ([XmbGlView]'s FRAME_TARGET_MS).
    //
    // ORDER MATTERS HERE, and it cost a measurement to learn: before [WaveScratch] existed, this
    // cap was worthless. A frame cost ~38 ms, so the screen was already pinned to every second
    // vsync — capping something that is already frame-bound saves nothing. With the per-frame cost
    // fixed, the screen reached a full 60 fps and spent the headroom redrawing a slow wave twice as
    // often (measured on the A12: 0.85 -> 1.11 of a core). NOW the cap is the thing that banks the
    // win instead of spending it.
    //
    // The gate is 25 ms rather than 33 ms on purpose. At a 60 Hz vsync the frame callbacks land at
    // 0/16.7/33.3 ms, so a 33 ms threshold sits right on top of the third one and any jitter below
    // it drops that frame entirely — 30 fps with a 20 fps stutter. Anything in (16.7, 33.3) picks
    // every second callback deterministically, and still gives 30 fps at 90 or 120 Hz.
    val timeSec = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        withInfiniteAnimationFrameNanos { start = it }
        var lastPublished = start
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (now - lastPublished >= FRAME_INTERVAL_NANOS) {
                    lastPublished = now
                    timeSec.floatValue = (now - start) / 1_000_000_000f
                }
            }
        }
    }

    val scratch = remember { WaveScratch() }

    Canvas(modifier) {
        val t = timeSec.floatValue
        // Base colour: the RGB cycle sweeps the hue wheel (~28s/turn, matching the GL peripheral
        // vibe); otherwise the picked colour, or the built-in royal blue when unset.
        val base: Color = when {
            rgbCycle -> Color.hsv(((t / 28f) * 360f) % 360f, 0.72f, 0.96f)
            colorArgb == 0 -> DEFAULT_WAVE
            else -> Color(colorArgb)
        }
        drawWaveScene(t, base, scratch.forSize(base, size.width, size.height, size.minDimension))
    }
}

/** Cor embutida da onda. Casa com XmbGlView.BG_BOT e com
 *  LibraryBackgroundColorPreferences.DefaultDisplayColor -- os tres tem de andar juntos, senao o
 *  mesmo app mostra fundos diferentes conforme o aparelho tenha ou nao GLES3. */
private val DEFAULT_WAVE = Color(0xFF16243D)

private const val WAVE_LAYERS = 4
private const val SAMPLES = 64

/** ~30 fps, deliberately gated below the 33.3 ms mark — see the comment at the call site. */
private const val FRAME_INTERVAL_NANOS = 25_000_000L

/**
 * Everything in the scene that does not depend on the clock.
 *
 * This is the whole optimisation, and it is worth spelling out why it is safe: a wave layer's
 * `baseY`, `amp`, gradient stops and `startY`/`endY` are functions of the LAYER INDEX, the canvas
 * size and the colour — the animation clock `t` only enters through `phase`, which moves the curve,
 * not the paint. The five vertical gradients (backdrop + one per layer) were therefore being rebuilt
 * thirty times a second to produce the identical object, and each rebuild makes Skia hand the driver
 * a fresh shader. That is what `Number Slow issue draw commands: 100% of frames` was reporting.
 *
 * The [Path] objects DO change every frame (the curve moves), so they are reused rather than
 * cached — `reset()` keeps the allocation instead of leaving eight of them per frame to the GC.
 *
 * Rebuilt only when the colour or the canvas size changes. With the RGB hue cycle on, the colour
 * changes every frame and this legitimately rebuilds every frame — that mode costs what it always
 * cost, no more.
 */
private class WaveScratch {
    private var keyColor: ULong = ULong.MAX_VALUE
    private var keyW = Float.NaN
    private var keyH = Float.NaN

    var backdrop: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))
        private set
    var glyphColor: Color = Color.Transparent
        private set

    class LayerPaint(
        val baseY: Float,
        val amp: Float,
        val len: Float,
        val speed: Float,
        val body: Brush,
        val crestColor: Color,
        val crestStroke: Stroke,
    )

    var layers: List<LayerPaint> = emptyList()
        private set

    /** Stroke per glyph SPOT, not per glyph kind: the width follows the spot's scale. */
    var glyphStroke: List<Stroke> = emptyList()
        private set
    var glyphRadius: FloatArray = FloatArray(0)
        private set

    // Reused across frames; the geometry is rewritten every frame with reset().
    val bodyPath = Array(WAVE_LAYERS) { Path() }
    val crestPath = Array(WAVE_LAYERS) { Path() }
    val glyphPath = Path()

    fun forSize(base: Color, w: Float, h: Float, minDim: Float): WaveScratch {
        if (base.value == keyColor && w == keyW && h == keyH) return this
        keyColor = base.value; keyW = w; keyH = h

        backdrop = Brush.verticalGradient(
            0.0f to base.scaleRgb(0.10f),
            0.55f to base.scaleRgb(0.35f),
            1.0f to base.scaleRgb(0.85f),
        )
        layers = (0 until WAVE_LAYERS).map { layer ->
            val f = layer.toFloat() / (WAVE_LAYERS - 1)
            val baseY = h * (0.40f + 0.16f * f)       // deeper layers sit lower
            val amp = h * (0.05f + 0.028f * (1f - f)) // gentle undulation
            val tint = base.lighten(0.30f + 0.22f * f)
            LayerPaint(
                baseY = baseY,
                amp = amp,
                len = 1.05f + 0.5f * f,
                speed = 0.26f + 0.14f * f,
                body = Brush.verticalGradient(
                    0.00f to tint.copy(alpha = 0f),
                    0.05f to tint.copy(alpha = 0.10f + 0.05f * f), // soft glow under the crest
                    0.55f to base.scaleRgb(1.06f).copy(alpha = 0.04f + 0.03f * f),
                    1.00f to base.scaleRgb(0.75f).copy(alpha = 0f),
                    startY = baseY - amp * 1.6f,
                    endY = h,
                ),
                crestColor = base.lighten(0.62f).copy(alpha = 0.07f + 0.07f * f),
                crestStroke = Stroke(width = minDim * (0.008f + 0.004f * f), cap = StrokeCap.Round),
            )
        }
        glyphColor = base.lighten(0.6f).copy(alpha = 0.06f)
        glyphRadius = FloatArray(GLYPH_SPOTS.size) { minDim * 0.04f * GLYPH_SPOTS[it].scale }
        glyphStroke = GLYPH_SPOTS.indices.map {
            Stroke(width = glyphRadius[it] * 0.14f, cap = StrokeCap.Round)
        }
        return this
    }
}

private fun DrawScope.drawWaveScene(t: Float, base: Color, s: WaveScratch) {
    val w = size.width
    val h = size.height

    // 1) Vertical gradient — near-black anchor at the top (where the content/grid sits), deepening
    //    to the chosen colour at the bottom. Same 0.20 top/bottom ratio the GL path uses.
    drawRect(s.backdrop)

    // 2) Soft flowing wave BANDS — filled translucent sheets that glow just under the crest and
    //    fade downward, layered back-to-front. Reads as flowing light, not thin squiggly lines.
    val step = w / SAMPLES
    val twoPi = 2f * PI.toFloat()
    for (layer in 0 until WAVE_LAYERS) {
        val lp = s.layers[layer]
        val phase = t * lp.speed + layer * 2.2f
        fun waveY(nx: Float): Float =
            lp.baseY + lp.amp * sin(nx * lp.len * twoPi + phase) +
                lp.amp * 0.34f * sin(nx * lp.len * 2.1f * twoPi - phase * 1.35f + layer)
        // Filled sheet from the crest curve down past the bottom edge.
        val body = s.bodyPath[layer].apply {
            reset()
            moveTo(0f, h + 2f)
            var i = 0
            while (i <= SAMPLES) { lineTo(i * step, waveY(i.toFloat() / SAMPLES)); i++ }
            lineTo(w, h + 2f)
            close()
        }
        drawPath(path = body, brush = lp.body)
        // Faint, wide, soft crest — enough to define the wave without reading as a hard line.
        val crest = s.crestPath[layer].apply {
            reset()
            var i = 0
            while (i <= SAMPLES) {
                val x = i * step; val y = waveY(i.toFloat() / SAMPLES)
                if (i == 0) moveTo(x, y) else lineTo(x, y); i++
            }
        }
        drawPath(path = crest, color = lp.crestColor, style = lp.crestStroke)
    }

    // 3) Drifting PlayStation glyphs — a faint, slow parallax layer, the PPSSPP "floating symbols"
    //    flavour. Fixed pseudo-random spots (deterministic, no RNG per frame) rising and looping.
    for (i in GLYPH_SPOTS.indices) {
        val spot = GLYPH_SPOTS[i]
        val drift = (t * (0.012f + 0.006f * (i % 3))) + spot.y
        val y = h * (1.1f - (drift % 1.2f))                 // rise from below, loop past the top
        val x = w * ((spot.x + 0.02f * sin(t * 0.2f + i)) % 1f)
        drawGlyph(spot.kind, Offset(x, y), s.glyphRadius[i], s.glyphColor, t + i,
            s.glyphStroke[i], s.glyphPath)
    }
}

/** (xFrac, yPhase, kind 0..3 = △○✕□, scale). Deterministic so nothing allocates per frame. */
private val GLYPH_SPOTS: List<Glyph> = listOf(
    Glyph(0.10f, 0.05f, 0, 1.1f), Glyph(0.24f, 0.55f, 1, 0.8f), Glyph(0.38f, 0.30f, 2, 1.0f),
    Glyph(0.52f, 0.80f, 3, 0.9f), Glyph(0.63f, 0.15f, 1, 1.2f), Glyph(0.71f, 0.62f, 0, 0.75f),
    Glyph(0.82f, 0.40f, 3, 1.05f), Glyph(0.90f, 0.90f, 2, 0.85f), Glyph(0.46f, 0.05f, 0, 0.7f),
    Glyph(0.16f, 0.72f, 3, 0.95f),
)

private data class Glyph(val x: Float, val y: Float, val kind: Int, val scale: Float)

private fun DrawScope.drawGlyph(
    kind: Int, c: Offset, r: Float, color: Color, spin: Float, stroke: Stroke, scratch: Path,
) {
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
            scratch.reset()
            scratch.moveTo(c.x, c.y - r)
            scratch.lineTo(c.x + r * 0.87f, c.y + r * 0.5f)
            scratch.lineTo(c.x - r * 0.87f, c.y + r * 0.5f)
            scratch.close()
            drawPath(scratch, color, style = stroke)
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

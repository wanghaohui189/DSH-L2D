package com.prts.client.live2d

import kotlin.math.abs

/** Viewport fractions: positive X moves right, positive Y moves down. */
data class PresentationTransform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)
data class FacialEffectParameter(val id: String, val value: Double, val blend: String)
data class PresentationViewport(val left: Float, val top: Float, val width: Float, val height: Float)

/** Pure reviewed presentation policy. It does not change mood, model files or model parameter ranges. */
object PresentationPolicy {
    /** One fitted viewport for TextureView presentation and pointer coordinates.
     * EGL can override the requested producer buffer size; never infer its aspect from the request.
     */
    fun fitViewport(viewWidth: Float, viewHeight: Float, bufferWidth: Float, bufferHeight: Float): PresentationViewport {
        if (listOf(viewWidth, viewHeight, bufferWidth, bufferHeight).any { !it.isFinite() || it <= 0f })
            return PresentationViewport(0f, 0f, 0f, 0f)
        val scale = minOf(viewWidth / bufferWidth, viewHeight / bufferHeight)
        val width = bufferWidth * scale
        val height = bufferHeight * scale
        return PresentationViewport((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }

    /** Renderer publishes five art boxes (head, body, legs, tail, ears), four fractions each. */
    private const val TOUCH_BOX_COUNT = 5

    fun touchRegion(viewport: PresentationViewport, localX: Float, localY: Float, bounds: FloatArray): String? {
        if (!localX.isFinite() || !localY.isFinite() || viewport.width <= 0f || viewport.height <= 0f ||
            bounds.size != TOUCH_BOX_COUNT * 4) return null
        val x = (localX - viewport.left) / viewport.width
        val y = (localY - viewport.top) / viewport.height
        if (x !in 0f..1f || y !in 0f..1f) return null
        fun hits(box: Int): Boolean {
            val offset = box * 4
            return bounds[offset] < bounds[offset + 2] && bounds[offset + 1] < bounds[offset + 3] &&
                x in bounds[offset]..bounds[offset + 2] && y in bounds[offset + 1]..bounds[offset + 3]
        }
        // Vertical position 0..1 inside a region box, used only inside a box that already hit.
        fun depth(box: Int): Float {
            val offset = box * 4
            return (y - bounds[offset + 1]) / (bounds[offset + 3] - bounds[offset + 1])
        }
        // Torso before the limb boxes: the torso box is the tight chest/waist/skirt band, while the leg
        // and tail boxes overlap it and must only claim what the torso does not cover.
        return when {
            hits(4) -> "ears"
            hits(0) -> if (depth(0) < .45f) "hair" else "face"
            hits(1) -> when {
                depth(1) < .50f -> "chest"
                depth(1) < .62f -> "waist"
                else -> "skirt"
            }
            hits(2) -> "legs"
            hits(3) -> "tail"
            else -> null
        }
    }

    /** A changed or mixed prop expression is rejected rather than silently treated as a face effect. */
    fun reviewedFaceOverlay(name: String, parameters: List<FacialEffectParameter>): Int {
        val recipe = when (name) {
            "＞＜" -> "key5" to 1
            "流泪" -> "key2" to 2
            "黑脸" -> "key1" to 4
            "脸红" -> "key7" to 8
            else -> return 0
        }
        var active = 0
        val seen = mutableSetOf<String>()
        for (parameter in parameters) {
            if (!seen.add(parameter.id) || parameter.blend != "Add" || !parameter.value.isFinite()) return 0
            if (parameter.id == recipe.first && parameter.value == 1.0) active++
            else if (parameter.id !in setOf("key3", "key4", "key5", "key6") || parameter.value != 0.0) return 0
        }
        return if (active == 1) recipe.second else 0
    }

    fun transform(scale: Float, offsetX: Float, offsetY: Float): PresentationTransform = PresentationTransform(
        if (scale.isFinite()) scale.coerceIn(.65f, 2.25f) else 1f,
        if (offsetX.isFinite()) offsetX.coerceIn(-.5f, .5f) else 0f,
        if (offsetY.isFinite()) offsetY.coerceIn(-.5f, .5f) else 0f,
    )

    /** Target peak fraction of the actual rig input's appropriate directional span.
     * The old body curves peaked at 1.2..4 on a +/-30 driver, too small for a full-body view.
     * Keep their authored timing and relative lobes, but give the torso its own meaningful span.
     * These are bounded presentation choices, not a claim that the physical output angle is equal.
     */
    fun gesturePeakFraction(gestureId: String, outputId: String): Double {
        val extended = gestureId.endsWith("-wide")
        val name = gestureId.removeSuffix("-wide")
        val body = outputId.startsWith("ParamBody")
        return when (name) {
            "call:nod" -> if (body) { if (extended) .48 else .28 } else { if (extended) .76 else .45 }
            "call:shake" -> if (body) { if (extended) .62 else .34 } else { if (extended) .82 else .48 }
            "call:bow" -> if (body) { if (extended) .86 else .58 } else { if (extended) .88 else .60 }
            "call:sway" -> if (body) { if (extended) .64 else .36 } else { if (extended) .60 else .34 }
            // 2026-11-21: the six 2026-11-20 body gestures were never reachable here, so
            // remapCallGesture threw "Unreviewed application gesture" during registration
            // and tapping them did nothing. Fractions are presentation choices on the rig
            // span (head sits on +/-30), same scale family as nod/shake/sway.
            "call:tilt" -> if (body) { if (extended) .30 else .18 } else { if (extended) .62 else .40 }
            "call:nod-twice" -> if (body) { if (extended) .62 else .40 } else { if (extended) .88 else .70 }
            "call:shiver" -> if (body) { if (extended) .40 else .26 } else { if (extended) .52 else .34 }
            "call:look-around" -> if (body) { if (extended) .52 else .34 } else { if (extended) .82 else .62 }
            "call:lean-in" -> if (body) { if (extended) .76 else .55 } else { if (extended) .70 else .48 }
            "call:look-up" -> if (body) { if (extended) .46 else .30 } else { if (extended) .78 else .55 }
            // 2026-11-21: shy is a body pose now, so the tilt must read clearly even
            // while the torso only leans a little (a shy duck, not a bow).
            "call:shy" -> if (body) { if (extended) .34 else .20 } else { if (extended) .78 else .52 }
            else -> error("Unreviewed application gesture")
        }
    }

    fun mapGestureValue(value: Double, curvePeak: Double, minimum: Double, center: Double, maximum: Double,
                        gestureId: String, outputId: String): Double {
        require(listOf(value, curvePeak, minimum, center, maximum).all { it.isFinite() })
        require(minimum <= center && center <= maximum && minimum < maximum && curvePeak > 0)
        if (value == 0.0) return center
        val span = if (value > 0) maximum - center else center - minimum
        require(span > 0) { "Requested rig direction has no available span" }
        return (center + (value / abs(curvePeak)).coerceIn(-1.0, 1.0) * span *
            gesturePeakFraction(gestureId, outputId)).coerceIn(minimum, maximum)
    }
}

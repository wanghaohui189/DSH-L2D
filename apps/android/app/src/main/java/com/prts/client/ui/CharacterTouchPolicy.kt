package com.prts.client.ui

import android.view.HapticFeedbackConstants

/** Region x gesture table for a deliberate touch on the character.
 *
 * Pure data: no clock, no I/O, no model handles. Regions come from [com.prts.client.live2d.PresentationPolicy.touchRegion]
 * (hair/face/chest/waist/skirt); gestures come from the touch rhythm in [AutoMotionDirector].
 * Every motion id is one of the model's own call gestures, so a missing rig capability falls back
 * instead of silently doing nothing. `label` is the on-device wording used by the debug log,
 * not a claim about what the user necessarily sees.
 */
internal object CharacterTouchPolicy {
    data class Reaction(
        val gesture: String,
        val fallbacks: List<String>,
        val expression: String,
        val peak: Double,
        val haptic: Int,
        val label: String,
        /** Pre-rendered `touch-voices/<voice>.mp3` played with the reaction; blank = silent. */
        val voice: String = "",
    ) {
        /** Ordered candidates: the authored motion first, then safe substitutes. */
        val gestures: List<String> get() = listOf(gesture) + fallbacks
    }

    /**
     * One line of the on-device 「摸哪里 → 她会怎么反应」 list, opened from the pet-mode button.
     * Data only: gesture / expression ids are resolved to Chinese titles by the UI
     * ([com.prts.client.live2d.Live2DView.gestureTitle] / [faceTitle]) and [voiceClips] to her
     * spoken line by the `touch-voices` manifest. Nothing here reads the clock or the model.
     */
    data class Row(
        val region: String,
        val regionTitle: String,
        /** tap / double / hold / rapid. */
        val kind: String,
        val kindTitle: String,
        val gesture: String,
        val expression: String,
        val label: String,
        /** Clip base names under `touch-voices/`; single taps alternate between two takes. */
        val voiceClips: List<String>,
    )

    private val regionOrder = listOf(
        "hair" to "摸头", "face" to "脸上", "ears" to "耳朵", "chest" to "胸口",
        "waist" to "腰", "skirt" to "裙摆", "legs" to "腿", "tail" to "尾巴",
    )
    private val kindOrder = listOf("tap" to "点一下", "double" to "双击", "hold" to "按住")

    /** Every reaction the touch table can currently produce, in display order (owner 2026-11-20:
     *  触屏提示要能点开看清单). Consumed by `TouchInventoryPanel`; keep the UI out of here. */
    fun inventory(): List<Row> {
        val rows = mutableListOf<Row>()
        for ((region, regionTitle) in regionOrder) {
            for ((kind, kindTitle) in kindOrder) {
                val reaction = reaction(region, kind) ?: continue
                rows.add(Row(region, regionTitle, kind, kindTitle, reaction.gesture,
                    reaction.expression, reaction.label, voiceClips(kind, region)))
            }
        }
        // Rapid taps are one global reaction, not a per-region entry.
        reaction("hair", "rapid")?.let { reaction ->
            rows.add(Row("", "连戳三下", "rapid", "连戳三下", reaction.gesture,
                reaction.expression, reaction.label, voiceClips("rapid", "hair")))
        }
        return rows
    }

    /** Mirrors [AutoMotionDirector.touchVoiceClip]'s pools so the list quotes what she will say. */
    fun voiceClips(kind: String, region: String): List<String> = when (kind) {
        "rapid" -> listOf("rapid_1", "rapid_2")
        "hold" -> listOf("hold_$region")
        "double" -> listOf("double_$region")
        else -> listOf("tap_${region}_1", "tap_${region}_2")
    }

    private val nodThenSway = listOf("call:sway", "call:bow")
    private val swayThenNod = listOf("call:nod", "call:bow")

    fun reaction(region: String, kind: String): Reaction? = when (kind) {
        "rapid" -> Reaction("call:shake", listOf("call:bow"), "angry", .95,
            HapticFeedbackConstants.LONG_PRESS, "连着戳了好几下，烦了")
        "hold" -> hold(region)
        "double" -> double(region)
        "tap" -> tap(region)
        else -> null
    }

    private fun tap(region: String): Reaction? = when (region) {
        "hair" -> Reaction("call:nod", swayThenNod, "happy", .90,
            HapticFeedbackConstants.CLOCK_TICK, "摸了摸头顶，点头")
        "face" -> Reaction("call:sway", nodThenSway, "surprised", .90,
            HapticFeedbackConstants.CLOCK_TICK, "戳了一下脸，吓一跳")
        "ears" -> Reaction("call:nod", swayThenNod, "surprised", .88,
            HapticFeedbackConstants.CLOCK_TICK, "碰到耳朵，抖了一下")
        "chest" -> Reaction("call:bow", nodThenSway, "shy", .88,
            HapticFeedbackConstants.CLOCK_TICK, "碰了碰胸口，缩了一下")
        "waist" -> Reaction("call:shake", swayThenNod, "surprised", .90,
            HapticFeedbackConstants.CLOCK_TICK, "挠到腰，躲了一下")
        "skirt" -> Reaction("call:sway", nodThenSway, "angry", .88,
            HapticFeedbackConstants.CLOCK_TICK, "碰到裙摆，轻轻抗议")
        "legs" -> Reaction("call:shake", swayThenNod, "shy", .88,
            HapticFeedbackConstants.CLOCK_TICK, "碰到腿，缩了一下")
        "tail" -> Reaction("call:sway", nodThenSway, "surprised", .90,
            HapticFeedbackConstants.CLOCK_TICK, "碰到尾巴，尾巴一甩")
        else -> null
    }

    private fun double(region: String): Reaction? = when (region) {
        // Deliberately falls back to the single-tap nod: a double tap has already fired the first tap's
        // reaction, so the pose toggle lives on hold/hair instead (owner 2026-11-16: 双击必然先触发单点).
        "hair" -> Reaction("call:nod", swayThenNod, "happy", .90,
            HapticFeedbackConstants.KEYBOARD_TAP, "连着摸头，点头")
        "face" -> Reaction("call:nod", swayThenNod, "shy", .92,
            HapticFeedbackConstants.KEYBOARD_TAP, "连戳两下脸，害羞")
        "ears" -> Reaction("call:sway", nodThenSway, "shy", .92,
            HapticFeedbackConstants.KEYBOARD_TAP, "连着碰耳朵，晃头害羞")
        "chest" -> Reaction("call:shake", swayThenNod, "shy", .92,
            HapticFeedbackConstants.KEYBOARD_TAP, "连着碰胸口，摆手")
        "waist" -> Reaction("call:sway", nodThenSway, "happy", .90,
            HapticFeedbackConstants.KEYBOARD_TAP, "连挠两下腰，笑着扭开")
        "skirt" -> Reaction("call:shake", listOf("call:bow"), "angry", .94,
            HapticFeedbackConstants.KEYBOARD_TAP, "连着碰裙摆，摇头")
        "legs" -> Reaction("call:bow", swayThenNod, "angry", .92,
            HapticFeedbackConstants.KEYBOARD_TAP, "连着碰腿，低头生气")
        "tail" -> Reaction("call:shake", listOf("call:bow"), "angry", .92,
            HapticFeedbackConstants.KEYBOARD_TAP, "连碰两下尾巴，甩尾摇头")
        else -> null
    }

    private fun hold(region: String): Reaction? = when (region) {
        // 2026-11-23 owner: 手部动作不要了 —— 原来 按住头顶=举手 / 脸=竖手指 / 腰=狗尾草
        // 都是手部姿势，全部回退成身体动作（点头/摇摆/欠身）。
        "hair" -> Reaction("call:nod", listOf("call:sway", "call:bow"), "happy", .90,
            HapticFeedbackConstants.LONG_PRESS, "按住头顶，点点头")
        "face" -> Reaction("call:sway", listOf("call:bow"), "shy", .90,
            HapticFeedbackConstants.LONG_PRESS, "按住脸，害羞扭开")
        "ears" -> Reaction("call:shake", listOf("call:bow"), "shy", .92,
            HapticFeedbackConstants.LONG_PRESS, "揪着耳朵，摇摇头")
        "chest" -> Reaction("call:sway", listOf("call:bow"), "shy", .88,
            HapticFeedbackConstants.LONG_PRESS, "被按住胸口，害羞")
        "waist" -> Reaction("call:sway", listOf("call:bow"), "happy", .90,
            HapticFeedbackConstants.LONG_PRESS, "按住腰，笑着扭开")
        "skirt" -> Reaction("call:bow", swayThenNod, "angry", .92,
            HapticFeedbackConstants.LONG_PRESS, "按住裙摆，生气")
        "legs" -> Reaction("call:sway", nodThenSway, "angry", .92,
            HapticFeedbackConstants.LONG_PRESS, "按住腿，甩开")
        // Owner 2026-11-20: C 只挂在触屏互动 —— the author's own 13s `脱捕` performance runs here only.
        "tail" -> Reaction("touch:tuobo", listOf("call:sway"), "surprised", .92,
            HapticFeedbackConstants.LONG_PRESS, "抓住尾巴不放，她挣脱了一下")
        else -> null
    }
}

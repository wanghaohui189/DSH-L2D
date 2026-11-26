package com.prts.client.audio

/** Sentence-level pipeline policy, not native token/PCM streaming. */
internal object LocalSpeechText {
    /** One spoken chunk plus the motion markers the model wrote right before it.
     *  Owner 2026-11-17: 「动作要跟着她说的话来」—— the model tags each clause, the
     *  router binds the tag to the chunk that follows it, the tag never reaches TTS. */
    data class Slice(val text: String, val gestures: List<String> = emptyList())

    /** Gestures this rig can actually perform; an unknown tag is dropped instead of
     *  blocking the valid one behind it in the same sentence.
     *  2026-11-23 owner: 手部动作不要了 —— raise-hand / lower-hand / wave / beckon / reach /
     *  shyarm 不再算可执行动作，收到这些旧标记只剥掉、不绑定到任何一片。 */
    private val supportedGestures = setOf(
        "nod", "shake", "bow", "sway",
        "tilt", "nod-twice", "shiver", "look-around", "lean-in", "look-up",
        "shy",
    )

    private val hidden = Regex("<(think|analysis|tool_call|tool_result|function_call|function_result)\\b[^>]*>.*?(?:</\\1\\s*>|$)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val controls = Regex("\\[(?:neutral|happy|sad|angry|surprised|thinking|shy|gesture:[a-z][a-z_-]{0,30}|face:[a-z][a-z_-]{0,20})]", RegexOption.IGNORE_CASE)
    private val gestureMarker = Regex("^\\[gesture:([a-z][a-z_-]{0,30})]", RegexOption.IGNORE_CASE)
    private val expressions = Regex("^\\[(neutral|happy|sad|angry|surprised|thinking|shy)]", RegexOption.IGNORE_CASE)

    /** Backend typed tool filtering is primary; retain a bounded local guard. */
    fun forSpeech(text: String): String = controls.replace(hidden.replace(text, ""), "")

    /** 2026-11-21 主人：「台词的方括号控制码显示出来了」（`[happy]` / `[gesture:nod]`）。
     *  字幕/气泡是**第二个**文本消费者：它和 TTS 用的不是同一份字符串。
     *  只剥控制码（`controls` 白名单：情绪 / `[gesture:*]` / `[face:*]`）与隐藏块，
     *  **保留（动作描写）和正文**——括号由 forSpeechSpoken 单独负责，这里不碰。
     *  动作解析不读这个函数：splitWithControls 仍然读原文，标记不会因为字幕变干净而失效。 */
    fun forDisplay(text: String): String =
        controls.replace(hidden.replace(text, ""), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()

    /** 括号里的动作描写只上字幕、不朗读（主人 2026-11-20：「括号里面的内容别删，但别说出来」）。
     *  字幕仍用原文，只有交给 TTS 的这一份剥括号；剥完没有可念的字就返回空串，
     *  上层会跳过这一片（不再合成），绝不回退成念括号。
     *  2026-11-23 主人拍板：「表情的都不念」。颜文字括号外的残渣（`و✧`、`♪` 这类）
     *  以前能过括号规则、被送去合成：火山返回空、edge 也空 → 这一片哑，还会毒化整轮。
     *  所以剥完括号再剔掉表情/颜文字字符，并要求真含一个中日英数字才念。
     *  **表情碎片不送合成；括号规则不变。** */
    private val parenthetical = Regex("[（(][^（）()]{0,80}[）)]")
    /** 表情/颜文字字符：符号类（So/Sk）、组合记号（Mn/Me）、格式符（Cf）+ FE0F 变体选择符 / ZWJ。 */
    private val emojiLike = Regex("[\\p{So}\\p{Sk}\\p{Mn}\\p{Me}\\p{Cf}\\uFE0F\\u200D]+")
    /** 可念内容：中日文（Han/Hiragana/Katakana）、拉丁字母、阿拉伯数字。只剩符号的碎片不算。 */
    private val speakable = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsLatin}\\p{Nd}]")
    fun forSpeechSpoken(text: String): String {
        val base = forSpeech(text)
        val out = emojiLike.replace(parenthetical.replace(base, ""), " ")
            .replace(Regex("[ \t]{2,}"), " ").trim()
        return if (speakable.containsMatchIn(out)) out else ""
    }

    /** Short first CN clause, then longer chunks to avoid one native run per comma.
     * JA keeps the original 60-code-point ceiling so new small cuts do not split
     * Kanji compounds before offline pronunciation. No surrogate pair is cut.
     */
    fun split(text: String, maximum: Int = 60, firstMaximum: Int = 28, eagerFirst: Boolean = true): List<String> =
        splitRanges(text, maximum, firstMaximum, eagerFirst)
            .map { text.substring(it.first, it.last + 1).trim() }
            .filter { it.isNotEmpty() }

    /** Split the reply body while lifting `[gesture:x]` / `[emotion]` markers out of it.
     * A marker binds to the chunk that starts after it, so "[gesture:bow]谢谢你帮我"
     * plays 微微倾身 on the chunk that actually says 谢谢 — not on the previous line. */
    fun splitWithControls(text: String, maximum: Int = 60, firstMaximum: Int = 28,
                          eagerFirst: Boolean = true): List<Slice> {
        require(maximum > 0 && firstMaximum > 0)
        val clean = StringBuilder()
        val gestures = ArrayList<Pair<Int, String>>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '[') { clean.append(text[index]); index++; continue }
            val tail = text.substring(index, minOf(text.length, index + 40))
            val gesture = gestureMarker.find(tail)
            if (gesture != null) {
                val name = gesture.groupValues[1].lowercase()
                if (name in supportedGestures) gestures.add(clean.length to name)
                index += gesture.value.length
                continue
            }
            val expression = expressions.find(tail)
            if (expression != null) { index += expression.value.length; continue }
            clean.append('[')
            index++
        }
        val body = clean.toString()
        var next = 0
        return splitRanges(body, maximum, firstMaximum, eagerFirst).map { range ->
            val attached = ArrayList<String>()
            // A marker takes effect from where it stands: one written at the start of a
            // sentence rides that sentence, one written mid-sentence rides the same chunk.
            while (next < gestures.size && gestures[next].first <= range.last) {
                attached.add(gestures[next].second)
                next++
            }
            Slice(body.substring(range.first, range.last + 1).trim(), attached)
        }.filter { it.text.isNotEmpty() }
    }

    /** Character ranges of every chunk; shared so split()/splitWithControls() stay in step.
     *  2026-11-20: never cut inside （…）/(…) —— 切片一旦把括号切成两半，朗读侧就剥不干净，
     *  她就会把「（头微微一低，」这种半截动作描写念出来。长括号最多多带上几十个字。 */
    private fun splitRanges(text: String, maximum: Int, firstMaximum: Int, eagerFirst: Boolean): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        var count = 0
        var offset = 0
        var depth = 0
        fun flush(endExclusive: Int) {
            if (endExclusive > start) result.add(start until endExclusive)
            start = endExclusive; count = 0
        }
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (cp == 0xff08 || cp == 0x28) depth++
            else if (cp == 0xff09 || cp == 0x29) { if (depth > 0) depth-- }
            count++
            offset += Character.charCount(cp)
            val first = eagerFirst && result.isEmpty()
            val limit = if (first) minOf(firstMaximum, maximum) else maximum
            val inParen = depth > 0
            val strong = !inParen && (cp in listOf(0x3002, 0xff01, 0xff1f, 0xff1b, 33, 63, 59, 10) ||
                (cp == 46 && !(offset < text.length && text[offset].isDigit() && offset - start > 1 && text[offset - 2].isDigit())))
            val firstPause = !inParen && first && count >= 8 && cp in listOf(0xff0c, 0x3001, 44)
            val next = if (offset < text.length) text.codePointAt(offset) else -1
            val insideAsciiWord = cp in 65..90 || cp in 97..122
            val nextAsciiWord = next in 65..90 || next in 97..122
            val reached = !inParen && count >= limit && !(insideAsciiWord && nextAsciiWord && count < maximum)
            if (strong || firstPause || (!inParen && count >= maximum) || reached) flush(offset)
        }
        flush(text.length)
        return result
    }
}

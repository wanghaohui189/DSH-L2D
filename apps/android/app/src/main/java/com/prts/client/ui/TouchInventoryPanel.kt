package com.prts.client.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.prts.client.R
import com.prts.client.live2d.Live2DView

/**
 * Fills the 「触屏互动」 page (`activity_touch_guide.xml`) with the current touch table.
 *
 * Pure view code: [CharacterTouchPolicy.inventory] owns the rows, [Live2DView.gestureTitle] /
 * [Live2DView.faceTitle] own the Chinese names, and the `touch-voices` manifest owns her line.
 * One card per body part: [节奏 · 动作/表情 · 说明「她那句话」].
 */
internal object TouchInventoryPanel {
    fun render(context: Context, target: LinearLayout,
               rows: List<CharacterTouchPolicy.Row>, lines: Map<String, String>) {
        target.removeAllViews()
        val strength = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * strength).toInt()
        val primary = ContextCompat.getColor(context, R.color.ui_text)

        // Regions keep the policy's display order; rapid taps are one global reaction.
        val byRegion = LinkedHashMap<String, MutableList<CharacterTouchPolicy.Row>>()
        for (row in rows) {
            if (row.kind == "rapid") continue
            byRegion.getOrPut(row.regionTitle) { mutableListOf() }.add(row)
        }

        for ((regionTitle, entries) in byRegion) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_glass_panel)
                setPadding(dp(13), dp(11), dp(13), dp(11))
            }
            card.addView(TextView(context).apply {
                text = regionTitle
                textSize = 13f
                setTextColor(primary)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-1, -2))
            for (entry in entries) {
                card.addView(TextView(context).apply {
                    text = kindLine(context, entry, lines)
                    textSize = 10.5f
                    setTextColor(ContextCompat.getColor(context, R.color.ui_text_secondary))
                    setLineSpacing(dp(2).toFloat(), 1f)
                    contentDescription = "${regionTitle}，${entry.kindTitle}，${entry.label}"
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }

        rows.firstOrNull { it.kind == "rapid" }?.let { rapid ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_glass_panel)
                setPadding(dp(13), dp(11), dp(13), dp(11))
            }
            card.addView(TextView(context).apply {
                text = "连戳三下"
                textSize = 13f
                setTextColor(primary)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(-1, -2))
            card.addView(TextView(context).apply {
                text = kindLine(context, rapid.copy(kindTitle = "连戳", regionTitle = ""), lines)
                textSize = 10.5f
                setTextColor(ContextCompat.getColor(context, R.color.ui_text_secondary))
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            target.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    /** `按住 · 举手（保持）· 开心 · 按住头顶，举手；再按一下放下「嗯…被摸头了，我举手」`
     *  Her line is the only bold part, so the page reads as "动作/表情 → 她会说什么". */
    private fun kindLine(context: Context, row: CharacterTouchPolicy.Row,
                         lines: Map<String, String>): CharSequence {
        val text = SpannableStringBuilder()
        text.append(row.kindTitle)
        val action = action(row)
        if (action.isNotBlank()) text.append(" · ").append(action)
        if (row.label.isNotBlank()) text.append(" · ").append(row.label)
        val quoteStart = text.length
        text.append("「").append(spoken(row, lines)).append("」")
        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.ui_text)),
            quoteStart, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.BOLD), quoteStart, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        return text
    }

    /**
     * Only a held pose / the touch performance actually moves her body: taps and doubles answer
     * with the face plus the local line, while body motion follows the sentence she speaks
     * (owner 2026-11-16 「动作按照说的话来做」). Saying "点头" for a tap would be a false claim.
     */
    private fun action(row: CharacterTouchPolicy.Row): String {
        val gesture = row.gesture
            .takeIf { it.startsWith("pose:") || it.startsWith("touch:") }
            ?.let { Live2DView.gestureTitle(it) }
        return listOfNotNull(gesture, Live2DView.faceTitle(row.expression)).joinToString(" · ")
    }

    /** First recorded take: single taps alternate, and the page footer says so instead of
     *  doubling every row's length with both variants. */
    private fun spoken(row: CharacterTouchPolicy.Row, lines: Map<String, String>): String =
        row.voiceClips.firstNotNullOfOrNull { lines[it] } ?: "（无语音）"
}

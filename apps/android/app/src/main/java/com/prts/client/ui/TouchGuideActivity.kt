package com.prts.client.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.prts.client.databinding.ActivityTouchGuideBinding
import org.json.JSONObject

/**
 * 「触屏互动」说明页：摸哪个部位、她会做什么动作/表情、说什么话。
 *
 * 入口：通话页宠物模式下的「触屏互动 · 看清单」按钮（和「权限」页同一种做法——独立页面，
 * 不在通话页上盖层，所以不会一直挡着她）。数据来自 [CharacterTouchPolicy.inventory] 和
 * 预渲染语音清单 `touch-voices/`，只读，不改任何状态。
 */
class TouchGuideActivity : AppCompatActivity() {
    private lateinit var b: ActivityTouchGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTouchGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnTouchGuideBack.setOnClickListener { finish() }
        val rows = CharacterTouchPolicy.inventory()
        TouchInventoryPanel.render(this, b.touchGuideRows, rows, spokenLines())
        b.touchGuideCount.text = "${rows.size} 种反应"
    }

    /** 「她那句话」的原文：和 TouchVoicePlayer 读同一份预渲染清单，读不到就只列动作/表情。 */
    private fun spokenLines(): Map<String, String> = runCatching {
        val text = assets.open("touch-voices/touch-voices.manifest.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val lines = JSONObject(text).optJSONObject("lines") ?: JSONObject()
        val table = LinkedHashMap<String, String>()
        lines.keys().forEach { key ->
            lines.optString(key).takeIf { it.isNotBlank() }?.let { line -> table[key] = line }
        }
        table
    }.getOrElse { emptyMap() }
}

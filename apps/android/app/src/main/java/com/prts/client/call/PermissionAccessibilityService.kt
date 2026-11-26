package com.prts.client.call

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.prts.client.audio.DiagLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「手机控制」（control.phone）的执行端：按指令点按 / 滑动 / 返回 / 回桌面，以及把当前
 * 窗口上的文字报给服务端选目标。
 *
 * 服务端（AstrBot 插件）只在权限页把 `control.phone` 打开时才会发指令；这个服务本身
 * 不判断权限（它拿不到权限状态），只在被调用时执行。没开无障碍服务时什么都不会做。
 *
 * 实现要点：
 * - 控件优先：先按文字找节点，`performAction(ACTION_CLICK)` 点它；找不到可点祖先才退回
 *   坐标手势（按节点 bounds 中心）。
 * - 只有坐标的指令（tap/swipe）走 [dispatchGesture]。
 * - 手势是异步的，统一用 latch 等结果，免得「以为点了其实没点」。
 */
class PermissionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PRTS-Permission"
        private const val MAX_NODES = 900

        @Volatile
        var instance: PermissionAccessibilityService? = null
            private set

        fun connected(): Boolean = instance != null

        /** 2026-11-19（0.0.77/153）：当前前台窗口的包名，给「打开 App 后核对」用。 */
        fun activePackageName(): String? = instance?.currentPackageName()
    }

    /** 读当前前台窗口包名；读不到（无障碍关/界面受保护）返回 null。 */
    fun currentPackageName(): String? = try {
        activeRoot()?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
        DiagLog.write("phone-control.log", "service_connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        DiagLog.write("phone-control.log", "service_destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要读事件流；指令来时现读 rootInActiveWindow。
    }

    override fun onInterrupt() {
        Log.i(TAG, "accessibility service interrupted")
        DiagLog.write("phone-control.log", "interrupted")
    }

    // ---------------------------------------------------------------- 指令

    fun clickText(query: String): Pair<Boolean, String> {
        val want = query.trim()
        if (want.isEmpty()) return false to "没给要点的文字"
        val root = activeRoot() ?: return false to "读不到当前界面"
        val exact = findMatch(collect(root), want)
        if (exact == null) {
            root.recycle()
            return false to "屏幕上没找到「$want」"
        }
        val result = clickNode(exact)
        root.recycle()
        if (result.first) DiagLog.write("phone-control.log", "click_text ok label=${labelOf(exact)}")
        return result
    }

    /**
     * 2026-11-18：滚动查找。长界面（设置列表、折叠线以下的内容）里目标不在首屏时，
     * 一屏一屏往下滚再重查，最多 [screens] 屏 / [budgetMs] 毫秒，找到就点。
     * 依据 Android 文档：ACTION_SCROLL_FORWARD 是「向前滚动节点内容」，只有实现了滚动
     * （isScrollable / 把该 action 加进 actionList）的节点才接受它；不接受的再退回手势滑。
     */
    fun scrollFindText(query: String, screens: Int = 6, budgetMs: Long = 9000L): Pair<Boolean, String> {
        val want = query.trim()
        if (want.isEmpty()) return false to "没给要滚着找的文字"
        val maxScreens = screens.coerceIn(1, 10)
        val deadline = android.os.SystemClock.elapsedRealtime() + budgetMs.coerceIn(2000L, 20000L)
        var scrolled = 0
        var previousSignature: String? = null
        for (round in 0 until maxScreens) {
            val root = activeRoot() ?: return false to "读不到当前界面"
            val nodes = collect(root)
            val exact = findMatch(nodes, want)
            if (exact != null) {
                val result = clickNode(exact)
                val label = labelOf(exact)
                root.recycle()
                DiagLog.write("phone-control.log", "scroll_find ok=$label screens=$scrolled ok=${result.first}")
                if (result.first) return true to (result.second + "（往下滚 $scrolled 屏找到）")
                return result
            }
            val signature = signatureOf(nodes)
            val canScroll = nodes.any { it.isScrollable }
            root.recycle()
            if (previousSignature != null && signature == previousSignature) {
                return false to "滚到头了也没找到「$want」（滚了 $scrolled 屏）"
            }
            if (!canScroll && scrolled > 0) return false to "这个界面滚不动了，没找到「$want」"
            previousSignature = signature
            if (!scrollForward()) return false to "这个界面滚不动，找不到「$want」"
            scrolled += 1
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                return false to "滚了 $scrolled 屏还没找到「$want」，先不找了"
            }
            try { Thread.sleep(320L) } catch (_: InterruptedException) { }
        }
        return false to "滚了 $scrolled 屏没找到「$want」"
    }

    /**
     * 2026-11-18：把文字写进可编辑控件。
     * Android 文档：ACTION_SET_TEXT 要求节点 isEditable()==true，文本通过
     * ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE 传；空 CharSequence 是清空，光标移到末尾。
     * 自绘 / 部分 WebView 控件不实现它（performAction 返回 false），这时如实报错不假装成功。
     */
    fun setText(query: String, value: String): Pair<Boolean, String> {
        if (value.isEmpty()) return false to "没给要输入的内容"
        val want = query.trim()
        val root = activeRoot() ?: return false to "读不到当前界面"
        val nodes = collect(root)
        val editables = nodes.filter { looksEditable(it) }
        var candidate: AccessibilityNodeInfo? = null
        var fallbackClick: AccessibilityNodeInfo? = null
        var matchCount = 0
        if (want.isEmpty()) {
            candidate = editables.firstOrNull { it.isFocused } ?: editables.firstOrNull()
        } else {
            val matches = nodes.filter { node ->
                val label = labelOf(node)
                val hint = hintOf(node)
                (label.isNotEmpty() && (label == want || label.contains(want, ignoreCase = true))) ||
                    (hint.isNotEmpty() && (hint == want || hint.contains(want, ignoreCase = true)))
            }
            matchCount = matches.size
            candidate = matches.firstNotNullOfOrNull { nearestEditable(it) }
            if (candidate == null) {
                // 2026-11-18 真机（vivo 设置首页 vigour_search_edit）：「搜索设置项」这几个字画在旁边的
                // TextView 上，真正的 EditText 文本是空的 —— 按文字找不到输入框，就按「匹配项的中心点
                // 落在哪个输入框里」认，再退到当前聚焦的那个。
                val center = matches.firstOrNull()?.let {
                    val rect = Rect()
                    it.getBoundsInScreen(rect)
                    Pair(rect.centerX(), rect.centerY())
                }
                if (center != null) {
                    candidate = editables.firstOrNull {
                        val rect = Rect()
                        it.getBoundsInScreen(rect)
                        rect.contains(center.first, center.second)
                    }
                }
                if (candidate == null) candidate = editables.firstOrNull { it.isFocused }
                // 找到了但没有可编辑的：有些搜索栏是「点开才变成真输入框」，先点它一下，再重找。
                if (candidate == null) fallbackClick = matches.firstOrNull()
            }
        }
        val clicked = fallbackClick
        if (candidate == null && clicked != null) clickNode(clicked)
        root.recycle()
        var secondRoot: AccessibilityNodeInfo? = null
        if (candidate == null && clicked != null) {
            try { Thread.sleep(500L) } catch (_: InterruptedException) { }
            secondRoot = activeRoot()
            if (secondRoot != null) {
                val secondNodes = collect(secondRoot)
                candidate = secondNodes.firstOrNull { looksEditable(it) && it.isFocused }
                    ?: secondNodes.firstOrNull { looksEditable(it) }
            }
        }
        val target = candidate
        if (target == null) {
            secondRoot?.recycle()
            DiagLog.write("phone-control.log", "set_text not_found query=$want editables=${editables.size} matches=$matchCount")
            val why = if (want.isEmpty()) "当前界面没有可输入的框" else "没找到「$want」这个输入框"
            return false to why
        }
        val label = labelOf(target).ifEmpty { hintOf(target) }.ifEmpty { "输入框" }
        // 有的控件要先拿到焦点才收 ACTION_SET_TEXT；先 FOCUS，不行再点一下（点完会重弹键盘）。
        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!target.isFocused) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                try { Thread.sleep(140L) } catch (_: InterruptedException) { }
            }
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        var readback = ""
        if (ok) {
            try { target.refresh() } catch (_: Exception) { }
            readback = (target.text ?: "").toString()
        }
        secondRoot?.recycle()
        DiagLog.write("phone-control.log",
            "set_text ok=$ok label=$label len=${value.length} readback=${readback.take(20)} editables=${editables.size} matches=$matchCount")
        return when {
            ok && readback.contains(value) -> true to "在「$label」里输入了「$value」"
            // 写进去了但控件不把内容暴露给无障碍（密码框/自绘输入框）：只回执「已发出」，别谎称回读成功。
            ok && readback.isEmpty() -> true to "往「$label」里输入了（这个控件回读不到内容）"
            ok -> false to "输入了但回读不一致（现在是「${readback.take(20)}」）"
            else -> false to "「$label」不接收输入（自绘控件不支持 ACTION_SET_TEXT）"
        }
    }

    fun tap(x: Int, y: Int): Boolean = gesture(240L) { builder ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean = gesture(ms.coerceIn(80L, 1200L)) { builder ->
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        builder.addStroke(GestureDescription.StrokeDescription(path, 0L, ms.coerceIn(80L, 1200L)))
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 2026-11-18：整屏翻页（往下/往上）。不依赖有没有可滚动节点，直接按屏幕高度比做手势。
     *  「往下滑」= 内容往上走一屏（手指从下往上拖），与 Android 的滚动方向一致。 */
    fun scrollPage(down: Boolean): Pair<Boolean, String> {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2
        val top = (m.heightPixels * 0.30f).toInt()
        val bottom = (m.heightPixels * 0.70f).toInt()
        val ok = if (down) swipe(x, bottom, x, top, 320L) else swipe(x, top, x, bottom, 320L)
        return if (ok) true to (if (down) "往下翻了一屏" else "往上翻了一屏")
        else false to "滑动被系统拦了"
    }

    /** 当前窗口上的文字 + 坐标，给服务端选目标（最多 [limit] 条，去重）。`*`=可点，`~`=可输入。 */
    fun visibleNodes(limit: Int = 30): Pair<Boolean, String> {
        val root = activeRoot() ?: return false to "读不到当前界面"
        val lines = ArrayList<String>()
        val seen = HashSet<String>()
        for (node in collect(root)) {
            val text = labelOf(node)
            if (text.isEmpty() || text.length > 40) continue
            if (!seen.add(text)) continue
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) continue
            // * = 可点；~ = 可输入（配合 settext 用）
            val mark = if (looksEditable(node)) "~" else if (node.isClickable) "*" else ""
            lines.add("$mark$text@${rect.centerX()},${rect.centerY()}")
            if (lines.size >= limit) break
        }
        root.recycle()
        if (lines.isEmpty()) return false to "当前界面上没读到文字"
        return true to lines.joinToString(" | ")
    }

    // ---------------------------------------------------------------- 内部

    private fun activeRoot(): AccessibilityNodeInfo? {
        // 后台线程里调 rootInActiveWindow 在部分 ROM 上会拿到 null，先垫到主线程取一次。
        val latch = CountDownLatch(1)
        var root: AccessibilityNodeInfo? = null
        main.post {
            root = try { rootInActiveWindow } catch (_: Exception) { null }
            latch.countDown()
        }
        try { latch.await(1500L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        val got = root
        if (got != null) return got
        repeat(2) {
            val retry = try { rootInActiveWindow } catch (_: Exception) { null }
            if (retry != null) return retry
            try { Thread.sleep(120L) } catch (_: InterruptedException) { }
        }
        return null
    }

    private fun labelOf(node: AccessibilityNodeInfo): String {
        val text = (node.text ?: "").toString().trim()
        if (text.isNotEmpty()) return text
        val desc = (node.contentDescription ?: "").toString().trim()
        if (desc.isNotEmpty()) return desc
        return ""
    }

    /** 输入框的提示语（hintText，API 26+）。搜索框在没内容时只有它，不读它找不到框。 */
    private fun hintOf(node: AccessibilityNodeInfo): String {
        val hint = try { node.hintText } catch (_: Exception) { null } ?: return ""
        return hint.toString().trim()
    }

    /** 按文字匹配节点：先完全相等，再包含（忽略大小写）。 */
    private fun findMatch(nodes: List<AccessibilityNodeInfo>, want: String): AccessibilityNodeInfo? {
        val matches = nodes.filter { node ->
            val text = labelOf(node)
            text.isNotEmpty() && (text == want || text.contains(want, ignoreCase = true))
        }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { labelOf(it) == want } ?: matches.first()
    }

    /** 节点或最近的祖先里第一个可编辑的（Text 在可点行里、EditText 外面套一层容器都常见）。 */
    private fun nearestEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var guard = 0
        while (current != null && guard < 8) {
            if (looksEditable(current)) return current
            current = current.parent
            guard += 1
        }
        return null
    }

    /**
     * 可编辑判断。`isEditable` 是文档口径，但实测有的 ROM 把 EditText 子类报成 false
     * （vivo 设置首页的 vigour_search_edit 就是），所以类名是 EditText 的也给它一次机会 ——
     * 真不接收 ACTION_SET_TEXT 的话 performAction 会返回 false，回执照样如实说失败。
     */
    private fun looksEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || (node.className?.toString()?.contains("EditText") == true)

    /** 点一个节点：先 ACTION_CLICK，点不动再按 bounds 中心发坐标手势。 */
    private fun clickNode(exact: AccessibilityNodeInfo): Pair<Boolean, String> {
        val label = labelOf(exact)
        var target: AccessibilityNodeInfo? = exact
        var guard = 0
        while (target != null && guard < 8) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true to "点了「$label」"
            }
            target = target.parent
            guard += 1
        }
        val rect = Rect()
        exact.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false to "「$label」点不动"
        val ok = tap(rect.centerX(), rect.centerY())
        return if (ok) true to "点了「$label」" else false to "点击被系统拦了"
    }

    /** 当前屏上的文字指纹：用来判断「滚了一屏但内容没变」= 已经到底。 */
    private fun signatureOf(nodes: List<AccessibilityNodeInfo>): String {
        val parts = ArrayList<String>(12)
        for (node in nodes) {
            val text = labelOf(node)
            if (text.isEmpty()) continue
            parts.add(text.take(16))
            if (parts.size >= 12) break
        }
        return parts.size.toString() + ":" + parts.joinToString("|")
    }

    /** 往下滚一屏：优先 ACTION_SCROLL_FORWARD，没节点接受就退回手势滑。 */
    private fun scrollForward(): Boolean {
        val root = activeRoot() ?: return false
        val nodes = collect(root)
        var scrolled = false
        for (node in nodes) {
            if (!node.isScrollable) continue
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                scrolled = true
                break
            }
        }
        root.recycle()
        if (scrolled) return true
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return false
        val x = width / 2
        return swipe(x, (height * 0.78f).toInt(), x, (height * 0.32f).toInt(), 300L)
    }

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_NODES) {
            val node = queue.removeFirst()
            guard += 1
            out.add(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                queue.add(child)
            }
        }
        return out
    }

    private fun gesture(durationMs: Long, build: (GestureDescription.Builder) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val description = GestureDescription.Builder()
        build(description)
        val stroke = try { description.build() } catch (_: Exception) { null } ?: return false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        return try {
            if (!dispatchGesture(stroke, callback, main)) false
            else {
                latch.await(durationMs + 1200L, TimeUnit.MILLISECONDS)
                ok
            }
        } catch (error: Exception) {
            Log.w(TAG, "gesture failed", error)
            DiagLog.write("phone-control.log", "gesture_error ${error.javaClass.simpleName}")
            false
        }
    }
}

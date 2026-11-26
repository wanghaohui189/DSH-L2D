package com.prts.client.call

/**
 * 内存里的通知进纸器（M4.5：observe.notifications 的「眼睛」）。
 *
 * 只存最近 20 条、只存标题和正文前 80 字、**不落盘**——进程一死就没了。
 * 上传与否由 PresenceService 决定，服务端还要再过一次 `observe.notifications` 权限门。
 */
object NotificationFeed {
    data class Item(val at: Long, val pkg: String, val title: String, val text: String)

    private val items = ArrayDeque<Item>()
    private const val CAP = 20

    @Synchronized
    fun add(pkg: String, title: String, text: String) {
        val t = title.trim().take(40)
        val body = text.trim().replace("\n", " ").take(80)
        if (t.isEmpty() && body.isEmpty()) return
        items.addLast(Item(System.currentTimeMillis(), pkg.take(40), t, body))
        while (items.size > CAP) items.removeFirst()
    }

    /** since=0 表示全部；返回时间升序。 */
    @Synchronized
    fun since(atMillis: Long): List<Item> = items.filter { it.at > atMillis }
}

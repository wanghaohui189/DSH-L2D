package com.prts.client.call

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知使用权：M4.5 起不再只是「状态入口」——observe.notifications 打开后会真的读标题。
 *
 * 边界（写死，不靠配置）：只进内存、只留 20 条、只取标题+正文前 80 字、不落盘。
 * 服务端还有一道 `observe.notifications` 权限门，两边都开才用。
 */
class PermissionNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i("PRTS-Permission", "notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.i("PRTS-Permission", "notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // 自己的常驻/来电通知不进池子，否则她会在通话里读到「小鲸鱼在等你」。
        if (n.packageName == packageName) return
        val extras = n.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        NotificationFeed.add(n.packageName, title, text)
    }
}

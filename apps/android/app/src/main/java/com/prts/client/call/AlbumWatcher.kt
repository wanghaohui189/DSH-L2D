package com.prts.client.call

import android.content.Context
import android.util.Log
import com.prts.client.ui.Prefs

/**
 * 通话里的相册观察（observe.album 的手机端一半）。
 *
 * 只在通话页活着时跑：进通话先看一眼「最近一张」（她在通话里能知道你刚拍了什么），
 * 之后每 45 秒看一眼有没有新照片，有新才上传缩略图；没权限就整条静默。
 * 不监听相册变化（省电），不读其它照片，不上传原图。
 */
class AlbumWatcher(
    private val ctx: Context,
    private val send: (jpeg: ByteArray, at: Long, name: String) -> Boolean,
) {
    companion object {
        private const val TAG = "PRTS-AlbumWatch"
        private const val TICK_MS = 45_000L
    }

    private var thread: Thread? = null

    @Volatile private var alive = false
    @Volatile private var lastId = 0L

    fun start() {
        if (alive) return
        if (!AlbumFeed.hasAccess(ctx)) {
            Log.i(TAG, "没有相册权限，本次通话不观察相册")
            return
        }
        if (!Prefs.grantCached(ctx, "observe.album")) {
            Log.i(TAG, "服务端 observe.album 关着（按权限页上次快照），不上传相册")
            return
        }
        alive = true
        thread = Thread({ loop() }, "PRTS-AlbumWatch").apply { isDaemon = true; start() }
    }

    fun stop() {
        alive = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (alive) {
            try {
                tick()
            } catch (error: Exception) {
                Log.i(TAG, "tick failed ${error.javaClass.simpleName}: ${error.message}")
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.i(TAG, "album watcher exited")
    }

    private fun tick() {
        if (!Prefs.grantCached(ctx, "observe.album")) {
            Log.i(TAG, "服务端 observe.album 关着，跳过这一轮")
            return
        }
        val latest = AlbumFeed.latest(ctx) ?: return
        if (latest.id == lastId) return
        val jpeg = AlbumFeed.thumbnail(ctx, latest.id) ?: return
        val ok = send(jpeg, latest.at, latest.name)
        if (ok) {
            lastId = latest.id
            Log.i(TAG, "相册上报最近一张 id=${latest.id} ${jpeg.size / 1024}KB")
        } else {
            Log.i(TAG, "相册上报失败（socket 未就绪），下次再试")
        }
    }
}

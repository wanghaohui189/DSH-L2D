package com.prts.client.ui

import android.content.Context
import com.prts.client.live2d.CanvasQuality
import com.prts.client.live2d.CanvasRenderPolicy

object Prefs {
    private const val F = "prts"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)

    fun get(ctx: Context): Pair<String, String> {
        val server = sp(ctx).getString("server", "").orEmpty()
        val config = sp(ctx).getString("config", "default")!!
        return server to config
    }

    fun set(ctx: Context, server: String, config: String) {
        sp(ctx).edit().putString("server", server).putString("config", config).apply()
    }

    fun getConfig(ctx: Context): String = sp(ctx).getString("config", "default")!!

    fun getSelectedBg(ctx: Context): String =
        sp(ctx).getString("bg", "builtin:aurora")!!

    fun setSelectedBg(ctx: Context, id: String) {
        sp(ctx).edit().putString("bg", id).apply()
    }

    fun getCanvasQuality(ctx: Context): CanvasQuality =
        CanvasRenderPolicy.fromSaved(sp(ctx).getString("live2d_canvas_quality", null))

    fun setCanvasQuality(ctx: Context, quality: CanvasQuality) {
        sp(ctx).edit().putString("live2d_canvas_quality", quality.key).apply()
    }

    /**
     * 服务端权限快照的手机侧缓存（observe.album / observe.screen）。
     *
     * 为什么要缓存：相册缩略图一旦上传就已经离开手机，服务端再「当没见过」也晚了。
     * 所以手机端先看上一轮从权限页拿到的服务端快照，关着就根本不上传；默认 false。
     */
    fun grantCached(ctx: Context, scope: String): Boolean =
        sp(ctx).getBoolean("perm_" + scope, false)

    fun setGrantCached(ctx: Context, scope: String, value: Boolean) {
        sp(ctx).edit().putBoolean("perm_" + scope, value).apply()
    }
}

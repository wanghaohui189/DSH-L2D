package com.prts.client.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 通话时把手机自己的位置报给中继（天气类问题用它，不写死城市）。
 *
 * 只用系统 LocationManager，不依赖 Google Play Services / FusedLocation；
 * 拿不到就返回 null，中继会退回用出口 IP 反查的城市。
 */
object LocationReporter {
    private const val LOCATION_TIMEOUT_MS = 5_000L

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 最近一次已知位置；GPS / 网络 / 被动都看，取最新的。 */
    fun lastKnown(ctx: Context): Triple<Double, Double, Float>? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        val providers = arrayOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in providers) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            } ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        val b = best ?: return null
        return Triple(b.latitude, b.longitude, if (b.hasAccuracy()) b.accuracy else 0f)
    }

    /** 主动要一次定位；有回调就先回调一次，几秒没结果就放弃。 */
    fun requestOnce(ctx: Context, onFix: (Double, Double, Float) -> Unit) {
        if (!hasPermission(ctx)) return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        lastKnown(ctx)?.let { onFix(it.first, it.second, it.third) }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    onFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
                    try {
                        lm.removeUpdates(this)
                    } catch (_: Exception) {
                    }
                }

                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            // One-shot: if the provider never delivers (indoor GPS), drop the listener instead of
            // holding it for the rest of the call.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { lm.removeUpdates(listener) }
            }, LOCATION_TIMEOUT_MS)
        } catch (_: Exception) {
        }
    }
}

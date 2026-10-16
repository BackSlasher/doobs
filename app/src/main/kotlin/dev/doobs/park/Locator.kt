package dev.doobs.park

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.doobs.logd
import dev.doobs.logw

/**
 * A single "where am I, now, well enough to find a car".
 *
 * Deliberately the platform's [LocationManager] rather than Play Services'
 * fused provider: doobs has no third-party dependencies.
 *
 * Asking once is not enough. The first fix a phone offers is usually a cached
 * coarse one — on this Pixel it came back in 20ms at ±100m, which puts the car
 * a street away. So we keep the request open, take the best fix that arrives,
 * and stop early once one is good enough to walk to.
 */
class Locator(private val context: Context) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var listener: LocationListener? = null
    private var best: Location? = null
    private var answered = false

    /**
     * Calls back on the main thread: [onImproved] each time the best fix gets
     * better, so the caller can show progress, then [onResult] exactly once.
     */
    fun locate(onImproved: (Location) -> Unit = {}, onResult: (Location?) -> Unit) {
        // Checked here as well as by the caller: this is the class that would
        // throw, and lint only credits a check it can see in the same method.
        val permitted =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            logw("asked to locate without a location permission")
            onResult(null)
            return
        }

        val manager = manager ?: run { onResult(null); return }

        val finish = { reason: String ->
            if (!answered) {
                answered = true
                stop()
                val fix = best ?: lastKnown(manager)
                logd("located ($reason): ${fix?.accuracy?.let { "±${it}m" } ?: "no fix"}")
                onResult(fix)
            }
        }

        val providers = providers(manager)
        if (providers.isEmpty()) {
            logw("no enabled location provider")
            finish("no provider")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isBetter(location, best)) {
                    best = location
                    onImproved(location)
                }
                // Good enough to walk to: stop burning the GPS.
                if (location.hasAccuracy() && location.accuracy <= GOOD_ENOUGH_METRES) {
                    finish("accurate enough")
                }
            }

            @Deprecated("Required on API < 30", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        this.listener = listener

        // Seed with whatever is already known, so a timeout still answers.
        best = lastKnown(manager)
        best?.let { onImproved(it) }

        logd("locating via ${providers.joinToString()} (seed ±${best?.accuracy ?: -1}m)")
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }.onFailure { logw("could not request updates from $provider", it) }
        }

        main.postDelayed({ finish("timed out") }, TIMEOUT_MS)
    }

    fun cancel() {
        answered = true
        stop()
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        listener?.let { l -> runCatching { manager?.removeUpdates(l) } }
        listener = null
    }

    /** Fresher wins; at similar freshness, tighter wins. */
    private fun isBetter(candidate: Location, incumbent: Location?): Boolean {
        if (incumbent == null) return true
        if (!candidate.hasAccuracy()) return false
        if (!incumbent.hasAccuracy()) return true
        if (candidate.time - incumbent.time > STALE_MS) return true
        return candidate.accuracy < incumbent.accuracy
    }

    /** Fused where it exists, then GPS, then the network — whichever are enabled. */
    private fun providers(manager: LocationManager): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun lastKnown(manager: LocationManager): Location? = runCatching {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        providers(manager).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    private companion object {
        /** Close enough that you can see the car from the pin. */
        const val GOOD_ENOUGH_METRES = 25f
        const val TIMEOUT_MS = 12_000L
        const val STALE_MS = 30_000L
    }
}

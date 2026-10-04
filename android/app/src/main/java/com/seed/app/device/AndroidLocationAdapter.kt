package com.seed.app.device

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

internal class AndroidLocationPermission(private val context: Context) : LocationPermission {
    override fun grants() = LocationGrants(
        context.checkSelfPermission(LocationCurrent.COARSE) == PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(LocationCurrent.FINE) == PackageManager.PERMISSION_GRANTED,
    )
}

/** Platform only; no last-known fallback, background service, or Google dependency. */
internal class AndroidLocationProvider(context: Context) : LocationProvider {
    private val manager = context.getSystemService(LocationManager::class.java)
    override fun enabledProviders(): List<String> = manager.getProviders(true)
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    @Suppress("DEPRECATION")
    override fun subscribe(provider: String, fix: (LocationFix) -> Unit, unavailable: () -> Unit): () -> Unit {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasAccuracy() && location.elapsedRealtimeNanos > 0) {
                    fix(LocationFix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time, location.elapsedRealtimeNanos / 1_000_000))
                }
            }
            override fun onProviderDisabled(provider: String) { unavailable() }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        catch (error: Exception) { manager.removeUpdates(listener); throw error }
        return { manager.removeUpdates(listener) }
    }
}

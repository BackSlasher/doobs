package dev.doobs.park

import android.content.Context
import android.location.Location

/**
 * Where the car is.
 *
 * This is the one thing doobs remembers. Every other command hands off to an
 * app that already owns that job, but nothing on the phone will hold "the spot
 * I just walked away from" for us, so we hold it: one spot, overwritten each
 * time, in SharedPreferences. No database, no history, nothing to migrate.
 */
class Parking(context: Context) {

    data class Spot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMetres: Float,
        val savedAt: Long,
    )

    private val prefs = context.getSharedPreferences("parking", Context.MODE_PRIVATE)

    fun save(location: Location) {
        // Stored as raw bits: SharedPreferences has no putDouble, and a float
        // loses several metres at these magnitudes — which is most of the
        // precision that makes a parking spot findable.
        prefs.edit()
            .putLong(LATITUDE, java.lang.Double.doubleToRawLongBits(location.latitude))
            .putLong(LONGITUDE, java.lang.Double.doubleToRawLongBits(location.longitude))
            .putFloat(ACCURACY, if (location.hasAccuracy()) location.accuracy else -1f)
            .putLong(SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun recall(): Spot? {
        if (!prefs.contains(LATITUDE)) return null
        return Spot(
            latitude = java.lang.Double.longBitsToDouble(prefs.getLong(LATITUDE, 0)),
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong(LONGITUDE, 0)),
            accuracyMetres = prefs.getFloat(ACCURACY, -1f),
            savedAt = prefs.getLong(SAVED_AT, 0),
        )
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val LATITUDE = "lat_bits"
        const val LONGITUDE = "lon_bits"
        const val ACCURACY = "accuracy"
        const val SAVED_AT = "saved_at"
    }
}

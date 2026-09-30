package dev.doobs

import android.util.Log

internal const val TAG = "doobs"

internal fun logd(message: String) {
    Log.d(TAG, message)
}

internal fun logw(message: String, error: Throwable? = null) {
    Log.w(TAG, message, error)
}

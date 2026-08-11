package com.notifymvp.sdk

import android.util.Log

private const val TAG = "NotifyMVP"

internal class NotifyLogger(private val enabled: Boolean) {

    fun debug(msg: String) { if (enabled) Log.d(TAG, msg) }
    fun info(msg: String)  { if (enabled) Log.i(TAG, msg) }
    fun warn(msg: String)  { Log.w(TAG, msg) }          // always log
    fun error(msg: String) { Log.e(TAG, msg) }          // always log
    fun error(msg: String, t: Throwable) { Log.e(TAG, msg, t) }
}

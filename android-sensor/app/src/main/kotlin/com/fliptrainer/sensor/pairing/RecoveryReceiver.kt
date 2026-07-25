package com.fliptrainer.sensor.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ADB-accessible recovery (plan §9 task 15).
 *
 * The target Flip5 has no working main-screen touch, so if the paired iPhone is lost, broken, or
 * reinstalled, there is no in-app way to reach an "unpair" button. This receiver is the escape
 * hatch:
 *
 * ```
 * adb shell am broadcast -a com.fliptrainer.action.UNPAIR
 * adb shell am broadcast -a com.fliptrainer.action.DUMP_DIAGNOSTICS
 * ```
 *
 * Security note, stated plainly: this is **exported and unauthenticated**, so anyone with ADB
 * access can unpair the device. That is a deliberate trade for a development appliance whose
 * screen cannot be tapped — ADB access already implies full control of the device. Before this is
 * anything other than a personal project, it needs a signature permission or a deliberate
 * hardware-button gesture instead (plan §6.6 says as much).
 */
class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_UNPAIR -> {
                PairedControllerStore(context).clear()
                Log.w(TAG, "paired controller cleared via ADB; the app will show a pairing QR again")
            }

            ACTION_DUMP_DIAGNOSTICS -> {
                Log.i(TAG, "diagnostics dump requested via ADB")
                // Written to app-private storage; pull with `adb pull`. Never contains video.
            }

            else -> Log.w(TAG, "ignoring unknown recovery action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "FlipTrainer"
        const val ACTION_UNPAIR = "com.fliptrainer.action.UNPAIR"
        const val ACTION_DUMP_DIAGNOSTICS = "com.fliptrainer.action.DUMP_DIAGNOSTICS"
    }
}

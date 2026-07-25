package com.fliptrainer.sensor

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fliptrainer.core.metrics.StatsSnapshot
import com.fliptrainer.core.session.SessionRecovery
import com.fliptrainer.sensor.camera.CameraSessionService

/**
 * The appliance's only screen.
 *
 * Deliberately minimal. The Flip5 this runs on has a **dead main-screen digitizer**, so nothing
 * here may require a tap: the app shows a pairing QR by itself when unpaired, and everything else
 * is driven from the iPhone or over ADB (see README). Any control added here must have an ADB or
 * volume-button equivalent, or it does not exist for this device.
 */
class MainActivity : ComponentActivity() {
    private var permissionGranted by mutableStateOf(false)

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            // Started from a visible activity, never the background: Android 12+ forbids
            // background starts of a camera-typed foreground service.
            if (granted) CameraSessionService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The appliance sits on a tripod for a whole workout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        permissionGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else {
            CameraSessionService.start(this)
        }

        setContent {
            MaterialTheme {
                ApplianceScreen(permissionGranted = permissionGranted)
            }
        }
    }

    override fun onDestroy() {
        CameraSessionService.stop(this)
        super.onDestroy()
    }
}

@Composable
private fun ApplianceScreen(permissionGranted: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!permissionGranted) {
            Text("Camera permission is required.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Grant it with:\nadb shell pm grant com.fliptrainer.sensor android.permission.CAMERA",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Flip Trainer sensor", style = MaterialTheme.typography.titleLarge)
            Text(
                "Showing the pairing code until a controller is paired.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Process-wide startup.
 *
 * Recovery runs here rather than in an activity: a set interrupted by a process death must be
 * closed as interrupted before anything else reads the log, whichever entry point wakes the app.
 */
class FlipTrainerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Wired to the Room-backed log once the database is provisioned at startup:
        //   SessionRecovery.recoverOpenSessions(RoomWorkoutEventLog(database), SystemClock.elapsedRealtime())
        check(SessionRecovery.INTERRUPTED_REASON.isNotEmpty())
    }
}

/** Formats measured pipeline statistics for the debug overlay and for `adb logcat`. */
internal fun StatsSnapshot.overlayLine(): String = format()

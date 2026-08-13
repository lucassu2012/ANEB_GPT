package com.aneb.probe.privacy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

internal object OwnedServiceLaunchBoundary {
    fun start(context: Context, intent: Intent): ComponentName? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
}

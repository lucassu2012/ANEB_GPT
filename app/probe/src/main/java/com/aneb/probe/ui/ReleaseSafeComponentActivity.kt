package com.aneb.probe.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aneb.probe.BuildConfig

abstract class ReleaseSafeComponentActivity : ComponentActivity() {
    // Both build-type branches finish in dispatchNewIntentToFramework(), which
    // invokes ComponentActivity.onNewIntent exactly once; lint cannot follow
    // the function reference through the testable dispatch helpers.
    @SuppressLint("MissingSuperCall")
    final override fun onNewIntent(intent: Intent) {
        dispatchNewIntentByBuildType(intent)
    }

    private fun dispatchNewIntentByBuildType(intent: Intent) {
        if (BuildConfig.DEBUG) {
            dispatchDebugNewIntent(
                launch = intent,
                dispatchToFramework = ::dispatchNewIntentToFramework,
            )
        } else {
            dispatchReleaseNewIntent(
                launch = intent,
                scrubExternalLaunch = { launch ->
                    launch.replaceExtras(Bundle())
                },
                dispatchToFramework = ::dispatchNewIntentToFramework,
            )
        }
    }

    private fun dispatchNewIntentToFramework(intent: Intent) {
        super.onNewIntent(intent)
    }
}

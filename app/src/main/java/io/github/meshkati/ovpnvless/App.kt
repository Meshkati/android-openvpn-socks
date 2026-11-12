package io.github.meshkati.ovpnvless

import android.app.Application
import android.content.res.Configuration

/**
 * Starts the local crash reports ([Crashes]) before anything else runs, then
 * keeps the home-screen widgets ([Widgets]) in step with [VpnState] and logs
 * power saving and network changes for bug reports ([SystemEvents]).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crashes.start(this)
        VpnState.addObserver { Widgets.onVpnStateChanged(this) }
        SystemEvents.start(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Before Android 12 the widgets' text colours are picked here, so a
        // switch to or from the dark theme needs a redraw.
        Widgets.refresh(this)
    }
}

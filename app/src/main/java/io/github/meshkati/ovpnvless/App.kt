package io.github.meshkati.ovpnvless

import android.app.Application

/** Starts the local crash reports ([Crashes]) before anything else runs. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crashes.start(this)
    }
}

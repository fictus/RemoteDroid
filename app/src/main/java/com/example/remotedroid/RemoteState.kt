package com.example.remotedroid

/** Tiny shared state so the Activity can show what the service is doing. */
object RemoteState {
    @Volatile var running = false
    @Volatile var url = ""
    @Volatile var pin = ""
    @Volatile var clients = 0
    @Volatile var error: String? = null
}

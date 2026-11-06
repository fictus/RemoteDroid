package com.example.remotedroid

/** Tiny shared state so the Activity can show what the service is doing. */
object RemoteState {
    /** The server/service is up (stays true even while the screen is locked). */
    @Volatile var running = false
    /** A MediaProjection session is live. False after lock until the user re-approves. */
    @Volatile var capturing = false
    @Volatile var url = ""
    @Volatile var clients = 0
    @Volatile var error: String? = null
}

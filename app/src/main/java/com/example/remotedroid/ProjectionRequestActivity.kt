package com.example.remotedroid

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible activity that shows Android's "start recording/casting" consent prompt and hands the
 * result to [RemoteService]. Used to resume capture after the phone was locked (Android ends the
 * projection on lock and never lets a token be reused).
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, RemoteService::class.java)
                    .putExtra(RemoteService.EXTRA_CODE, r.resultCode)
                    .putExtra(RemoteService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}

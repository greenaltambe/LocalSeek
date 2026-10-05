package com.augt.localseek.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.augt.localseek.MainActivity

/** Quick Settings tile that opens LocalSeek. It has no on/off state. */
class SearchTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            // startActivityAndCollapse(Intent) throws on API 34+; the PendingIntent overload exists only there.
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            // The PendingIntent overload does not exist below API 34, so the Intent overload is required here.
            startActivityAndCollapse(intent)
        }
    }
}

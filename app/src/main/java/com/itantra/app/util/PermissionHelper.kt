package com.itantra.app.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Handles runtime permission requests for all required permissions.
 */
class PermissionHelper(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 1001

        fun getRequiredPermissions(): Array<String> {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            return perms.toTypedArray()
        }
    }

    fun requestAllPermissions() {
        val needed = getRequiredPermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_CODE)
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }

            if (denied.isNotEmpty()) {
                Toast.makeText(
                    activity,
                    "Permissions needed: ${denied.joinToString(", ")}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun hasAllPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

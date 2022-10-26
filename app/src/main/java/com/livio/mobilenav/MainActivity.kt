package com.livio.mobilenav

//
//  MainActivity.kt
//  MobileNav
//
//  Created by Noah Stanford on 2/2/2022.
//  Copyright © 2021 Ford. All rights reserved.
//

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.search.MapboxSearchSdk
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: WeakReference<MainActivity>? = null
        const val BLUETOOTH_REQUEST_CODE = 200

        fun getNewestInstance(): WeakReference<MainActivity>? {
            return instance
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = WeakReference(this)

        try {
            MapboxSearchSdk.initialize(
                application = application,
                accessToken = getString(R.string.mapbox_access_token),
                locationEngine = LocationEngineProvider.getBestLocationEngine(this)
            )
        }
        catch (exception: IllegalStateException) {}

        setContentView(R.layout.activity_main)

        //If we are connected to a module we want to start our SdlService
        if (BuildConfig.TRANSPORT == "MULTI" || BuildConfig.TRANSPORT == "MULTI_HB") {
            val permissionsNeeded: Array<String> = permissionsNeeded()
            if (permissionsNeeded.size > 0) {
                requestPermission(permissionsNeeded, BLUETOOTH_REQUEST_CODE)
                for (permission in permissionsNeeded) {
                    if (Manifest.permission.BLUETOOTH_CONNECT == permission) {
                        // We need to request BLUETOOTH_CONNECT permission to connect to SDL via Bluetooth
                        return
                    }
                }
            }

            //If we are connected to a module we want to start our SdlService
            SdlReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }

    }

    /**
     * Boolean method that checks API level and check to see if we need to request BLUETOOTH_CONNECT permission
     * @return false if we need to request BLUETOOTH_CONNECT permission
     */
    private fun hasBTPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) checkPermission(Manifest.permission.BLUETOOTH_CONNECT) else true
    }

    /**
     * Boolean method that checks API level and check to see if we need to request POST_NOTIFICATIONS permission
     * @return false if we need to request POST_NOTIFICATIONS permission
     */
    private fun hasPNPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkPermission(Manifest.permission.POST_NOTIFICATIONS) else true
    }

    private fun checkPermission(permission: String): Boolean {
        return PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(
            applicationContext,
            permission
        )
    }

    private fun requestPermission(permissions: Array<String>, REQUEST_CODE: Int) {
        ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
    }

    private fun permissionsNeeded(): Array<String> {
        val result = ArrayList<String>()
        if (!hasBTPermission()) {
            result.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (!hasPNPermission()) {
            result.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return result.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            BLUETOOTH_REQUEST_CODE -> if (grantResults.size > 0) {
                var i = 0
                while (i < grantResults.size) {
                    if (permissions[i] == Manifest.permission.BLUETOOTH_CONNECT) {
                        val btConnectGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED
                        if (btConnectGranted) {
                            SdlReceiver.queryForConnectedService(this)
                        }
                    } else if (permissions[i] == Manifest.permission.POST_NOTIFICATIONS) {
                        val postNotificationGranted =
                            grantResults[i] == PackageManager.PERMISSION_GRANTED
                        if (!postNotificationGranted) {
                            // User denied permission, Notifications for SDL will not appear
                            // on Android 13 devices.
                        }
                    }
                    i++
                }
            }
        }
    }


}
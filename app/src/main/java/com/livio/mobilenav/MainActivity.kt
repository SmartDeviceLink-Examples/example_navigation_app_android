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
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!checkBluetoothPermission()) {
                    requestBluetoothPermission()
                    return
                }
            }

            SdlReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }

    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkBluetoothPermission(): Boolean {
        val btConnectPermission =
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.BLUETOOTH_CONNECT)
        return btConnectPermission == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            BLUETOOTH_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            BLUETOOTH_REQUEST_CODE -> if (grantResults.isNotEmpty()) {
                val connectAccepted = grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (!connectAccepted) {
                    Toast.makeText(
                        this,
                        "BLUETOOTH_CONNECT Permission is needed for Bluetooth testing",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else {
                    SdlReceiver.queryForConnectedService(this)
                }
            }
        }
    }


}
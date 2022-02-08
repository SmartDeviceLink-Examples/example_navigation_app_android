package com.livio.mobilenav

/**
 * This class is based on the class found here: https://github.com/mapbox/mapbox-maps-android/blob/android-v10.2.0/app/src/main/java/com/mapbox/maps/testapp/utils/LocationPermissionHelper.kt
 */

//
//  LocationPermissionHelper.kt
//  MobileNav
//
//  Created by Noah Stanford on 2/2/2022.
//  Copyright © 2021 Ford. All rights reserved.
//

import android.content.Context
import android.widget.Toast
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import java.lang.ref.WeakReference

class LocationPermissionHelper(val context: WeakReference<Context>) {
    private lateinit var permissionsManager: PermissionsManager

    init {
        instance = this
    }

    fun checkPermissions(onMapReady: () -> Unit) {
        if (PermissionsManager.areLocationPermissionsGranted(context.get())) {
            onMapReady()
        } else {
            permissionsManager = PermissionsManager(object : PermissionsListener {
                override fun onExplanationNeeded(permissionsToExplain: List<String>) {
                    Toast.makeText(
                        context.get(), "You need to accept location permissions.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onPermissionResult(granted: Boolean) {
                    if (granted) {
                        onMapReady()
                    }
                }
            })
            permissionsManager.requestLocationPermissions(MainActivity.getNewestInstance()?.get())
        }
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        permissionsManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        private var instance: LocationPermissionHelper? = null

        fun getNewestInstance(): LocationPermissionHelper? {
            return null
        }
    }
}
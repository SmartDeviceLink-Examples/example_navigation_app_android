package com.livio.mobilenav

//
//  MainActivity.kt
//  MobileNav
//
//  Created by Noah Stanford on 2/2/2022.
//  Copyright © 2021 Ford. All rights reserved.
//

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.search.MapboxSearchSdk
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    companion object {
        private var instance: WeakReference<MainActivity>? = null

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
            SdlReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }

    }


}
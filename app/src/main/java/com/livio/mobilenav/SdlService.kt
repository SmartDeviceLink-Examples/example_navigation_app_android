package com.livio.mobilenav

//
//  SdlService.kt
//  MobileNav
//
//  Created by Noah Stanford on 2/2/2022.
//  Copyright © 2021 Ford. All rights reserved.
//

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnRotateListener
import com.mapbox.maps.plugin.gestures.OnScaleListener
import com.mapbox.maps.plugin.gestures.OnShoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.search.MapboxSearchSdk
import com.mapbox.search.ui.view.CommonSearchViewConfiguration
import com.mapbox.search.ui.view.DistanceUnitType
import com.mapbox.search.ui.view.SearchBottomSheetView
import com.mapbox.search.ui.view.category.Category
import com.mapbox.search.ui.view.category.SearchCategoriesBottomSheetView
import com.mapbox.search.ui.view.feedback.SearchFeedbackBottomSheetView
import com.mapbox.search.ui.view.place.SearchPlaceBottomSheetView
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.file.filetypes.SdlArtwork
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.rpc.*
import com.smartdevicelink.proxy.rpc.enums.*
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.streaming.video.SdlRemoteDisplay
import com.smartdevicelink.transport.BaseTransportConfig
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.transport.TCPTransportConfig
import com.smartdevicelink.util.DebugTool
import com.smartdevicelink.util.SystemInfo
import java.lang.ref.WeakReference
import java.util.*

class SdlService : Service() {
    companion object {
        // Arbitrary for the purposes of this app
        private const val FOREGROUND_SERVICE_ID = 1234545
        private const val HASH_ID = "356447790"
        private const val TAG = "SdlService"
    }

    //The manager handles communication between the application and SDL
    private var sdlManager: SdlManager? = null
    private var channel: NotificationChannel? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = NotificationChannel(
                applicationInfo.packageName,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel!!)
                val serviceNotification = Notification.Builder(this, channel!!.id)
                    .setContentTitle(getString(R.string.notification_title))
                    .setSmallIcon(R.drawable.ic_sdl)
                    .setContentText(getString(R.string.notification_content))
                    .setChannelId(channel!!.id)
                    .build()
                startForeground(FOREGROUND_SERVICE_ID, serviceNotification)
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startProxy()
        return START_STICKY
    }

    private fun startProxy() {
        // This logic is to select the correct transport and security levels defined in the selected build flavor
        // Build flavors are selected by the "build variants" tab typically located in the bottom left of Android Studio
        // Typically in your app, you will only set one of these.
        if (sdlManager == null) {
            Log.i(TAG, "Starting SDL Proxy")
            // Enable DebugTool for debug build type
            if (BuildConfig.DEBUG) {
                DebugTool.enableDebugTool()
            }
            var transport: BaseTransportConfig? = null
            when {
                BuildConfig.TRANSPORT.equals("MULTI") -> {
                    val securityLevel: Int = when {
                        BuildConfig.SECURITY.equals("HIGH") -> {
                            MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH
                        }
                        BuildConfig.SECURITY.equals("MED") -> {
                            MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED
                        }
                        BuildConfig.SECURITY.equals("LOW") -> {
                            MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW
                        }
                        else -> {
                            MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF
                        }
                    }
                    transport = MultiplexTransportConfig(this, APP_ID, securityLevel)
                }
                BuildConfig.TRANSPORT.equals("TCP") -> {
                    transport =
                        TCPTransportConfig(
                            PORT,
                            IP_ADDRESS,
                            true
                        )
                }
                BuildConfig.TRANSPORT.equals("MULTI_HB") -> {
                    val mtc = MultiplexTransportConfig(
                        this,
                        APP_ID,
                        MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF
                    )
                    mtc.setRequiresHighBandwidth(true)
                    transport = mtc
                }
            }

            // The app type to be used
            val appType = Vector<AppHMIType>()
            appType.add(AppHMIType.NAVIGATION)

            // The manager listener helps you know when certain events that pertain to the SDL Manager happen
            val listener: SdlManagerListener = object : SdlManagerListener {
                override fun onStart() {
                    // After this callback is triggered the SdlManager can be used to interact with the connected SDL session (updating the display, sending RPCs, etc)
                    sdlManager!!.addOnRPCNotificationListener(
                        FunctionID.ON_HMI_STATUS,
                        object : OnRPCNotificationListener() {
                            override fun onNotified(notification: RPCNotification) {
                                val onHMIStatus = notification as OnHMIStatus
                                if (onHMIStatus.windowID != null && onHMIStatus.windowID != PredefinedWindows.DEFAULT_WINDOW.value) {
                                    return
                                }
                                if (onHMIStatus.hmiLevel == HMILevel.HMI_FULL) {
                                    startVideoStream()
                                }

                                if (onHMIStatus.hmiLevel == HMILevel.HMI_NONE) {

                                    //Stop the stream
                                    if (sdlManager?.videoStreamManager != null && sdlManager?.videoStreamManager?.isStreaming!!) {
                                        sdlManager?.videoStreamManager?.stopStreaming();
                                    }

                                }
                            }
                        })
                }

                override fun onDestroy() {
                    this@SdlService.stopSelf()
                }

                override fun onError(info: String, e: Exception) {}

                override fun managerShouldUpdateLifecycle(
                    language: Language,
                    hmiLanguage: Language
                ): LifecycleConfigurationUpdate {
                    return LifecycleConfigurationUpdate(getString(R.string.app_name), getString(R.string.app_short_name), null, null)
                }

                override fun onSystemInfoReceived(systemInfo: SystemInfo): Boolean {
                    // Check the SystemInfo object to ensure that the connection to the device should continue
                    return true
                }
            }

            // Create App Icon, this is set in the SdlManager builder
            val appIcon = SdlArtwork(null, FileType.GRAPHIC_PNG, R.mipmap.ic_launcher, true)
            sdlManager = SdlManagerFactory.createSdlManager(
                this,
                APP_ID,
                getString(R.string.app_name),
                listener,
                appType,
                appIcon,
                transport,
                Language.EN_US,
                HASH_ID
            )
        }


        sdlManager?.start()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager != null && channel != null) { //If this is the only notification on your channel
                notificationManager.deleteNotificationChannel(channel!!.id)
            }
            stopForeground(true)
        }
        sdlManager?.dispose()
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        throw UnsupportedOperationException("Not yet implemented")
    }

    fun startVideoStream() {
        if (sdlManager?.videoStreamManager != null) {
            sdlManager?.videoStreamManager?.start { success: Boolean ->
                if (success) {
                    Log.i(TAG, "Stream start successful")
                    sdlManager?.videoStreamManager?.startRemoteDisplayStream(
                        applicationContext,
                        MapRemoteDisplay::class.java,
                        null,
                        false,
                        null,
                        null
                    )
                } else {
                    Log.e(TAG, "Stream failed to start")
                }
            }
        }
    }

    class MapRemoteDisplay(context: Context?, display: Display?) :
        SdlRemoteDisplay(context, display) {

        companion object {
            private val DEFAULT_ZOOM = 18.0
            private val ZOOM_IN_SCALE = 2.0
            private val ZOOM_OUT_SCALE = 0.5
            const val SEARCH_PIN_SOURCE_ID = "search.pin.source.id"
            const val SEARCH_PIN_IMAGE_ID = "search.pin.image.id"
            const val SEARCH_PIN_LAYER_ID = "search.pin.layer.id"

            private fun createSearchPinDrawable(): Drawable? {
                return MainActivity.getNewestInstance()?.get()?.let { it ->
                    ContextCompat.getDrawable(it, R.drawable.red_marker)
                }
            }
        }

        private var mapView: MapView? = null
        private var pointAnnotationManager: PointAnnotationManager? = null
        private lateinit var locationPermissionHelper: LocationPermissionHelper
        private lateinit var mapboxNavigation: MapboxNavigation
        private lateinit var searchButton: FloatingActionButton
        private lateinit var zoomInButton: FloatingActionButton
        private lateinit var zoomOutButton: FloatingActionButton
        private lateinit var recenterButton: FloatingActionButton

        private lateinit var searchBottomSheetView: SearchBottomSheetView
        private lateinit var searchPlaceView: SearchPlaceBottomSheetView
        private lateinit var searchCategoriesView: SearchCategoriesBottomSheetView
        private lateinit var feedbackBottomSheetView: SearchFeedbackBottomSheetView

        @Volatile private var centerMap = true
        @Volatile private var indicatorPosition: Point? = null
        @Volatile private var indicatorBearing: Double? = null

        private val clickListener =  { _: Point ->
            searchBottomSheetView.hide()
            false
        }

        private val flingListener = {
            searchBottomSheetView.hide()
            centerMap = false
        }

        private val rotateListener = object : OnRotateListener {
            override fun onRotate(detector: RotateGestureDetector) {
                searchBottomSheetView.hide()
                centerMap = false
            }

            override fun onRotateBegin(detector: RotateGestureDetector) {

            }

            override fun onRotateEnd(detector: RotateGestureDetector) {
            }


        }

        private val shoveListener = object : OnShoveListener {
            override fun onShove(detector: ShoveGestureDetector) {
                searchBottomSheetView.hide()
                centerMap = false
            }

            override fun onShoveBegin(detector: ShoveGestureDetector) {

            }

            override fun onShoveEnd(detector: ShoveGestureDetector) {
            }

        }

        private val scaleListener = object : OnScaleListener {
            override fun onScale(detector: StandardScaleGestureDetector) {
                searchBottomSheetView.hide()
                centerMap = false
            }

            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
            }

            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
            }

        }

        private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
            if (centerMap) {
                indicatorBearing = it
                mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().bearing(it).build())
            }
        }

        private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
            if (centerMap) {
                indicatorPosition = it
                mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().center(it).build())
                mapView?.gestures?.focalPoint = mapView?.getMapboxMap()?.pixelForCoordinate(it)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.stream)
            try {
                MainActivity.getNewestInstance()?.get()?.application?.let {
                    MapboxSearchSdk.initialize(
                        application = it,
                        accessToken = context.getString(R.string.mapbox_access_token),
                        locationEngine = LocationEngineProvider.getBestLocationEngine(context)
                    )
                }
            }
            catch (exception: IllegalStateException) {}

            searchButton = findViewById(R.id.search_button)
            mapView = findViewById(R.id.map_view)
            val annotationApi = mapView?.annotations
            pointAnnotationManager = annotationApi?.createPointAnnotationManager()
            zoomInButton = findViewById(R.id.zoom_in_button)
            zoomOutButton = findViewById(R.id.zoom_out_button)
            recenterButton = findViewById(R.id.recenter_location_button)
            searchBottomSheetView = findViewById(R.id.search_view)
            searchPlaceView = findViewById(R.id.search_place_view)
            searchCategoriesView = findViewById(R.id.search_categories_view)
            feedbackBottomSheetView = findViewById(R.id.search_feedback_view)
            locationPermissionHelper = LocationPermissionHelper(WeakReference(context))
            val navigationOptions: NavigationOptions = NavigationOptions.Builder(context)
                .accessToken(context.getString(R.string.mapbox_access_token))
                .build()
            mapboxNavigation = MapboxNavigationProvider.create(navigationOptions)
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                mapboxNavigation.startTripSession(true)
            }
            locationPermissionHelper.checkPermissions {
                onMapReady()
            }
            searchBottomSheetView.apply {
                savedInstanceState?.let {
                    initializeSearch(savedInstanceState, SearchBottomSheetView.Configuration())
                }
                hide()
                isHideableByDrag = true
                addOnCategoryClickListener{ openCategory(it) }
                addOnSearchResultClickListener { searchResult, _ ->
                    searchResult.coordinate?.let {
                        showMarker(it)
                    }
                }
                addOnHistoryClickListener { history ->
                    history.coordinate?.let {
                        showMarker(it)
                    }
                }
            }
            searchCategoriesView.apply {
                initialize(CommonSearchViewConfiguration(DistanceUnitType.IMPERIAL))
                addOnCloseClickListener{ resetToRoot() }
                addOnSearchResultClickListener { searchResult, _ ->
                    searchResult.coordinate?.let {
                        showMarker(it)
                    }
                }
            }
            searchPlaceView.apply {
                initialize(CommonSearchViewConfiguration(DistanceUnitType.IMPERIAL))
                addOnNavigateClickListener { searchPlace -> showMarker(searchPlace.coordinate) }
            }
            feedbackBottomSheetView.apply {
                savedInstanceState?.let {
                    initialize(savedInstanceState)
                }
            }

        }


        private fun showMarker(coordinate: Point) {
            centerMap = false
            val cameraOptions = CameraOptions.Builder()
                .center(coordinate)
                .build()


            val pointAnnotationOptions = createSearchPinDrawable()?.toBitmap()?.let {
                PointAnnotationOptions()
                    .withPoint(coordinate)
                    .withIconImage(it)
                    .withIconSize(0.50)
            }

            pointAnnotationOptions?.let {
                pointAnnotationManager?.deleteAll()
                pointAnnotationManager?.create(it)
                mapView?.getMapboxMap()?.setCamera(cameraOptions)
                hideWholeSheet()
            }


        }

        private fun handleOnBackPressed(): Boolean {
            return searchBottomSheetView.handleOnBackPressed() ||
                    searchCategoriesView.handleOnBackPressed() ||
                    feedbackBottomSheetView.handleOnBackPressed()
        }

        override fun onBackPressed() {
            if (!handleOnBackPressed()) {
                if(bottomSheetIsShown())
                {
                    hideWholeSheet()
                }
                else {
                    super.onBackPressed()
                }
            }
        }

        private fun resetToRoot() {
            searchBottomSheetView.open()
            feedbackBottomSheetView.hide()
            searchPlaceView.hide()
            searchCategoriesView.hide()
            searchCategoriesView.cancelCategoryLoading()
        }

        private fun bottomSheetIsShown(): Boolean {
            return !searchBottomSheetView.isHidden() ||
                    !searchPlaceView.isHidden() ||
                    !searchCategoriesView.isHidden() ||
                    !feedbackBottomSheetView.isHidden()
        }

        private fun hideWholeSheet() {
            searchBottomSheetView.hide()
            feedbackBottomSheetView.hide()
            searchPlaceView.hide()
            searchCategoriesView.hide()
        }

        private fun openCategory(category: Category, fromBackStack: Boolean = false) {
            if (fromBackStack) {
                searchCategoriesView.restorePreviousNonHiddenState(category)
            } else {
                searchCategoriesView.open(category)
            }
            searchBottomSheetView.hide()
        }

        private fun registerGestureListeners() {
            mapView?.gestures?.addOnMapClickListener(clickListener)
            mapView?.gestures?.addOnFlingListener(flingListener)
            mapView?.gestures?.addOnRotateListener(rotateListener)
            mapView?.gestures?.addOnShoveListener(shoveListener)
            mapView?.gestures?.addOnScaleListener(scaleListener)
        }

        private fun unRegisterGestureListeners() {
            mapView?.gestures?.removeOnMapClickListener(clickListener)
            mapView?.gestures?.removeOnFlingListener(flingListener)
            mapView?.gestures?.removeOnRotateListener(rotateListener)
            mapView?.gestures?.removeOnShoveListener(shoveListener)
            mapView?.gestures?.removeOnScaleListener(scaleListener)
        }

        private fun onMapReady() {
            mapView?.getMapboxMap()?.setCamera(
                CameraOptions.Builder()
                    .zoom(DEFAULT_ZOOM)
                    .build()
            )
            mapView?.getMapboxMap()?.loadStyleUri(
                Style.MAPBOX_STREETS
            ) {
                initLocationComponent()
                mapView?.getMapboxMap()?.addOnCameraChangeListener {
                    mapView?.getMapboxMap()?.triggerRepaint()
                }
                image(SEARCH_PIN_IMAGE_ID) {
                   createSearchPinDrawable()?.toBitmap(config = Bitmap.Config.ARGB_8888)?.let { bits ->
                        bitmap(bits)
                    }
                }
                symbolLayer(SEARCH_PIN_LAYER_ID, SEARCH_PIN_SOURCE_ID) {
                    iconImage(SEARCH_PIN_IMAGE_ID)
                    iconAllowOverlap(true)
                }
            }

        }

        private fun initLocationComponent() {
            val locationComponentPlugin = mapView?.location
            locationComponentPlugin?.updateSettings {
                this.enabled = true
                this.locationPuck = LocationPuck2D(
                    bearingImage = AppCompatResources.getDrawable(
                        context,
                        R.drawable.mapbox_navigation_puck_icon,
                    ),
                    shadowImage = AppCompatResources.getDrawable(
                        context,
                        R.drawable.mapbox_user_icon_shadow,
                    ),
                    scaleExpression = Expression.interpolate {
                        linear()
                        zoom()
                        stop {
                            literal(0.0)
                            literal(0.6)
                        }
                        stop {
                            literal(20.0)
                            literal(1.0)
                        }
                    }.toJson()
                )
            }
            locationComponentPlugin?.addOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
            locationComponentPlugin?.addOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        }

        override fun onStart() {
            super.onStart()
            mapView?.onStart()
            registerGestureListeners()
            searchButton.setOnClickListener { searchBottomSheetView.open() }
            zoomInButton.setOnClickListener { mapView?.camera?.scaleBy(ZOOM_IN_SCALE, null) }
            zoomOutButton.setOnClickListener { mapView?.camera?.scaleBy(ZOOM_OUT_SCALE, null) }
            recenterButton.setOnClickListener {
                centerMap = true
                val cameraOptionsBuilder = CameraOptions.Builder().zoom(DEFAULT_ZOOM)
                indicatorPosition?.let {
                    cameraOptionsBuilder.center(it)
                }
                indicatorBearing?.let {
                    cameraOptionsBuilder.bearing(it)
                }
                mapView?.getMapboxMap()?.setCamera(cameraOptionsBuilder.build())
                pointAnnotationManager?.deleteAll()
            }
        }

        override fun onStop() {
            super.onStop()
            mapView?.onStop()

            unRegisterGestureListeners()

            mapboxNavigation.stopTripSession()
            mapView?.location
                ?.removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
            mapView?.location
                ?.removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        }

        override fun onViewResized(width: Int, height: Int) {
            Log.i(TAG, String.format("Remote view new width and height (%d, %d)", width, height))
        }
    }
}
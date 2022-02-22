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
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.attribution.*
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
import com.mapbox.search.*
import com.mapbox.search.result.SearchResult
import com.mapbox.search.result.SearchSuggestion
import com.mapbox.search.ui.utils.maki.*
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.file.filetypes.SdlArtwork
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.managers.screen.choiceset.KeyboardAutocompleteCompletionListener
import com.smartdevicelink.managers.screen.choiceset.KeyboardCharacterSetCompletionListener
import com.smartdevicelink.managers.screen.choiceset.KeyboardListener
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.rpc.*
import com.smartdevicelink.proxy.rpc.enums.*
import com.smartdevicelink.proxy.rpc.enums.Language
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.streaming.video.SdlRemoteDisplay
import com.smartdevicelink.transport.BaseTransportConfig
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.transport.TCPTransportConfig
import com.smartdevicelink.util.DebugTool
import com.smartdevicelink.util.SystemInfo
import java.lang.ref.WeakReference
import java.util.*


class SdlService : Service(){
    companion object {
        // Arbitrary for the purposes of this app
        private const val FOREGROUND_SERVICE_ID = 535655454
        private const val HASH_ID = "656576757"
        private const val TAG = "SdlService"
        var sdlManagerReference: WeakReference<SdlManager>? = null
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
                    transport.setRequiresAudioSupport(false)
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

        sdlManagerReference = WeakReference(sdlManager)

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
        SdlRemoteDisplay(context, display){

        companion object {
            private val DEFAULT_ZOOM = 18.0
            private val ZOOM_IN_SCALE = 2.0
            private val ZOOM_OUT_SCALE = 0.5
            private val METERS_TO_MILES_FACTOR = 0.00062137119224
            const val SEARCH_PIN_SOURCE_ID = "search.pin.source.id"
            const val SEARCH_PIN_IMAGE_ID = "search.pin.image.id"
            const val SEARCH_PIN_LAYER_ID = "search.pin.layer.id"

            private fun createSearchPinDrawable(): Drawable? {
                return MainActivity.getNewestInstance()?.get()?.let { it ->
                    ContextCompat.getDrawable(it, R.drawable.red_marker)
                }
            }

            private fun metersToMiles(meters: Double): Double {
                return METERS_TO_MILES_FACTOR * meters
            }
        }

        private var mapView: MapView? = null
        private var pointAnnotationManager: PointAnnotationManager? = null
        private lateinit var attributionButton: AttributionViewImpl
        private lateinit var locationPermissionHelper: LocationPermissionHelper
        private lateinit var mapboxNavigation: MapboxNavigation
        private lateinit var searchButton: FloatingActionButton
        private lateinit var zoomInButton: FloatingActionButton
        private lateinit var zoomOutButton: FloatingActionButton
        private lateinit var recenterButton: FloatingActionButton
        private lateinit var mapboxLinkLabel: TextView
        private lateinit var openstreetmapLinkLabel: TextView
        private lateinit var improveMapLinkLabel: TextView
        private lateinit var searchLayout: RelativeLayout
        private lateinit var searchListView: ListView
        private lateinit var toolbar: MaterialToolbar

        private var searchTerm = ""
        @Volatile private var centerMap = true
        @Volatile private var indicatorPosition: Point? = null
        @Volatile private var indicatorBearing: Double? = null

        private val searchEngine = MapboxSearchSdk.getSearchEngine()

        private class SearchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            lateinit var searchResultName: TextView
            lateinit var searchResultAddress: TextView
            lateinit var searchResultDistance: TextView
            lateinit var resultPopulate: ImageView
        }

        private var keyboardListener: KeyboardListener = object : KeyboardListener {
            override fun onUserDidSubmitInput(inputText: String, event: KeyboardEvent) {
                when (event) {
                    KeyboardEvent.ENTRY_VOICE -> {
                    }
                    KeyboardEvent.ENTRY_SUBMITTED -> {

                        searchTerm = inputText
                        searchEngine.search(
                            inputText,
                            SearchOptions(),
                            searchCallback
                        )

                        searchListView.smoothScrollToPosition(0)
                        searchLayout.visibility = View.VISIBLE

                    }
                    else -> {
                    }
                }
            }

            override fun onKeyboardDidAbortWithReason(event: KeyboardEvent) {
                when (event) {
                    KeyboardEvent.ENTRY_CANCELLED -> {
                    }
                    KeyboardEvent.ENTRY_ABORTED -> {
                    }
                    else -> {
                    }
                }
            }

            override fun updateAutocompleteWithInput(
                currentInputText: String,
                keyboardAutocompleteCompletionListener: KeyboardAutocompleteCompletionListener
            ) {
                // Check the input text and return a list of autocomplete results
            }

            override fun updateCharacterSetWithInput(
                currentInputText: String,
                keyboardCharacterSetCompletionListener: KeyboardCharacterSetCompletionListener
            ) {
                // Check the input text and return a set of characters to allow the user to enter
            }

            override fun onKeyboardDidSendEvent(event: KeyboardEvent, currentInputText: String) {
                // This is sent upon every event, such as keypresses, cancellations, and aborting
            }

            override fun onKeyboardDidUpdateInputMask(event: KeyboardEvent) {
                when (event) {
                    KeyboardEvent.INPUT_KEY_MASK_ENABLED -> {
                    }
                    KeyboardEvent.INPUT_KEY_MASK_DISABLED -> {
                    }
                    else -> {
                    }
                }
            }
        }

        private val searchListAdapter = context?.let {
            object : ArrayAdapter<SearchSuggestion>(it, R.layout.search_result_item) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    var currentView: View
                    if (convertView != null) {
                        currentView = convertView
                    } else {
                        currentView = LayoutInflater.from(it).inflate(R.layout.search_result_item, parent, false)
                        val viewHolder = SearchViewHolder(currentView)
                        viewHolder.searchResultName = currentView.findViewById(R.id.search_result_name)
                        viewHolder.searchResultAddress = currentView.findViewById(R.id.search_result_address)
                        viewHolder.searchResultDistance = currentView.findViewById(R.id.search_result_distance)
                        viewHolder.resultPopulate = currentView.findViewById(R.id.result_populate)
                        currentView.tag = viewHolder
                    }

                    val searchSuggestion = getItem(position)
                    val viewHolder = currentView.tag as SearchViewHolder
                    viewHolder.searchResultName.text = searchSuggestion?.name
                    viewHolder.searchResultAddress.text = searchSuggestion?.address?.formattedAddress()
                    viewHolder.searchResultDistance.text = searchSuggestion?.distanceMeters?.let { meters ->
                        String.format("%.3f mi", metersToMiles(meters))
                    }

                    return currentView
                }

            }
        }

        private val searchCallback = object : SearchSuggestionsCallback {
            override fun onError(e: Exception) {
                e.printStackTrace()
            }

            override fun onSuggestions(
                suggestions: List<SearchSuggestion>,
                responseInfo: ResponseInfo
            ) {
                searchListAdapter?.clear()
                searchListAdapter?.addAll(suggestions)
                searchListAdapter?.notifyDataSetChanged()
            }

        }

        private val searchSelectionCallback = object : SearchSelectionCallback {
            override fun onCategoryResult(
                suggestion: SearchSuggestion,
                results: List<SearchResult>,
                responseInfo: ResponseInfo
            ) {
            }

            override fun onError(e: Exception) {
                e.printStackTrace()
            }

            override fun onResult(
                suggestion: SearchSuggestion,
                result: SearchResult,
                responseInfo: ResponseInfo
            ) {
                result.coordinate?.let { showMarker(it) }
            }

            override fun onSuggestions(
                suggestions: List<SearchSuggestion>,
                responseInfo: ResponseInfo
            ) {
            }

        }

        private val clickListener =  { _: Point ->
            searchLayout.visibility = View.GONE
            false
        }

        private val flingListener = {
            searchLayout.visibility = View.GONE
            centerMap = false
        }

        private val rotateListener = object : OnRotateListener {
            override fun onRotate(detector: RotateGestureDetector) {
                searchLayout.visibility = View.GONE
                centerMap = false
            }

            override fun onRotateBegin(detector: RotateGestureDetector) {

            }

            override fun onRotateEnd(detector: RotateGestureDetector) {
            }


        }

        private val shoveListener = object : OnShoveListener {
            override fun onShove(detector: ShoveGestureDetector) {
                searchLayout.visibility = View.GONE
                centerMap = false
            }

            override fun onShoveBegin(detector: ShoveGestureDetector) {

            }

            override fun onShoveEnd(detector: ShoveGestureDetector) {
            }

        }

        private val scaleListener = object : OnScaleListener {
            override fun onScale(detector: StandardScaleGestureDetector) {
                searchLayout.visibility = View.GONE
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

            attributionButton = findViewById(R.id.attribution_button)
            searchButton = findViewById(R.id.search_button)
            mapView = findViewById(R.id.map_view)
            val annotationApi = mapView?.annotations
            pointAnnotationManager = annotationApi?.createPointAnnotationManager()
            zoomInButton = findViewById(R.id.zoom_in_button)
            zoomOutButton = findViewById(R.id.zoom_out_button)
            recenterButton = findViewById(R.id.recenter_location_button)
            mapboxLinkLabel = findViewById(R.id.mapbox_link)
            openstreetmapLinkLabel = findViewById(R.id.openstreetmap_link)
            improveMapLinkLabel = findViewById(R.id.improve_map_link)
            searchLayout = findViewById(R.id.search_coordinator)
            toolbar = findViewById(R.id.toolbar)
            searchListView = findViewById(R.id.search_list_view)

            val alertDialog = AlertDialog.Builder(MainActivity.getNewestInstance()?.get())
                .setTitle(context.getString(R.string.telemetry_opt_out))
                .setMessage(context.getString(R.string.telemetry_opt_out_message))
                .setPositiveButton(R.string.opt_out) { dialog, _ ->
                    val telemetry = mapView?.attribution?.getMapAttributionDelegate()?.telemetry()
                    telemetry?.disableTelemetrySession()
                    telemetry?.setUserTelemetryRequestState(false)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel){ dialog, _ ->
                    dialog.cancel()
                }
                .create()

            attributionButton.setOnClickListener {
                alertDialog.show()
            }

            mapboxLinkLabel.setOnClickListener {
                val urlIntent = Intent(Intent.ACTION_VIEW)
                urlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                urlIntent.data = Uri.parse(context.getString(R.string.mapbox_url))
                context.startActivity(urlIntent)
            }

            openstreetmapLinkLabel.setOnClickListener {
                val urlIntent = Intent(Intent.ACTION_VIEW)
                urlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                urlIntent.data = Uri.parse(context.getString(R.string.openstreetmap_url))
                context.startActivity(urlIntent)
            }

            improveMapLinkLabel.setOnClickListener {
                val urlIntent = Intent(Intent.ACTION_VIEW)
                urlIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                urlIntent.data = Uri.parse(context.getString(R.string.improve_map_url))
                context.startActivity(urlIntent)
            }

            searchListView.adapter = searchListAdapter
            searchListView.onItemClickListener =
                AdapterView.OnItemClickListener { parent, view, position, id ->
                    val suggestion = searchListAdapter?.getItem(position)
                    if (suggestion != null) {
                        searchEngine.select(suggestion, searchSelectionCallback)
                    }
                }


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
                    .withIconSize(0.25)
            }

            pointAnnotationOptions?.let {
                pointAnnotationManager?.deleteAll()
                pointAnnotationManager?.create(it)
                mapView?.getMapboxMap()?.setCamera(cameraOptions)
                searchLayout.visibility = View.GONE
            }


        }

        override fun onBackPressed() {
            if (searchLayout.visibility != View.GONE) {
                searchLayout.visibility = View.GONE
            } else {
                super.onBackPressed()
            }
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
            mapView?.attribution?.enabled = false
            mapView?.attribution?.clickable = false
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
            registerGestureListeners()
            searchButton.setOnClickListener {
                val cancelId = sdlManagerReference?.get()?.screenManager?.presentKeyboard(context.getString(R.string.search), null, keyboardListener)
            }
            toolbar.navigationIcon = AppCompatResources.getDrawable(context, R.drawable.arrow_back)
            toolbar.setNavigationOnClickListener {
                searchLayout.visibility = View.GONE
            }
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

        private fun initLocationComponent() {
            val locationComponentPlugin = mapView?.location

            locationComponentPlugin?.updateSettings {
                this.enabled = true
                this.locationPuck = LocationPuck2D(
                    bearingImage = AppCompatResources.getDrawable(
                        context,
                        R.drawable.location_puck
                    )
                )
            }
            locationComponentPlugin?.addOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
            locationComponentPlugin?.addOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        }

        override fun onStart() {
            super.onStart()
            mapView?.onStart()

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
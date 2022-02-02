package com.livio.mobilenav

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.UserDictionary.Words
import android.util.Log
import android.view.Display
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.Style.Companion.MAPBOX_STREETS
import com.mapbox.maps.Style.Companion.SATELLITE
import com.mapbox.maps.extension.observable.subscribeCameraChange
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.expressions.generated.Expression.Companion.interpolate
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
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
import com.mapbox.navigation.core.MapboxNavigationProvider.create
import com.smartdevicelink.managers.SdlManager
import com.smartdevicelink.managers.SdlManagerListener
import com.smartdevicelink.managers.file.filetypes.SdlArtwork
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate
import com.smartdevicelink.managers.screen.AlertAudioData
import com.smartdevicelink.managers.screen.AlertView
import com.smartdevicelink.managers.screen.SoftButtonObject
import com.smartdevicelink.managers.screen.SoftButtonState
import com.smartdevicelink.managers.screen.menu.MenuCell
import com.smartdevicelink.managers.screen.menu.MenuConfiguration
import com.smartdevicelink.protocol.enums.FunctionID
import com.smartdevicelink.proxy.RPCNotification
import com.smartdevicelink.proxy.RPCResponse
import com.smartdevicelink.proxy.rpc.*
import com.smartdevicelink.proxy.rpc.enums.*
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener
import com.smartdevicelink.streaming.video.SdlRemoteDisplay
import com.smartdevicelink.streaming.video.VideoStreamingParameters
import com.smartdevicelink.transport.MultiplexTransportConfig
import com.smartdevicelink.transport.TCPTransportConfig
import com.smartdevicelink.util.DebugTool
import com.smartdevicelink.util.SystemInfo
import java.lang.ref.WeakReference
import java.util.*

class SdlService : Service() {
    //The manager handles communication between the application and SDL
    private var sdlManager: SdlManager? = null
    private var channel: NotificationChannel? = null
    private val settingList: MutableList<String> = ArrayList()
    private var settingIndex = 0

    override fun onCreate() {
        super.onCreate()
        settingList.add("Hello")
        settingList.add("There")
        settingList.add("Friends")
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
        if (sdlManager == null) {
            val transport = MultiplexTransportConfig(
                this,
                Words.APP_ID,
                MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF
            )

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
                                    //startVideoStream()
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
            val transportConfig = TCPTransportConfig(PORT, IP_ADDRESS, false)
            sdlManager = SdlManagerFactory.createSdlManager(
                this,
                Words.APP_ID,
                getString(R.string.app_name),
                listener,
                appType,
                appIcon,
                transportConfig,
                Language.EN_US,
                HASH_ID
            )
            //addTextWithScreenManager();
            sdlManager?.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        //...
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

    private fun addTextWithScreenManager(text: String) {
        sdlManager?.screenManager?.beginTransaction()
        sdlManager?.screenManager?.textField1 = text
        sdlManager?.screenManager?.commit { success: Boolean ->
            if (success) {
                DebugTool.logInfo(TAG, "Text set successfully")
                Log.i(TAG, "Text set successfully")
            } else {
                Log.i(TAG, "Text set failed")
            }
        }
    }

    private fun addButtonsWithScreenManager() {
        val softButtonObjects: MutableList<SoftButtonObject> = ArrayList()
        val previousSettingState = SoftButtonState("previousSettingState", "Previous Setting", null)
        val previousSettingButton = SoftButtonObject(
            "previousSettingButton",
            previousSettingState,
            object : SoftButtonObject.OnEventListener {
                override fun onPress(
                    softButtonObject: SoftButtonObject,
                    onButtonPress: OnButtonPress
                ) {
                    settingIndex = (settingIndex - 1 + settingList.size) % settingList.size
                    addTextWithScreenManager(settingList[settingIndex])
                }

                override fun onEvent(
                    softButtonObject: SoftButtonObject,
                    onButtonEvent: OnButtonEvent
                ) {
                }
            })
        softButtonObjects.add(previousSettingButton)
        val nextSettingState = SoftButtonState("nextSettingState", "Next Setting", null)
        val nextSettingButton = SoftButtonObject(
            "nextSettingButton",
            nextSettingState,
            object : SoftButtonObject.OnEventListener {
                override fun onPress(
                    softButtonObject: SoftButtonObject,
                    onButtonPress: OnButtonPress
                ) {
                    settingIndex = (settingIndex + 1 + settingList?.size) % settingList?.size
                    addTextWithScreenManager(settingList[settingIndex])
                }

                override fun onEvent(
                    softButtonObject: SoftButtonObject,
                    onButtonEvent: OnButtonEvent
                ) {
                }
            })
        softButtonObjects.add(nextSettingButton)
        val addSettingState = SoftButtonState("addSettingState", "Add", null)
        val addSettingButton = SoftButtonObject(
            "addSettingButton",
            addSettingState,
            object : SoftButtonObject.OnEventListener {
                override fun onPress(
                    softButtonObject: SoftButtonObject,
                    onButtonPress: OnButtonPress
                ) {
                }

                override fun onEvent(
                    softButtonObject: SoftButtonObject,
                    onButtonEvent: OnButtonEvent
                ) {
                }
            })
        softButtonObjects.add(addSettingButton)
        val applySettingState = SoftButtonState("applySettingState", "Add", null)
        val applySettingButton = SoftButtonObject(
            "applySettingButton",
            applySettingState,
            object : SoftButtonObject.OnEventListener {
                override fun onPress(
                    softButtonObject: SoftButtonObject,
                    onButtonPress: OnButtonPress
                ) {
                }

                override fun onEvent(
                    softButtonObject: SoftButtonObject,
                    onButtonEvent: OnButtonEvent
                ) {
                }
            })
        softButtonObjects.add(applySettingButton)
        sdlManager?.screenManager?.beginTransaction()
        sdlManager?.screenManager?.softButtonObjects = softButtonObjects
        sdlManager?.screenManager?.commit { success: Boolean ->
            if (success) {
                DebugTool.logInfo(TAG, "Button set successfully")
                Log.i(TAG, "Button set successfully")
            } else {
                Log.i(TAG, "Button set failed")
            }
        }
    }

    fun setMenu() {
        val mainMenuLayout = MenuLayout.TILES
        val submenuLayout = MenuLayout.LIST
        val menuConfiguration = MenuConfiguration(mainMenuLayout, submenuLayout)
        val cellList: MutableList<MenuCell> = ArrayList()
        val cell = MenuCell(
            "Cell text",
            "Secondary Text",
            "Tertiary Text",
            null,
            null,
            listOf("cell text")
        ) { trigger: TriggerSource? -> }
        cellList.add(cell)
        val innerCell = MenuCell(
            "inner menu cell",
            "secondary text",
            "tertiary test",
            null,
            null,
            listOf("inner menu cell")
        ) { trigger: TriggerSource? -> }
        val subCell = MenuCell(
            "cell",
            "secondary text",
            "tertiary text",
            MenuLayout.LIST,
            null,
            null,
            listOf(innerCell)
        )
        cellList.add(subCell)
        sdlManager?.screenManager?.beginTransaction()
        sdlManager?.screenManager?.menuConfiguration = menuConfiguration
        sdlManager?.screenManager?.menu = cellList
        sdlManager?.screenManager?.commit { success: Boolean ->
            if (success) {
                DebugTool.logInfo(TAG, "Menu set successfully")
                Log.i(TAG, "Menu set successfully")
            } else {
                Log.i(TAG, "Menu set failed")
            }
        }
    }

    private fun buildSampleAlert(): AlertView {
        val builder = AlertView.Builder()
        return builder.setText("Sample Alert")
            .setSecondaryText("Secondary Text")
            .setAudio(AlertAudioData("Sample Alert. Testing 1 2 3."))
            .setTimeout(5)
            .build()
    }

    private fun showSampleSlider() {
        val slider = Slider()
        slider.numTicks = 5
        slider.position = 5
        slider.sliderHeader = "A sweet sample header"
        slider.sliderFooter = listOf("A sweet sample footer")
        slider.cancelID = 99999
        slider.onRPCResponseListener = object : OnRPCResponseListener() {
            override fun onResponse(correlationId: Int, response: RPCResponse) {
                if (response.success) {
                    val sliderResponse = response as SliderResponse
                    Log.i(TAG, "Slider position set: " + sliderResponse.sliderPosition)
                }
            }
        }
        sdlManager?.sendRPC(slider)
    }

    private fun showSampleScrollableMessage() {
        val scrollableMessageText =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.Vestibulum mattis ullamcorper velit sed ullamcorper morbi tincidunt ornare. Purus in massa tempor nec feugiat nisl pretium fusce id. Pharetra convallis posuere morbi leo urna molestie at elementum eu. Dictum sit amet justo donec enim diam."
        val softButton1 = SoftButton(SoftButtonType.SBT_TEXT, 65534)
        softButton1.text = "Button 1"
        val softButton2 = SoftButton(SoftButtonType.SBT_TEXT, 65533)
        softButton2.text = "Button 2"
        val softButtonList = listOf(softButton1, softButton2)
        val scrollableMessage = ScrollableMessage()
            .setScrollableMessageBody(scrollableMessageText)
            .setTimeout(5000)
            .setSoftButtons(softButtonList)
        scrollableMessage.cancelID = 99998
        sdlManager?.sendRPC(scrollableMessage)
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
        private var mapView: MapView? = null
        private lateinit var locationPermissionHelper: LocationPermissionHelper
        private lateinit var mapboxNavigation: MapboxNavigation

        private lateinit var recenterButton: FloatingActionButton
        @Volatile private var centerMap = false

        private val flingListener = {
            centerMap = false
        }

        private val rotateListener = object : OnRotateListener {
            override fun onRotate(detector: RotateGestureDetector) {
                centerMap = false
            }

            override fun onRotateBegin(detector: RotateGestureDetector) {

            }

            override fun onRotateEnd(detector: RotateGestureDetector) {
            }


        }

        private val shoveListener = object : OnShoveListener {
            override fun onShove(detector: ShoveGestureDetector) {
                centerMap = false
            }

            override fun onShoveBegin(detector: ShoveGestureDetector) {

            }

            override fun onShoveEnd(detector: ShoveGestureDetector) {
            }

        }

        private val scaleListener = object : OnScaleListener {
            override fun onScale(detector: StandardScaleGestureDetector) {
                centerMap = false
            }

            override fun onScaleBegin(detector: StandardScaleGestureDetector) {
            }

            override fun onScaleEnd(detector: StandardScaleGestureDetector) {
            }

        }

        private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().bearing(it).build())
            invalidate()
        }

        private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().center(it).build())
            mapView?.gestures?.focalPoint = mapView?.getMapboxMap()?.pixelForCoordinate(it)
            invalidate()
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.stream)
            //searchBar = findViewById(R.id.search_bar)
            //searchButton = findViewById(R.id.search_button)
            mapView = findViewById(R.id.map_view)
            recenterButton = findViewById(R.id.recenter_location_button)
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
            recenterButton.setOnClickListener {
                centerMap = true
            }

        }

        private fun registerGestureListeners() {
            mapView?.gestures?.addOnFlingListener(flingListener)
            mapView?.gestures?.addOnRotateListener(rotateListener)
            mapView?.gestures?.addOnShoveListener(shoveListener)
            mapView?.gestures?.addOnScaleListener(scaleListener)
        }

        private fun unRegisterGestureListeners() {
            mapView?.gestures?.removeOnFlingListener(flingListener)
            mapView?.gestures?.removeOnRotateListener(rotateListener)
            mapView?.gestures?.removeOnShoveListener(shoveListener)
            mapView?.gestures?.removeOnScaleListener(scaleListener)
        }

        private fun onMapReady() {
            mapView?.getMapboxMap()?.setCamera(
                CameraOptions.Builder()
                    .zoom(14.0)
                    .build()
            )
            mapView?.getMapboxMap()?.loadStyleUri(
                Style.MAPBOX_STREETS
            ) {
                initLocationComponent()
                mapView?.getMapboxMap()?.addOnCameraChangeListener {
                    mapView?.getMapboxMap()?.triggerRepaint()
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
                        R.drawable.mapbox_user_puck_icon,
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

    companion object {
        // Arbitrary for the purposes of this app
        private const val FOREGROUND_SERVICE_ID = 1234545
        private const val HASH_ID = "356447790"
        private const val PORT = 12345
        private const val IP_ADDRESS = "192.168.1.56"
        private const val TAG = "SdlService"
    }
}
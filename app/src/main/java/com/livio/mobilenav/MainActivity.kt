package com.livio.mobilenav

import android.Manifest
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.livio.mobilenav.R
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.view.ScaleGestureDetector
import android.widget.EditText
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.graphics.blue
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.livio.mobilenav.SdlService
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.android.gestures.RotateGestureDetector
import com.mapbox.android.gestures.ShoveGestureDetector
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.delegates.listeners.OnCameraChangeListener
import com.mapbox.maps.plugin.gestures.*
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.RouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.ui.maps.camera.view.MapboxRecenterButton
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineOptions
import com.mapbox.navigation.utils.internal.toPoint
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    private var mapView: MapView? = null
    private lateinit var locationPermissionHelper: LocationPermissionHelper
    private lateinit var mapboxNavigation: MapboxNavigation
    //private lateinit var searchBar: EditText
    //private lateinit var searchButton: MaterialButton
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
        if (centerMap) {
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().bearing(it).build())
        }
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        if (centerMap) {
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().center(it).zoom(14.0).build())
            mapView?.gestures?.focalPoint = mapView?.getMapboxMap()?.pixelForCoordinate(it)
        }
    }

    companion object {
        private var instance: WeakReference<MainActivity>? = null

        fun getNewestInstance(): WeakReference<MainActivity>? {
            return instance
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instance = WeakReference(this)

        setContentView(R.layout.activity_main)
        //If we are connected to a module we want to start our SdlService
        //If we are connected to a module we want to start our SdlService
        /*if (BuildConfig.TRANSPORT == "MULTI" || BuildConfig.TRANSPORT == "MULTI_HB") {
            SdlReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }*/
        //searchBar = findViewById(R.id.search_bar)
        //searchButton = findViewById(R.id.search_button)
        mapView = findViewById(R.id.map_view)
        recenterButton = findViewById(R.id.recenter_location_button)
        locationPermissionHelper = LocationPermissionHelper(WeakReference(this))
        val navigationOptions: NavigationOptions = NavigationOptions.Builder(this)
            .accessToken(this.getString(R.string.mapbox_access_token))
            .build()
        mapboxNavigation = MapboxNavigationProvider.create(navigationOptions)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
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
                    this@MainActivity,
                    R.drawable.mapbox_user_puck_icon,
                ),
                shadowImage = AppCompatResources.getDrawable(
                    this@MainActivity,
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationPermissionHelper.getNewestInstance()?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
package com.livio.mobilenav

//
//  MainActivity.kt
//  MobileNav
//
//  Created by Noah Stanford on 2/2/2022.
//  Copyright © 2021 Ford. All rights reserved.
//

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import com.mapbox.maps.plugin.gestures.*
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
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    companion object {
        private val DEFAULT_ZOOM = 18.0
        private val ZOOM_IN_SCALE = 2.0
        private val ZOOM_OUT_SCALE = 0.5
        private var instance: WeakReference<MainActivity>? = null
        const val SEARCH_PIN_SOURCE_ID = "search.pin.source.id"
        const val SEARCH_PIN_IMAGE_ID = "search.pin.image.id"
        const val SEARCH_PIN_LAYER_ID = "search.pin.layer.id"

        fun getNewestInstance(): WeakReference<MainActivity>? {
            return instance
        }

        private fun createSearchPinDrawable(): Drawable? {
            return instance?.get()?.let { it ->
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
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().bearing(it).build())
        }
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        if (centerMap) {
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().center(it).build())
            mapView?.gestures?.focalPoint = mapView?.getMapboxMap()?.pixelForCoordinate(it)
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
        //If we are connected to a module we want to start our SdlService
        /*if (BuildConfig.TRANSPORT == "MULTI" || BuildConfig.TRANSPORT == "MULTI_HB") {
            SdlReceiver.queryForConnectedService(this)
        } else if (BuildConfig.TRANSPORT == "TCP") {
            val proxyIntent = Intent(this, SdlService::class.java)
            startService(proxyIntent)
        }*/
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
        searchBottomSheetView.apply {
            initializeSearch(savedInstanceState, SearchBottomSheetView.Configuration())
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
            initialize(savedInstanceState)
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
                    this@MainActivity,
                    R.drawable.mapbox_navigation_puck_icon,
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
        searchButton.setOnClickListener { searchBottomSheetView.open() }
        zoomInButton.setOnClickListener { mapView?.camera?.scaleBy(ZOOM_IN_SCALE, null) }
        zoomOutButton.setOnClickListener { mapView?.camera?.scaleBy(ZOOM_OUT_SCALE, null) }
        recenterButton.setOnClickListener {
            centerMap = true
            mapView?.getMapboxMap()?.setCamera(CameraOptions.Builder().zoom(DEFAULT_ZOOM).build())
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationPermissionHelper.getNewestInstance()?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
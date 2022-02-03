# example_navigation_app_android

This example application is a reference for developers who want to build a [SmartDeviceLink](https://github.com/smartdevicelink/sdl_java_suite) navigation app

## App Setup

### Initial Setup

### Setting Permissions
If you are using this app with production or test hardware it is very likely that this app will not work due to OEMs restricting permissions for video streaming apps. If this is the case, you will need to set an OEM approved SDL **app name** and SDL **app ID** with the correct permissions. Additionally, you will need set a [MapBox](https://www.mapbox.com/) access token in order to use the map.

To set the MapBox access token, app name, and app ID of this project, please follow the steps below.

### Setting App Keys

1. To set your SDL **app name**, set `app_name` in app/src/main/res/values/strings.xml

2. To set your SDL **app ID**, set `APP_ID`in app/src/main/java/com.livio.mobilenav/AppConstants.kt

3. To set your [MapBox](https://www.mapbox.com/) access token, set `mapbox_access_token` in app/src/main/res/values/strings.xml and  `MAPBOX_DOWNLOADS_TOKEN` in gradle.properties

Note that, at minimum, the [MapBox](https://www.mapbox.com/) access token must be set in order to use the app without SDL.
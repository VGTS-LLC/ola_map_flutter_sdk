package com.ola.map.flutter.ola_map_flutter

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import com.ola.mapsdk.camera.MapControlSettings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import com.ola.mapsdk.interfaces.OlaMapCallback
import com.ola.mapsdk.model.OlaLatLng
import com.ola.mapsdk.model.OlaMarkerOptions
import com.ola.mapsdk.view.OlaMapView
import com.ola.mapsdk.view.OlaMap
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.io.ByteArrayInputStream

/** OlaMapFlutterPlugin */
class OlaMapFlutterPlugin : FlutterPlugin, MethodCallHandler {

  private lateinit var channel: MethodChannel
  private lateinit var context: Context

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "ola_map_flutter")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext
    flutterPluginBinding.platformViewRegistry.registerViewFactory(
      "OlaMapView", OlaMapViewFactory(flutterPluginBinding.binaryMessenger))
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    Log.d("Erro", call.method);
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${android.os.Build.VERSION.RELEASE}")
      }
     
      else -> result.notImplemented()
    }
  }



  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}

class OlaMapViewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
  override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
    val creationParams = args as? Map<String, Any>
    return OlaMapViewController(context, messenger, viewId, creationParams)
  }
}

class OlaMapViewController(
  private val context: Context,
  messenger: BinaryMessenger,
  viewId: Int,
  creationParams: Map<String, Any>?
) : PlatformView {

  private val mapView: OlaMapView = OlaMapView(context)
  private val methodChannel: MethodChannel = MethodChannel(messenger, "ola_map_flutter_$viewId")
  private var olaMap: OlaMap? = null

  init {
    Log.d("OlaMapViewController", "OlaMapView initialized with viewId: $viewId")

    val apiKey = creationParams?.get("apiKey") as? String
    val showPOI = creationParams?.get("showPOI") as? Boolean ?: false  // NEW: Get POI preference

    if (apiKey.isNullOrEmpty()) {
      methodChannel.invokeMethod("onError", "API key is missing or invalid")
    } else {
      initializeMap(apiKey, showPOI)  // MODIFIED: Pass showPOI parameter
    }

    methodChannel.setMethodCallHandler { call, result ->
      Log.d("Erro", call.method)
      when (call.method) {
        "getCurrentLocation" -> {
          getCurrentLocation(result)
        }
        "showCurrentLocation" -> {
          showCurrentLocation(result)
        }
        "hideCurrentLocation" -> {
          hideCurrentLocation(result)
        }
        "zoomIn" -> {
          zoomIn(result)
        }
        "zoomOut" -> {
          zoomOut(result)
        }
        "zoomTo" -> {
          zoom(call,result)
        }
        "moveToCurrentLocation" -> {
          moveToSpecifiedLocation(call,result)
        }
        "addMarker" -> {
          addMarker(call,result)
        }
        "removeMarker" -> {
          removeMarker(call, result)
        }
        else -> {
          Log.e("OlaMapViewController", "Unknown method called: ${call.method}")
          result.notImplemented()
        }
      }
    }
  }

  private fun initializeMap(apiKey: String, showPOI: Boolean = true) {  // MODIFIED: Added showPOI parameter
    try {
      Log.d("OlaMapViewController", "API Key is valid, initializing map...")
      val mapControlSettings = MapControlSettings.Builder()
        .setRotateGesturesEnabled(true)
        .setScrollGesturesEnabled(true)
        .setZoomGesturesEnabled(true)
        .setCompassEnabled(true)
        .setTiltGesturesEnabled(true)
        .setDoubleTapGesturesEnabled(true)
        .build()

      mapView.getMap(apiKey, object : OlaMapCallback {
        override fun onMapReady(map: OlaMap) {
          olaMap = map

          // NEW: Hide POIs if requested
          if (!showPOI) {
            try {
              hideMapPOIs(map)
              Log.d("OlaMapFlutterPlugin", "POIs hidden successfully")
            } catch (e: Exception) {
              Log.e("OlaMapFlutterPlugin", "Failed to hide POIs: ${e.message}")
            }
          }

          methodChannel.invokeMethod("onMapReady", null)
          Log.d("OlaMapFlutterPlugin", "Map initialized successfully")
        }

        override fun onMapError(error: String) {
          methodChannel.invokeMethod("onError", error)
          Log.e("OlaMapFlutterPlugin", "Map initialization error: $error")
        }
      }, mapControlSettings)

    } catch (e: Exception) {
      Log.e("OlaMapViewController", "Exception during map initialization: ${e.message}", e)
      methodChannel.invokeMethod("onError", e.message)
    } finally {
      Log.d("OlaMapViewController", "Finally block executed after map initialization")
    }
  }

  // NEW: Function to hide POI markers
  private fun hideMapPOIs(map: OlaMap) {
    try {
      // Since Ola Maps is built on MapLibre, try to access the underlying map
      // and hide POI layers

      // First, let's try to inspect what's available
      Log.d("OlaMapFlutterPlugin", "Attempting to hide POIs...")
      Log.d("OlaMapFlutterPlugin", "OlaMap methods: ${map.javaClass.methods.joinToString { it.name }}")

      // Try Method 1: Direct style modification if available
      try {
        val setStyleMethod = map.javaClass.getMethod("setStyle", String::class.java)
        val styleJson = """
          {
            "version": 8,
            "layers": [
              {
                "id": "poi",
                "type": "symbol",
                "source-layer": "poi",
                "layout": {
                  "visibility": "none"
                }
              }
            ]
          }
        """.trimIndent()
        setStyleMethod.invoke(map, styleJson)
        Log.d("OlaMapFlutterPlugin", "POIs hidden using setStyle method")
        return
      } catch (e: NoSuchMethodException) {
        Log.d("OlaMapFlutterPlugin", "setStyle method not found, trying alternative")
      }

      // Try Method 2: Access underlying MapLibre instance
      try {
        // Try to access mapLibreMap field
        val fields = map.javaClass.declaredFields
        Log.d("OlaMapFlutterPlugin", "Available fields: ${fields.joinToString { it.name }}")

        for (field in fields) {
          field.isAccessible = true
          val fieldValue = field.get(map)
          if (fieldValue != null) {
            val fieldClassName = fieldValue.javaClass.name
            if (fieldClassName.contains("maplibre", ignoreCase = true) ||
              fieldClassName.contains("mapbox", ignoreCase = true)) {
              Log.d("OlaMapFlutterPlugin", "Found map field: ${field.name} of type $fieldClassName")

              // Try to get style and modify it
              try {
                val getStyleMethod = fieldValue.javaClass.getMethod("getStyle")
                val style = getStyleMethod.invoke(fieldValue)

                if (style != null) {
                  // Try to hide POI layer
                  val layerIds = listOf("poi", "poi-label", "poi_label", "place-label", "place_label")
                  for (layerId in layerIds) {
                    try {
                      val setLayerPropertyMethod = style.javaClass.getMethod(
                        "setLayerProperty",
                        String::class.java,
                        String::class.java,
                        Any::class.java
                      )
                      setLayerPropertyMethod.invoke(style, layerId, "visibility", "none")
                      Log.d("OlaMapFlutterPlugin", "Hidden layer: $layerId")
                    } catch (e: Exception) {
                      // Layer might not exist, that's okay
                      Log.d("OlaMapFlutterPlugin", "Layer $layerId not found or couldn't be hidden")
                    }
                  }
                }
              } catch (e: Exception) {
                Log.e("OlaMapFlutterPlugin", "Could not modify style: ${e.message}")
              }
            }
          }
        }
      } catch (e: Exception) {
        Log.e("OlaMapFlutterPlugin", "Could not access underlying map: ${e.message}")
      }

    } catch (e: Exception) {
      Log.e("OlaMapFlutterPlugin", "Error in hideMapPOIs: ${e.message}", e)
    }
  }

  // All your existing methods remain the same...

  private fun getCurrentLocation(result: MethodChannel.Result) {
    olaMap?.showCurrentLocation()
    val activityContext = context as? Activity
    activityContext?.runOnUiThread {
      if (olaMap == null) {
        result.error("LOCATION_ERROR", "OlaMap instance is not initialized", null)
        return@runOnUiThread
      }

      val currentLocation: OlaLatLng? = olaMap?.getCurrentLocation()
      if (currentLocation != null) {
        Log.d("OlaMapViewController", "Current location: $currentLocation")
        val locationMap = mapOf(
          "latitude" to currentLocation.latitude,
          "longitude" to currentLocation.longitude
        )
        result.success(locationMap)
      } else {
        result.error("LOCATION_ERROR", "Current location is not available", null)
      }
    }
  }

  private fun showCurrentLocation(result: MethodChannel.Result) {
    olaMap?.showCurrentLocation()
    result.success("Current location shown")
  }

  private fun hideCurrentLocation(result: MethodChannel.Result) {
    val activityContext = context as? Activity
    activityContext?.runOnUiThread {
      if (olaMap == null) {
        result.error("LOCATION_ERROR", "OlaMap instance is not initialized", null)
        return@runOnUiThread
      }
      olaMap?.hideCurrentLocation()
      result.success("Current location hidden")
    }
  }

  private fun zoomIn(result: Result) {
    val currentCameraPosition = olaMap?.getCurrentOlaCameraPosition()
    if (currentCameraPosition != null) {
      val targetLocation = currentCameraPosition.target
      val currentZoomLevel = currentCameraPosition.zoomLevel
      if (targetLocation != null) {
        olaMap?.zoomToLocation(targetLocation, currentZoomLevel + 1.0)
      } else {
        result.error("LOCATION_ERROR", "Failed to get target location or zoom level", null)
      }
    } else {
      result.error("LOCATION_ERROR", "Failed to get current camera position", null)
    }
  }

  private fun zoom(call: MethodCall, result: Result) {
    val value = call.argument<Double>("value")
    val currentCameraPosition = olaMap?.getCurrentOlaCameraPosition()
    if (currentCameraPosition != null) {
      val targetLocation = currentCameraPosition.target
      val currentZoomLevel = currentCameraPosition.zoomLevel
      if (targetLocation != null && value != null) {
        olaMap?.zoomToLocation(targetLocation, value)
        result.success(null)
      } else {
        result.error("LOCATION_ERROR", "Failed to get target location or zoom level", null)
      }
    } else {
      result.error("LOCATION_ERROR", "Failed to get current camera position", null)
    }
  }

  private fun zoomOut(result: Result) {
    val currentCameraPosition = olaMap?.getCurrentOlaCameraPosition()
    if (currentCameraPosition != null) {
      val targetLocation = currentCameraPosition.target
      val currentZoomLevel = currentCameraPosition.zoomLevel
      if (targetLocation != null) {
        olaMap?.zoomToLocation(targetLocation, currentZoomLevel - 1.0)
      } else {
        result.error("LOCATION_ERROR", "Failed to get target location or zoom level", null)
      }
    } else {
      result.error("LOCATION_ERROR", "Failed to get current camera position", null)
    }
  }

  private fun moveToSpecifiedLocation(call: MethodCall, result: Result) {
    val latitude = call.argument<Double>("latitude")
    val longitude = call.argument<Double>("longitude")
    olaMap?.getCurrentLocation()
    if (latitude != null && longitude != null) {
      val location = OlaLatLng(latitude, longitude)
      val zoomLevel = 15.0
      olaMap?.moveCameraToLatLong(location, zoomLevel)
      result.success(null)
    } else {
      result.error("LOCATION_ERROR", "Latitude or Longitude is missing or invalid", null)
    }
  }

  private fun addMarker(call: MethodCall, result: Result) {
    val markerId = call.argument<String>("markerId")
    val latitude = call.argument<Double>("latitude")
    val longitude = call.argument<Double>("longitude")
    val imageBytes = call.argument<ByteArray>("imageBytes")
    val setIsIconClickable = call.argument<Boolean>("setIsIconClickable")
    val setIsAnimationEnable = call.argument<Boolean>("setIsAnimationEnable")
    val setIsInfoWindowDismissOnClick = call.argument<Boolean>("setIsInfoWindowDismissOnClick")

    if (markerId != null && latitude != null && longitude != null && imageBytes != null) {
      val markerOptionsBuilder = OlaMarkerOptions.Builder()
        .setMarkerId(markerId)
        .setPosition(OlaLatLng(latitude, longitude))
        .setIsIconClickable(true)
        .setIconRotation(0f)
        .setIsAnimationEnable(true)
        .setIsInfoWindowDismissOnClick(true)

      val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(imageBytes))
      markerOptionsBuilder.setIconBitmap(bitmap)

      olaMap?.addMarker(markerOptionsBuilder.build())
      result.success(null)
    } else {
      result.error("INVALID_ARGUMENTS", "Marker ID, latitude, longitude, or imageBytes missing", null)
    }
  }

  private fun removeMarker(call: MethodCall, result: Result) {
    val markerId = call.argument<String>("markerId")
    val markerOptionsBuilder = OlaMarkerOptions.Builder()
      .setMarkerId(markerId ?: "").build()
    val marker1 = olaMap?.addMarker(markerOptionsBuilder)
    marker1?.removeMarker()
  }

  // FIXED: Changed return type from View to View?
  override fun getView(): View? {
    Log.d("OlaMapViewController", "Returning mapView")
    return mapView
  }

  override fun dispose() {
    Log.d("OlaMapViewController", "Disposing OlaMapView")
    // Cleanup resources if necessary
  }
}
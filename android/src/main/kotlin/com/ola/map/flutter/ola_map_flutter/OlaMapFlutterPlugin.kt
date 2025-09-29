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
    val showPOI = creationParams?.get("showPOI") as? Boolean ?: true  // Default to true

    if (apiKey.isNullOrEmpty()) {
      methodChannel.invokeMethod("onError", "API key is missing or invalid")
    } else {
      initializeMap(apiKey, showPOI)
    }

    methodChannel.setMethodCallHandler { call, result ->
      Log.d("OlaMapMethod", call.method)
      when (call.method) {
        "getCurrentLocation" -> getCurrentLocation(result)
        "showCurrentLocation" -> showCurrentLocation(result)
        "hideCurrentLocation" -> hideCurrentLocation(result)
        "zoomIn" -> zoomIn(result)
        "zoomOut" -> zoomOut(result)
        "zoomTo" -> zoom(call, result)
        "moveToCurrentLocation" -> moveToSpecifiedLocation(call, result)
        "addMarker" -> addMarker(call, result)
        "removeMarker" -> removeMarker(call, result)
        else -> {
          Log.e("OlaMapViewController", "Unknown method called: ${call.method}")
          result.notImplemented()
        }
      }
    }
  }

  private fun initializeMap(apiKey: String, showPOI: Boolean) {
    try {
      Log.d("OlaMapInit", "Initializing map with showPOI: $showPOI")

      // Try to modify the MapView before initialization
      if (!showPOI) {
        try {
          modifyMapViewToHidePOI()
        } catch (e: Exception) {
          Log.e("OlaMapInit", "Failed to modify MapView: ${e.message}")
        }
      }

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
          Log.d("OlaMapInit", "Map ready, attempting to hide POIs: $showPOI")

          if (!showPOI) {
            // Post with delay to ensure map is fully loaded
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
              hideMapPOIs(map)
            }, 500)
          }

          methodChannel.invokeMethod("onMapReady", null)
        }

        override fun onMapError(error: String) {
          methodChannel.invokeMethod("onError", error)
          Log.e("OlaMapInit", "Map initialization error: $error")
        }
      }, mapControlSettings)

    } catch (e: Exception) {
      Log.e("OlaMapInit", "Exception during initialization: ${e.message}", e)
      methodChannel.invokeMethod("onError", e.message)
    }
  }

  private fun modifyMapViewToHidePOI() {
    try {
      // Access OlaMapView's internal fields to modify style before loading
      val fields = mapView.javaClass.declaredFields
      Log.d("OlaMapPOI", "OlaMapView fields: ${fields.joinToString { it.name }}")

      // Try to find and modify style URL or configuration
      for (field in fields) {
        field.isAccessible = true
        Log.d("OlaMapPOI", "Field: ${field.name}, Type: ${field.type}")

        if (field.name.contains("style", ignoreCase = true) ||
          field.name.contains("url", ignoreCase = true)) {
          try {
            val value = field.get(mapView)
            Log.d("OlaMapPOI", "Field ${field.name} value: $value")

            // Try to modify style URL to hide POI
            if (value is String && value.contains("style")) {
              val newUrl = "$value?poi=false"
              field.set(mapView, newUrl)
              Log.d("OlaMapPOI", "Modified style URL: $newUrl")
            }
          } catch (e: Exception) {
            Log.e("OlaMapPOI", "Cannot modify field ${field.name}: ${e.message}")
          }
        }
      }
    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in modifyMapViewToHidePOI: ${e.message}", e)
    }
  }

  private fun hideMapPOIs(map: OlaMap) {
    try {
      Log.d("OlaMapPOI", "=== Starting POI hiding process ===")

      // Log all available methods on OlaMap
      val methods = map.javaClass.methods
      Log.d("OlaMapPOI", "Available OlaMap methods:")
      methods.forEach { method ->
        Log.d("OlaMapPOI", "  - ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
      }

      // Log all fields
      val fields = map.javaClass.declaredFields
      Log.d("OlaMapPOI", "Available OlaMap fields:")
      fields.forEach { field ->
        field.isAccessible = true
        Log.d("OlaMapPOI", "  - ${field.name}: ${field.type.simpleName}")
      }

      // Try to access underlying map implementation
      var mapLibreFound = false
      for (field in fields) {
        field.isAccessible = true
        try {
          val fieldValue = field.get(map)
          if (fieldValue != null) {
            val className = fieldValue.javaClass.name
            Log.d("OlaMapPOI", "Field ${field.name} is of type: $className")

            if (className.contains("maplibre", ignoreCase = true) ||
              className.contains("mapbox", ignoreCase = true) ||
              className.contains("map", ignoreCase = true)) {

              mapLibreFound = true
              Log.d("OlaMapPOI", "Found potential map object in field: ${field.name}")

              // Try to get the style
              try {
                val mapMethods = fieldValue.javaClass.methods
                Log.d("OlaMapPOI", "Methods on ${field.name}:")
                mapMethods.forEach { method ->
                  Log.d("OlaMapPOI", "    - ${method.name}")
                }

                // Try getStyle
                val getStyleMethod = fieldValue.javaClass.getMethod("getStyle")
                val style = getStyleMethod.invoke(fieldValue)

                if (style != null) {
                  Log.d("OlaMapPOI", "Got style object: ${style.javaClass.name}")

                  // Log style methods
                  val styleMethods = style.javaClass.methods
                  Log.d("OlaMapPOI", "Style methods:")
                  styleMethods.forEach { method ->
                    Log.d("OlaMapPOI", "    - ${method.name}")
                  }

                  // Try to get all layer IDs
                  try {
                    val getLayersMethod = style.javaClass.getMethod("getLayers")
                    val layers = getLayersMethod.invoke(style)
                    if (layers is List<*>) {
                      Log.d("OlaMapPOI", "Found ${layers.size} layers:")
                      layers.forEach { layer ->
                        if (layer != null) {
                          try {
                            val getIdMethod = layer.javaClass.getMethod("getId")
                            val layerId = getIdMethod.invoke(layer) as? String
                            Log.d("OlaMapPOI", "    Layer ID: $layerId")

                            // Hide POI-related layers
                            if (layerId != null && (
                                      layerId.contains("poi", ignoreCase = true) ||
                                              layerId.contains("place", ignoreCase = true) ||
                                              layerId.contains("label", ignoreCase = true))) {

                              try {
                                val removeLayerMethod = style.javaClass.getMethod("removeLayer", String::class.java)
                                removeLayerMethod.invoke(style, layerId)
                                Log.d("OlaMapPOI", "✓ Removed layer: $layerId")
                              } catch (e: Exception) {
                                // Try visibility property instead
                                try {
                                  val setLayerPropertyMethod = style.javaClass.getMethod(
                                    "setLayerProperty",
                                    String::class.java,
                                    String::class.java,
                                    Any::class.java
                                  )
                                  setLayerPropertyMethod.invoke(style, layerId, "visibility", "none")
                                  Log.d("OlaMapPOI", "✓ Hid layer: $layerId")
                                } catch (e2: Exception) {
                                  Log.e("OlaMapPOI", "✗ Failed to hide layer $layerId: ${e2.message}")
                                }
                              }
                            }
                          } catch (e: Exception) {
                            Log.e("OlaMapPOI", "Error processing layer: ${e.message}")
                          }
                        }
                      }
                    }
                  } catch (e: Exception) {
                    Log.e("OlaMapPOI", "Could not get layers: ${e.message}")
                  }
                } else {
                  Log.w("OlaMapPOI", "Style is null")
                }
              } catch (e: Exception) {
                Log.e("OlaMapPOI", "Error accessing style from ${field.name}: ${e.message}")
              }
            }
          }
        } catch (e: Exception) {
          Log.e("OlaMapPOI", "Error accessing field ${field.name}: ${e.message}")
        }
      }

      if (!mapLibreFound) {
        Log.w("OlaMapPOI", "Could not find underlying map implementation")
      }

      Log.d("OlaMapPOI", "=== POI hiding process complete ===")

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in hideMapPOIs: ${e.message}", e)
    }
  }

  // ... [Keep all your existing methods exactly as they are] ...

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
        result.error("LOCATION_ERROR", "Failed to get target location", null)
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
        result.error("LOCATION_ERROR", "Failed to get target location", null)
      }
    } else {
      result.error("LOCATION_ERROR", "Failed to get current camera position", null)
    }
  }

  private fun moveToSpecifiedLocation(call: MethodCall, result: Result) {
    val latitude = call.argument<Double>("latitude")
    val longitude = call.argument<Double>("longitude")
    if (latitude != null && longitude != null) {
      val location = OlaLatLng(latitude, longitude)
      olaMap?.moveCameraToLatLong(location, 15.0)
      result.success(null)
    } else {
      result.error("LOCATION_ERROR", "Latitude or Longitude is missing", null)
    }
  }

  private fun addMarker(call: MethodCall, result: Result) {
    val markerId = call.argument<String>("markerId")
    val latitude = call.argument<Double>("latitude")
    val longitude = call.argument<Double>("longitude")
    val imageBytes = call.argument<ByteArray>("imageBytes")

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
      result.error("INVALID_ARGUMENTS", "Marker parameters missing", null)
    }
  }

  private fun removeMarker(call: MethodCall, result: Result) {
    val markerId = call.argument<String>("markerId")
    val markerOptionsBuilder = OlaMarkerOptions.Builder()
      .setMarkerId(markerId ?: "").build()
    val marker1 = olaMap?.addMarker(markerOptionsBuilder)
    marker1?.removeMarker()
  }

  override fun getView(): View? {
    return mapView
  }

  override fun dispose() {
    Log.d("OlaMapViewController", "Disposing OlaMapView")
  }
}
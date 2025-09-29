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
    Log.d("Erro", call.method)
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
    val showPOI = creationParams?.get("showPOI") as? Boolean ?: true

    if (apiKey.isNullOrEmpty()) {
      methodChannel.invokeMethod("onError", "API key is missing or invalid")
    } else {
      initializeMap(apiKey, showPOI)
    }

    methodChannel.setMethodCallHandler { call, result ->
      Log.d("Erro", call.method)
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
      Log.d("OlaMapInit", "Initializing map - showPOI: $showPOI")

      // NEW: Try to modify API key to include style parameter
      val modifiedApiKey = if (!showPOI) {
        Log.d("OlaMapInit", "Attempting to use custom style URL")
        apiKey // We'll modify after map is ready
      } else {
        apiKey
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
          Log.d("OlaMapInit", "✓ Map ready!")

          if (!showPOI) {
            // Try to load custom style
            tryLoadCustomStyle(map)

            // Also try hiding layers
            hideAllPOIs(map)

            // Retry after delays
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
              hideAllPOIs(map)
            }, 500)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
              hideAllPOIs(map)
            }, 1500)
          }

          methodChannel.invokeMethod("onMapReady", null)
        }

        override fun onMapError(error: String) {
          methodChannel.invokeMethod("onError", error)
          Log.e("OlaMapInit", "Map initialization error: $error")
        }
      }, mapControlSettings)

    } catch (e: Exception) {
      Log.e("OlaMapInit", "Exception: ${e.message}", e)
      methodChannel.invokeMethod("onError", e.message)
    }
  }

  // NEW: Try to load a custom style without POIs
  private fun tryLoadCustomStyle(map: OlaMap) {
    try {
      Log.d("OlaMapStyle", "Attempting to load custom style")

      // Try to find a setStyleUrl or similar method
      val methods = map.javaClass.methods

      for (method in methods) {
        if (method.name.contains("style", ignoreCase = true) &&
          method.parameterCount == 1 &&
          method.parameterTypes[0] == String::class.java) {

          Log.d("OlaMapStyle", "Found style method: ${method.name}")

          try {
            // Try Ola Maps style URL without POI
            val customStyleUrl = "https://api.olamaps.io/tiles/vector/v1/styles/default-light-standard/style.json"
            method.invoke(map, customStyleUrl)
            Log.d("OlaMapStyle", "✓ Applied custom style URL")
            return
          } catch (e: Exception) {
            Log.e("OlaMapStyle", "Failed to apply style: ${e.message}")
          }
        }
      }

      Log.w("OlaMapStyle", "No style method found")

    } catch (e: Exception) {
      Log.e("OlaMapStyle", "Error in tryLoadCustomStyle: ${e.message}")
    }
  }

  private fun hideAllPOIs(map: OlaMap) {
    try {
      Log.d("OlaMapPOI", "========================================")
      Log.d("OlaMapPOI", "Starting POI removal process")
      Log.d("OlaMapPOI", "========================================")

      // Get the MapboxMapImpl field we found in logs
      val mapField = map.javaClass.getDeclaredField("map")
      mapField.isAccessible = true
      val mapboxMapImpl = mapField.get(map)

      if (mapboxMapImpl == null) {
        Log.e("OlaMapPOI", "MapboxMapImpl is null")
        return
      }

      Log.d("OlaMapPOI", "✓ Got MapboxMapImpl: ${mapboxMapImpl.javaClass.name}")

      // Get the native Mapbox/MapLibre map
      try {
        val getNativeMapMethod = mapboxMapImpl.javaClass.getMethod("getNativeMap")
        val nativeMap = getNativeMapMethod.invoke(mapboxMapImpl)

        if (nativeMap != null) {
          Log.d("OlaMapPOI", "✓ Got native map: ${nativeMap.javaClass.name}")

          // Access the actual MapLibre/Mapbox map instance
          val nativeMethods = nativeMap.javaClass.methods
          Log.d("OlaMapPOI", "Native map methods: ${nativeMethods.map { it.name }.distinct().sorted().joinToString()}")

          // Try to get style from native map
          try {
            val getStyleMethod = nativeMap.javaClass.getMethod("getStyle")
            val style = getStyleMethod.invoke(nativeMap)

            if (style != null) {
              Log.d("OlaMapPOI", "✓ Got style from native map!")
              removeAllPOILayers(style)
            } else {
              Log.w("OlaMapPOI", "Style is null, scheduling retry...")

              // Style might not be loaded yet, retry after delay
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tryRemovePOIsFromNativeMap(nativeMap)
              }, 1000)
            }
          } catch (e: Exception) {
            Log.e("OlaMapPOI", "Error getting style: ${e.message}")
          }
        }
      } catch (e: Exception) {
        Log.e("OlaMapPOI", "Could not get native map: ${e.message}")
      }

      // Also try direct approach on MapboxMapImpl
      try {
        val mapViewField = mapboxMapImpl.javaClass.getDeclaredField("mapView")
        mapViewField.isAccessible = true
        val mapView = mapViewField.get(mapboxMapImpl)

        if (mapView != null) {
          Log.d("OlaMapPOI", "✓ Got MapView: ${mapView.javaClass.name}")
          tryRemovePOIsFromMapView(mapView)
        }
      } catch (e: Exception) {
        Log.e("OlaMapPOI", "Could not access MapView: ${e.message}")
      }

      Log.d("OlaMapPOI", "========================================")

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in hideAllPOIs: ${e.message}", e)
    }
  }

  private fun tryRemovePOIsFromNativeMap(nativeMap: Any) {
    try {
      val getStyleMethod = nativeMap.javaClass.getMethod("getStyle")
      val style = getStyleMethod.invoke(nativeMap)

      if (style != null) {
        Log.d("OlaMapPOI", "✓ Got style on retry!")
        removeAllPOILayers(style)
      } else {
        Log.w("OlaMapPOI", "Style still null on retry")
      }
    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Retry failed: ${e.message}")
    }
  }

  private fun tryRemovePOIsFromMapView(mapView: Any) {
    try {
      // MapLibre MapView should have getMapAsync or similar
      val methods = mapView.javaClass.methods
      Log.d("OlaMapPOI", "MapView methods: ${methods.map { it.name }.distinct().take(30).sorted().joinToString()}")

      // Try to get the map from MapView
      for (methodName in listOf("getMapAsync", "getMap", "getMapboxMap")) {
        try {
          val method = mapView.javaClass.getMethod(methodName)
          Log.d("OlaMapPOI", "Found method: $methodName")
          // This might need a callback, skip for now
        } catch (e: Exception) {
          // Method doesn't exist
        }
      }
    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in tryRemovePOIsFromMapView: ${e.message}")
    }
  }

  private fun removeAllPOILayers(style: Any) {
    try {
      Log.d("OlaMapPOI", "--- Removing POI layers from style ---")

      // Get all layers
      val getLayersMethod = style.javaClass.getMethod("getLayers")
      val layers = getLayersMethod.invoke(style) as? List<*>

      if (layers == null || layers.isEmpty()) {
        Log.w("OlaMapPOI", "No layers found in style")
        return
      }

      Log.d("OlaMapPOI", "✓ Found ${layers.size} total layers")

      // Collect all layer IDs
      val layerIdsToRemove = mutableListOf<String>()

      for (layer in layers) {
        if (layer != null) {
          try {
            val getIdMethod = layer.javaClass.getMethod("getId")
            val layerId = getIdMethod.invoke(layer) as? String

            if (layerId != null) {
              // Check if this is a POI/place/label layer
              val isPOI = layerId.contains("poi", ignoreCase = true) ||
                      layerId.contains("place", ignoreCase = true) ||
                      layerId.contains("label", ignoreCase = true) ||
                      layerId.contains("symbol", ignoreCase = true)

              Log.d("OlaMapPOI", "Layer: $layerId ${if (isPOI) "→ WILL REMOVE" else ""}")

              if (isPOI) {
                layerIdsToRemove.add(layerId)
              }
            }
          } catch (e: Exception) {
            Log.e("OlaMapPOI", "Error processing layer: ${e.message}")
          }
        }
      }

      // Now remove the POI layers
      var removedCount = 0
      for (layerId in layerIdsToRemove) {
        try {
          val removeLayerMethod = style.javaClass.getMethod("removeLayer", String::class.java)
          removeLayerMethod.invoke(style, layerId)
          Log.d("OlaMapPOI", "✓ REMOVED: $layerId")
          removedCount++
        } catch (e: Exception) {
          Log.e("OlaMapPOI", "✗ Failed to remove $layerId: ${e.message}")

          // Try alternative: hide with visibility
          try {
            val getLayerMethod = style.javaClass.getMethod("getLayer", String::class.java)
            val layer = getLayerMethod.invoke(style, layerId)

            if (layer != null) {
              // Try to set visibility to NONE
              try {
                // This is MapLibre/Mapbox style
                val propertyValueClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyValue")
                val propertyFactoryClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyFactory")
                val visibilityMethod = propertyFactoryClass.getMethod("visibility", String::class.java)
                val visibilityValue = visibilityMethod.invoke(null, "none")

                val setPropertiesMethod = layer.javaClass.getMethod("setProperties", propertyValueClass)
                setPropertiesMethod.invoke(layer, visibilityValue)

                Log.d("OlaMapPOI", "✓ HIDDEN: $layerId (via visibility)")
                removedCount++
              } catch (e2: Exception) {
                Log.e("OlaMapPOI", "Could not set visibility: ${e2.message}")
              }
            }
          } catch (e2: Exception) {
            Log.e("OlaMapPOI", "Could not hide layer: ${e2.message}")
          }
        }
      }

      Log.d("OlaMapPOI", "========================================")
      Log.d("OlaMapPOI", "✓✓✓ Successfully processed $removedCount/${layerIdsToRemove.size} POI layers")
      Log.d("OlaMapPOI", "========================================")

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in removeAllPOILayers: ${e.message}", e)
    }
  }

  private fun tryHidePOIsFromMapObject(mapObject: Any, fieldName: String): Boolean {
    try {
      Log.d("OlaMapPOI", "--- Analyzing $fieldName ---")

      // Log all methods
      val methods = mapObject.javaClass.methods
      val methodNames = methods.map { it.name }.distinct().sorted()
      Log.d("OlaMapPOI", "Available methods (${methodNames.size}): ${methodNames.take(20).joinToString()}")

      // Try to get style
      var style: Any? = null

      for (methodName in listOf("getStyle", "style", "getMapStyle")) {
        try {
          val method = mapObject.javaClass.getMethod(methodName)
          style = method.invoke(mapObject)
          if (style != null) {
            Log.d("OlaMapPOI", "✓ Got style using $methodName")
            break
          }
        } catch (e: Exception) {
          // Method doesn't exist, try next
        }
      }

      if (style == null) {
        Log.w("OlaMapPOI", "✗ Could not get style from $fieldName")
        return false
      }

      Log.d("OlaMapPOI", "✓ Style object: ${style.javaClass.name}")

      // Get all layers
      var layers: List<*>? = null
      try {
        val getLayersMethod = style.javaClass.getMethod("getLayers")
        layers = getLayersMethod.invoke(style) as? List<*>
        Log.d("OlaMapPOI", "✓ Found ${layers?.size ?: 0} layers")
      } catch (e: Exception) {
        Log.e("OlaMapPOI", "✗ Could not get layers: ${e.message}")
        return false
      }

      if (layers.isNullOrEmpty()) {
        Log.w("OlaMapPOI", "✗ No layers found")
        return false
      }

      // List all layer IDs
      val layerIds = mutableListOf<String>()
      layers.forEach { layer ->
        if (layer != null) {
          try {
            val getIdMethod = layer.javaClass.getMethod("getId")
            val layerId = getIdMethod.invoke(layer) as? String
            if (layerId != null) {
              layerIds.add(layerId)
            }
          } catch (e: Exception) {
            // Skip
          }
        }
      }

      Log.d("OlaMapPOI", "Layer IDs: ${layerIds.joinToString()}")

      // Hide POI-related layers
      val poiPatterns = listOf(
        "poi", "place", "label", "marker", "symbol",
        "shop", "store", "restaurant", "cafe", "hotel",
        "hospital", "bank", "school", "park"
      )

      var hiddenCount = 0

      for (layerId in layerIds) {
        val shouldHide = poiPatterns.any { pattern ->
          layerId.contains(pattern, ignoreCase = true)
        }

        if (shouldHide) {
          Log.d("OlaMapPOI", "Attempting to hide: $layerId")

          // Try method 1: Remove layer
          var hidden = false
          try {
            val removeLayerMethod = style.javaClass.getMethod("removeLayer", String::class.java)
            removeLayerMethod.invoke(style, layerId)
            Log.d("OlaMapPOI", "  ✓ Removed layer: $layerId")
            hiddenCount++
            hidden = true
          } catch (e: Exception) {
            Log.d("OlaMapPOI", "  ✗ Could not remove: ${e.message}")
          }

          // Try method 2: Set visibility to none
          if (!hidden) {
            try {
              val getLayerMethod = style.javaClass.getMethod("getLayer", String::class.java)
              val layer = getLayerMethod.invoke(style, layerId)

              if (layer != null) {
                try {
                  val setVisibilityMethod = layer.javaClass.getMethod("setProperties", Any::class.java)
                  // This might not work, but let's try
                  Log.d("OlaMapPOI", "  ~ Trying setProperties on layer")
                } catch (e: Exception) {
                  // Try setting visibility property
                  try {
                    // MapLibre style approach
                    val valueClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyValue")
                    val visibilityClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyFactory")
                    val visibilityMethod = visibilityClass.getMethod("visibility", String::class.java)
                    val visibilityValue = visibilityMethod.invoke(null, "none")

                    val setPropertiesMethod = layer.javaClass.getMethod("setProperties", valueClass)
                    setPropertiesMethod.invoke(layer, visibilityValue)

                    Log.d("OlaMapPOI", "  ✓ Hidden via visibility: $layerId")
                    hiddenCount++
                  } catch (e2: Exception) {
                    Log.d("OlaMapPOI", "  ✗ Could not set visibility: ${e2.message}")
                  }
                }
              }
            } catch (e: Exception) {
              Log.d("OlaMapPOI", "  ✗ Could not get layer: ${e.message}")
            }
          }
        }
      }

      Log.d("OlaMapPOI", "✓ Hidden $hiddenCount POI layers")
      return hiddenCount > 0

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in tryHidePOIsFromMapObject: ${e.message}", e)
      return false
    }
  }

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
    if (latitude != null && longitude != null) {
      val location = OlaLatLng(latitude, longitude)
      olaMap?.moveCameraToLatLong(location, 15.0)
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

  override fun getView(): View? {
    return mapView
  }

  override fun dispose() {
    Log.d("OlaMapViewController", "Disposing OlaMapView")
  }
}
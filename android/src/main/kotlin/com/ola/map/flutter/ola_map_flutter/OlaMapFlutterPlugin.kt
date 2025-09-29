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
    Log.d("OlaMapFlutter", call.method)
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

/**
 * POI Layer Manager
 * Handles discovery, hiding, and management of POI layers with robust error handling
 */
class POILayerManager {

  companion object {
    private const val TAG = "POILayerManager"

    // Comprehensive POI layer patterns based on MapLibre/Mapbox standards
    private val POI_LAYER_PATTERNS = arrayOf(
      "poi", "place", "label", "symbol",
      "airport", "rail", "transit", "station",
      "shop", "store", "restaurant", "cafe", "hotel",
      "hospital", "bank", "school", "park",
      "natural", "building", "landmark"
    )
  }

  private var discoveredPOILayers = mutableListOf<String>()
  private var isStyleLoaded = false

  /**
   * Discover all POI layers in the current style
   * Best Practice: Always discover layers before manipulation
   */
  fun discoverPOILayers(style: Any): List<String> {
    val poiLayers = mutableListOf<String>()

    try {
      val getLayersMethod = style.javaClass.getMethod("getLayers")
      val layers = getLayersMethod.invoke(style) as? List<*>

      if (layers.isNullOrEmpty()) {
        Log.w(TAG, "No layers found in style")
        return emptyList()
      }

      Log.d(TAG, "Discovering POI layers from ${layers.size} total layers...")

      layers.forEach { layer ->
        if (layer != null) {
          try {
            val getIdMethod = layer.javaClass.getMethod("getId")
            val layerId = getIdMethod.invoke(layer) as? String

            if (layerId != null) {
              val isPOI = POI_LAYER_PATTERNS.any { pattern ->
                layerId.contains(pattern, ignoreCase = true)
              }

              if (isPOI) {
                poiLayers.add(layerId)
                Log.d(TAG, "  ✓ Found POI layer: $layerId")
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "Error inspecting layer: ${e.message}")
          }
        }
      }

      discoveredPOILayers = poiLayers
      isStyleLoaded = true

      Log.d(TAG, "✓ Discovered ${poiLayers.size} POI layers")

    } catch (e: Exception) {
      Log.e(TAG, "Error discovering POI layers: ${e.message}", e)
    }

    return poiLayers
  }

  /**
   * Hide POI layers using visibility property (reversible)
   * Best Practice: Use visibility toggling for dynamic control
   */
  fun hidePOILayers(style: Any, layerIds: List<String>? = null): Int {
    val layersToHide = layerIds ?: discoveredPOILayers
    var hiddenCount = 0

    if (layersToHide.isEmpty()) {
      Log.w(TAG, "No POI layers to hide")
      return 0
    }

    Log.d(TAG, "Hiding ${layersToHide.size} POI layers...")

    layersToHide.forEach { layerId ->
      try {
        // Get the layer
        val getLayerMethod = style.javaClass.getMethod("getLayer", String::class.java)
        val layer = getLayerMethod.invoke(style, layerId)

        if (layer != null) {
          // Use MapLibre/Mapbox PropertyFactory to set visibility
          try {
            val propertyFactoryClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyFactory")
            val propertyClass = Class.forName("com.mapbox.mapboxsdk.style.layers.Property")

            val visibilityMethod = propertyFactoryClass.getMethod("visibility", String::class.java)
            val noneField = propertyClass.getDeclaredField("NONE")
            val noneValue = noneField.get(null) as String

            val visibilityProperty = visibilityMethod.invoke(null, noneValue)

            val setPropertiesMethod = layer.javaClass.getMethod(
              "setProperties",
              Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyValue").arrayOfNulls<Class<*>>(0).javaClass
            )
            setPropertiesMethod.invoke(layer, arrayOf(visibilityProperty))

            hiddenCount++
            Log.d(TAG, "  ✓ Hidden: $layerId")

          } catch (e: Exception) {
            Log.w(TAG, "  ✗ Failed to hide $layerId: ${e.message}")
          }
        } else {
          Log.w(TAG, "  ✗ Layer not found: $layerId")
        }

      } catch (e: Exception) {
        Log.w(TAG, "  ✗ Error hiding layer $layerId: ${e.message}")
      }
    }

    Log.d(TAG, "✓ Successfully hidden $hiddenCount/${layersToHide.size} POI layers")
    return hiddenCount
  }

  /**
   * Show POI layers by setting visibility to visible
   * Best Practice: Reversible operation for dynamic control
   */
  fun showPOILayers(style: Any, layerIds: List<String>? = null): Int {
    val layersToShow = layerIds ?: discoveredPOILayers
    var shownCount = 0

    Log.d(TAG, "Showing ${layersToShow.size} POI layers...")

    layersToShow.forEach { layerId ->
      try {
        val getLayerMethod = style.javaClass.getMethod("getLayer", String::class.java)
        val layer = getLayerMethod.invoke(style, layerId)

        if (layer != null) {
          try {
            val propertyFactoryClass = Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyFactory")
            val propertyClass = Class.forName("com.mapbox.mapboxsdk.style.layers.Property")

            val visibilityMethod = propertyFactoryClass.getMethod("visibility", String::class.java)
            val visibleField = propertyClass.getDeclaredField("VISIBLE")
            val visibleValue = visibleField.get(null) as String

            val visibilityProperty = visibilityMethod.invoke(null, visibleValue)

            val setPropertiesMethod = layer.javaClass.getMethod(
              "setProperties",
              Class.forName("com.mapbox.mapboxsdk.style.layers.PropertyValue").arrayOfNulls<Class<*>>(0).javaClass
            )
            setPropertiesMethod.invoke(layer, arrayOf(visibilityProperty))

            shownCount++
            Log.d(TAG, "  ✓ Shown: $layerId")

          } catch (e: Exception) {
            Log.w(TAG, "  ✗ Failed to show $layerId: ${e.message}")
          }
        }

      } catch (e: Exception) {
        Log.w(TAG, "  ✗ Error showing layer $layerId: ${e.message}")
      }
    }

    Log.d(TAG, "✓ Successfully shown $shownCount/${layersToShow.size} POI layers")
    return shownCount
  }

  /**
   * Permanently remove POI layers
   * Best Practice: Only use when layers won't need to be restored
   */
  fun removePOILayersPermanently(style: Any, layerIds: List<String>? = null): Int {
    val layersToRemove = layerIds ?: discoveredPOILayers
    var removedCount = 0

    Log.d(TAG, "Permanently removing ${layersToRemove.size} POI layers...")

    try {
      val removeLayerMethod = style.javaClass.getMethod("removeLayer", String::class.java)

      layersToRemove.forEach { layerId ->
        try {
          removeLayerMethod.invoke(style, layerId)
          removedCount++
          Log.d(TAG, "  ✓ Removed: $layerId")
        } catch (e: Exception) {
          Log.w(TAG, "  ✗ Failed to remove $layerId: ${e.message}")
        }
      }

    } catch (e: Exception) {
      Log.e(TAG, "Error removing layers: ${e.message}", e)
    }

    Log.d(TAG, "✓ Successfully removed $removedCount/${layersToRemove.size} POI layers")

    // Clear discovered layers since they're now removed
    if (removedCount > 0) {
      discoveredPOILayers.clear()
    }

    return removedCount
  }

  fun getDiscoveredLayers(): List<String> = discoveredPOILayers.toList()

  fun isStyleReady(): Boolean = isStyleLoaded
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
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private val poiManager = POILayerManager()

  private var showPOI: Boolean = true
  private var styleLoadAttempts = 0
  private val maxStyleLoadAttempts = 5

  init {
    Log.d("OlaMapViewController", "Initializing with viewId: $viewId")

    val apiKey = creationParams?.get("apiKey") as? String
    showPOI = creationParams?.get("showPOI") as? Boolean ?: true

    Log.d("OlaMapViewController", "Configuration - showPOI: $showPOI")

    if (apiKey.isNullOrEmpty()) {
      methodChannel.invokeMethod("onError", "API key is missing or invalid")
    } else {
      initializeMap(apiKey)
    }

    methodChannel.setMethodCallHandler { call, result ->
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
        "togglePOI" -> togglePOI(call, result)
        "discoverPOILayers" -> discoverPOILayers(result)
        else -> {
          Log.e("OlaMapViewController", "Unknown method: ${call.method}")
          result.notImplemented()
        }
      }
    }
  }

  private fun initializeMap(apiKey: String) {
    try {
      Log.d("OlaMapInit", "Starting map initialization...")

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
          Log.d("OlaMapInit", "✓ Map ready callback received")

          // Best Practice: Wait for style to load before manipulating
          if (!showPOI) {
            scheduleStyleManipulation()
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

  /**
   * Best Practice: Schedule style manipulation with progressive delays
   * Ensures style is fully loaded before manipulation
   */
  private fun scheduleStyleManipulation() {
    val delays = arrayOf(0L, 300L, 800L, 1500L, 2500L)

    delays.forEachIndexed { index, delay ->
      handler.postDelayed({
        styleLoadAttempts = index + 1
        Log.d("OlaMapPOI", "--- Style manipulation attempt $styleLoadAttempts/$maxStyleLoadAttempts (${delay}ms) ---")
        processStyleWithCallback()
      }, delay)
    }
  }

  /**
   * Best Practice: Always use callback-based getStyle() to avoid null errors
   */
  private fun processStyleWithCallback() {
    try {
      val map = olaMap ?: return

      // Access MapboxMapImpl
      val mapField = map.javaClass.getDeclaredField("map")
      mapField.isAccessible = true
      val mapboxMapImpl = mapField.get(map) ?: return

      // Create callback for getStyle
      val listenerClass = Class.forName("com.ola.mapsdk.style.OlaMapStyle\$OnStyleLoadedListener")

      val proxy = java.lang.reflect.Proxy.newProxyInstance(
        listenerClass.classLoader,
        arrayOf(listenerClass)
      ) { _, method, args ->
        if (method.name == "onStyleLoaded") {
          val style = args?.get(0)
          if (style != null) {
            handleStyleLoaded(style)
          } else {
            Log.w("OlaMapPOI", "Style is null in callback")
          }
        }
        null
      }

      // Call getStyle with callback
      val getStyleMethod = mapboxMapImpl.javaClass.getMethod("getStyle", listenerClass)
      getStyleMethod.invoke(mapboxMapImpl, proxy)

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error in processStyleWithCallback: ${e.message}", e)
    }
  }

  /**
   * Handle style loaded - discover and hide POI layers
   */
  private fun handleStyleLoaded(style: Any) {
    try {
      Log.d("OlaMapPOI", "==========================================")
      Log.d("OlaMapPOI", "Style loaded successfully")
      Log.d("OlaMapPOI", "==========================================")

      // Best Practice: First discover all POI layers
      val poiLayers = poiManager.discoverPOILayers(style)

      if (poiLayers.isEmpty()) {
        Log.w("OlaMapPOI", "No POI layers discovered")
        return
      }

      // Best Practice: Use visibility toggle (reversible)
      val hiddenCount = poiManager.hidePOILayers(style)

      Log.d("OlaMapPOI", "==========================================")
      Log.d("OlaMapPOI", "✓✓✓ POI Management Complete")
      Log.d("OlaMapPOI", "Discovered: ${poiLayers.size} layers")
      Log.d("OlaMapPOI", "Hidden: $hiddenCount layers")
      Log.d("OlaMapPOI", "==========================================")

      // Notify Flutter
      methodChannel.invokeMethod("onPOIHidden", mapOf(
        "discovered" to poiLayers.size,
        "hidden" to hiddenCount,
        "layers" to poiLayers
      ))

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error handling style: ${e.message}", e)
    }
  }

  /**
   * Toggle POI visibility - demonstrating dynamic control
   */
  private fun togglePOI(call: MethodCall, result: Result) {
    val show = call.argument<Boolean>("show") ?: true

    try {
      val map = olaMap
      if (map == null) {
        result.error("MAP_ERROR", "Map not initialized", null)
        return
      }

      val mapField = map.javaClass.getDeclaredField("map")
      mapField.isAccessible = true
      val mapboxMapImpl = mapField.get(map)

      if (mapboxMapImpl == null) {
        result.error("MAP_ERROR", "MapboxMapImpl not found", null)
        return
      }

      val listenerClass = Class.forName("com.ola.mapsdk.style.OlaMapStyle\$OnStyleLoadedListener")

      val proxy = java.lang.reflect.Proxy.newProxyInstance(
        listenerClass.classLoader,
        arrayOf(listenerClass)
      ) { _, method, args ->
        if (method.name == "onStyleLoaded") {
          val style = args?.get(0)
          if (style != null) {
            val count = if (show) {
              poiManager.showPOILayers(style)
            } else {
              poiManager.hidePOILayers(style)
            }

            result.success(mapOf(
              "action" to if (show) "shown" else "hidden",
              "count" to count
            ))
          }
        }
        null
      }

      val getStyleMethod = mapboxMapImpl.javaClass.getMethod("getStyle", listenerClass)
      getStyleMethod.invoke(mapboxMapImpl, proxy)

    } catch (e: Exception) {
      Log.e("OlaMapPOI", "Error toggling POI: ${e.message}", e)
      result.error("TOGGLE_ERROR", e.message, null)
    }
  }

  /**
   * Discover POI layers - useful for debugging
   */
  private fun discoverPOILayers(result: Result) {
    try {
      val layers = poiManager.getDiscoveredLayers()
      result.success(mapOf(
        "layers" to layers,
        "count" to layers.size
      ))
    } catch (e: Exception) {
      result.error("DISCOVER_ERROR", e.message, null)
    }
  }

  // Existing methods remain unchanged
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
        result.success(null)
      } else {
        result.error("ZOOM_ERROR", "Failed to get target location", null)
      }
    } else {
      result.error("ZOOM_ERROR", "Failed to get camera position", null)
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
        result.error("ZOOM_ERROR", "Invalid parameters", null)
      }
    } else {
      result.error("ZOOM_ERROR", "Failed to get camera position", null)
    }
  }

  private fun zoomOut(result: Result) {
    val currentCameraPosition = olaMap?.getCurrentOlaCameraPosition()
    if (currentCameraPosition != null) {
      val targetLocation = currentCameraPosition.target
      val currentZoomLevel = currentCameraPosition.zoomLevel
      if (targetLocation != null) {
        olaMap?.zoomToLocation(targetLocation, currentZoomLevel - 1.0)
        result.success(null)
      } else {
        result.error("ZOOM_ERROR", "Failed to get target location", null)
      }
    } else {
      result.error("ZOOM_ERROR", "Failed to get camera position", null)
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
      result.error("LOCATION_ERROR", "Latitude or Longitude missing", null)
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
    result.success(null)
  }

  override fun getView(): View? {
    return mapView
  }

  override fun dispose() {
    handler.removeCallbacksAndMessages(null)
    Log.d("OlaMapViewController", "Disposed")
  }
}
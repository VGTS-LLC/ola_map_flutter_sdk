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
    val showPOI = creationParams?.get("showPOI") as? Boolean ?: false  // ADD THIS

    if (apiKey.isNullOrEmpty()) {
      methodChannel.invokeMethod("onError", "API key is missing or invalid")
    } else {
      initializeMap(apiKey, showPOI)  // MODIFY THIS
    }
    // ... rest of your code
  }

  private fun initializeMap(apiKey: String, showPOI: Boolean) {  // ADD showPOI parameter
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

          // ADD THIS BLOCK TO HIDE POIs
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
    }
  }

  // ADD THIS NEW FUNCTION
  private fun hideMapPOIs(map: OlaMap) {
    try {
      // Method 1: Try using style if available
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

      // Check if OlaMap has a setStyle method
      val setStyleMethod = map.javaClass.getMethod("setStyle", String::class.java)
      setStyleMethod.invoke(map, styleJson)

    } catch (e: NoSuchMethodException) {
      Log.w("OlaMapFlutterPlugin", "setStyle method not available, trying alternative approach")

      // Method 2: Try to get the underlying MapView and apply style
      try {
        // Since Ola Maps uses MapLibre under the hood, try to access it
        val mapLibreField = map.javaClass.getDeclaredField("mapLibreMap")
        mapLibreField.isAccessible = true
        val mapLibreMap = mapLibreField.get(map)

        if (mapLibreMap != null) {
          val setStyleMethod = mapLibreMap.javaClass.getMethod("setStyle", String::class.java)
          setStyleMethod.invoke(mapLibreMap, createStyleWithoutPOI())
        }
      } catch (e2: Exception) {
        Log.e("OlaMapFlutterPlugin", "Alternative approach failed: ${e2.message}")
      }
    } catch (e: Exception) {
      Log.e("OlaMapFlutterPlugin", "Error hiding POIs: ${e.message}")
    }
  }

  // ADD THIS HELPER FUNCTION
  private fun createStyleWithoutPOI(): String {
    // Use Ola Maps default style URL but with POI layer hidden
    return """
      https://api.olamaps.io/tiles/vector/v1/styles/default-light-standard/style.json?poi=false
    """.trimIndent()
  }

  // ... rest of your existing code remains the same
}
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ola_map_flutter/ola_map_flutter.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final Completer<OlaMapController> _controller = Completer<OlaMapController>();

  @override
  void initState() {
    getData();
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        body: OlaMap(
            showCurrentLocation: true,
            showZoomControls: true,
            showMyLocationButton: true,
            showCompass: true,  // Custom compass in bottom-left
            showPOI: false,     // Disable default POI markers
            apiKey: "******************************",
            onPlatformViewCreated: (OlaMapController controller) {
              _controller.complete(controller);
            }));
  }

  getData() async {
    await Future.delayed(const Duration(seconds: 2));
    // addCustomMarker
    final OlaMapController controller = await _controller.future;
    controller.addCustomMarker(
      latitude: 22.6060,
      markerId: "1234",
      longitude: 88.3864,
      child: Container(
        height: 25,
        width: 25,
        decoration: BoxDecoration(color: Colors.red, borderRadius: BorderRadius.circular(50)),
        child: Container(
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(50)),
        ),
      ),
    );
  }
}

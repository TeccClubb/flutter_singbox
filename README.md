# Flutter SingBox VPN Plugin

A Flutter plugin for integrating SingBox VPN functionality into your Flutter applications. This plugin provides a bridge to the native SingBox implementation on Android, allowing you to configure, start, and monitor a VPN connection.

## Features

- Configure SingBox VPN settings with JSON configuration
- Start and stop VPN connections
- Monitor VPN connection status in real-time
- Track traffic statistics (upload/download speeds, data usage, connections)
- Session-based data usage tracking

## Installation

Add the plugin to your pubspec.yaml:

```yaml
dependencies:
  flutter_singbox: ^0.1.0
```

## Usage

### Basic Usage

```dart
import 'package:flutter_singbox/flutter_singbox.dart';

// Create an instance of the plugin
final flutterSingbox = FlutterSingbox();

// Save a configuration
await flutterSingbox.saveConfig(jsonString);

// Start the VPN
await flutterSingbox.startVPN();

// Stop the VPN
await flutterSingbox.stopVPN();

// Get current VPN status
String status = await flutterSingbox.getVPNStatus();
```

### Listening for Status Updates

```dart
flutterSingbox.onStatusChanged.listen((statusMap) {
  String status = statusMap['status'];
  int statusCode = statusMap['statusCode'];
  
  print('VPN Status: $status');
});
```

### Monitoring Traffic Statistics

```dart
flutterSingbox.onTrafficUpdate.listen((stats) {
  // Get speed values
  String uploadSpeed = stats['formattedUplinkSpeed'];
  String downloadSpeed = stats['formattedDownlinkSpeed'];
  
  // Get total values
  String uploadTotal = stats['formattedUplinkTotal'];
  String downloadTotal = stats['formattedDownlinkTotal'];
  
  // Get session data usage (since connection started)
  String sessionTotal = stats['formattedSessionTotal'];
  
  // Get raw values (in bytes) for custom formatting
  int uploadSpeedBytes = stats['uplinkSpeed'];
  int downloadSpeedBytes = stats['downlinkSpeed'];
  
  // Get connection counts
  int connectionsIn = stats['connectionsIn'];
  int connectionsOut = stats['connectionsOut'];
});
```

## Configuration Format

SingBox uses JSON configuration. Here's a basic example:

```json
{
  "dns": {
    "servers": [
      {
        "address": "8.8.8.8"
      }
    ]
  },
  "inbounds": [
    {
      "type": "tun",
      "tag": "tun-in",
      "interface_name": "tun0",
      "mtu": 9000,
      "auto_route": true,
      "strict_route": true,
      "stack": "system",
      "sniff": true
    }
  ],
  "outbounds": [
    {
      "type": "direct",
      "tag": "direct"
    }
  ]
}
```

## Android Setup

Add the following permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

For VPN service support, add:

```xml
<service
    android:name="com.tecclub.flutter_singbox.bg.VPNService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

## Example App

See the `example` folder for a complete working example of the plugin in action.

## License

This project is licensed under the MIT License.

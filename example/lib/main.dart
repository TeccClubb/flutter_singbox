import 'dart:developer';

import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_singbox/flutter_singbox.dart';
import 'per_app_tunneling_page.dart';

// Global instance of FlutterSingbox to share across the app
final FlutterSingbox singboxPlugin = FlutterSingbox();

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      routes: {
        '/': (context) => const HomePage(),
        '/per_app_tunneling': (context) =>
            PerAppTunnelingPage(singbox: singboxPlugin),
      },
      initialRoute: '/',
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  String _platformVersion = 'Unknown';
  String _vpnStatus = 'Stopped';
  final _configController = TextEditingController();

  // Traffic stats
  String _uploadSpeed = '0 B/s';
  String _downloadSpeed = '0 B/s';
  String _uploadTotal = '0 B';
  String _downloadTotal = '0 B';
  String _sessionTotal = '0 B';
  int _connectionsIn = 0;
  int _connectionsOut = 0;

  // Using the global singbox instance
  final _flutterSingboxPlugin = singboxPlugin;
  StreamSubscription? _statusSubscription;
  StreamSubscription? _trafficSubscription;

  @override
  void initState() {
    super.initState();
    initPlugin();
  }

  @override
  void dispose() {
    _statusSubscription?.cancel();
    _trafficSubscription?.cancel();
    _configController.dispose();
    super.dispose();
  }

  // Initialize the plugin and set up listeners
  Future<void> initPlugin() async {
    try {
      // Get platform version
      final platformVersion =
          await _flutterSingboxPlugin.getPlatformVersion() ??
          'Unknown platform version';

      // Get current VPN status and log it
      final vpnStatus = await _flutterSingboxPlugin.getVPNStatus();
      print('Initial VPN status: $vpnStatus');

      // Get saved config
      await _flutterSingboxPlugin.getConfig();

      // Listen for status changes
      _statusSubscription = _flutterSingboxPlugin.onStatusChanged.listen((
        event,
      ) {
        if (event['status'] != null) {
          print('VPN status update: ${event['status']}');
          setState(() {
            _vpnStatus = event['status'] as String;
          });
        }
      });

      // Listen for traffic updates
      _trafficSubscription = _flutterSingboxPlugin.onTrafficUpdate.listen((
        stats,
      ) {
        setState(() {
          _uploadSpeed = stats['formattedUplinkSpeed'] as String;
          _downloadSpeed = stats['formattedDownlinkSpeed'] as String;
          _uploadTotal = stats['formattedUplinkTotal'] as String;
          _downloadTotal = stats['formattedDownlinkTotal'] as String;
          _sessionTotal = stats['formattedSessionTotal'] as String;
          _connectionsIn = stats['connectionsIn'] as int;
          _connectionsOut = stats['connectionsOut'] as int;
        });
      });

      // Update state with initial values
      if (mounted) {
        setState(() {
          _platformVersion = platformVersion;
          _vpnStatus = vpnStatus;
          _configController.text = _formatJson(sampleConfig);
        });
      }
    } on PlatformException catch (e) {
      debugPrint('Error initializing plugin: ${e.message}');
    }
  }

  // Format JSON string for better readability
  String _formatJson(String jsonStr) {
    try {
      var jsonObj = json.decode(jsonStr);
      return const JsonEncoder.withIndent('  ').convert(jsonObj);
    } catch (e) {
      return jsonStr;
    }
  }

  // Save configuration
  Future<void> _saveConfig() async {
    try {
      final success = await _flutterSingboxPlugin.saveConfig(
        _configController.text,
      );
      if (success) {
        // ScaffoldMessenger.of(context).showSnackBar(
        //   const SnackBar(content: Text('Configuration saved successfully'))
        // );
      } else {
        log('Failed to save configuration');
        // ScaffoldMessenger.of(context).showSnackBar(
        //   const SnackBar(content: Text('Failed to save configuration')),
        // );
      }
    } on PlatformException catch (e) {
      log('Error: ${e.message}');
    }
  }

  // Start VPN connection
  Future<void> _startVPN() async {
    try {
      await _saveConfig();
      final success = await _flutterSingboxPlugin.startVPN();
      if (!success) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Failed to start VPN')));
      }
    } on PlatformException catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Error: ${e.message}')));
    }
  }

  // Stop VPN connection
  Future<void> _stopVPN() async {
    try {
      final success = await _flutterSingboxPlugin.stopVPN();
      if (!success) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('Failed to stop VPN')));
      }
    } on PlatformException catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Error: ${e.message}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('SingBox VPN Plugin Example'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Platform info
            Text('Running on: $_platformVersion'),
            const SizedBox(height: 20),

            // Status card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'VPN Status: $_vpnStatus',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 10),

                    // VPN control buttons
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        ElevatedButton(
                          onPressed: _vpnStatus == VPNStatus.STOPPED
                              ? _startVPN
                              : null,
                          child: const Text('Connect VPN'),
                        ),
                        ElevatedButton(
                          onPressed: _vpnStatus == VPNStatus.STARTED
                              ? _stopVPN
                              : null,
                          child: const Text('Disconnect VPN'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),

            // Traffic stats - only show when connected
            if (_vpnStatus == VPNStatus.STARTED)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Traffic Statistics',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 10),

                      // Session total (highlighted)
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.primaryContainer,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Center(
                          child: Text(
                            'Session Data Usage: $_sessionTotal',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: Theme.of(
                                context,
                              ).colorScheme.onPrimaryContainer,
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 15),

                      // Speeds
                      _buildStatRow('Upload Speed:', _uploadSpeed),
                      _buildStatRow('Download Speed:', _downloadSpeed),
                      const Divider(),

                      // Totals
                      _buildStatRow('Upload Total:', _uploadTotal),
                      _buildStatRow('Download Total:', _downloadTotal),
                      const Divider(),

                      // Connections
                      _buildStatRow(
                        'Connections In:',
                        _connectionsIn.toString(),
                      ),
                      _buildStatRow(
                        'Connections Out:',
                        _connectionsOut.toString(),
                      ),
                    ],
                  ),
                ),
              ),
            const SizedBox(height: 20),

            // Configuration
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'SingBox Configuration',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller: _configController,
                      maxLines: 8,
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        hintText: 'Enter SingBox JSON configuration',
                      ),
                    ),
                    const SizedBox(height: 10),
                    Center(
                      child: ElevatedButton(
                        onPressed: _saveConfig,
                        child: const Text('Save Configuration'),
                      ),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 20),

            // Per-App Tunneling Configuration Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Per-App Tunneling',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      'Configure which apps use the VPN tunnel and which bypass it.',
                    ),
                    const SizedBox(height: 10),
                    Center(
                      child: ElevatedButton.icon(
                        onPressed: () {
                          Navigator.pushNamed(context, '/per_app_tunneling');
                        },
                        icon: const Icon(Icons.app_registration),
                        label: const Text('Configure Per-App Tunneling'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // Helper method to build stat rows
  Widget _buildStatRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}

String sampleConfig = '''
{
  "dns": {
    "final": "local-dns",
    "rules": [
      {
        "clash_mode": "Global",
        "server": "proxy-dns",
        "source_ip_cidr": ["172.19.0.0/30"]
      },
      {
        "server": "proxy-dns",
        "source_ip_cidr": ["172.19.0.0/30"]
      },
      {
        "clash_mode": "Direct",
        "server": "direct-dns"
      }
    ],
    "servers": [
      {
        "address": "tls://208.67.222.123",
        "address_resolver": "local-dns",
        "detour": "proxy",
        "tag": "proxy-dns"
      },
      {
        "address": "local",
        "detour": "direct",
        "tag": "local-dns"
      },
      {
        "address": "rcode://success",
        "tag": "block"
      },
      {
        "address": "local",
        "detour": "direct",
        "tag": "direct-dns"
      }
    ],
    "strategy": "prefer_ipv4"
  },
  "inbounds": [
    {
      "inet4_address": "172.19.0.1/30",
      "inet6_address": "fdfe:dcba:9876::1/126",
      "auto_route": true,
      "endpoint_independent_nat": false,
      "mtu": 9000,
      "platform": {
        "http_proxy": {
          "enabled": true,
          "server": "127.0.0.1",
          "server_port": 2080
        }
      },
      "sniff": true,
      "stack": "system",
      "strict_route": false,
      "type": "tun"
    },
    {
      "listen": "127.0.0.1",
      "listen_port": 2080,
      "sniff": true,
      "type": "mixed",
      "users": []
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "type": "selector",
      "outbounds": ["auto", "vless-0b7630b3", "direct"]
    },
    {
      "tag": "auto",
      "type": "urltest",
      "outbounds": ["vless-0b7630b3"],
      "url": "http://www.gstatic.com/generate_204",
      "interval": "10m",
      "tolerance": 50
    },
    {
      "tag": "direct",
      "type": "direct"
    },
    {
      "tag": "dns-out",
      "type": "dns"
    },
    {
      "type": "vless",
      "tag": "vless-0b7630b3",
      "server": "singa.tecclubx.com",
      "server_port": 443,
      "uuid": "b656cd02-61a3-49ae-8007-74fbbcf8e0cc",
      "flow": "",
      "transport": {
        "path": "/sing-box",
        "headers": {
          "Host": "singa.tecclubx.com"
        },
        "type": "ws"
      },
      "tls": {
        "enabled": true,
        "server_name": "singa.tecclubx.com",
        "insecure": true
      }
    }
  ],
  "route": {
    "auto_detect_interface": true,
    "final": "proxy",
    "rules": [
      {
        "clash_mode": "Direct",
        "outbound": "direct"
      },
      {
        "clash_mode": "Global",
        "outbound": "proxy"
      },
      {
        "protocol": "dns",
        "outbound": "dns-out"
      }
    ]
  }
}
''';

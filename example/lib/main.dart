import 'dart:developer';

import 'package:flutter/material.dart';
import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_singbox_vpn/flutter_singbox.dart';
import 'package:permission_handler/permission_handler.dart';
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

  // Logs
  final List<String> _logs = [];
  final ScrollController _logScrollController = ScrollController();
  bool _showLogs = false;

  // Using the global singbox instance
  final _flutterSingboxPlugin = singboxPlugin;
  StreamSubscription? _statusSubscription;
  StreamSubscription? _trafficSubscription;
  StreamSubscription? _logSubscription;

  @override
  void initState() {
    super.initState();
    initPlugin();
  }

  @override
  void dispose() {
    _statusSubscription?.cancel();
    _trafficSubscription?.cancel();
    _logSubscription?.cancel();
    _configController.dispose();
    _logScrollController.dispose();
    super.dispose();
  }

  // Initialize the plugin and set up listeners
  Future<void> initPlugin() async {
    try {
      // // Get platform version
      // final platformVersion =
      //     await _flutterSingboxPlugin.getPlatformVersion() ??
      //     'Unknown platform version';

      // Get current VPN status and log it
      Permission.notification.request();
      _flutterSingboxPlugin.setNotificationTitle('SafePro VPN');
      _flutterSingboxPlugin.setNotificationDescription(
        'Your secure VPN connection is active',
      );
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

      // Listen for log messages
      _logSubscription = _flutterSingboxPlugin.onLogMessage.listen((event) {
        log('Log event: $event');
        if (event['type'] == 'clear') {
          setState(() {
            _logs.clear();
          });
        } else if (event['type'] == 'log' && event['message'] != null) {
          setState(() {
            _logs.add(event['message'] as String);
            // Keep only last 200 logs
            while (_logs.length > 200) {
              _logs.removeAt(0);
            }
          });
          // Auto-scroll to bottom
          if (_logScrollController.hasClients) {
            Future.delayed(const Duration(milliseconds: 50), () {
              _logScrollController.animateTo(
                _logScrollController.position.maxScrollExtent,
                duration: const Duration(milliseconds: 100),
                curve: Curves.easeOut,
              );
            });
          }
        }
      });

      // Update state with initial values
      if (mounted) {
        setState(() {
          _platformVersion = "16";
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

            // Logs Card - show when VPN is connected or when we have logs
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'Service Logs',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        Row(
                          children: [
                            IconButton(
                              icon: Icon(
                                _showLogs
                                    ? Icons.visibility_off
                                    : Icons.visibility,
                              ),
                              onPressed: () {
                                setState(() {
                                  _showLogs = !_showLogs;
                                });
                              },
                              tooltip: _showLogs ? 'Hide Logs' : 'Show Logs',
                            ),
                            IconButton(
                              icon: const Icon(Icons.delete),
                              onPressed: () async {
                                await _flutterSingboxPlugin.clearLogs();
                                setState(() {
                                  _logs.clear();
                                });
                              },
                              tooltip: 'Clear Logs',
                            ),
                          ],
                        ),
                      ],
                    ),
                    if (_showLogs) ...[
                      const SizedBox(height: 10),
                      Container(
                        height: 300,
                        decoration: BoxDecoration(
                          color: Colors.black87,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: _logs.isEmpty
                            ? const Center(
                                child: Text(
                                  'No logs yet. Connect VPN to see logs.',
                                  style: TextStyle(color: Colors.white54),
                                ),
                              )
                            : ListView.builder(
                                controller: _logScrollController,
                                padding: const EdgeInsets.all(8),
                                itemCount: _logs.length,
                                itemBuilder: (context, index) {
                                  final log = _logs[index];
                                  // Color logs based on content
                                  Color logColor = Colors.white70;
                                  if (log.contains('error') ||
                                      log.contains('Error') ||
                                      log.contains('ERROR')) {
                                    logColor = Colors.red;
                                  } else if (log.contains('warn') ||
                                      log.contains('Warn') ||
                                      log.contains('WARN')) {
                                    logColor = Colors.orange;
                                  } else if (log.contains('info') ||
                                      log.contains('Info') ||
                                      log.contains('INFO')) {
                                    logColor = Colors.lightBlue;
                                  }
                                  return Padding(
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 1,
                                    ),
                                    child: Text(
                                      log,
                                      style: TextStyle(
                                        color: logColor,
                                        fontFamily: 'monospace',
                                        fontSize: 11,
                                      ),
                                    ),
                                  );
                                },
                              ),
                      ),
                    ],
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
    "log": {
        "level": "debug",
        "disabled": false,
        "timestamp": true
    },
    "dns": {
        "final": "local-dns",
        "rules": [
            {
                "clash_mode": "Global",
                "server": "proxy-dns",
                "source_ip_cidr": [
                    "172.19.0.0/30"
                ]
            },
            {
                "server": "proxy-dns",
                "source_ip_cidr": [
                    "172.19.0.0/30"
                ]
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
            "type": "tun",
            "tag": "tun-in",
            "interface_name": "tun0",
            "inet4_address": "172.19.0.1/30",
            "mtu": 1400,
            "auto_route": true,
            "strict_route": true,
            "stack": "mixed",
            "sniff": true,
            "sniff_override_destination": false,
            "domain_strategy": "ipv4_only"
        }
    ],
    "outbounds": [
        {
            "tag": "proxy",
            "type": "selector",
            "outbounds": [
                "chained"
            ]
        },
        {
            "type": "wireguard",
            "tag": "chained",
            "server": "205.198.86.198",
            "server_port": 443,
            "local_address": [
                "10.0.0.3/16"
            ],
            "private_key": "qI1BqT25ylggqfCaL7CncBDLxEo3+urBaop18Rx7oH0=",
            "peer_public_key": "tlcyqr3tGZwhZHJkGE51NMtzMw1zbeifCe699MEg1lU=",
            "mtu": 1280
        },
        {
            "tag": "dns-out",
            "type": "dns"
        },
        {
            "tag": "direct",
            "type": "direct"
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

import 'dart:developer';

import 'package:flutter/material.dart';
import 'package:flutter_singbox/flutter_singbox.dart';
import 'package:installed_apps/app_info.dart';
import 'package:installed_apps/installed_apps.dart';

class PerAppTunnelingPage extends StatefulWidget {
  final FlutterSingbox singbox;

  const PerAppTunnelingPage({Key? key, required this.singbox})
    : super(key: key);

  @override
  _PerAppTunnelingPageState createState() => _PerAppTunnelingPageState();
}

class _PerAppTunnelingPageState extends State<PerAppTunnelingPage> {
  String _proxyMode = ProxyMode.OFF;
  List<String> _selectedApps = [];
  List<Map<String, dynamic>> _installedApps = [];
  bool _isLoading = true;
  bool _isSearching = false;
  final _searchController = TextEditingController();
  List<Map<String, dynamic>> _filteredApps = [];

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadSettings() async {
    setState(() {
      _isLoading = true;
    });

    try {
      // Load proxy mode
      final proxyMode = await widget.singbox.getPerAppProxyMode();

      // Load selected apps
      final selectedApps = await widget.singbox.getPerAppProxyList();

      // Load installed apps using the installed_apps package
      final apps = await InstalledApps.getInstalledApps(
        false,
        true,
        "",
        BuiltWith.native_or_others,
      );
      final installedApps = apps
          .map(
            (app) => {
              'packageName': app.packageName,
              'appName': app.name,
              'isSystemApp': false,
              'icon': app.icon,
            },
          )
          .toList();

      setState(() {
        _proxyMode = proxyMode;
        _selectedApps = List<String>.from(selectedApps);
        _installedApps = installedApps;
        _filteredApps = List.from(_installedApps);
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _isLoading = false;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Error loading settings: $e')));
    }
  }

  Future<void> _saveSettings() async {
    try {
      await widget.singbox.setPerAppProxyMode(ProxyMode.EXCLUDE);
      await widget.singbox.setPerAppProxyList(_selectedApps);

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Settings saved')));
    } catch (e) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Error saving settings: $e')));
    }
  }

  void _filterApps(String query) {
    setState(() {
      if (query.isEmpty) {
        _filteredApps = List.from(_installedApps);
      } else {
        _filteredApps = _installedApps
            .where(
              (app) =>
                  app['appName'].toString().toLowerCase().contains(
                    query.toLowerCase(),
                  ) ||
                  app['packageName'].toString().toLowerCase().contains(
                    query.toLowerCase(),
                  ),
            )
            .toList();
      }
    });
  }

  void _toggleApp(String packageName) {
    log('Package Name: $packageName');
    setState(() {
      // Create a new modifiable list from the potentially fixed-length list
      _selectedApps = List<String>.from(_selectedApps);

      if (_selectedApps.contains(packageName)) {
        _selectedApps.remove(packageName);
      } else {
        _selectedApps.add(packageName);
      }
    });
  }

  void _selectAll() {
    setState(() {
      _selectedApps = List<String>.from(
        _filteredApps.map((app) => app['packageName'].toString()),
      );
    });
  }

  void _deselectAll() {
    setState(() {
      _selectedApps = <String>[];
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: _isSearching
            ? TextField(
                controller: _searchController,
                autofocus: true,
                decoration: const InputDecoration(
                  hintText: 'Search apps...',
                  hintStyle: TextStyle(color: Colors.white70),
                  border: InputBorder.none,
                ),
                style: const TextStyle(color: Colors.white),
                onChanged: _filterApps,
              )
            : const Text('Per-App Tunneling'),
        actions: [
          IconButton(
            icon: Icon(_isSearching ? Icons.close : Icons.search),
            onPressed: () {
              setState(() {
                _isSearching = !_isSearching;
                if (!_isSearching) {
                  _searchController.clear();
                  _filterApps('');
                }
              });
            },
          ),
          PopupMenuButton<String>(
            onSelected: (value) {
              if (value == 'select_all') {
                _selectAll();
              } else if (value == 'deselect_all') {
                _deselectAll();
              }
            },
            itemBuilder: (BuildContext context) => [
              const PopupMenuItem(
                value: 'select_all',
                child: Text('Select All'),
              ),
              const PopupMenuItem(
                value: 'deselect_all',
                child: Text('Deselect All'),
              ),
            ],
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // Proxy Mode Selection
                Card(
                  margin: const EdgeInsets.all(8.0),
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Tunneling Mode',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        RadioListTile<String>(
                          title: const Text('Tunnel all apps'),
                          subtitle: const Text(
                            'All traffic goes through the VPN',
                          ),
                          value: ProxyMode.OFF,
                          groupValue: _proxyMode,
                          onChanged: (value) {
                            setState(() {
                              _proxyMode = value!;
                            });
                          },
                        ),
                        RadioListTile<String>(
                          title: const Text('Only tunnel selected apps'),
                          subtitle: const Text(
                            'Only selected apps use the VPN',
                          ),
                          value: ProxyMode.INCLUDE,
                          groupValue: _proxyMode,
                          onChanged: (value) {
                            setState(() {
                              _proxyMode = value!;
                            });
                          },
                        ),
                        RadioListTile<String>(
                          title: const Text('Bypass selected apps'),
                          subtitle: const Text('Selected apps bypass the VPN'),
                          value: ProxyMode.EXCLUDE,
                          groupValue: _proxyMode,
                          onChanged: (value) {
                            setState(() {
                              _proxyMode = value!;
                            });
                          },
                        ),
                      ],
                    ),
                  ),
                ),

                // Status bar showing selected count
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16.0,
                    vertical: 8.0,
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        '${_selectedApps.length} apps selected',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          color: Theme.of(context).colorScheme.secondary,
                        ),
                      ),
                      Text(
                        '${_filteredApps.length} apps shown',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.secondary,
                        ),
                      ),
                    ],
                  ),
                ),

                // App List
                Expanded(
                  child: _filteredApps.isEmpty
                      ? const Center(child: Text('No apps found'))
                      : ListView.builder(
                          itemCount: _filteredApps.length,
                          itemBuilder: (context, index) {
                            final app = _filteredApps[index];
                            final packageName = app['packageName'].toString();
                            final isSelected = _selectedApps.contains(
                              packageName,
                            );

                            return CheckboxListTile(
                              title: Text(app['appName'].toString()),
                              subtitle: Text(packageName),
                              value: isSelected,
                              onChanged: (bool? value) {
                                _toggleApp(packageName);
                              },
                            );
                          },
                        ),
                ),
              ],
            ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _saveSettings,
        icon: const Icon(Icons.save),
        label: const Text('Save'),
      ),
    );
  }
}

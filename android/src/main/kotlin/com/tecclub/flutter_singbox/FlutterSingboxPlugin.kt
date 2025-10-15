package com.tecclub.flutter_singbox

import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.tecclub.flutter_singbox.bg.BoxService
import com.tecclub.flutter_singbox.bg.ServiceConnection
import com.tecclub.flutter_singbox.database.Settings
import com.tecclub.flutter_singbox.config.SimpleConfigManager
import com.tecclub.flutter_singbox.constant.Action
import com.tecclub.flutter_singbox.constant.Alert
import com.tecclub.flutter_singbox.constant.Status
import com.tecclub.flutter_singbox.constant.ServiceMode
import com.tecclub.flutter_singbox.constant.TrafficStats
import go.Seq
import io.nekohasekai.libbox.Libbox
import java.io.File
import com.tecclub.flutter_singbox.utils.StatusClient
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.nekohasekai.libbox.StatusMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ApplicationHelper class - this is a copy of the Application functionality
 * directly embedded in the plugin class to avoid separate file dependencies
 */
class ApplicationHelper {
    companion object {
        // Instance of the application context
        lateinit var application: Context
            private set
        
        // Quick access to common system services
        val powerManager: PowerManager by lazy { 
            application.getSystemService(Context.POWER_SERVICE) as PowerManager 
        }
        
        val connectivity by lazy { 
            application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
        }
        
        val packageManager by lazy { 
            application.packageManager 
        }
        
        val notificationManager by lazy { 
            application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager 
        }
        
        val wifiManager by lazy { 
            application.getSystemService(Context.WIFI_SERVICE) as WifiManager 
        }
        
        val clipboard by lazy { 
            application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager 
        }
        
        // Version info for the app
        val versionName by lazy {
            try {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                "com.tecclub.flutter_singbox"
            }
        }
        
        val versionCode: Long by lazy {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    application.packageManager.getPackageInfo(application.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    application.packageManager.getPackageInfo(application.packageName, 0).versionCode.toLong()
                }
            } catch (e: PackageManager.NameNotFoundException) {
                0L
            }
        }
        
        /**
         * Initialize the application with the given context
         * This is called from the FlutterSingboxPlugin when the plugin is attached to the engine
         */
        fun initialize(context: Context) {
            application = context.applicationContext
            
            Seq.setContext(application)
            
            // Initialize config manager
            SimpleConfigManager.init(application)
            
            @Suppress("OPT_IN_USAGE")
            GlobalScope.launch(Dispatchers.IO) {
                initializeLibbox(application)
            }
        }
        
        /**
         * Initialize the libbox library with the application's directories
         */
        private fun initializeLibbox(context: Context) {
            val baseDir = context.filesDir
            baseDir.mkdirs()
            val workingDir = context.getExternalFilesDir(null) ?: return
            workingDir.mkdirs()
            val tempDir = context.cacheDir
            tempDir.mkdirs()
            Libbox.setup(baseDir.path, workingDir.path, tempDir.path, false)
            Libbox.redirectStderr(File(workingDir, "stderr.log").path)
        }
    }
}

/** FlutterSingboxPlugin */
class FlutterSingboxPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware,
    StatusClient.Handler,
    ServiceConnection.Callback,
    PluginRegistry.ActivityResultListener {

    // Method channel for Flutter to native communication
    private lateinit var methodChannel: MethodChannel
    
    // Event channel for broadcasting status updates to Flutter
    private lateinit var statusEventChannel: EventChannel
    private lateinit var trafficEventChannel: EventChannel
    
    // Context and Activity references
    private lateinit var context: Context
    private var activity: Activity? = null
    
    // Service connection
    private val connection by lazy { ServiceConnection(context, this, false) }
    private val statusClient by lazy { StatusClient(coroutineScope, this) }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    
    // Status tracking
    private val _vpnStatus = MutableStateFlow(Status.Stopped)
    val vpnStatus: StateFlow<Status> = _vpnStatus
    
    // For checking the initial status upon startup
    private var statusInitialized = false
    
    // Traffic stats
    private var _trafficStats = MutableStateFlow<Map<String, Any>>(
        mapOf(
            "uplinkSpeed" to 0L,
            "downlinkSpeed" to 0L,
            "uplinkTotal" to 0L,
            "downlinkTotal" to 0L,
            "connectionsIn" to 0,
            "connectionsOut" to 0,
            "sessionUplink" to 0L,
            "sessionDownlink" to 0L
        )
    )
    val trafficStats: StateFlow<Map<String, Any>> = _trafficStats
    
    // Session data tracking
    private var sessionStartUplinkTotal = 0L
    private var sessionStartDownlinkTotal = 0L
    
    // VPN permission request code
    private val VPN_REQUEST_CODE = 24
    private var pendingVpnPermissionResult: Result? = null
    
    // Event sink for status updates
    private var statusEventSink: EventChannel.EventSink? = null
    private var trafficEventSink: EventChannel.EventSink? = null
    
    // Periodic status check
    private fun startPeriodicStatusCheck() {
        coroutineScope.launch {
            while (true) {
                try {
                    // Wait 15 seconds between checks
                    kotlinx.coroutines.delay(15000)
                    
                    // If status is Started, verify it's actually running
                    if (_vpnStatus.value == Status.Started) {
                        android.util.Log.e("FlutterSingboxPlugin", "Performing periodic status check")
                        checkServiceStatus()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FlutterSingboxPlugin", "Error in periodic status check", e)
                }
            }
        }
    }

    // Status broadcast receiver
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Action.BROADCAST_STATUS_CHANGED) {
                val statusOrdinal = intent.getIntExtra(Action.EXTRA_STATUS, Status.Stopped.ordinal)
                val status = Status.values()[statusOrdinal]
                
                android.util.Log.e("FlutterSingboxPlugin", "Received broadcast status change: ${status.name}")
                
                // Update the status
                _vpnStatus.value = status
                
                // We've now initialized the status
                statusInitialized = true
                
                // Send status update via the helper method
                sendStatusUpdate(status)
                
                // Verify the status with a detailed check
                checkServiceStatus()
            }
        }
    }
    
    // Alert broadcast receiver
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Action.BROADCAST_ALERT) {
                val alertOrdinal = intent.getIntExtra(Action.EXTRA_ALERT, -1)
                val message = intent.getStringExtra(Action.EXTRA_ALERT_MESSAGE)
                
                val alertMessage = if (alertOrdinal >= 0 && alertOrdinal < Alert.values().size) {
                    when (Alert.values()[alertOrdinal]) {
                        Alert.EmptyConfiguration -> "Empty configuration"
                        Alert.StartService -> "Failed to start service${if (message?.isNotEmpty() == true) ": $message" else ""}"
                        Alert.CreateService -> "Failed to create service${if (message?.isNotEmpty() == true) ": $message" else ""}"
                        Alert.VpnNoAddress -> "No VPN address configured"
                        else -> "Error: ${if (message?.isNotEmpty() == true) message else "Unknown issue"}"
                    }
                } else {
                    "Unknown error"
                }
                
                // Send alert to Flutter
                val alertMap = mapOf(
                    "alert" to alertOrdinal,
                    "message" to alertMessage
                )
                
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    statusEventSink?.success(alertMap)
                }
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        android.util.Log.e("FlutterSingboxPlugin", "Plugin attaching to engine")
        context = flutterPluginBinding.applicationContext
        
        // Initialize service state
        _vpnStatus.value = Status.Stopped
        
        // Setup method channel
        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.tecclub.flutter_singbox/methods")
        methodChannel.setMethodCallHandler(this)
        
        // Setup status event channel
        statusEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.tecclub.flutter_singbox/status_events")
        statusEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                android.util.Log.e("FlutterSingboxPlugin", "Status event channel - onListen called")
                statusEventSink = events
                
                // Always do a fresh status check first, before sending any status
                coroutineScope.launch {
                    // Disconnect from any previous connection
                    try {
                        connection.disconnect()
                        statusClient.disconnect()
                    } catch (e: Exception) {
                        android.util.Log.e("FlutterSingboxPlugin", "Error disconnecting before status check", e)
                    }
                    
                    // Force status to Stopped initially
                    _vpnStatus.value = Status.Stopped
                    
                    // Now check real status
                    checkServiceStatus()
                    
                    // After status check, send the current status to Flutter
                    if (statusEventSink != null) {
                        android.util.Log.e("FlutterSingboxPlugin", "Sending initial status: ${_vpnStatus.value}")
                        sendStatusUpdate(_vpnStatus.value)
                    }
                }
            }
            
            override fun onCancel(arguments: Any?) {
                android.util.Log.e("FlutterSingboxPlugin", "Status event channel - onCancel called")
                statusEventSink = null
            }
        })
        
        // Setup traffic stats event channel
        trafficEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.tecclub.flutter_singbox/traffic_events")
        trafficEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                android.util.Log.e("FlutterSingboxPlugin", "Traffic event channel - onListen called")
                trafficEventSink = events
            }
            
            override fun onCancel(arguments: Any?) {
                android.util.Log.e("FlutterSingboxPlugin", "Traffic event channel - onCancel called")
                trafficEventSink = null
            }
        })
        
        // Initialize Application class (we use the existing one from the package)
        // We can call initialize from both places for redundancy
        Application.initialize(context)
        ApplicationHelper.initialize(context)
        
        // Register broadcast receivers
        context.registerReceiver(statusReceiver, IntentFilter(Action.BROADCAST_STATUS_CHANGED), 
            Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(alertReceiver, IntentFilter(Action.BROADCAST_ALERT), 
            Context.RECEIVER_NOT_EXPORTED)
            
        // Initialize status - ensure it's accurate
        checkServiceStatus()
    }
    
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        android.util.Log.e("FlutterSingboxPlugin", "Plugin detaching from engine")
        
        // Clear event channel handlers
        statusEventChannel.setStreamHandler(null)
        trafficEventChannel.setStreamHandler(null)
        methodChannel.setMethodCallHandler(null)
        
        // Clear event sinks
        statusEventSink = null
        trafficEventSink = null
        
        // Disconnect from service
        try {
            connection.disconnect()
            statusClient.disconnect()
            android.util.Log.e("FlutterSingboxPlugin", "Disconnected from service during detach")
        } catch (e: Exception) {
            android.util.Log.e("FlutterSingboxPlugin", "Error disconnecting from service during detach", e)
        }
        
        // Unregister receivers
        try {
            context.unregisterReceiver(statusReceiver)
            context.unregisterReceiver(alertReceiver)
            android.util.Log.e("FlutterSingboxPlugin", "Unregistered broadcast receivers during detach")
        } catch (e: Exception) {
            android.util.Log.e("FlutterSingboxPlugin", "Error unregistering receivers during detach", e)
            // Ignore if receivers not registered
        }
        
        // Cancel coroutine scope
        coroutineScope.cancel()
    }
    
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
        
        // Check if service is already running
        checkServiceStatus()
    }
    
    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }
    
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }
    
    override fun onDetachedFromActivity() {
        activity = null
    }
    
    private fun checkServiceStatus() {
        android.util.Log.e("FlutterSingboxPlugin", "Checking service status")
        coroutineScope.launch {
            try {
                // First, check if the VPN service is actually running using more specific class names
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val runningServices = withContext(Dispatchers.IO) {
                    manager.getRunningServices(Integer.MAX_VALUE)
                }
                
                // Look for both potential service class names
                val isServiceRunning = runningServices.any { 
                    it.service.className.contains("BoxService") || 
                    it.service.className.contains("VPNService")
                }
                
                android.util.Log.e("FlutterSingboxPlugin", "VPN service running check: $isServiceRunning")
                
                if (!isServiceRunning) {
                    // Service is definitely not running, update status to Stopped
                    android.util.Log.e("FlutterSingboxPlugin", "VPN service is not running, setting status to Stopped")
                    
                    // Disconnect from service and status client to clean up any stale connections
                    try {
                        connection.disconnect()
                        statusClient.disconnect()
                    } catch (e: Exception) {
                        android.util.Log.e("FlutterSingboxPlugin", "Error disconnecting during service check: ${e.message}")
                    }
                    
                    // Update status to Stopped
                    _vpnStatus.value = Status.Stopped
                    sendStatusUpdate(Status.Stopped)
                    
                    // No need to try connecting if service is not running
                    return@launch
                }
                
                // If we're already bound, no need to reconnect
                if (connection.isBound && connection.binder != null) {
                    android.util.Log.e("FlutterSingboxPlugin", "Already connected to service")
                    
                    // Use current status
                    sendStatusUpdate(_vpnStatus.value)
                    return@launch
                }
                
                // Not bound or no binder, so disconnect and reconnect
                android.util.Log.e("FlutterSingboxPlugin", "Not properly connected, reconnecting")
                
                // Always ensure we disconnect before connecting to get a fresh connection
                try {
                    connection.disconnect()
                    statusClient.disconnect()
                } catch (e: Exception) {
                    android.util.Log.e("FlutterSingboxPlugin", "Error disconnecting: ${e.message}")
                }
                
                // Connect to service
                android.util.Log.e("FlutterSingboxPlugin", "Connecting to service")
                connection.connect()
                statusClient.connect()
                
                // Wait a moment to get status
                kotlinx.coroutines.delay(500)
                
                // Check if we successfully connected
                if (connection.isBound) {
                    android.util.Log.e("FlutterSingboxPlugin", "Connected to service successfully")
                    // We'll get the status through the onServiceStatusChanged callback
                } else {
                    android.util.Log.e("FlutterSingboxPlugin", "Failed to connect, assuming Stopped")
                    _vpnStatus.value = Status.Stopped
                }
                
                android.util.Log.e("FlutterSingboxPlugin", "Service status check completed, current status: ${_vpnStatus.value}")
                
                // After connecting, broadcast the current status to Flutter
                sendStatusUpdate(_vpnStatus.value)
            } catch (e: Exception) {
                android.util.Log.e("FlutterSingboxPlugin", "Error checking service status: ${e.message}")
                _vpnStatus.value = Status.Stopped
                sendStatusUpdate(Status.Stopped)
            }
        }
    }
    
    // Helper method to send status updates to Flutter
    private fun sendStatusUpdate(status: Status) {
        android.util.Log.e("FlutterSingboxPlugin", "Sending status update to Flutter: ${status.name}")
        val statusMap = mapOf(
            "status" to status.name,
            "statusCode" to status.ordinal
        )
        
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            statusEventSink?.success(statusMap)
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "saveConfig" -> {
                val config = call.argument<String>("config") ?: ""
                saveConfig(config, result)
            }
            "getConfig" -> {
                getConfig(result)
            }
            "startVPN" -> {
                startVPN(result)
            }
            "stopVPN" -> {
                stopVPN(result)
            }
            "getVPNStatus" -> {
                getVPNStatus(result)
            }
            // Per-App Tunneling Methods
            "setPerAppProxyMode" -> {
                val mode = call.argument<String>("mode") ?: Settings.PER_APP_PROXY_OFF
                setPerAppProxyMode(mode, result)
            }
            "getPerAppProxyMode" -> {
                getPerAppProxyMode(result)
            }
            "setPerAppProxyList" -> {
                val appList = call.argument<List<String>>("appList") ?: emptyList()
                setPerAppProxyList(appList, result)
            }
            "getPerAppProxyList" -> {
                getPerAppProxyList(result)
            }
            "getInstalledApps" -> {
                getInstalledApps(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }
    
    private var configContent: String? = null
    
    private fun saveConfig(config: String, result: Result) {
        android.util.Log.e("FlutterSingboxPlugin", "Saving config: ${config.length} bytes")
        configContent = config // Store locally for immediate use when starting VPN
        
        coroutineScope.launch {
            try {
                val success = SimpleConfigManager.saveConfig(config)
                android.util.Log.e("FlutterSingboxPlugin", "Config save result: $success")
                if (success) {
                    result.success(true)
                } else {
                    result.error("CONFIG_SAVE_FAILED", "Failed to save configuration", null)
                }
            } catch (e: Exception) {
                android.util.Log.e("FlutterSingboxPlugin", "Error saving config: ${e.message}", e)
                result.error("CONFIG_SAVE_ERROR", e.message, null)
            }
        }
    }
    
    private fun getConfig(result: Result) {
        coroutineScope.launch {
            try {
                val config = SimpleConfigManager.getConfig()
                result.success(config)
            } catch (e: Exception) {
                result.error("CONFIG_GET_ERROR", e.message, null)
            }
        }
    }
    
    private fun startVPN(result: Result) {
        android.util.Log.e("FlutterSingboxPlugin", "Starting VPN...")
        activity?.let {
            val intent = VpnService.prepare(it)
            if (intent != null) {
                android.util.Log.e("FlutterSingboxPlugin", "VPN permission required, showing dialog")
                pendingVpnPermissionResult = result
                it.startActivityForResult(intent, VPN_REQUEST_CODE)
            } else {
                android.util.Log.e("FlutterSingboxPlugin", "VPN permission already granted, starting service")
                startVPNService(result)
            }
        } ?: run {
            android.util.Log.e("FlutterSingboxPlugin", "Activity unavailable, cannot start VPN")
            result.error("ACTIVITY_UNAVAILABLE", "Activity is not available", null)
        }
    }
    
    private fun startVPNService(result: Result) {
        android.util.Log.e("FlutterSingboxPlugin", "Starting VPN Service...")
        try {
            // Update status to Starting
            _vpnStatus.value = Status.Starting
            android.util.Log.e("FlutterSingboxPlugin", "Set VPN status to Starting")
            
            // Reset session traffic counters
            sessionStartUplinkTotal = 0
            sessionStartDownlinkTotal = 0
            
            // Start the service using proper method
            android.util.Log.e("FlutterSingboxPlugin", "Calling startService method")
            startService(result)
            
            // Connect to the service
            android.util.Log.e("FlutterSingboxPlugin", "Connecting to service")
            connection.connect()
            
            android.util.Log.e("FlutterSingboxPlugin", "VPN service start successful")
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("FlutterSingboxPlugin", "Error starting VPN service: ${e.message}", e)
            _vpnStatus.value = Status.Stopped
            result.error("START_VPN_ERROR", e.message, null)
        }
    }
    
    private fun startService(result: Result) {
        coroutineScope.launch(Dispatchers.IO) {
            android.util.Log.e("FlutterSingboxPlugin", "Starting service in IO coroutine")

            if (Settings.serviceMode == ServiceMode.VPN) {
                 android.util.Log.e("FlutterSingboxPlugin", "Service mode is VPN, checking if prepare is needed")
                 if (prepare(result)) {
                     android.util.Log.e("FlutterSingboxPlugin", "VPN prepare returned true, exiting launch")
                     return@launch
                 }
                 android.util.Log.e("FlutterSingboxPlugin", "VPN prepare returned false, continuing")
             }
             
            // Load config from SimpleConfigManager and set it as an extra
            val config = SimpleConfigManager.getConfig()
            android.util.Log.e("FlutterSingboxPlugin", "Loaded config from SimpleConfigManager, length: ${config.length}")
            
            // Create intent with action and config content
            val intent = Intent(context, Settings.serviceClass()).apply {
                action = BoxService.ACTION_START
                putExtra(BoxService.EXTRA_CONFIG_CONTENT, config)
            }
            android.util.Log.e("FlutterSingboxPlugin", "Created intent with ACTION_START and config extra")
            
            withContext(Dispatchers.Main) {
                android.util.Log.e("FlutterSingboxPlugin", "Starting foreground service")
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                android.util.Log.e("FlutterSingboxPlugin", "Service start request sent")
            }
        }
    }

    private suspend fun prepare(result: Result) = withContext(Dispatchers.Main) {
         try {
             android.util.Log.e("FlutterSingboxPlugin", "Preparing VPN service")
             val intent = VpnService.prepare(this@FlutterSingboxPlugin.context)
             if (intent != null) {
                 android.util.Log.e("FlutterSingboxPlugin", "VPN preparation returned an intent, need permission")
                 
                 // We shouldn't call startVPN here as it would cause an infinite loop
                 // Instead, we need to store the result and show the dialog
                 activity?.let {
                     android.util.Log.e("FlutterSingboxPlugin", "Starting VPN permission request activity")
                     pendingVpnPermissionResult = result
                     it.startActivityForResult(intent, VPN_REQUEST_CODE)
                     true
                 } ?: run {
                     android.util.Log.e("FlutterSingboxPlugin", "Activity unavailable for permission request")
                     onServiceAlert(Alert.RequestVPNPermission, "Activity unavailable")
                     false
                 }
             } else {
                 android.util.Log.e("FlutterSingboxPlugin", "VPN preparation returned null, permission already granted")
                 false
             }
         } catch (e: Exception) {
             android.util.Log.e("FlutterSingboxPlugin", "Error preparing VPN: ${e.message}", e)
             onServiceAlert(Alert.RequestVPNPermission, e.message)
             false
         }
     }
    
    private fun stopVPN(result: Result) {
        try {
            android.util.Log.e("FlutterSingboxPlugin", "Stopping VPN")
            
            // Update status to Stopping
            _vpnStatus.value = Status.Stopping
            
            // Immediately send stopping status update to Flutter
            sendStatusUpdate(Status.Stopping)
            
            // Send broadcast to stop the service
            android.util.Log.e("FlutterSingboxPlugin", "Sending SERVICE_CLOSE broadcast")
            context.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    context.packageName
                )
            )
            
            // Disconnect from service
            android.util.Log.e("FlutterSingboxPlugin", "Disconnecting from service")
            connection.disconnect()
            statusClient.disconnect()
            
            // After a brief delay, update to Stopped if not already updated by the receiver
            coroutineScope.launch {
                android.util.Log.e("FlutterSingboxPlugin", "Setting final status to Stopped after delay")
                kotlinx.coroutines.delay(1500) // Wait 1.5 seconds
                
                // Force status to Stopped regardless of current state
                _vpnStatus.value = Status.Stopped
                sendStatusUpdate(Status.Stopped)
                
                // Schedule one more check after additional delay to ensure status is correct
                kotlinx.coroutines.delay(1000) // Wait another second
                checkServiceStatus()
            }
            
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("FlutterSingboxPlugin", "Error stopping VPN", e)
            
            // Even on error, set status to Stopped for UI consistency
            _vpnStatus.value = Status.Stopped
            sendStatusUpdate(Status.Stopped)
            
            result.error("STOP_VPN_ERROR", e.message, null)
        }
    }
    
    private fun getVPNStatus(result: Result) {
        android.util.Log.e("FlutterSingboxPlugin", "getVPNStatus called, refreshing status")
        
        coroutineScope.launch {
            try {
                // Check if the VPN service is actually running
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val isServiceRunning = withContext(Dispatchers.IO) {
                    manager.getRunningServices(Integer.MAX_VALUE).any { 
                        it.service.className.contains("BoxService") || 
                        it.service.className.contains("VPNService")
                    }
                }
                
                android.util.Log.e("FlutterSingboxPlugin", "VPN service running check in getVPNStatus: $isServiceRunning")
                
                if (!isServiceRunning) {
                    // Service is definitely not running
                    _vpnStatus.value = Status.Stopped
                    android.util.Log.e("FlutterSingboxPlugin", "Setting status to Stopped because service is not running")
                }
                
                // Return the current status (which may have just been updated)
                result.success(_vpnStatus.value.name)
                
                // Also update status via event channel
                sendStatusUpdate(_vpnStatus.value)
                
                // Do a complete status check in the background
                statusInitialized = true
                checkServiceStatus()
                
            } catch (e: Exception) {
                android.util.Log.e("FlutterSingboxPlugin", "Error checking VPN status", e)
                result.success(_vpnStatus.value.name) // Return current status as fallback
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == VPN_REQUEST_CODE) {
            android.util.Log.e("FlutterSingboxPlugin", "VPN permission request result: ${if (resultCode == Activity.RESULT_OK) "GRANTED" else "DENIED"}")
            if (resultCode == Activity.RESULT_OK) {
                android.util.Log.e("FlutterSingboxPlugin", "VPN permission granted, starting service")
                pendingVpnPermissionResult?.let {
                    // First load the config that should be used
                    val configContent = SimpleConfigManager.getConfig()
                    android.util.Log.e("FlutterSingboxPlugin", "Loaded config from SimpleConfigManager: ${configContent.length} bytes")
                    
                    // Start VPN service with the config
                    startVPNService(it)
                } ?: run {
                    android.util.Log.e("FlutterSingboxPlugin", "WARNING: No pending result found after permission granted")
                }
            } else {
                android.util.Log.e("FlutterSingboxPlugin", "VPN permission denied by user")
                pendingVpnPermissionResult?.error("VPN_PERMISSION_DENIED", "VPN permission denied", null)
            }
            pendingVpnPermissionResult = null
            return true
        }
        return false
    }
    
    // ServiceConnection.Callback implementation
    override fun onServiceStatusChanged(status: Status) {
        android.util.Log.e("FlutterSingboxPlugin", "onServiceStatusChanged: ${status.name}")
        
        // Verify actual service state to avoid false "running" status
        if (status != Status.Stopped) {
            // Double-check that service is actually running
            coroutineScope.launch {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val isServiceRunning = withContext(Dispatchers.IO) {
                    manager.getRunningServices(Integer.MAX_VALUE).any { 
                        it.service.className.contains("BoxService") || 
                        it.service.className.contains("VPNService")
                    }
                }
                
                if (!isServiceRunning && status != Status.Stopping) {
                    android.util.Log.e("FlutterSingboxPlugin", 
                        "WARNING: Service reported status ${status.name} but service is not actually running. Forcing Stopped status.")
                    _vpnStatus.value = Status.Stopped
                    sendStatusUpdate(Status.Stopped)
                    
                    statusClient.disconnect()
                    return@launch
                }
                
                // Service is actually running, so update the status
                _vpnStatus.value = status
                sendStatusUpdate(status)
            }
        } else {
            // If status is already Stopped, accept it immediately
            _vpnStatus.value = status
            sendStatusUpdate(status)
        }
        
        // Connect or disconnect status client based on connection state
        if (status == Status.Started) {
            android.util.Log.e("FlutterSingboxPlugin", "Service started, connecting status client")
            statusClient.connect()
            
            // Reset session traffic counters when connection starts
            sessionStartUplinkTotal = 0
            sessionStartDownlinkTotal = 0
        } else if (status == Status.Stopped) {
            android.util.Log.e("FlutterSingboxPlugin", "Service stopped, disconnecting status client")
            statusClient.disconnect()
            
            // Reset traffic stats when stopped
            _trafficStats.value = mapOf<String, Any>(
                "uplinkSpeed" to 0L,
                "downlinkSpeed" to 0L,
                "uplinkTotal" to 0L,
                "downlinkTotal" to 0L,
                "connectionsIn" to 0,
                "connectionsOut" to 0,
                "sessionUplink" to 0L,
                "sessionDownlink" to 0L,
                "sessionTotal" to 0L,
                "formattedUplinkSpeed" to "0 B/s",
                "formattedDownlinkSpeed" to "0 B/s",
                "formattedUplinkTotal" to "0 B",
                "formattedDownlinkTotal" to "0 B",
                "formattedSessionUplink" to "0 B",
                "formattedSessionDownlink" to "0 B",
                "formattedSessionTotal" to "0 B"
            )
        }
        
        // We've now initialized the status
        statusInitialized = true
        
        // Send status update via the helper method
        sendStatusUpdate(status)
    }
    
    override fun onServiceAlert(type: Alert, message: String?) {
        // Alert is handled by the alertReceiver
    }
    
    // StatusClient.Handler implementation
    override fun onStatusUpdate(status: StatusMessage) {
        // When first status update comes, set session start values
        if (sessionStartUplinkTotal == 0L && sessionStartDownlinkTotal == 0L && status.uplinkTotal > 0) {
            sessionStartUplinkTotal = status.uplinkTotal
            sessionStartDownlinkTotal = status.downlinkTotal
        }
        
        // Calculate session data (data transferred since connection started)
        val sessionUplink = status.uplinkTotal - sessionStartUplinkTotal
        val sessionDownlink = status.downlinkTotal - sessionStartDownlinkTotal
        
        // Update traffic stats
        val stats = mapOf(
            "uplinkSpeed" to status.uplink,
            "downlinkSpeed" to status.downlink,
            "uplinkTotal" to status.uplinkTotal,
            "downlinkTotal" to status.downlinkTotal,
            "connectionsIn" to status.connectionsIn,
            "connectionsOut" to status.connectionsOut,
            "sessionUplink" to sessionUplink,
            "sessionDownlink" to sessionDownlink,
            "sessionTotal" to (sessionUplink + sessionDownlink),
            "formattedUplinkSpeed" to TrafficStats.formatBytes(status.uplink) + "/s",
            "formattedDownlinkSpeed" to TrafficStats.formatBytes(status.downlink) + "/s",
            "formattedUplinkTotal" to TrafficStats.formatBytes(status.uplinkTotal),
            "formattedDownlinkTotal" to TrafficStats.formatBytes(status.downlinkTotal),
            "formattedSessionUplink" to TrafficStats.formatBytes(sessionUplink),
            "formattedSessionDownlink" to TrafficStats.formatBytes(sessionDownlink),
            "formattedSessionTotal" to TrafficStats.formatBytes(sessionUplink + sessionDownlink)
        )
        
        _trafficStats.value = stats as Map<String, Any>
        
        // Send traffic stats to Flutter
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            trafficEventSink?.success(stats)
        }
    }
    
    // Per-App Tunneling methods
    
    private fun setPerAppProxyMode(mode: String, result: Result) {
        try {
            when (mode) {
                Settings.PER_APP_PROXY_OFF, 
                Settings.PER_APP_PROXY_INCLUDE, 
                Settings.PER_APP_PROXY_EXCLUDE -> {
                    Settings.perAppProxyMode = mode
                    result.success(true)
                }
                else -> {
                    result.error("INVALID_MODE", "Invalid per-app proxy mode: $mode", null)
                }
            }
        } catch (e: Exception) {
            result.error("SET_MODE_ERROR", e.message, null)
        }
    }
    
    private fun getPerAppProxyMode(result: Result) {
        result.success(Settings.perAppProxyMode)
    }
    
    private fun setPerAppProxyList(appList: List<String>, result: Result) {
        try {
            Settings.perAppProxyList = appList
            result.success(true)
        } catch (e: Exception) {
            result.error("SET_APP_LIST_ERROR", e.message, null)
        }
    }
    
    private fun getPerAppProxyList(result: Result) {
        result.success(Settings.perAppProxyList)
    }
    
    private fun getInstalledApps(result: Result) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                
                val appDetails = apps
                    .filter { it.packageName != context.packageName } // Exclude our app
                    .filter { !it.packageName.startsWith("com.android.") } // Filter out system apps
                    .map { appInfo ->
                        try {
                            val label = pm.getApplicationLabel(appInfo).toString()
                            mapOf(
                                "packageName" to appInfo.packageName,
                                "appName" to label,
                                "isSystemApp" to ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                            )
                        } catch (e: Exception) {
                            mapOf(
                                "packageName" to appInfo.packageName,
                                "appName" to appInfo.packageName,
                                "isSystemApp" to ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                            )
                        }
                    }
                    .sortedBy { it["appName"] as String }
                
                // Return on main thread
                Handler(Looper.getMainLooper()).post {
                    result.success(appDetails)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    result.error("GET_APPS_ERROR", e.message, null)
                }
            }
        }
    }
}

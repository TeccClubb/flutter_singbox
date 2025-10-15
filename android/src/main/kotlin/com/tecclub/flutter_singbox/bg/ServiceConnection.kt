package com.tecclub.flutter_singbox.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.tecclub.flutter_singbox.bg.VPNService
import com.tecclub.flutter_singbox.constant.Action
import com.tecclub.flutter_singbox.constant.Alert
import com.tecclub.flutter_singbox.constant.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ServiceConnection(
    private val context: Context,
    private val callback: Callback,
    private val register: Boolean = true,
) : ServiceConnection {

    companion object {
        private const val TAG = "ServiceConnection"
        private const val BIND_AUTO_CREATE = Context.BIND_AUTO_CREATE
    }

    // Create a callback handler for the service
    private var service: IBinder? = null
    var isBound = false
        private set
    
    val binder: ServiceBinder?
        get() = service as? ServiceBinder

    private var _status = Status.Stopped
    val status get() = _status

    fun connect() {
        android.util.Log.e(TAG, "Connecting to service")
        
        // First check if the service is actually running using ActivityManager
        val isServiceRunning = try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = runBlocking {
                withContext(Dispatchers.IO) {
                    manager.getRunningServices(Integer.MAX_VALUE)
                }
            }
            
            val isRunning = runningServices.any { 
                it.service.className.contains("VPNService") 
            }
            
            android.util.Log.e(TAG, "Service running check: $isRunning")
            isRunning
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking if service is running", e)
            false
        }
        
        if (!isServiceRunning) {
            android.util.Log.e(TAG, "VPN service is not running, setting status to Stopped")
            callback.onServiceStatusChanged(Status.Stopped)
            return
        }
        
        // Create intent to bind to the service
        val intent = runBlocking {
            withContext(Dispatchers.IO) {
                Intent(context, VPNService::class.java).setAction(Action.SERVICE)
            }
        }
        
        // Try to determine if the service is already running by checking if binding succeeds
        val bound = try {
            context.bindService(intent, this, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to bind to service", e)
            false
        }
        
        // If binding fails, it could mean the service isn't running
        if (!bound) {
            android.util.Log.e(TAG, "Failed to bind to service, setting status to Stopped")
            callback.onServiceStatusChanged(Status.Stopped)
        }
        
        android.util.Log.e(TAG, "Connect binding result: $bound")
    }

    fun disconnect() {
        android.util.Log.e(TAG, "Disconnecting from service")
        
        // First update status to avoid race conditions
        _status = Status.Stopped
        
        // Only try to unbind if we're bound
        if (isBound) {
            try {
                // Before unbinding, notify that service is considered stopped
                android.util.Log.e(TAG, "Unbinding from service, setting status to Stopped")
                callback.onServiceStatusChanged(Status.Stopped)
                
                // Now unbind
                context.unbindService(this)
            } catch (e: IllegalArgumentException) {
                android.util.Log.e(TAG, "Error unbinding from service", e)
            } finally {
                // Even if unbinding fails, mark as unbound
                isBound = false
            }
        } else {
            android.util.Log.e(TAG, "Not bound to service, nothing to disconnect")
            // Still notify status change in case UI is waiting for it
            callback.onServiceStatusChanged(Status.Stopped)
        }
        
        // Clear service reference
        service = null
        
        android.util.Log.e(TAG, "Disconnected from service")
    }

    fun reconnect() {
        android.util.Log.e(TAG, "Reconnecting to service")
        
        // First disconnect properly
        try {
            context.unbindService(this)
            android.util.Log.e(TAG, "Unbound from previous service connection")
        } catch (e: IllegalArgumentException) {
            android.util.Log.e(TAG, "No existing service binding to unbind", e)
        }
        
        // Clear service reference
        service = null
        
        // Create new connection intent
        val intent = runBlocking {
            withContext(Dispatchers.IO) {
                Intent(context, VPNService::class.java).setAction(Action.SERVICE)
            }
        }
        
        // Attempt to bind to the service
        val bound = try {
            context.bindService(intent, this, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to bind during reconnect", e)
            false
        }
        
        android.util.Log.e(TAG, "Reconnect binding result: $bound")
        
        // If binding fails, service is probably not running, so update status
        if (!bound) {
            android.util.Log.e(TAG, "Service not running, updating status to Stopped")
            callback.onServiceStatusChanged(Status.Stopped)
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        android.util.Log.e(TAG, "Service connected")
        this.service = binder
        this.isBound = true
        
        try {
            // Since we're connected to the service, assume it's running
            _status = Status.Started
            
            // Get the service status through the ServiceBinder
            val serviceBinder = binder as? ServiceBinder
            if (serviceBinder != null) {
                android.util.Log.e(TAG, "Got ServiceBinder")
                // In the plugin version, we can't directly access the status
                // Instead, we'll use our assumption that the service is running
            } else {
                android.util.Log.e(TAG, "Service is connected but binder is not ServiceBinder")
            }
            
            // Notify via callback that service is Started since we're connected
            callback.onServiceStatusChanged(Status.Started)
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error initializing service connection", e)
            _status = Status.Stopped
            callback.onServiceStatusChanged(Status.Stopped)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        android.util.Log.e(TAG, "Service disconnected")
        this.service = null
        this.isBound = false
        
        // Update our local status
        _status = Status.Stopped
        
        // Notify that service is stopped through the callback
        callback.onServiceStatusChanged(Status.Stopped)
    }

    override fun onBindingDied(name: ComponentName?) {
        reconnect()
        Log.d(TAG, "service dead")
    }

    interface Callback {
        fun onServiceStatusChanged(status: Status)
        fun onServiceAlert(type: Alert, message: String?) {}
        // Traffic updates are now handled by StatusClient
    }

    // Removed ServiceCallback inner class as we now use the separate ServiceCallback interface
}

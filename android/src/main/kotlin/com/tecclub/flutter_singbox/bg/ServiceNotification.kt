package com.tecclub.flutter_singbox.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tecclub.flutter_singbox.R
import com.tecclub.flutter_singbox.config.SimpleConfigManager
import com.tecclub.flutter_singbox.constant.Status
import androidx.lifecycle.MutableLiveData

class ServiceNotification(
    private val statusLiveData: MutableLiveData<Status>,
    private val service: Service
) {
    companion object {
        private const val CHANNEL_ID = "tecclub_singbox_channel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var pendingIntent: PendingIntent? = null
    
    // Get title and description from config manager
    private val notificationTitle: String
        get() = SimpleConfigManager.getNotificationTitle()
    
    private val notificationDescription: String
        get() = SimpleConfigManager.getNotificationDescription()

    init {
        try {
            android.util.Log.e("ServiceNotification", "Initializing notification")
            createNotificationChannel()
            
            // For Flutter plugin, we don't need an activity intent
            // We'll use a broadcast intent instead that the Flutter plugin can listen for
            android.util.Log.e("ServiceNotification", "Creating broadcast intent")
            val broadcastIntent = Intent(service.packageName + ".OPEN_APP")
            pendingIntent = PendingIntent.getBroadcast(
                service, 0, broadcastIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create a basic builder with VPN key icon
            android.util.Log.e("ServiceNotification", "Creating notification builder with title: $notificationTitle")
            notificationBuilder = NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_key) // Use VPN key icon
                .setContentTitle(notificationTitle)
                .setContentText(notificationDescription)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                
            // Try to set the pending intent if possible
            if (pendingIntent != null) {
                notificationBuilder.setContentIntent(pendingIntent)
            }
            
            android.util.Log.e("ServiceNotification", "Notification builder created successfully")
        } catch (e: Exception) {
            android.util.Log.e("ServiceNotification", "Error initializing notification: ${e.message}", e)
            
            // Create an absolutely minimal builder to prevent crashes
            notificationBuilder = NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(notificationTitle)
                .setContentText(notificationDescription)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                notificationTitle,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "$notificationTitle service notification"
                setShowBadge(false)
            }
            
            // Get notification manager from the service context
            val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun show(profileName: String, details: String) {
        // Use custom title and description if available
        val title = notificationTitle
        val desc = if (details.isNotEmpty()) details else notificationDescription
        
        val notification = notificationBuilder
            .setContentTitle(title)
            .setContentText(desc)
            .build()
            
        service.startForeground(NOTIFICATION_ID, notification)
    }
    
    fun start() {
        // This method is called when the service is successfully started
        statusLiveData.postValue(Status.Started)
    }
    
    fun stop() {
        // This method is called when the service is stopping
        statusLiveData.postValue(Status.Stopped)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            service.stopForeground(true)
        }
    }
}
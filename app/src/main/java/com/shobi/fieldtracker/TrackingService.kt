package com.shobi.fieldtracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that runs only while the rider is on shift.
 * Keeps collecting locations with the screen off and sends them to Odoo.
 */
class TrackingService : Service() {

    companion object {
        private const val CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var queue: PointQueue
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var executor: ExecutorService
    private var lastFlushAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        queue = PointQueue.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        executor = Executors.newSingleThreadExecutor()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // Android refused (e.g. permission removed). Nothing we can do from here.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!prefs.onShift || !prefs.isPaired || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            requestUpdates()
            running = true
        }
        return START_STICKY
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestUpdates() {
        val intervalMs = prefs.intervalSeconds * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .setWaitForAccurateLocation(true)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onLocation(loc: Location) {
        // Every position is recorded with its accuracy. Odoo decides what goes on the route line.
        val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        queue.add(
            t = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            acc = if (loc.hasAccuracy()) loc.accuracy else 0f,
            spdKmh = speedKmh,
            battery = batteryLevel(),
            mock = mock
        )
        val now = System.currentTimeMillis()
        // Send at most every interval (batched), so a burst of locations becomes one request.
        if (now - lastFlushAt >= prefs.intervalSeconds * 1000L - 2000L) {
            lastFlushAt = now
            if (!executor.isShutdown) executor.execute { handleSync(Uploader.sync(this)) }
        }
    }

    private fun handleSync(result: Uploader.Result) {
        if (result == Uploader.Result.UNPAIRED || result == Uploader.Result.ENDED_BY_SERVER) {
            prefs.onShift = false
            stopSelf()
        }
    }

    private fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        running = false
        fused.removeLocationUpdates(callback)
        // Send whatever is left, then shut the worker down.
        executor.execute { Uploader.sync(this) }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

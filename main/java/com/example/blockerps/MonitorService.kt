package com.example.blockerps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 *此段code為人工修改gemini邏輯後的程式碼
 */
class MonitorService : Service() {

    companion object {
        private const val Tag = "BPS_MonitorSvc"
        private const val Pref_Name = "BlockerData"
        private const val TickSet = 1000L
        private const val UsageTraceBack = 2000L
        private const val NoticeID = 9021
    }

    private val MainHandler = Handler(Looper.getMainLooper())
    private var lastActivePkg: String? = null

    // Cache preferences instance to avoid redundant Context calls inside the loop
    private val appPrefs by lazy { getSharedPreferences(Pref_Name, Context.MODE_PRIVATE) }

    private val monitorTicker = object : Runnable {
        override fun run() {
            try {
                inspectForegroundApp()
                evaluateUsageLimits()
            } catch (e: Exception) {
                Log.e(Tag, "Error during tracking tick", e)
            } finally {
                MainHandler.postDelayed(this, TickSet)
            }
        }
    }

    private fun inspectForegroundApp() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val end = System.currentTimeMillis()
        val start = end - UsageTraceBack

        val events = usm.queryEvents(start, end) ?: return
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastActivePkg = event.packageName
            }
        }
    }

    private fun evaluateUsageLimits() {
        val currentPkg = lastActivePkg ?: return
        if (currentPkg == packageName) return

        val trackedApps = appPrefs.getStringSet("monitored_apps", null) ?: return
        if (!trackedApps.contains(currentPkg)) return

        // Increment cumulative time
        val updatedSecs = appPrefs.getInt("${currentPkg}_seconds", 0) + 1
        appPrefs.edit().putInt("${currentPkg}_seconds", updatedSecs).apply()

        // Evaluate thresholds
        val quotaMin = appPrefs.getInt("${currentPkg}_limit_min", 5)
        val quotaSec = appPrefs.getInt("${currentPkg}_limit_sec", 0)
        val targetQuota = (quotaMin * 60) + quotaSec

        if (targetQuota in 1..updatedSecs) {
            Log.i(Tag, "Quota exceeded for: $currentPkg. Launching overlay block.")
            routeToLockScreen()
        }
    }

    private fun routeToLockScreen() {
        try {
            val lockIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(lockIntent)
        } catch (ex: Exception) {
            Log.e(Tag, "Failed to launch block activity", ex)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initForegroundNotification()
        MainHandler.removeCallbacks(monitorTicker)
        MainHandler.post(monitorTicker)
        return START_STICKY
    }

    private fun initForegroundNotification() {
        val chanId = "bps_tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                chanId,
                "App Tracker Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles active background app usage monitoring"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, chanId)
            .setContentTitle("BlockerPlus Active")
            .setContentText("Monitoring configured application usage...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NoticeID, notification)
    }

    override fun onDestroy() {
        MainHandler.removeCallbacks(monitorTicker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
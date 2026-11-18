package com.azzahid.jezail.core.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.azzahid.jezail.core.data.Preferences
import com.azzahid.jezail.core.services.BootActivity
import com.azzahid.jezail.core.services.HttpServerService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastTriggerMs < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "ignore duplicate boot broadcast: ${intent.action}")
                    return
                }
                lastTriggerMs = now
                Log.d(TAG, "boot received (${intent.action}), starting server")
                HttpServerService.start(context, Preferences.serverPort)
                tryStartBootActivity(context)
                scheduleRetry(context, 1)
            }

            ACTION_RETRY -> {
                if (HttpServerService.isRunning()) {
                    Log.d(TAG, "server already running, cancel retries")
                    return
                }
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1)
                Log.d(TAG, "retry server start, attempt=$attempt")
                HttpServerService.start(context, Preferences.serverPort)
                tryStartBootActivity(context)
                scheduleRetry(context, attempt + 1)
            }
        }
    }

    /**
     * Android 10 起 targetSdk 29+ 不能从后台直接启动 Activity，
     * 拥有悬浮窗权限的应用是豁免项，所以需要预先授权 SYSTEM_ALERT_WINDOW。
     */
    private fun tryStartBootActivity(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            Log.d(TAG, "skip BootActivity: overlay permission not granted")
            return
        }
        try {
            val launch = Intent(context, BootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
            Log.d(TAG, "BootActivity launched")
        } catch (e: Exception) {
            Log.e(TAG, "start BootActivity failed", e)
        }
    }

    private fun scheduleRetry(context: Context, attempt: Int) {
        if (attempt > MAX_RETRY) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val retry = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQ_RETRY_BASE + attempt,
            retry,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + RETRY_INTERVAL_MS
        // 非精确闹钟，不需要 SCHEDULE_EXACT_ALARM
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.d(TAG, "scheduled retry $attempt in ${RETRY_INTERVAL_MS}ms")
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_RETRY = "com.azzahid.jezail.action.RETRY_SERVER"
        private const val EXTRA_ATTEMPT = "attempt"
        private const val REQ_RETRY_BASE = 200
        private const val RETRY_INTERVAL_MS = 15_000L
        private const val MAX_RETRY = 3
        private const val DEDUP_WINDOW_MS = 10_000L
        private var lastTriggerMs = 0L
    }
}

package com.azzahid.jezail.core.services

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.azzahid.jezail.core.data.Preferences

/**
 * 透明无界面 Activity，仅用于开机时短暂切到前台，
 * 确保 [HttpServerService] 能正常启动，服务起来后立刻退到后台。
 */
class BootActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var checks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "BootActivity started, bringing up server")
        pollServer()
    }

    private fun pollServer() {
        if (isFinishing) return
        // 服务还没起就再拉一次（幂等：同端口已运行时 HttpServerService 会直接跳过）
        if (!HttpServerService.isRunning() && checks <= 10) {
            HttpServerService.start(this, Preferences.serverPort)
        }
        checks++
        if (HttpServerService.isRunning() || checks > MAX_CHECKS) {
            Log.d(
                TAG,
                "server running=${HttpServerService.isRunning()}, finishing BootActivity"
            )
            finishAndRemoveTask()
            return
        }
        handler.postDelayed(::pollServer, POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BootActivity"
        private const val POLL_INTERVAL_MS = 200L
        private const val MAX_CHECKS = 50 // ~10s
    }
}

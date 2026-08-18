package io.github.fairyxh.VirtualEnv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.github.fairyxh.VirtualEnv.R
import java.util.concurrent.Executors

/** 控制端进程保活入口；录像内容仍由 App 采样链和后端数据库维护。 */
class AppKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        RootProcessProtector.protectNow()
        val channelId = "zve_app_keepalive"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "测试框架后台服务", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId).setSmallIcon(R.mipmap.logo)
                .setContentTitle("测试框架服务运行中")
                .setContentText("保留测试控制与录像采样状态")
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(R.mipmap.logo)
                .setContentTitle("测试框架服务运行中").setOngoing(true).build()
        }
        startForeground(0x5A5646, notification)
        Executors.newSingleThreadExecutor().execute {
            runCatching {
                val result = ApiClient.getRecordingStatus()
                val data = result.data ?: return@runCatching
                if (data.optBoolean("recording", false)) {
                    RecordingCaptureManager.start(
                        this,
                        data.optLong("recordingId", -1L),
                        data.optLong("intervalMs", 1000L) / 1000.0
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RootProcessProtector.protectNow()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
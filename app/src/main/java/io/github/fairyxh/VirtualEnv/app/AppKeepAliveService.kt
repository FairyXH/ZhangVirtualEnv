package io.github.fairyxh.VirtualEnv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.github.fairyxh.VirtualEnv.R

/** 控制端进程保活入口；录制生命周期由 system_server Backend 独立维护。 */
class AppKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        RootProcessProtector.start()
        io.github.fairyxh.VirtualEnv.app.remote.RemoteEnvironmentRuntime.get(this).reconnectPersisted()
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
        // Recording is system-owned. Do not recreate an App-side sampler after a
        // service restart: a second writer can inflate frame payloads and compete
        // with system_server finalization.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RootProcessProtector.start()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
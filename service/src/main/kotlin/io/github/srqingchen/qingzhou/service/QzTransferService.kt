package io.github.srqingchen.qingzhou.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.network.session.SessionManager
import kotlinx.coroutines.launch

/**
 * 接收守护前台服务（dataSync）：保证息屏/后台时仍可被发现与接收。
 * 每次 onStartCommand 都重调 startForeground（A9+ 规范）。
 */
class QzTransferService : LifecycleService() {

    companion object {
        const val CHANNEL_RECEIVE = "qz_receive"
        const val CHANNEL_EVENTS = "qz_events"
        const val NOTIF_ID_RECEIVE = 1001
        const val ACTION_STOP = "io.github.srqingchen.qingzhou.action.STOP_RECEIVE"
        const val ACTION_ACCEPT_PAIR = "io.github.srqingchen.qingzhou.action.ACCEPT_PAIR"
        const val ACTION_DECLINE_PAIR = "io.github.srqingchen.qingzhou.action.DECLINE_PAIR"
        const val EXTRA_PAIR_ID = "pair_id"
        const val ACTION_ACCEPT_XFER = "io.github.srqingchen.qingzhou.action.ACCEPT_XFER"
        const val ACTION_DECLINE_XFER = "io.github.srqingchen.qingzhou.action.DECLINE_XFER"
        const val EXTRA_TOKEN = "token"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, QzTransferService::class.java),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        startInForeground()
        QzLog.i("service", "接收守护服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                TransferCenter.stop(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ACCEPT_PAIR -> {
                val pairId = intent.getStringExtra(EXTRA_PAIR_ID)
                if (pairId != null) {
                    SessionManager.acceptPair(pairId)
                    cancelEventNotification(pairId.hashCode())
                }
            }
            ACTION_DECLINE_PAIR -> {
                val pairId = intent.getStringExtra(EXTRA_PAIR_ID)
                if (pairId != null) {
                    SessionManager.declinePair(pairId)
                    cancelEventNotification(pairId.hashCode())
                }
            }

            ACTION_ACCEPT_XFER -> {
                val token = intent.getStringExtra(EXTRA_TOKEN)
                if (token != null) {
                    io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine.acceptOffer(token)
                    cancelEventNotification(token.hashCode())
                }
            }

            ACTION_DECLINE_XFER -> {
                val token = intent.getStringExtra(EXTRA_TOKEN)
                if (token != null) {
                    io.github.srqingchen.qingzhou.core.network.transfer.FileTransferEngine.declineOffer(token)
                    cancelEventNotification(token.hashCode())
                }
            }
        }
        // 每次启动都重新 startForeground
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        QzLog.i("service", "接收守护服务已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startInForeground() {
        val notification = buildReceiveNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_RECEIVE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID_RECEIVE, notification)
        }
    }

    private fun buildReceiveNotification(context: Context): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, QzTransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_RECEIVE)
            .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
            .setContentTitle("青舟 · 正在接收")
            .setContentText("同一网络或热点下的设备可以发现你并发送内容")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, "停止接收", stopIntent)
            .build()
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVE, "接收守护", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "接收与配对", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun cancelEventNotification(id: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }
}

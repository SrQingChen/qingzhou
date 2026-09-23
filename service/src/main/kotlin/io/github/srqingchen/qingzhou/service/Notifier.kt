package io.github.srqingchen.qingzhou.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.srqingchen.qingzhou.core.model.formatSize
import io.github.srqingchen.qingzhou.core.network.QzEvent

/**
 * 事件 → 系统通知（M1 基础版；M2 升级为四级降级链，M3 上岛）。
 */
object Notifier {

    fun onEvent(context: Context, event: QzEvent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        when (event) {
            is QzEvent.TextReceived -> {
                val n = NotificationCompat.Builder(context, QzTransferService.CHANNEL_EVENTS)
                    .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
                    .setContentTitle("收到来自 ${event.fromName} 的消息")
                    .setContentText(event.content.take(80))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(event.content))
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(event.hashCode(), n)
            }

            is QzEvent.PairRequested -> {
                val accept = PendingIntent.getService(
                    context, event.requestId.hashCode(),
                    Intent(context, QzTransferService::class.java)
                        .setAction(QzTransferService.ACTION_ACCEPT_PAIR)
                        .putExtra(QzTransferService.EXTRA_PAIR_ID, event.requestId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val decline = PendingIntent.getService(
                    context, (event.requestId + "x").hashCode(),
                    Intent(context, QzTransferService::class.java)
                        .setAction(QzTransferService.ACTION_DECLINE_PAIR)
                        .putExtra(QzTransferService.EXTRA_PAIR_ID, event.requestId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(context, QzTransferService.CHANNEL_EVENTS)
                    .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
                    .setContentTitle("配对请求 · ${event.name}")
                    .setContentText(
                        "机型 ${event.model.ifEmpty { "未知" }} · 核对码 ${event.shortCode.take(3)} ${event.shortCode.takeLast(3)}",
                    )
                    .setOngoing(true)
                    .addAction(0, "接受", accept)
                    .addAction(0, "拒绝", decline)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(event.requestId.hashCode(), n)
            }

            is QzEvent.PairCompleted -> {
                nm.cancel(event.hashCode()) // 兜底
                val n = NotificationCompat.Builder(context, QzTransferService.CHANNEL_EVENTS)
                    .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
                    .setContentTitle("配对成功")
                    .setContentText("已与 ${event.name} 完成配对，可以互发消息与文件")
                    .setAutoCancel(true)
                    .build()
                nm.notify(event.hashCode(), n)
            }

            is QzEvent.PairDeclined -> nm.cancel("pair_${event.fingerprint}".hashCode())

            is QzEvent.OfferRequested -> {
                val accept = PendingIntent.getService(
                    context, event.token.hashCode(),
                    Intent(context, QzTransferService::class.java)
                        .setAction(QzTransferService.ACTION_ACCEPT_XFER)
                        .putExtra(QzTransferService.EXTRA_TOKEN, event.token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val decline = PendingIntent.getService(
                    context, (event.token + "x").hashCode(),
                    Intent(context, QzTransferService::class.java)
                        .setAction(QzTransferService.ACTION_DECLINE_XFER)
                        .putExtra(QzTransferService.EXTRA_TOKEN, event.token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = NotificationCompat.Builder(context, QzTransferService.CHANNEL_EVENTS)
                    .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
                    .setContentTitle("${event.name} 想发送文件")
                    .setContentText("${event.fileNames.size} 个文件 · ${formatSize(event.totalBytes)}")
                    .setOngoing(true)
                    .addAction(0, "接收", accept)
                    .addAction(0, "拒绝", decline)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(event.token.hashCode(), n)
            }

            is QzEvent.FilesReceived -> {
                nm.cancel(event.token.hashCode())
                val n = NotificationCompat.Builder(context, QzTransferService.CHANNEL_EVENTS)
                    .setSmallIcon(io.github.srqingchen.qingzhou.service.R.drawable.ic_stat_qingzhou)
                    .setContentTitle("已接收 ${event.fileNames.size} 个文件")
                    .setContentText("来自 ${event.fromName} · 保存在 Download/QingZhou")
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
                nm.notify(event.token.hashCode(), n)
            }

            is QzEvent.PairFailed -> Unit // UI 内提示，不打扰通知栏
            is QzEvent.DeviceFound, is QzEvent.DeviceLost -> Unit
            is QzEvent.TextSent -> Unit
        }
    }
}

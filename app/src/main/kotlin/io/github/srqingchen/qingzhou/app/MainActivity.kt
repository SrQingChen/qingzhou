package io.github.srqingchen.qingzhou.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.data.ShareInbox
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzTheme
import io.github.srqingchen.qingzhou.feature.home.HomeScreen

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果无关紧要：拒绝仅少通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleShareIntent(intent)
        setContent {
            QzTheme {
                HomeScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** 系统分享入口：其他应用「分享 → 青舟」暂存内容，发现页横幅点设备发送。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val payload = when {
                    stream != null -> ShareInbox.SharePayload(
                        uris = listOf(stream),
                        mimeType = intent.type,
                        fromApp = referrerPackage(),
                    )

                    !text.isNullOrBlank() -> ShareInbox.SharePayload(text = text, fromApp = referrerPackage())

                    else -> return
                }
                ShareInbox.offer(payload)
                QzLog.i("share", "收到分享：${payload.summary}")
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!streams.isNullOrEmpty()) {
                    ShareInbox.offer(
                        ShareInbox.SharePayload(uris = streams, mimeType = intent.type, fromApp = referrerPackage()),
                    )
                    QzLog.i("share", "收到分享：${streams.size} 个文件")
                }
            }
        }
    }

    private fun referrerPackage(): String? = runCatching { referrer?.host }.getOrNull()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

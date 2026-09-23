package io.github.srqingchen.qingzhou.service.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager
import io.github.srqingchen.qingzhou.service.R
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 超级岛发布器（四级降级）：
 * ① OS3 超级岛：`miui.focus.param`（V3 模板，胶囊态环形进度 + 展开态明细）
 * ② OS2 焦点通知：同一 JSON（数据接口相同）
 * ③ Android 16+ 原生 Live Updates（Notification.ProgressStyle）
 * ④ 普通常驻通知（进度条 + 速率）
 *
 * 「兼容模式」：非白名单应用直发会被云端鉴权摘岛（fail-open 漏洞）；
 * 岛展示期间经 Shizuku 临时切断 com.xiaomi.xmsf 联网令鉴权放行。
 * 工程要点继承尘露验证经验：单线程网关 + 意图代数防竞态 + 状态落盘防遗留。
 */
object FocusIslandPublisher {

    private const val TAG = "island"
    private const val CHANNEL_ID = "qz_island"
    private const val NOTIF_ID = 2001
    private const val ACCENT = "#00838F"
    private const val ACCENT_UNREACH = "#33FFFFFF"

    /** 进度上岛开关。 */
    @Volatile
    var enabled = true

    /** 兼容模式（断 xmsf 令鉴权放行）；需 Shizuku 就绪。 */
    @Volatile
    var compatMode = false

    /** 由 Application 生命周期维护：应用是否前台（决定待命岛显隐）。 */
    @Volatile
    var appForeground = false

    @Volatile
    private var channelReady = false

    private val gateExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var gateWanted = false

    @Volatile
    private var gateGeneration = 0L

    /** 焦点通知协议版本：0=不支持 1=OS1 2=OS2 3=OS3（岛）。 */
    fun focusProtocol(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    // ---------- 发布 ----------

    /**
     * 发布传输进度。
     * @param firstShow 首次出现（岛直接以胶囊形态出现 + 光效）
     */
    fun publishProgress(
        context: Context,
        title: String,
        content: String,
        progress: Int,
        progressText: String,
        firstShow: Boolean,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
    ) {
        if (!enabled) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        engageGateIfNeeded(appContext)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(appContext, title, content, progress, progressText, firstShow, actions))
    }

    /** 待命岛：空闲（无传输）且应用退后台时常驻 —— 对齐尘露“离应用即上岛”。 */
    fun publishIdle(context: Context, myName: String) {
        if (!enabled) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        engageGateIfNeeded(appContext)
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIF_ID,
            buildNotification(
                appContext,
                title = "青舟 · 待命",
                content = "$myName 正在附近等待",
                progress = 0,
                progressText = "可接收消息与文件",
                firstShow = false,
                actions = emptyList(),
            ),
        )
    }

    /** 收岛。 */
    fun dismiss(context: Context) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        releaseGate(appContext)
    }

    /** 服务启动时调用：清理上次遗留的断网状态（仅当本进程无需断网时）。 */
    fun restoreGateIfNeeded(context: Context) {
        if (gateWanted) return
        val flag = gateFlagFile(context)
        if (flag.exists()) {
            val gen = ++gateGeneration
            gateExecutor.execute {
                if (gen != gateGeneration) return@execute
                val err = QzShizukuManager.shell?.xmsfGate(false)
                if (err == null) {
                    flag.delete()
                    QzLog.i(TAG, "已恢复上次遗留的 xmsf 断网状态")
                }
            }
        }
    }

    // ---------- 兼容模式网关 ----------

    private fun engageGateIfNeeded(context: Context) {
        if (!compatMode || gateWanted) return
        if (QzShizukuManager.shell == null) return
        gateWanted = true
        val gen = ++gateGeneration
        gateExecutor.execute {
            if (gen != gateGeneration) return@execute
            val err = QzShizukuManager.shell?.xmsfGate(true)
            if (err == null) {
                runCatching { gateFlagFile(context).writeText("blocked") }
                QzLog.i(TAG, "兼容模式：已临时切断 xmsf 联网（岛鉴权 fail-open）")
            } else {
                gateWanted = false
                QzLog.w(TAG, "兼容模式切断 xmsf 失败（岛可能不上岛，退化为通知）: $err")
            }
        }
    }

    private fun releaseGate(context: Context) {
        if (!gateWanted) return
        gateWanted = false
        val gen = ++gateGeneration
        gateExecutor.execute {
            runCatching { Thread.sleep(1500) }
            if (gen != gateGeneration) return@execute
            val err = QzShizukuManager.shell?.xmsfGate(false)
            if (err == null) {
                runCatching { gateFlagFile(context).delete() }
                QzLog.i(TAG, "兼容模式：xmsf 联网已恢复")
            } else {
                QzLog.w(TAG, "xmsf 恢复失败（将在下次启动时重试）: $err")
            }
        }
    }

    private fun gateFlagFile(context: Context): File =
        File(context.applicationContext.filesDir, "island_gate.flag")

    // ---------- 构建 ----------

    private fun ensureChannel(context: Context) {
        if (channelReady) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "超级岛进度", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "传输进度上岛与常驻进度通知"
                setSound(null, null)
                enableVibration(false)
            },
        )
        channelReady = true
    }

    private fun buildNotification(
        context: Context,
        title: String,
        content: String,
        progress: Int,
        progressText: String,
        firstShow: Boolean,
        actions: List<Pair<String, PendingIntent>>,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_qingzhou)
            .setContentTitle(title)
            .setContentText("$content · $progressText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .setContentIntent(launchIntent(context))
        actions.take(3).forEachIndexed { i, (label, pi) ->
            builder.addAction(0, label, pi)
        }

        // ①② 小米焦点/岛 extras：注入 compat builder（<36 路径即 OS1/2/3 的主力机型）
        attachMiuiExtras(builder, title, content, progressText, progress, firstShow)

        // ③ Android 16+ 原生 Live Updates：ProgressStyle（ColorOS/vivo 等亦认）；
        // compat 库暂无对应 Style，API 36+ 走框架 Builder 另建
        return if (Build.VERSION.SDK_INT >= 36) {
            buildFrameworkNotification(context, title, "$content · $progressText", progress, firstShow, actions)
        } else {
            builder.build()
        }
    }

    /** 小米焦点/岛 extras 注入（不识别的系统自动忽略，天然降级）。 */
    private fun attachMiuiExtras(
        builder: NotificationCompat.Builder,
        title: String,
        content: String,
        progressText: String,
        progress: Int,
        firstShow: Boolean,
    ) {
        val icon = simpleIcon(builder.mContext)
        val extras = Bundle().apply {
            putString("miui.focus.param", buildFocusJson(title, content, progressText, progress, firstShow))
            if (icon != null) {
                putBundle(
                    "miui.focus.pics",
                    Bundle().apply {
                        putParcelable("miui.focus.pic_progress_app", icon)
                        putParcelable("miui.focus.pic_progress_capsule", icon)
                    },
                )
            }
        }
        builder.addExtras(extras)
    }

    /** API 36+：框架 Builder（支持 Notification.ProgressStyle 原生灵动胶囊）。 */
    private fun buildFrameworkNotification(
        context: Context,
        title: String,
        text: String,
        progress: Int,
        firstShow: Boolean,
        actions: List<Pair<String, PendingIntent>>,
    ): Notification {
        val builder = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_qingzhou)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .setContentIntent(launchIntent(context))
        actions.take(3).forEach { (label, pi) -> builder.addAction(android.app.Notification.Action.Builder(0, label, pi).build()) }
        runCatching { builder.setStyle(android.app.Notification.ProgressStyle()) }

        val icon = simpleIcon(context)
        val extras = Bundle().apply {
            putString("miui.focus.param", buildFocusJson(title, text, "", progress, firstShow))
            if (icon != null) {
                putBundle(
                    "miui.focus.pics",
                    Bundle().apply {
                        putParcelable("miui.focus.pic_progress_app", icon)
                        putParcelable("miui.focus.pic_progress_capsule", icon)
                    },
                )
            }
        }
        builder.addExtras(extras)
        return builder.build()
    }

    /** V3 焦点岛参数（business 复用官方 download_progress 模板）。 */
    private fun buildFocusJson(
        title: String,
        content: String,
        progressText: String,
        progress: Int,
        firstShow: Boolean,
    ): String {
        val root = JSONObject().put(
            "param_v2",
            JSONObject()
                .put("business", "download_progress")
                .put("updatable", true)
                .put("notifyId", NOTIF_ID.toString())
                .put("timeout", 1440)
                .put("sequence", SystemClock.elapsedRealtime())
                .put("aodTitle", "$title $progressText")
                .put("ticker", "$title $content")
                .put("reopen", "reopen")
                .put("islandProperty", 1)
                .put("islandTimeout", 86400)
                .put("dismissIsland", false)
                .put("islandFirstFloat", firstShow)
                .apply {
                    if (firstShow) put("outEffectSrc", "glow")
                }
                .put("chatInfo", JSONObject().put("title", title).put("content", content))
                .put("multiProgressInfo", JSONObject().put("progress", progress).put("color", ACCENT))
                .put(
                    "bigIslandArea",
                    JSONObject().put(
                        "imageTextInfoLeft",
                        JSONObject()
                            .put("type", 1)
                            .put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_progress_app"))
                            .put(
                                "textInfo",
                                JSONObject().put("title", title).put("content", "$content · $progressText")
                                    .put("showHighlightColor", true),
                            ),
                    ),
                )
                .put(
                    "smallIslandArea",
                    JSONObject().put(
                        "combinePicInfo",
                        JSONObject()
                            .put("picInfo", JSONObject().put("type", 1).put("pic", "miui.focus.pic_progress_capsule"))
                            .put(
                                "progressInfo",
                                JSONObject()
                                    .put("progress", progress)
                                    .put("colorReach", ACCENT)
                                    .put("colorUnReach", ACCENT_UNREACH)
                                    .put("isCCW", true),
                            ),
                    ),
                ),
        )
        return root.toString()
    }

    private fun launchIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun simpleIcon(context: Context): android.graphics.drawable.Icon? = runCatching {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_stat_qingzhou) ?: return null
        val size = 88
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        android.graphics.drawable.Icon.createWithBitmap(bitmap)
    }.getOrNull()
}

package io.github.srqingchen.qingzhou.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.srqingchen.qingzhou.core.common.QzLog
import io.github.srqingchen.qingzhou.core.data.ChatStore
import io.github.srqingchen.qingzhou.core.data.InboxStore
import io.github.srqingchen.qingzhou.core.data.PairedDeviceStore
import io.github.srqingchen.qingzhou.core.data.SettingsStore
import io.github.srqingchen.qingzhou.core.shizuku.QzShizukuManager
import io.github.srqingchen.qingzhou.service.TransferCenter
import io.github.srqingchen.qingzhou.service.island.FocusIslandPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QingzhouApp : Application() {

    private var activityCount = 0

    override fun onCreate() {
        super.onCreate()
        // 组装根：日志 → 存储 → Shizuku 通道 → 接收总控
        QzLog.init(this)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        SettingsStore.init(this, appScope)
        PairedDeviceStore.init(this, appScope)
        InboxStore.init(this, appScope)
        ChatStore.init(this, appScope)
        runCatching {
            QzLog.setMode(io.github.srqingchen.qingzhou.core.common.QzLog.Mode.valueOf(SettingsStore.settings.value.logMode))
        }
        QzShizukuManager.start(this)
        appScope.launch {
            runCatching { TransferCenter.start(this@QingzhouApp) }
                .onFailure { QzLog.e("app", "总控启动失败：${it.message}") }
        }

        // 前后台监听（尘露同款）：退后台空闲时上待命岛，回前台收起
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                if (activityCount == 1) FocusIslandPublisher.appForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount <= 0) {
                    activityCount = 0
                    FocusIslandPublisher.appForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        QzLog.i("app", "青舟启动")
    }
}

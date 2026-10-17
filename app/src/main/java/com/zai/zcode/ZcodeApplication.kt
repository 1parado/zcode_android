package com.zai.zcode

import android.app.Application
import android.util.Log
import com.zai.zcode.core.common.ZLog
import dagger.hilt.android.HiltAndroidApp

/**
 * App 入口：安装 ZLog 的 Logcat sink（core 模块纯 JVM，需要宿主提供输出通道）。
 * 注意：日志内容不携带凭据（协议层只打事件与状态，不打 token）。
 */
@HiltAndroidApp
class ZcodeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ZLog.install { level, tag, message, error ->
            when (level) {
                ZLog.Level.DEBUG -> Log.d(tag, message, error)
                ZLog.Level.INFO -> Log.i(tag, message, error)
                ZLog.Level.WARN -> Log.w(tag, message, error)
                ZLog.Level.ERROR -> Log.e(tag, message, error)
            }
        }
    }
}

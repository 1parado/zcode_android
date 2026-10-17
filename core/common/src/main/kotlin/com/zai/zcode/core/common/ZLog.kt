package com.zai.zcode.core.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简日志门面：core 模块是纯 JVM，不能直接用 android.util.Log。
 * App 启动时（或测试里）安装一个 sink 即可输出。
 */
object ZLog {
    fun interface Sink {
        fun log(level: Level, tag: String, message: String, error: Throwable?)
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    private var sink: Sink? = null
    private val installed = AtomicBoolean(false)

    fun install(newSink: Sink) {
        sink = newSink
    }

    /** 测试 / 未安装时的默认输出：stderr。只在显式开启时生效。 */
    fun enableStderrFallback() {
        if (installed.compareAndSet(false, true)) {
            sink = Sink { level, tag, message, error ->
                System.err.println("[zcode/${level.name}] $tag: $message${error?.let { " — $it" } ?: ""}")
            }
        }
    }

    fun d(tag: String, message: String) = dispatch(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = dispatch(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = dispatch(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = dispatch(Level.ERROR, tag, message, error)

    private fun dispatch(level: Level, tag: String, message: String, error: Throwable?) {
        try {
            sink?.log(level, tag, message, error)
        } catch (_: Throwable) {
            // 日志永远不能把业务打挂
        }
    }
}

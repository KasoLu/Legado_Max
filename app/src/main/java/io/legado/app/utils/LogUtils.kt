@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

@SuppressLint("SimpleDateFormat")
@Suppress("unused")
object LogUtils {
    const val TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"
    val logTimeFormat by lazy { SimpleDateFormat(TIME_PATTERN) }

    private var fileHandler: FileHandler? = null
    private var appContext: Context? = null

    /**
     * 初始化文件日志。仅当 recordLog 开启时才创建文件 handler（并生成日志文件）；
     * 关闭时不创建，避免产生大量 0 字节日志文件。
     * 运行中通过 [upLevel] 开启记录时会按需补建 handler。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (AppConfig.recordLog) {
            fileHandler = createFileHandler(context)?.also {
                logger.addHandler(it)
            }
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        logger.log(Level.INFO, "$tag $msg")
    }

    inline fun d(tag: String, lazyMsg: () -> String) {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "$tag ${lazyMsg()}")
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        logger.log(Level.WARNING, "$tag $msg")
    }

    val logger: Logger by lazy {
        Logger.getLogger("Legado")
    }

    private fun createFileHandler(context: Context): FileHandler? {
        try {
            val root = context.externalCacheDir ?: return null
            val logFolder = FileUtils.createFolderIfNotExist(root, "logs")
            globalExecutor.execute {
                val expiredTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
                logFolder.listFiles()?.forEach {
                    if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) {
                        it.delete()
                    }
                }
            }
            val date = getCurrentDateStr(TIME_PATTERN).replace(" ", "_").replace(":", "-")
            val logPath = FileUtils.getPath(root = logFolder, "appLog-$date.txt")
            return AsyncFileHandler(logPath).apply {
                formatter = object : java.util.logging.Formatter() {
                    override fun format(record: LogRecord): String {
                        // LogRecord 无公开 time 属性；getInstant() 为 API 26+，
                        // 低版本/异常时回退当前时间（异步写盘的时间漂移仅限排队时延）
                        // LogRecord.millis 为记录创建时刻（纯 long，无 java.time 依赖）；为 0 时回退当前时间
                        val epoch = if (record.millis > 0L) {
                            record.millis
                        } else {
                            System.currentTimeMillis()
                        }
                        val time = SimpleDateFormat(TIME_PATTERN).format(Date(epoch))
                        return time + ": " + record.message + "\n"
                    }
                }
                level = if (AppConfig.recordLog) {
                    Level.INFO
                } else {
                    Level.OFF
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.putNotSave("创建fileHandler出错\n$e", e)
            return null
        }
    }

    /**
     * 更新文件日志级别（记录开关变化时调用）。
     * 开启记录且 handler 尚未创建（启动时开关为关）时，补建 handler 并挂到 logger。
     */
    fun upLevel() {
        if (AppConfig.recordLog) {
            val handler = fileHandler
            if (handler == null) {
                val context = appContext ?: return
                fileHandler = createFileHandler(context)?.also {
                    logger.addHandler(it)
                }
            } else {
                handler.level = Level.INFO
            }
        } else {
            fileHandler?.level = Level.OFF
        }
    }

    /**
     * 获取当前时间
     */
    @SuppressLint("SimpleDateFormat")
    fun getCurrentDateStr(pattern: String): String {
        val date = Date()
        val sdf = SimpleDateFormat(pattern)
        return sdf.format(date)
    }

    fun logDeviceInfo() {
        d("DeviceInfo") {
            buildString {
                kotlin.runCatching {
                    //获取系统信息
                    append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                    append("BRAND=").append(Build.BRAND).append("\n")
                    append("MODEL=").append(Build.MODEL).append("\n")
                    append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                    append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                    val userAgent = try {
                        WebSettings.getDefaultUserAgent(appCtx)
                    } catch (e: Throwable) {
                        e.toString()
                    }
                    append("WebViewUserAgent=").append(userAgent).append("\n")
                    append("packageName=").append(appCtx.packageName).append("\n")
                    append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                    //获取app版本信息
                    AppConst.appInfo.let {
                        append("versionName=").append(it.versionName).append("\n")
                        append("versionCode=").append(it.versionCode).append("\n")
                    }
                }
            }
        }
    }

}

fun Throwable.printOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}

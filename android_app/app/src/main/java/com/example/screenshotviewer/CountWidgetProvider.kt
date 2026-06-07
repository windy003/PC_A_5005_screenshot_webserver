package com.example.screenshotviewer

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 图片数量统计小部件。
 *
 * 三行显示：
 *   1) 自定义名称（橙色）
 *   2) X 个（X 红色、个 绿色）
 *   3) xx:xx 前（xx:xx 红色、前 蓝色，使用 Chronometer 自动走时）
 *
 * 数据来源：服务端免登录接口 {url}/{path}/count_api -> {"count": N}
 * 字体大小根据小部件实际尺寸自动计算，尽可能大且不溢出。
 */
open class CountWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PREFS = "CountWidgetPrefs"
        const val MAIN_PREFS = "ScreenshotViewerPrefs"   // 主界面的配置，用于取默认服务器地址
        const val ACTION_REFRESH = "com.example.screenshotviewer.ACTION_WIDGET_REFRESH"

        /** 自动刷新间隔：每分钟拉一次服务端 count_api。 */
        const val AUTO_REFRESH_INTERVAL_MS = 60_000L

        fun keyName(id: Int) = "name_$id"
        fun keyUrl(id: Int) = "url_$id"
        fun keyPath(id: Int) = "path_$id"
        fun keyCount(id: Int) = "count_$id"
        fun keyFetchWall(id: Int) = "fetchwall_$id"

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** 主动请求刷新某个小部件（配置保存后调用）。 */
        fun requestRefresh(context: Context, appWidgetId: Int) {
            val comp = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider
            val intent = Intent(ACTION_REFRESH).apply {
                if (comp != null) component = comp
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            context.sendBroadcast(intent)
        }

        /** 用于定时刷新的 PendingIntent（每个 provider 子类一个，互不干扰）。 */
        private fun alarmPendingIntent(context: Context, providerClass: Class<*>): PendingIntent {
            val intent = Intent(context, providerClass).apply { action = ACTION_REFRESH }
            return PendingIntent.getBroadcast(
                context,
                providerClass.name.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** 注册每分钟一次的定时刷新（重复调用是幂等的）。 */
        fun scheduleAlarm(context: Context, providerClass: Class<*>) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = alarmPendingIntent(context, providerClass)
            // 用 setRepeating（非精确，省电、无需精确闹钟权限）；间隔 1 分钟。
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AUTO_REFRESH_INTERVAL_MS,
                AUTO_REFRESH_INTERVAL_MS,
                pi
            )
        }

        /** 取消定时刷新（最后一个该类型小部件被移除时调用）。 */
        fun cancelAlarm(context: Context, providerClass: Class<*>) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(alarmPendingIntent(context, providerClass))
        }

        /** 用当前缓存的数据刷新小部件 UI（不发起网络请求）。 */
        fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val name = prefs.getString(keyName(id), "") ?: ""
            val count = prefs.getInt(keyCount(id), -1)
            val fetchWall = prefs.getLong(keyFetchWall(id), 0L)

            val views = RemoteViews(context.packageName, R.layout.widget_count)

            // 第一行：名称
            views.setTextViewText(R.id.widget_name, name)

            // 第二行：数量
            val countText = if (count >= 0) count.toString() else "—"
            views.setTextViewText(R.id.widget_count_num, countText)

            // 第三行：Chronometer 自动走时，base = 上次获取时刻
            if (fetchWall > 0) {
                val elapsedSince = System.currentTimeMillis() - fetchWall
                val base = SystemClock.elapsedRealtime() - elapsedSince
                views.setChronometer(R.id.widget_time, base, null, true)
            } else {
                // 还未成功获取过：停在 00:00
                views.setChronometer(R.id.widget_time, SystemClock.elapsedRealtime(), null, false)
            }

            // 字体自适应
            val options = mgr.getAppWidgetOptions(id)
            applyTextSizes(views, options, name, countText)

            // 点击小部件 -> 打开主界面对应标签页（small 小部件开 small 标签，large 开 large 标签）
            val slot = if (mgr.getAppWidgetInfo(id)?.provider?.className
                    == LargeCountWidgetProvider::class.java.name) 1 else 0
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_SLOT, slot)
                // 带上被点击的小部件 id，MainActivity 打开后会立即对它再拉一次 count_api
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            val pi = PendingIntent.getActivity(
                context, id, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(id, views)
        }

        /** 根据小部件尺寸（dp）计算并设置每一行的字体大小（sp）。 */
        private fun applyTextSizes(
            views: RemoteViews,
            options: Bundle,
            name: String,
            countText: String
        ) {
            // 取 minWidth(竖屏宽) 与 minHeight(横屏高) 这两个较保守的值；兜底默认按 1x1≈40dp。
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val w = (if (minW > 0) minW else 40).toFloat()
            val h = (if (minH > 0) minH else 40).toFloat()

            // 扣除与布局一致的内/外边距（dp）：
            //   竖直 = 根 padding 3*2 + 每行上下 margin 1*2 ×3 = 12
            //   水平 = 根 padding 3*2 + 行内左右 padding 2*2 = 10
            val vPad = 3f * 2 + 1f * 2 * 3
            val hPad = 3f * 2 + 2f * 2
            val availW = (w - hPad).coerceAtLeast(14f)
            val rowH = ((h - vPad) / 3f).coerceAtLeast(7f)

            // 估算整行文本宽度（单位 em）：中文/全角约 1.15em，数字/字母/冒号约 0.60em
            fun emWidth(s: String): Float {
                var sum = 0f
                for (ch in s) sum += if (ch.code >= 0x2E80) 1.15f else 0.60f
                return sum.coerceAtLeast(1f)
            }

            // 第一、二行的完整文本（含中文单位）
            val texts = listOf(
                if (name.isEmpty()) " " else name,
                countText + "个"
            )

            // 第三行（时间行）由 Chronometer + 后缀两个 TextView 组成，粗体冒号/数字会更宽，
            // 两个 TextView 之间还有间隙，因此在 "00:00前" 的基础上额外加 0.9em 安全余量，
            // 避免右侧的 "前" 被挤出边界。
            val timeEm = emWidth("00:00前") + 0.9f

            // 统一字号：宽度上以“最宽一行”为准、不超过可用宽度；高度上不超过每行行高；取较小值
            val maxEm = maxOf(texts.maxOf { emWidth(it) }, timeEm)
            val byWidth = availW / maxEm
            val byHeight = rowH * 0.62f
            val size = (minOf(byHeight, byWidth) * 0.90f).coerceIn(5f, 200f)

            views.setTextViewTextSize(R.id.widget_name, TypedValue.COMPLEX_UNIT_SP, size)
            views.setTextViewTextSize(R.id.widget_count_num, TypedValue.COMPLEX_UNIT_SP, size)
            views.setTextViewTextSize(R.id.widget_count_unit, TypedValue.COMPLEX_UNIT_SP, size)
            views.setTextViewTextSize(R.id.widget_time, TypedValue.COMPLEX_UNIT_SP, size)
            views.setTextViewTextSize(R.id.widget_time_suffix, TypedValue.COMPLEX_UNIT_SP, size)
        }

        /** 同步发起一次网络请求并更新小部件（在后台线程调用）。 */
        private fun fetchOnce(context: Context, mgr: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = prefs.getString(keyUrl(id), "") ?: ""
            val path = prefs.getString(keyPath(id), "") ?: ""

            if (url.isNotEmpty()) {
                val full = buildString {
                    append(url.trimEnd('/'))
                    val p = path.trim('/')
                    if (p.isNotEmpty()) {
                        append('/')
                        append(p)
                    }
                    append("/count_api")
                }
                try {
                    val request = Request.Builder().url(full).get().build()
                    http.newCall(request).execute().use { resp ->
                        val body = resp.body?.string()
                        if (resp.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            if (json.optBoolean("success", false)) {
                                val count = json.optInt("count", -1)
                                prefs.edit()
                                    .putInt(keyCount(id), count)
                                    .putLong(keyFetchWall(id), System.currentTimeMillis())
                                    .apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()   // 失败保持上次数据，不更新时间
                }
            }

            updateWidget(context, mgr, id)
        }

        /** 后台刷新一批小部件。 */
        fun refreshInBackground(context: Context, ids: IntArray, onDone: (() -> Unit)? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            thread {
                try {
                    for (id in ids) if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        fetchOnce(context, mgr, id)
                    }
                } finally {
                    onDone?.invoke()
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        // 添加第一个该类型小部件时，启动每分钟一次的定时刷新
        scheduleAlarm(context, javaClass)
    }

    override fun onDisabled(context: Context) {
        // 最后一个该类型小部件被移除时，取消定时刷新
        cancelAlarm(context, javaClass)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // 确保定时刷新已注册（重启后系统会触发 onUpdate，借此重新登记闹钟）
        scheduleAlarm(context, javaClass)
        // 先用缓存立即显示，再后台拉取最新数据
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // 尺寸变化时重算字体
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val action = intent.action
        if (action == ACTION_REFRESH || action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids: IntArray =
                if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
                    intArrayOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))
                } else {
                    mgr.getAppWidgetIds(ComponentName(context, javaClass))
                }

            val pending = goAsync()
            refreshInBackground(context, ids) { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) {
            editor.remove(keyName(id))
                .remove(keyUrl(id))
                .remove(keyPath(id))
                .remove(keyCount(id))
                .remove(keyFetchWall(id))
        }
        editor.apply()
    }
}

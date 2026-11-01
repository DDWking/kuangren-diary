package com.ddw.kuangren.lock

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ddw.kuangren.MainActivity

/**
 * 全屏软锁覆盖层。用系统悬浮窗实现，不依赖用户正在使用的桌面窗口。
 * 只在"使用情况访问 + 悬浮窗"权限都授予后才真正显示。
 */
object OverlayController {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var reasonView: TextView? = null
    private var statusView: TextView? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = root != null

    fun show(context: Context, appLabel: String, reason: String, status: String, curfew: String) {
        val app = context.applicationContext
        handler.post { showInternal(app, appLabel, reason, status, curfew) }
    }

    fun update(context: Context, reason: String, status: String) {
        handler.post {
            reasonView?.text = reason
            statusView?.text = status
        }
    }

    fun hide() {
        handler.post { hideInternal() }
    }

    private fun showInternal(context: Context, appLabel: String, reason: String, status: String, curfew: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (root != null) {
            reasonView?.text = reason
            statusView?.text = status
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = buildView(context, appLabel, reason, status, curfew)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(view, params)
            windowManager = wm
            root = view
        } catch (_: Throwable) {
            root = null
            windowManager = null
        }
    }

    private fun hideInternal() {
        val view = root ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Throwable) {
        }
        root = null
        windowManager = null
        reasonView = null
        statusView = null
    }

    private fun buildView(
        context: Context,
        appLabel: String,
        reason: String,
        status: String,
        curfew: String
    ): View {
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#F705070D"))
            setPadding(dp(context, 28), dp(context, 48), dp(context, 28), dp(context, 32))
        }

        rootLayout.addView(text(context, "SYSTEM LOCK // 软锁", 11f, "#FF4D5E", mono = true, bold = true))
        rootLayout.addView(spacer(context, 18))

        rootLayout.addView(text(context, "系统锁定", 30f, "#E8F1FF", bold = true))
        rootLayout.addView(spacer(context, 10))
        rootLayout.addView(text(context, appLabel, 14f, "#7C8CA6"))
        rootLayout.addView(spacer(context, 28))

        val statusViewLocal = text(context, status, 20f, "#38F2E8", mono = true, bold = true)
        rootLayout.addView(statusViewLocal)
        statusView = statusViewLocal
        rootLayout.addView(spacer(context, 18))

        val reasonViewLocal = text(context, reason, 14f, "#E8F1FF")
        reasonViewLocal.gravity = Gravity.CENTER
        rootLayout.addView(reasonViewLocal)
        reasonView = reasonViewLocal

        rootLayout.addView(spacer(context, 8))
        rootLayout.addView(text(context, "宵禁 " + curfew + " · 娱乐额度由呆每天分配", 11f, "#7C8CA6"))
        rootLayout.addView(spacer(context, 32))

        val divider = View(context).apply { setBackgroundColor(Color.parseColor("#1E3A5F")) }
        rootLayout.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)))
        rootLayout.addView(spacer(context, 24))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val extend = button(context, "申请延时", "#38F2E8", "#05070D", filled = true)
        extend.setOnClickListener {
            hideInternal()
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("open_tab", "panel")
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
            }
        }
        val home = button(context, "返回桌面", "#E8F1FF", "#05070D", filled = false)
        home.setOnClickListener {
            hideInternal()
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
            }
        }
        buttons.addView(extend, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
            rightMargin = dp(context, 10)
        })
        buttons.addView(home, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
            leftMargin = dp(context, 10)
        })
        rootLayout.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return rootLayout
    }

    private fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: String,
        mono: Boolean = false,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(Color.parseColor(color))
        textSize = sizeSp
        gravity = Gravity.CENTER
        if (mono) typeface = Typeface.MONOSPACE
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacer(context: Context, heightDp: Int): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context, heightDp)) }

    private fun button(
        context: Context,
        label: String,
        bg: String,
        fg: String,
        filled: Boolean
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.parseColor(fg))
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 4).toFloat()
            if (filled) {
                setColor(Color.parseColor(bg))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1), Color.parseColor("#1E3A5F"))
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

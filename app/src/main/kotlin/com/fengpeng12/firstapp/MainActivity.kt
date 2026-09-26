package com.fengpeng12.firstapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    // 计数状态
    private var count = 0
    private lateinit var countText: TextView
    private lateinit var stateText: TextView
    private lateinit var root: LinearLayout
    private lateinit var minusBtn: TextView
    private lateinit var plusBtn: TextView
    private lateinit var themeBtn: TextView
    private lateinit var resetBtn: TextView

    // 主题索引
    private var themeIndex = 0

    // 长按连发
    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var repeatFired = false

    // 主题配色：名称 / 渐变起色 / 渐变止色 / 强调色
    private val themes = listOf(
        Theme("幻紫", "#6A11CB", "#2575FC", "#6A11CB"),
        Theme("晚霞", "#FF512F", "#DD2476", "#DD2476"),
        Theme("青柠", "#11998E", "#38EF7D", "#11998E"),
        Theme("蜜桃", "#F7971E", "#FFD200", "#E08900"),
        Theme("深海", "#1A2980", "#26D0CE", "#1A2980")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 去掉顶部系统标题栏，让渐变背景直接铺到顶部，与下方界面保持一致
        actionBar?.hide()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(40), dp(28), dp(40))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(12).toFloat()
        }

        // 标题
        card.addView(TextView(this).apply {
            text = "我的第一个APP"
            setTextColor(Color.parseColor("#1A1A2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        // 副标题
        card.addView(TextView(this).apply {
            text = "长按按钮可连续增减"
            setTextColor(Color.parseColor("#8A8A9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        })

        // 计数显示
        countText = TextView(this).apply {
            text = "0"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(countText)

        // 状态提示
        stateText = TextView(this).apply {
            text = "从零开始"
            setTextColor(Color.parseColor("#8A8A9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(28))
        }
        card.addView(stateText)

        // 按钮行
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        minusBtn = makeButton("−") { changeCount(-1) }
        plusBtn = makeButton("+") { changeCount(1) }
        buttonRow.addView(minusBtn)
        buttonRow.addView(plusBtn)
        card.addView(buttonRow)

        // 重置按钮
        resetBtn = TextView(this).apply {
            text = "重置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animateTap(it)
                count = 0
                refresh()
            }
        }
        card.addView(resetBtn)

        // 主题切换按钮
        themeBtn = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                animateTap(it)
                themeIndex = (themeIndex + 1) % themes.size
                applyTheme()
            }
        }

        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            themeBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        )

        setContentView(root)
        applyTheme()
    }

    // 应用当前主题配色
    private fun applyTheme() {
        val t = themes[themeIndex]
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(t.start), Color.parseColor(t.end))
        )

        val accent = Color.parseColor(t.accent)
        countText.setTextColor(accent)
        plusBtn.background = circle(accent)
        resetBtn.setTextColor(accent)

        themeBtn.text = "🎨 主题：${t.name}"
        themeBtn.setTextColor(accent)
        themeBtn.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(2), accent)
        }

        // 顶部已无标题栏，渐变背景直接铺到顶部；状态栏染成主题起始色，上下连成一体
        window.statusBarColor = Color.parseColor(t.start)
    }

    // 计数增减并刷新
    private fun changeCount(delta: Int) {
        count += delta
        refresh()
    }

    // 刷新数字与状态提示，并播放弹性动画
    private fun refresh() {
        countText.text = count.toString()
        stateText.text = when {
            count > 0 -> "正在累加中"
            count < 0 -> "正在递减中"
            else -> "已归零"
        }

        countText.animate().cancel()
        countText.scaleX = 0.7f
        countText.scaleY = 0.7f
        countText.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // 生成圆形按钮：短按单次，长按连发
    private fun makeButton(label: String, action: () -> Unit): TextView {
        val params = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = params
            background = circle(Color.WHITE)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                        startRepeat(v, action)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        // 若长按未触发连发，则按一次单次点击处理
                        if (!stopRepeat()) {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            action()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        stopRepeat()
                    }
                }
                true
            }
        }
    }

    // 开始长按连发：首次 400ms 后触发，随后每 120ms 一次
    private fun startRepeat(view: View, action: () -> Unit) {
        stopRepeat()
        repeatFired = false
        val runnable = object : Runnable {
            override fun run() {
                repeatFired = true
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
                handler.postDelayed(this, 120)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, 400)
    }

    // 停止连发，返回「期间是否已触发过连发」
    private fun stopRepeat(): Boolean {
        val fired = repeatFired
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
        repeatFired = false
        return fired
    }

    // 生成纯色圆形背景
    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // 点击时轻微缩放反馈
    private fun animateTap(view: View) {
        view.animate().cancel()
        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
            }
            .start()
    }

    // dp 转 px
    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRepeat()
    }

    // 主题数据类
    data class Theme(val name: String, val start: String, val end: String, val accent: String)
}

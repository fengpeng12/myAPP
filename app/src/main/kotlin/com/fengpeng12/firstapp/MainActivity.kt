package com.fengpeng12.firstapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

    private var count = 0
    private lateinit var countText: TextView
    private lateinit var stateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根布局：紫色渐变背景，内容居中
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#6A11CB"), Color.parseColor("#2575FC"))
            )
        }

        // 白色圆角卡片
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
            text = "点击下方按钮试试"
            setTextColor(Color.parseColor("#8A8A9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        })

        // 计数显示
        countText = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor("#6A11CB"))
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
        buttonRow.addView(makeButton("−", "#FF6B6B") { changeCount(-1) })
        buttonRow.addView(makeButton("+", "#6A11CB") { changeCount(1) })
        card.addView(buttonRow)

        // 重置按钮
        card.addView(TextView(this).apply {
            text = "重置"
            setTextColor(Color.parseColor("#2575FC"))
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
        })

        root.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
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

        // 数字弹跳：先放大再回弹
        countText.animate().cancel()
        countText.scaleX = 0.7f
        countText.scaleY = 0.7f
        countText.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // 生成带按压反馈的圆形按钮
    private fun makeButton(label: String, colorHex: String, onClick: () -> Unit): TextView {
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
            layoutParams = params

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f)
                        .setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                        .scaleX(1f).scaleY(1f).setDuration(90).start()
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
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
}

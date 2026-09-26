package com.fengpeng12.firstapp

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    // 计数状态
    private var count = 0
    private lateinit var countText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 根布局：紫色渐变背景，内容居中
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#6A11CB"), Color.parseColor("#2575FC"))
            )
        }

        // 白色圆角卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(36), dp(28), dp(36))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.WHITE)
            }
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
            setPadding(0, dp(8), 0, dp(24))
        })

        // 计数显示
        countText = TextView(this).apply {
            text = "0"
            setTextColor(Color.parseColor("#6A11CB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }
        card.addView(countText)

        // 按钮行
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttonRow.addView(makeButton("−", "#FF6B6B") {
            count--
            refresh()
        })
        buttonRow.addView(makeButton("+", "#6A11CB") {
            count++
            refresh()
        })
        card.addView(buttonRow)

        // 重置按钮
        card.addView(TextView(this).apply {
            text = "重置"
            setTextColor(Color.parseColor("#8A8A9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
            setOnClickListener {
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

    // 刷新计数显示
    private fun refresh() {
        countText.text = count.toString()
    }

    // 生成一个圆角按钮
    private fun makeButton(label: String, colorHex: String, onClick: () -> Unit): TextView {
        val params = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                // 轻微立体感
                setStroke(dp(1), Color.parseColor("#20000000"))
            }
            setOnClickListener { onClick() }
            layoutParams = params
        }
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

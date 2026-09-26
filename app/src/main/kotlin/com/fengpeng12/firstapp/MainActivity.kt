package com.fengpeng12.firstapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "你好!"
            textSize = 32f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        setContentView(tv)
    }
}

package com.example.tasks

import android.content.Context
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

/** 一条待办任务 */
data class Task(var title: String, var done: Boolean)

class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnClearDone: MaterialButton
    private val tasks = mutableListOf<Task>()

    private val prefs by lazy { getSharedPreferences("tasks_store", Context.MODE_PRIVATE) }

    /** 基础内边距，与系统栏 inset 叠加 */
    private val basePadding by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.inputTask)
        listContainer = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnClearDone = findViewById(R.id.btnClearDone)

        applyWindowInsets(findViewById(R.id.root))

        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { addTask() }
        btnClearDone.setOnClickListener { clearDone() }
        input.setOnEditorActionListener { _, _, _ ->
            addTask()
            true
        }

        load()
        refresh()
    }

    /** 开启全屏沉浸式：内容延伸到系统栏之下，状态栏图标用深色 */
    private fun enableEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    /** 给根布局加上系统栏内边距，避免内容被状态栏/导航栏遮挡 */
    private fun applyWindowInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** 添加新任务 */
    private fun addTask() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入任务内容", Toast.LENGTH_SHORT).show()
            return
        }
        tasks.add(Task(text, false))
        input.setText("")
        save()
        refresh()
    }

    /** 删除任务 */
    private fun removeTask(task: Task) {
        tasks.remove(task)
        save()
        refresh()
    }

    /** 清除全部已完成的任务 */
    private fun clearDone() {
        val done = tasks.count { it.done }
        if (done == 0) {
            Toast.makeText(this, getString(R.string.no_done), Toast.LENGTH_SHORT).show()
            return
        }
        tasks.removeAll { it.done }
        save()
        refresh()
        Toast.makeText(
            this,
            "${getString(R.string.cleared_prefix)} $done ${getString(R.string.cleared_suffix)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 重新渲染列表与统计 */
    private fun refresh() {
        listContainer.removeAllViews()
        tvEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        tasks.forEach { task ->
            listContainer.addView(buildRow(task))
        }
        val doneCount = tasks.count { it.done }
        btnClearDone.visibility = if (doneCount > 0) View.VISIBLE else View.GONE
        tvSubtitle.text = if (tasks.isEmpty()) {
            getString(R.string.subtitle_empty)
        } else {
            "共 ${tasks.size} 项 · 已完成 $doneCount"
        }
    }

    /** 由 item_task 布局构建一行 */
    private fun buildRow(task: Task): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_task, listContainer, false)
        val card = row.findViewById<View>(R.id.cardRow)
        val cb = row.findViewById<CheckBox>(R.id.cbDone)
        val title = row.findViewById<TextView>(R.id.tvTitle)
        val del = row.findViewById<MaterialButton>(R.id.btnDel)

        bindRow(cb, title, task)

        card.setOnClickListener {
            task.done = !task.done
            save()
            bindRow(cb, title, task)
            refreshSubtitle()
        }
        del.setOnClickListener { removeTask(task) }
        return row
    }

    /** 根据完成状态刷新勾选与文字样式 */
    private fun bindRow(cb: CheckBox, title: TextView, task: Task) {
        cb.isChecked = task.done
        title.text = task.title
        if (task.done) {
            title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            title.setTextColor(getColor(R.color.done_text))
        } else {
            title.paintFlags = title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            title.setTextColor(getColor(R.color.text_primary))
        }
    }

    /** 仅刷新顶部统计文字 */
    private fun refreshSubtitle() {
        val doneCount = tasks.count { it.done }
        tvSubtitle.text = "共 ${tasks.size} 项 · 已完成 $doneCount"
    }

    /** 存储格式：done|标题，一行一条 */
    private fun save() {
        val sb = StringBuilder()
        tasks.forEach { t ->
            sb.append(if (t.done) "1" else "0").append("|").append(t.title).append("\n")
        }
        prefs.edit().putString("data", sb.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("data", "") ?: ""
        tasks.clear()
        raw.lines().filter { it.contains("|") }.forEach { line ->
            val idx = line.indexOf("|")
            val done = line.substring(0, idx) == "1"
            val title = line.substring(idx + 1)
            if (title.isNotEmpty()) tasks.add(Task(title, done))
        }
    }
}

package com.example.tasks

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 一条待办任务 */
data class Task(var title: String, var done: Boolean)

class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private val tasks = mutableListOf<Task>()

    private val prefs by lazy { getSharedPreferences("tasks_store", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.inputTask)
        listContainer = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        val btnAdd = findViewById<Button>(R.id.btnAdd)

        btnAdd.setOnClickListener { addTask() }

        load()
        refresh()
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

    /** 重新渲染列表 */
    private fun refresh() {
        listContainer.removeAllViews()
        tvEmpty.visibility = if (tasks.isEmpty()) TextView.VISIBLE else TextView.GONE
        tasks.forEach { task ->
            listContainer.addView(buildRow(task))
        }
    }

    /** 构建一行的视图 */
    private fun buildRow(task: Task): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 12, 8, 12)
        }

        val check = CheckBox(this).apply {
            isChecked = task.done
            setOnCheckedChangeListener { _, checked ->
                task.done = checked
                save()
            }
        }
        row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val title = TextView(this).apply {
            text = task.title
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f)
        }
        row.addView(title)

        val del = Button(this).apply {
            text = getString(R.string.delete)
            setOnClickListener { removeTask(task) }
        }
        row.addView(del)

        return row
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

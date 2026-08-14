package io.github.fairyxh.VirtualEnv.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.fairyxh.VirtualEnv.R
import io.github.fairyxh.VirtualEnv.util.LogStore
import java.io.File

/**
 * 崩溃报告弹窗。
 *
 * 由 [io.github.fairyxh.VirtualEnv.util.CrashCatcher] 启动，展示崩溃栈 + 最近日志，
 * 提供“复制”“导出为文件”“关闭”三个动作。导出优先走 SAF / MediaStore，
 * 无需存储权限；未由 crash 场景启动时（如设置页手动查看）也能使用。
 */
class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_CRASH_FILE = "crash_file"
        const val EXTRA_TITLE = "title"
    }

    private var crashFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        crashFile = intent.getStringExtra(EXTRA_CRASH_FILE)?.let { File(it) }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.crash_report_title)

        val content = buildContent()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(Color.rgb(18, 18, 20))
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        root.addView(titleView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = getString(R.string.crash_report_hint)
            textSize = 13f
            setTextColor(Color.rgb(180, 180, 185))
        }
        root.addView(hint,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            })

        val contentView = TextView(this).apply {
            text = content
            textSize = 12f
            setTextColor(Color.rgb(230, 230, 235))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(contentView) }
        root.addView(scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = 16
            })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun button(label: String): Button = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
        }
        val copyBtn = button(getString(R.string.crash_report_copy))
        val exportBtn = button(getString(R.string.crash_report_export))
        val closeBtn = button(getString(R.string.crash_report_close))
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash", content))
            Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
        }
        exportBtn.setOnClickListener { exportAll() }
        closeBtn.setOnClickListener { finish() }
        row.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(exportBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 })
        row.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 })
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 16
        })

        setContentView(root)
    }

    private fun buildContent(): String {
        val sb = StringBuilder()
        crashFile?.let { f ->
            if (f.exists()) {
                try {
                    sb.append("== 崩溃文件：").append(f.name).append(" ==").append('\n')
                    sb.append(f.readText()).append('\n')
                    return sb.toString()
                } catch (_: Throwable) {
                }
            }
        }
        sb.append(LogStore.exportText())
        return sb.toString()
    }

    /** 导出崩溃文件 + 运行日志到 MediaStore Downloads（无需存储权限）。 */
    private fun exportAll() {
        try {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val name = "zhang-virtual-env_$stamp.txt"
            val text = buildContent()
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ZhangVirtualEnv")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    Toast.makeText(this, R.string.crash_report_exported, Toast.LENGTH_SHORT).show()
                    return
                }
            }
            // 低版本 / 失败回退：私有 files 目录导出
            val dir = File(filesDir, "export").apply { mkdirs() }
            val out = File(dir, name)
            out.writeText(text)
            Toast.makeText(this, getString(R.string.crash_report_exported_fallback, out.absolutePath), Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, getString(R.string.crash_report_export_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}

package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.R
import com.proxybrowser.app.core.Settings

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this

        // 顶部栏
        val back = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(R.drawable.bg_ripple)
            val p = (10 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            val s = dp(36)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { finish() }
        }
        val title = TextView(ctx).apply {
            text = "设置"
            textSize = 18f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 10, 16, 10)
            addView(back)
            addView(title)
        }

        val scroll = ScrollView(ctx)
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 16)
        }
        scroll.addView(list)

        // 通用设置（搜索引擎）
        list.addView(navRow("通用设置") {
            val engines = Settings.SEARCH_ENGINES
            val names = engines.map { it.first }.toTypedArray()
            var checked = engines.indexOfFirst { it.second == Settings.searchEngine(ctx) }.coerceAtLeast(0)
            AlertDialog.Builder(ctx)
                .setTitle("默认搜索引擎")
                .setSingleChoiceItems(names, checked) { d, which -> checked = which }
                .setPositiveButton("确定") { d, _ ->
                    Settings.setSearchEngine(ctx, engines[checked].second)
                    Toast.makeText(ctx, "已设为：${engines[checked].first}", Toast.LENGTH_SHORT).show()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        })

        // 代理设置 -> ProxySettingsActivity
        list.addView(navRow("代理设置") {
            startActivity(Intent(ctx, ProxySettingsActivity::class.java))
        })

        // 广告拦截（开关）
        list.addView(toggleRow("广告拦截", Settings.isAdBlockEnabled(ctx)) { _, on ->
            Settings.setAdBlockEnabled(ctx, on)
        })
        // 用户脚本（开关）
        list.addView(toggleRow("用户脚本", Settings.isUserScript(ctx)) { _, on ->
            Settings.setUserScript(ctx, on)
            Toast.makeText(ctx, if (on) "用户脚本已开启（需重启页面生效）" else "用户脚本已关闭", Toast.LENGTH_SHORT).show()
        })
        // 视频嗅探（开关）
        list.addView(toggleRow("视频嗅探", Settings.isSniffer(ctx)) { _, on ->
            Settings.setSniffer(ctx, on)
        })
        // 书签管理（占位）
        list.addView(navRow("书签管理") { Toast.makeText(ctx, "书签管理：敬请期待", Toast.LENGTH_SHORT).show() })
        // 下载管理（占位）
        list.addView(navRow("下载管理") { Toast.makeText(ctx, "下载管理：敬请期待", Toast.LENGTH_SHORT).show() })
        // 隐私（DNT 开关）
        list.addView(toggleRow("隐私（禁止追踪 DNT）", Settings.isDnt(ctx)) { _, on ->
            Settings.setDnt(ctx, on)
        })
        // 数据管理（占位）
        list.addView(navRow("数据管理") { Toast.makeText(ctx, "数据管理：敬请期待", Toast.LENGTH_SHORT).show() })

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            addView(topBar)
            addView(View(ctx).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(scroll)
        }
        setContentView(root)
    }

    private fun navRow(name: String, onClick: () -> Unit): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 18, 16, 18)
            setBackgroundResource(R.drawable.bg_ripple)
        }
        val tv = TextView(ctx).apply {
            text = name
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chevron = TextView(ctx).apply {
            text = "›"
            textSize = 22f
            setTextColor(getColor(R.color.text_secondary))
        }
        row.addView(tv)
        row.addView(chevron)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun toggleRow(name: String, initial: Boolean, onChange: (CompoundButton, Boolean) -> Unit): View {
        val ctx = this
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 18, 16, 18)
        }
        val tv = TextView(ctx).apply {
            text = name
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx).apply {
            isChecked = initial
            setOnCheckedChangeListener(onChange)
        }
        row.addView(tv)
        row.addView(sw)
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

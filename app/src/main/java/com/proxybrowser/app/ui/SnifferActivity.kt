package com.proxybrowser.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.proxybrowser.app.R
import com.proxybrowser.app.core.VideoSniffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频嗅探结果页（夸克风格卡片流）。
 * 支持单条复制 / 用系统播放器打开 / 删除，以及整体清空。
 */
class SnifferActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var emptyBlock: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        VideoSniffer.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@SnifferActivity, R.color.bg))
        }
        root.addView(Ui.topBar(this, "视频嗅探", Ui.ghostButton(this, "清空") { confirmClear() }))
        root.addView(TextView(this).apply {
            text = "浏览含视频的页面后，这里会列出可播放/可下载的媒体地址"
            textSize = 11.5f
            setTextColor(Ui.color(this@SnifferActivity, R.color.text_tertiary))
            setPadding(
                Ui.dp(this@SnifferActivity, 20), 0,
                Ui.dp(this@SnifferActivity, 20), Ui.dp(this@SnifferActivity, 10)
            )
        })

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@SnifferActivity, 14), 0,
                Ui.dp(this@SnifferActivity, 14), Ui.dp(this@SnifferActivity, 28)
            )
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyBlock = buildEmpty()
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildEmpty(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            Ui.dp(this@SnifferActivity, 30), Ui.dp(this@SnifferActivity, 80),
            Ui.dp(this@SnifferActivity, 30), 0
        )
        addView(TextView(this@SnifferActivity).apply {
            text = "🎬"
            textSize = 36f
            gravity = Gravity.CENTER
        })
        addView(TextView(this@SnifferActivity).apply {
            text = "还没有嗅探到视频"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.color(this@SnifferActivity, R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@SnifferActivity, 12), 0, 0)
        })
        addView(TextView(this@SnifferActivity).apply {
            text = "确认「设置 → 视频嗅探」已开启，\n然后打开视频页面并点击播放"
            textSize = 12.5f
            setTextColor(Ui.color(this@SnifferActivity, R.color.text_secondary))
            gravity = Gravity.CENTER
            setLineSpacing(Ui.dp(this@SnifferActivity, 4).toFloat(), 1f)
            setPadding(0, Ui.dp(this@SnifferActivity, 8), 0, 0)
        })
    }

    private fun refresh() {
        list.removeAllViews()
        val items = VideoSniffer.getAll(this)
        if (items.isEmpty()) {
            list.addView(emptyBlock)
            return
        }
        items.forEach { list.addView(itemCard(it)) }
    }

    private fun itemCard(m: VideoSniffer.Media): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = Ui.dp(ctx, 18).toFloat()
                setColor(Ui.color(ctx, R.color.bg_soft))
            }
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 13), Ui.dp(ctx, 14), Ui.dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 10) }
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.pillTag(ctx, m.ext.removePrefix(".").uppercase(), Ui.color(ctx, R.color.accent)))
        head.addView(TextView(ctx).apply {
            text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.time))
            textSize = 11f
            setTextColor(Ui.color(ctx, R.color.text_tertiary))
            setPadding(Ui.dp(ctx, 8), 0, 0, 0)
        })
        card.addView(head)

        card.addView(TextView(ctx).apply {
            text = m.title.ifEmpty { guessName(m.url) }
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Ui.color(ctx, R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.dp(ctx, 7), 0, 0)
        })
        card.addView(TextView(ctx).apply {
            text = m.url
            textSize = 11f
            setTextColor(Ui.color(ctx, R.color.text_tertiary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, Ui.dp(ctx, 4), 0, 0)
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, Ui.dp(ctx, 10), 0, 0)
        }
        actions.addView(Ui.ghostButton(ctx, "复制") { copy(m.url) })
        actions.addView(Ui.ghostButton(ctx, "播放") { openWith(m.url) }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(ctx, 8) }
        })
        actions.addView(Ui.ghostButton(ctx, "删除") {
            VideoSniffer.remove(ctx, m.url)
            refresh()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(ctx, 8) }
        })
        card.addView(actions)
        return card
    }

    private fun guessName(url: String): String {
        val path = url.substringBefore('?')
        return path.substringAfterLast('/').ifEmpty { url }
    }

    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("video", s))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun openWith(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            copy(url)
            Toast.makeText(this, "未找到可用的播放器，链接已复制", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        if (VideoSniffer.getAll(this).isEmpty()) {
            Toast.makeText(this, "列表已经是空的", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.Theme_PB_Dialog)
            .setTitle("清空嗅探记录")
            .setMessage("只清空这个列表，不影响已下载的文件。")
            .setPositiveButton("清空") { _, _ ->
                VideoSniffer.clear(this)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

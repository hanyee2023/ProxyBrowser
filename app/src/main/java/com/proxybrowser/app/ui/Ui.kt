package com.proxybrowser.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.proxybrowser.app.R

/**
 * 夸克风格的 UI 积木。
 * 所有页面共用同一套圆角/配色/间距，风格才不会散。
 */
object Ui {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun color(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 18).toFloat()
            setColor(color(ctx, R.color.bg_soft))
        }
        setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
    }

    fun title(ctx: Context, text: String, size: Float = 16f): TextView = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color(ctx, R.color.text_primary))
        setTypeface(null, Typeface.BOLD)
    }

    fun body(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(color(ctx, R.color.text_primary))
    }

    fun caption(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(color(ctx, R.color.text_secondary))
    }

    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.06f
        setTextColor(color(ctx, R.color.text_tertiary))
        setPadding(dp(ctx, 8), dp(ctx, 18), dp(ctx, 8), dp(ctx, 8))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(color(ctx, R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
    }

    /**
     * 圆形图标按钮。最后一个参数是 onClick，避免 Kotlin 尾随 lambda 落到 tint 上。
     */
    fun iconButton(
        ctx: Context,
        res: Int,
        sizeDp: Int = 40,
        tint: Int? = null,
        onClick: (() -> Unit)? = null
    ): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_ripple_pill)
        val p = dp(ctx, sizeDp / 4)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = ColorStateList.valueOf(tint ?: color(ctx, R.color.icon))
        if (onClick != null) setOnClickListener { onClick() }
    }

    fun primaryButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(color(ctx, R.color.text_on_accent))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 999).toFloat()
                colors = intArrayOf(color(ctx, R.color.grad_start), color(ctx, R.color.grad_end))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(dp(ctx, 18), dp(ctx, 11), dp(ctx, 18), dp(ctx, 11))
            setOnClickListener { onClick() }
        }

    fun ghostButton(ctx: Context, text: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(ctx, R.color.text_primary))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 999).toFloat()
                setColor(color(ctx, R.color.surface_sunken))
            }
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            setOnClickListener { onClick() }
        }

    fun pillTag(ctx: Context, text: String, tint: Int): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(tint)
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 999).toFloat()
            setColor((tint and 0x00FFFFFF) or 0x1F000000)
        }
        setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
    }

    fun input(ctx: Context, hint: String, multiLine: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(color(ctx, R.color.text_primary))
        setHintTextColor(color(ctx, R.color.text_tertiary))
        setSingleLine(!multiLine)
        if (multiLine) minLines = 3
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, if (multiLine) 14 else 999).toFloat()
            setColor(color(ctx, R.color.surface_sunken))
            setStroke(dp(ctx, 1), color(ctx, R.color.divider))
        }
        setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
    }

    fun topBar(ctx: Context, title: String, trailing: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 10), dp(ctx, 12), dp(ctx, 8))
            addView(iconButton(ctx, R.drawable.ic_back, onClick = {
                (ctx as? android.app.Activity)?.finish()
            }))
            addView(Ui.title(ctx, title, 17f).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(ctx, 6), 0, 0, 0)
            })
            if (trailing != null) addView(trailing)
        }
}

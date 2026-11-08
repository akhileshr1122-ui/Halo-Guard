package com.haloapps.guard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared look & feel + tiny view helpers for every screen (no XML layouts, no libraries). */
open class BaseActivity : Activity() {

    protected val bgColor = Color.parseColor("#0E1116")
    protected val cardColor = Color.parseColor("#171B22")
    protected val chipColor = Color.parseColor("#232935")
    protected val textColor = Color.parseColor("#E8EAED")
    protected val subColor = Color.parseColor("#9AA0A6")
    protected val red = Color.parseColor("#FF5A5F")
    protected val amber = Color.parseColor("#FFB020")
    protected val green = Color.parseColor("#3DDC84")
    protected val accent = Color.parseColor("#7AA2FF")

    protected val prefs by lazy { getSharedPreferences("guard", MODE_PRIVATE) }

    /** Builds the scrolling page with title + the 3-tab nav. Returns the column to add content to. */
    @Suppress("DEPRECATION")
    protected fun setupScreen(title: String, subtitle: String, tab: Int): LinearLayout {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        val content = vbox()
        content.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        setContentView(scroll)

        val nav = hbox()
        val tabs = listOf<Pair<String, Class<*>>>(
            "Apps" to MainActivity::class.java,
            "Photo trail" to TrailActivity::class.java,
            "Photo lock" to LockActivity::class.java
        )
        tabs.forEachIndexed { i, (name, cls) ->
            nav.addView(chip(name, i == tab) {
                if (i != tab) {
                    startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    overridePendingTransition(0, 0)
                }
            })
        }
        val navScroll = HorizontalScrollView(this)
        navScroll.isHorizontalScrollBarEnabled = false
        navScroll.addView(nav)
        content.addView(navScroll)

        content.addView(text(title, 26f, textColor, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        content.addView(text(subtitle, 14f, subColor).apply { setPadding(0, dp(4), 0, dp(12)) })
        return content
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    protected fun hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    protected fun space(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }

    protected fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    protected fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    protected fun sectionLabel(s: String): TextView =
        text(s.uppercase(), 11f, subColor, bold = true).apply { setPadding(0, dp(14), 0, dp(6)) }

    protected fun infoLine(s: String, color: Int): TextView =
        text(s, 13f, color).apply { setPadding(0, dp(6), 0, 0) }

    protected fun card(): LinearLayout = vbox().apply {
        background = rounded(cardColor, 16)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    protected fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(label, 13f, if (selected) bgColor else textColor, bold = selected).apply {
            background = rounded(if (selected) accent else chipColor, 18)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    protected fun button(label: String, color: Int, onClick: () -> Unit): TextView =
        text(label, 13f, color, bold = true).apply {
            background = rounded(chipColor, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    protected fun chipRow(): Pair<HorizontalScrollView, LinearLayout> {
        val row = hbox()
        val sc = HorizontalScrollView(this)
        sc.isHorizontalScrollBarEnabled = false
        sc.addView(row)
        return sc to row
    }

    protected fun safeStart(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            // nothing to open
        }
    }

    protected fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}

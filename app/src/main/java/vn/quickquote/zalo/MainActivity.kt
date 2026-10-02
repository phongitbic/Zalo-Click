package vn.quickquote.zalo

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Màn hình cài đặt. Giao diện dựng bằng code, tự co giãn theo màn hình:
 *  - < 600dp: 1 cột, lề 16dp.
 *  - >= 600dp: lề 24dp, nội dung giới hạn 640dp và căn giữa.
 *  - >= 720dp (tablet, điện thoại xoay ngang): 2 cột, tối đa 1040dp.
 * Tự đổi màu theo chế độ Sáng/Tối của hệ thống.
 */
class MainActivity : Activity() {

    companion object {
        private const val MIN_SIZE = 36
        private const val MAX_SIZE = 88
        private val SIZE_PRESETS = listOf("Nhỏ" to 44, "Vừa" to 56, "Lớn" to 72)

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val RED = Color.parseColor("#E5484D")
        private val DOT_OK = Color.parseColor("#4ADE80")
        private val DOT_WARN = Color.parseColor("#FCD34D")
        private val DOT_OFF = Color.parseColor("#FF9B9B")
    }

    /** Bảng màu theo chế độ Sáng/Tối. */
    private class Palette(dark: Boolean) {
        val bg = c(if (dark) "#0F1115" else "#F2F4F8")
        val card = c(if (dark) "#1A1D23" else "#FFFFFF")
        val text = c(if (dark) "#ECEEF2" else "#16191F")
        val sub = c(if (dark) "#9AA3B2" else "#6B7280")
        val line = c(if (dark) "#2A2F38" else "#E5E7EB")
        val soft = c(if (dark) "#172742" else "#EAF2FF")
        val accent = c(if (dark) "#4C9AFF" else "#0068FF")
        val muted = c(if (dark) "#4A515E" else "#C4C9D2")
        val thumbOff = c(if (dark) "#9AA3B2" else "#FAFAFA")
        val heroTop = c(if (dark) "#0F4FC4" else "#1E6FFF")
        val heroBottom = c(if (dark) "#0B2F7A" else "#0050D8")

        private fun c(s: String) = Color.parseColor(s)
    }

    private class Card(val root: LinearLayout, val body: LinearLayout, val heading: TextView)

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private var dark = false
    private var twoPane = false
    private var contentDp = 360   // bề rộng vùng nội dung
    private var paneDp = 360      // bề rộng một cột

    private lateinit var statusChip: TextView
    private lateinit var permissionBtn: TextView
    private lateinit var batteryBtn: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var activePreview: TextView
    private lateinit var templateBox: LinearLayout
    private lateinit var templateHeading: TextView
    private lateinit var sizeValue: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var preview: BubbleView
    private lateinit var reportInfo: TextView
    private val presetChips = mutableListOf<Pair<Int, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        p = Palette(dark)

        // Bố cục theo bề rộng màn hình hiện tại (xoay máy / chia đôi màn hình sẽ dựng lại Activity)
        val screenDp = resources.configuration.screenWidthDp.takeIf { it > 0 } ?: 360
        val gutter = if (screenDp >= 600) 24 else 16
        twoPane = screenDp >= 720
        contentDp = minOf(screenDp - gutter * 2, if (twoPane) 1040 else 640)
        paneDp = if (twoPane) (contentDp - 16) / 2 else contentDp
        setupSystemBars()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        page.addView(buildHero())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(contentDp), WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = -dp(24)
            }
        }
        if (twoPane) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = column().apply {
                addView(buildBubbleCard(first = true))
                addView(buildGuideCard())
            }
            val right = column().apply {
                addView(buildTemplateCard(first = true))
                addView(buildDiagCard())
            }
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            row.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
            content.addView(row)
        } else {
            content.addView(buildBubbleCard(first = true))
            content.addView(buildTemplateCard())
            content.addView(buildGuideCard())
            content.addView(buildDiagCard())
        }
        content.addView(buildFooter())
        page.addView(content)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshReportInfo()
        // Khi mở app lúc tiến trình vừa bị dọn, hệ thống cần một nhịp để nối lại dịch vụ Trợ năng.
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ refreshStatus() }, 1200)
    }

    override fun onPause() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun setupSystemBars() {
        window.statusBarColor = p.heroTop
        window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    // ================================================================ hero

    private fun buildHero(): View {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.heroTop, p.heroBottom)
            )
            setPadding(0, dp(20), 0, dp(44))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(contentDp), WRAP)
        }

        // Logo + tên app
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(BubbleView(this), LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        names.addView(TextView(this).apply {
            text = "Quote & trả lời nhanh trên Zalo"
            textSize = 13f
            setTextColor(0xD9FFFFFF.toInt())
        })
        brand.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        inner.addView(brand)

        // Trạng thái
        statusChip = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = rounded(0x2EFFFFFF, 999f)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) }
        }
        inner.addView(statusChip)

        // Mẫu đang dùng
        val activeBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0x1FFFFFFF, 14f).apply { setStroke(dp(1), 0x33FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        activeBox.addView(TextView(this).apply {
            text = "MẪU ĐANG DÙNG"
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xB3FFFFFF.toInt())
        })
        activePreview = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        activeBox.addView(activePreview)
        inner.addView(activeBox)

        permissionBtn = button("Bật quyền Trợ năng để bắt đầu", style = BtnStyle.ON_DARK) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
        }
        inner.addView(permissionBtn)

        batteryBtn = button("Cho phép chạy nền (tránh nút nổi tự mất)", style = BtnStyle.ON_DARK) {
            openBatterySettings()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        inner.addView(batteryBtn)

        hero.addView(inner)
        refreshActivePreview()
        return hero
    }

    private fun refreshStatus() {
        val inSettings = isServiceEnabled()
        // Cài đặt có thể vẫn ghi "Bật" trong khi hệ thống đã dừng dịch vụ (dọn RAM, tối ưu pin,
        // cài đè bản mới). Lúc đó bật/tắt nút nổi không có tác dụng -> phải báo rõ cho người dùng.
        val running = ZaloQuoteService.running
        permissionBtn.visibility = if (inSettings && running) View.GONE else View.VISIBLE
        permissionBtn.text = if (inSettings && !running)
            "Trợ năng bị dừng – bấm để TẮT rồi BẬT lại Zcar Click"
        else
            "Bật quyền Trợ năng để bắt đầu"
        batteryBtn.visibility = if (ignoringBatteryOpt()) View.GONE else View.VISIBLE
        val (color, label) = when {
            !inSettings -> DOT_OFF to "Chưa bật quyền Trợ năng"
            !running -> DOT_OFF to "Dịch vụ bị hệ thống dừng – nút nổi sẽ không hiện"
            !prefs.enabled -> DOT_WARN to "Nút nổi đang tắt"
            else -> DOT_OK to "Sẵn sàng hoạt động"
        }
        statusChip.text = SpannableString("●  $label").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun refreshActivePreview() {
        activePreview.text = prefs.activeTemplate ?: "Chưa có mẫu. Thêm mẫu ở mục Tin nhắn mẫu."
    }

    // ================================================================ sections

    private fun buildBubbleCard(first: Boolean = false): View {
        val c = card("Nút nổi", "Hiển thị và kích thước nút bấm nhanh", first)
        c.body.addView(switchRow("Hiện nút nổi", "Bật/tắt nhanh ở ô Cài đặt nhanh trên thanh thông báo", prefs.enabled) {
            prefs.enabled = it
            refreshStatus()
            if (it && !ZaloQuoteService.running) {
                Toast.makeText(
                    this,
                    "Dịch vụ Trợ năng đang bị dừng nên nút nổi chưa hiện. Hãy tắt rồi bật lại Zcar Click trong Trợ năng.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        c.body.addView(divider())
        c.body.addView(switchRow("Chỉ hiện khi mở Zalo", "Tự ẩn khi bạn chuyển sang ứng dụng khác", prefs.onlyInZalo) {
            prefs.onlyInZalo = it
        })
        c.body.addView(divider())

        // Tiêu đề + giá trị
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        head.addView(title("Kích thước nút"), LinearLayout.LayoutParams(0, WRAP, 1f))
        sizeValue = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.accent)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = rounded(p.soft, 999f)
        }
        head.addView(sizeValue)
        c.body.addView(head)

        // Thanh kéo + xem trước
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeSeek = SeekBar(this).apply {
            max = MAX_SIZE - MIN_SIZE
            progress = (prefs.sizeDp - MIN_SIZE).coerceIn(0, MAX_SIZE - MIN_SIZE)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.muted)
            setPadding(dp(4), dp(12), dp(12), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) prefs.sizeDp = MIN_SIZE + v
                    updatePreview()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(sizeSeek, LinearLayout.LayoutParams(0, WRAP, 1f))
        val box = FrameLayout(this).apply {
            background = rounded(p.bg, 16f)
        }
        preview = BubbleView(this)
        box.addView(preview, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        row.addView(box, LinearLayout.LayoutParams(dp(MAX_SIZE + 12), dp(MAX_SIZE + 12)))
        c.body.addView(row)

        // Chọn nhanh
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        presetChips.clear()
        SIZE_PRESETS.forEachIndexed { i, (name, size) ->
            val chip = TextView(this).apply {
                text = "$name · $size"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                setOnClickListener {
                    prefs.sizeDp = size
                    sizeSeek.progress = size - MIN_SIZE
                    updatePreview()
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
            presetChips.add(size to chip)
        }
        c.body.addView(chips)
        updatePreview()
        return c.root
    }

    private fun buildTemplateCard(first: Boolean = false): View {
        val c = card("Tin nhắn mẫu", "Chạm để chọn mẫu dùng. Nhấn giữ nút nổi để đổi nhanh.", first)
        templateHeading = c.heading
        templateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.body.addView(templateBox)
        c.body.addView(button("+  Thêm mẫu mới", style = BtnStyle.OUTLINE) { editTemplate(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        })
        c.body.addView(divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })
        c.body.addView(switchRow("Chỉ quote tin của người khác", "Bỏ qua tin nhắn do chính bạn gửi (bong bóng bên phải)", prefs.quoteOthersOnly) {
            prefs.quoteOthersOnly = it
        })
        c.body.addView(divider())
        c.body.addView(switchRow("Tự động bấm Gửi", "Tắt nếu muốn kiểm tra nội dung trước khi gửi", prefs.autoSend) {
            prefs.autoSend = it
        })
        renderTemplates()
        return c.root
    }

    private fun buildGuideCard(): View {
        val c = card("Cách dùng", null)
        val steps = listOf(
            "Mở một cuộc trò chuyện Zalo (nhóm hoặc cá nhân).",
            "Chạm nút nổi → app quote tin nhắn gần nhất và gửi mẫu đang chọn.",
            "Nhấn giữ nút nổi → đổi sang mẫu tiếp theo.",
            "Kéo nút để di chuyển, thả ra nút tự hít vào mép màn hình."
        )
        steps.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(p.accent)
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.soft) }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
            row.addView(TextView(this).apply {
                text = s
                textSize = 14f
                setTextColor(p.text)
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            c.body.addView(row)
        }
        return c.root
    }

    private fun buildDiagCard(): View {
        val c = card("Chẩn đoán lỗi", "Dùng khi app quote không thành công", false)
        c.body.addView(switchRow("Hiện từng bước khi chạy", "Hiện thông báo nhỏ ở mỗi bước để biết app dừng ở đâu", prefs.showSteps) {
            prefs.showSteps = it
        })
        c.body.addView(divider())
        reportInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(p.sub)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(p.bg, 12f)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
        c.body.addView(reportInfo)

        // Màn hình hẹp: xếp 2 nút theo chiều dọc
        val stacked = paneDp < 320
        val row = LinearLayout(this).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        val copy = button("Sao chép báo cáo", style = BtnStyle.FILLED) { copyReport() }
        val share = button("Chia sẻ", style = BtnStyle.OUTLINE) { shareReport() }
        if (stacked) {
            row.addView(copy, LinearLayout.LayoutParams(MATCH, WRAP))
            row.addView(share, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        } else {
            row.addView(copy, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            row.addView(share, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        c.body.addView(row)
        return c.root
    }

    private fun buildFooter(): View = TextView(this).apply {
        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        text = getString(R.string.app_name) + (version?.let { " · phiên bản $it" } ?: "")
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(p.sub)
        setPadding(0, dp(20), 0, dp(28))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    // ================================================================ templates

    private fun renderTemplates() {
        templateBox.removeAllViews()
        val list = prefs.templates
        templateHeading.text = if (list.isEmpty()) "Tin nhắn mẫu" else "Tin nhắn mẫu (${list.size})"
        refreshActivePreview()
        if (list.isEmpty()) {
            templateBox.addView(TextView(this).apply {
                text = "Chưa có mẫu nào. Thêm mẫu đầu tiên để bắt đầu."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(p.sub)
                setPadding(dp(12), dp(20), dp(12), dp(20))
                background = rounded(p.bg, 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
            })
            return
        }
        val active = prefs.activeIndex.coerceIn(0, list.size - 1)
        list.forEachIndexed { i, t ->
            val selected = i == active
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(6), dp(4))
                val shape = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (selected) p.soft else p.card)
                    setStroke(dp(if (selected) 2 else 1), if (selected) p.accent else p.line)
                }
                background = RippleDrawable(ColorStateList.valueOf(0x1A000000), shape, null)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
                isClickable = true
                setOnClickListener {
                    if (prefs.activeIndex != i) {
                        prefs.activeIndex = i
                        renderTemplates()
                    }
                }
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, dp(8), 0)
            }
            top.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (selected) p.accent else p.card)
                    setStroke(dp(if (selected) 5 else 2), if (selected) p.accent else p.muted)
                }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                rightMargin = dp(12)
                topMargin = dp(2)
            })
            top.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setTextColor(p.text)
                setLineSpacing(0f, 1.15f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            item.addView(top)

            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(30), dp(4), 0, 0)
            }
            bottom.addView(TextView(this).apply {
                if (selected) {
                    text = "Đang dùng"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    background = rounded(p.accent, 999f)
                } else {
                    text = "${t.length} ký tự"
                    textSize = 12f
                    setTextColor(p.sub)
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            bottom.addView(linkButton("Sửa", p.accent) { editTemplate(i) })
            bottom.addView(linkButton("Xoá", RED) { deleteTemplate(i) })
            item.addView(bottom)

            templateBox.addView(item)
        }
    }

    private fun dialogTheme() =
        if (dark) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun editTemplate(index: Int) {
        val list = prefs.templates.toMutableList()
        val counter = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.END
            setTextColor(p.sub)
            setPadding(0, dp(2), dp(4), 0)
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            hint = "Nhập nội dung trả lời…"
            setText(if (index >= 0) list[index] else "")
            setSelection(text.length)
            backgroundTintList = ColorStateList.valueOf(p.accent)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(counter, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle(if (index >= 0) "Sửa mẫu" else "Thêm mẫu")
            .setView(wrap)
            .setPositiveButton("Lưu") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                if (index >= 0) {
                    list[index] = v
                } else {
                    list.add(v)
                    prefs.activeIndex = list.size - 1
                }
                prefs.templates = list
                renderTemplates()
            }
            .setNegativeButton("Huỷ", null)
            .create()
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save?.setTextColor(p.accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.sub)

        fun sync() {
            val len = et.text.toString().trim().length
            counter.text = "$len ký tự"
            save?.isEnabled = len > 0
            save?.alpha = if (len > 0) 1f else 0.4f
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = sync()
        })
        sync()
    }

    private fun deleteTemplate(index: Int) {
        val t = prefs.templates.getOrNull(index) ?: return
        val dialog = AlertDialog.Builder(this, dialogTheme())
            .setTitle("Xoá mẫu này?")
            .setMessage("“" + (if (t.length > 120) t.take(120) + "…" else t) + "”")
            .setPositiveButton("Xoá") { _, _ ->
                val list = prefs.templates.toMutableList()
                if (index in list.indices) list.removeAt(index)
                prefs.templates = list
                val a = prefs.activeIndex
                if (a >= index && a > 0) prefs.activeIndex = a - 1
                renderTemplates()
            }
            .setNegativeButton("Huỷ", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(RED)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.sub)
    }

    // ================================================================ diagnostics

    private fun refreshReportInfo() {
        val r = Diag.load(this)
        reportInfo.text = if (r == null) {
            "Chưa có báo cáo lỗi. Khi app quote không thành công, báo cáo sẽ tự được lưu tại đây."
        } else {
            "Có báo cáo lỗi gần nhất (${maxOf(1, r.length / 1000)} KB). Hãy gửi cho người hỗ trợ để tinh chỉnh."
        }
    }

    private fun copyReport() {
        val r = Diag.load(this)
        if (r == null) {
            Toast.makeText(this, "Chưa có báo cáo lỗi", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zalo-quick-quote-report", r))
        Toast.makeText(this, "Đã sao chép báo cáo", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val r = Diag.load(this)
        if (r == null) {
            Toast.makeText(this, "Chưa có báo cáo lỗi", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, r.take(90_000))
        }
        startActivity(Intent.createChooser(i, "Gửi báo cáo lỗi"))
    }

    // ================================================================ helpers

    private fun ignoringBatteryOpt(): Boolean = try {
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) {
        true
    }

    /** Xin bỏ tối ưu pin để hệ thống không dừng dịch vụ Trợ năng khi màn hình tắt lâu / qua đêm. */
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Không mở được cài đặt pin trên máy này", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val full = "$packageName/${ZaloQuoteService::class.java.name}"
        val short = "$packageName/.ZaloQuoteService"
        return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun updatePreview() {
        val size = prefs.sizeDp
        sizeValue.text = "$size dp"
        val s = dp(size)
        preview.layoutParams = (preview.layoutParams as FrameLayout.LayoutParams).apply {
            width = s
            height = s
        }
        preview.requestLayout()
        for ((value, chip) in presetChips) {
            val on = value == size
            chip.setTextColor(if (on) p.accent else p.sub)
            chip.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            val shape = rounded(if (on) p.soft else p.card, 10f).apply {
                setStroke(dp(1), if (on) p.accent else p.line)
            }
            chip.background = RippleDrawable(ColorStateList.valueOf(0x1A000000), shape, null)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** Thẻ bo góc gồm tiêu đề, mô tả ngắn và phần nội dung. */
    private fun card(heading: String, desc: String?, first: Boolean = false): Card {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = rounded(p.card, 18f).apply { setStroke(dp(1), p.line) }
            elevation = if (dark) 0f else dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = if (first) 0 else dp(16)
            }
        }
        val h = TextView(this).apply {
            text = heading
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.text)
        }
        root.addView(h)
        if (desc != null) {
            root.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(p.sub)
                setPadding(0, dp(2), 0, 0)
            })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        return Card(root, body, h)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(p.text)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(p.sub)
        setPadding(0, dp(2), 0, 0)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1) / 2))
    }

    private fun switchRow(t: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(12), 0, dp(12))
            background = RippleDrawable(ColorStateList.valueOf(0x14000000), null, rounded(Color.WHITE, 8f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title(t))
        col.addView(subtitle(sub))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) })
        val sw = Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(p.accent, p.thumbOff)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf((p.accent and 0x00FFFFFF) or 0x80000000.toInt(), p.muted)
            )
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private enum class BtnStyle { FILLED, OUTLINE, ON_DARK }

    private fun button(t: String, style: BtnStyle, onClick: () -> Unit) =
        TextView(this).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val bg = rounded(p.card, 12f).apply {
                when (style) {
                    BtnStyle.ON_DARK -> setColor(Color.WHITE)
                    BtnStyle.FILLED -> setColor(p.accent)
                    BtnStyle.OUTLINE -> setStroke(dp(1), p.accent)
                }
            }
            setTextColor(
                when (style) {
                    BtnStyle.FILLED -> Color.WHITE
                    BtnStyle.ON_DARK -> Color.parseColor("#0050D8")
                    BtnStyle.OUTLINE -> p.accent
                }
            )
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun linkButton(t: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minWidth = dp(48)
        minHeight = dp(40)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), null, rounded(Color.WHITE, 8f))
        setOnClickListener { onClick() }
    }
}

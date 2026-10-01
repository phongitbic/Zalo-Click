package vn.quickquote.zalo

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ghi lại từng bước + cấu trúc màn hình Zalo khi thao tác thất bại, để gửi cho người hỗ trợ. */
object Diag {
    private const val FILE = "last_report.txt"
    private val sb = StringBuilder()

    fun start(ctx: Context) {
        sb.setLength(0)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        line("Zcar Click ${appVersion(ctx)} | $time")
        line("Máy: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Zalo: ${zaloVersion(ctx)}")
    }

    fun line(s: String) {
        sb.append(s).append('\n')
    }

    fun dump(title: String, roots: List<AccessibilityNodeInfo>) {
        line("")
        line("########## $title ##########")
        val start = sb.length
        // In cửa sổ Zalo trước; thanh hệ thống/bàn phím chỉ ghi tên để báo cáo không bị cắt mất phần Zalo
        val zaloFirst = roots.sortedBy { if (it.packageName?.toString() == ZaloQuoteService.ZALO_PKG) 0 else 1 }
        zaloFirst.forEachIndexed { i, r ->
            val pkg = r.packageName?.toString()
            line("=== window $i pkg=$pkg")
            if (pkg != ZaloQuoteService.ZALO_PKG) {
                line("  (bỏ qua chi tiết)")
                return@forEachIndexed
            }
            dumpNode(r, 0)
            if (sb.length - start > 70_000) {
                line("... (cắt bớt)")
                return
            }
        }
    }

    fun describe(n: AccessibilityNodeInfo): String {
        val b = Rect().also { n.getBoundsInScreen(it) }
        return buildString {
            append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { append(" id=").append(it.substringAfterLast('/')) }
            n.text?.let { append(" text=\"").append(it.toString().take(40).replace('\n', ' ')).append('"') }
            n.contentDescription?.let { append(" desc=\"").append(it.toString().take(40)).append('"') }
            append(" [").append(b.left).append(',').append(b.top).append(',')
                .append(b.right).append(',').append(b.bottom).append(']')
            if (n.isClickable) append(" C")
            if (n.isLongClickable) append(" L")
            if (n.isScrollable) append(" S")
            if (n.isEditable) append(" E")
            if (n.isFocused) append(" F")
        }
    }

    private fun dumpNode(n: AccessibilityNodeInfo, depth: Int) {
        if (depth > 50) return
        sb.append("  ".repeat(depth)).append(describe(n)).append('\n')
        for (i in 0 until n.childCount) n.getChild(i)?.let { dumpNode(it, depth + 1) }
    }

    fun save(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun load(ctx: Context): String? = try {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    } catch (_: Exception) {
        null
    }

    private fun appVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun zaloVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ZaloQuoteService.ZALO_PKG, 0).versionName ?: "?"
    } catch (_: Exception) {
        "không đọc được"
    }
}

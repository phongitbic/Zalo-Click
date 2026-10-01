package vn.quickquote.zalo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer
import kotlin.math.abs

/**
 * Luồng khi bấm nút nổi trong 1 cuộc trò chuyện Zalo:
 *  1) Tìm tin nhắn thấp nhất phía trên ô nhập.
 *  2) Cách A: nhấn giữ tin nhắn -> chọn "Trả lời".
 *     Cách B (dự phòng): vuốt ngang tin nhắn (vuốt để trả lời).
 *  3) Điền nội dung mẫu -> (tuỳ chọn) bấm Gửi.
 * Không bao giờ bấm "Quay lại" nếu không chắc có menu đang mở (tránh thoát khỏi cuộc trò chuyện).
 * Khi thất bại: lưu báo cáo chẩn đoán để gửi cho người hỗ trợ.
 */
class ZaloQuoteService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ZALO_PKG = "com.zing.zalo"
        private val REPLY_WORDS = listOf("trả lời", "reply", "phản hồi")
        private val SEND_WORDS = listOf("gửi", "send", "gửi tin nhắn", "gửi tin")
        private val STATUS_REGEX = Regex(
            "^(đã xem|đã nhận|đã gửi|đang gửi|seen|received|sent|delivered|hôm nay|hôm qua|today|yesterday)\\b.*" +
                "|^\\d{1,2}:\\d{2}( (am|pm|sa|ch))?$",
            RegexOption.IGNORE_CASE
        )
        private const val BUSY_TIMEOUT_MS = 10_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var currentPkg: String? = null
    private var busy = false
    private var bubbleAddedAt = 0L

    /** Các nhãn gần ô nhập trước thao tác, dùng để nhận biết thanh trả lời mới. */
    private data class Snapshot(val replyLabels: Set<String>)

    /** Kết quả chuẩn bị trong lúc người dùng đang ấn nút nổi. */
    private data class PreparedTap(
        val createdAt: Long,
        val inputTop: Int,
        val target: Target?,
        val before: Snapshot
    )

    private var operationStartedAt = 0L

    // ================================================================ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        currentPkg = foregroundPkg()
        refreshBubble()
        handler.postDelayed(watchdog, 1500)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    private fun cleanup() {
        if (::prefs.isInitialized) prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        removeBubble()
        removeBanner()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.toString()?.let { if (!isOverlayPkg(it)) lastEventPkg = it }
            }
            handler.removeCallbacks(pkgCheck)
            handler.postDelayed(pkgCheck, 150)
        }
    }

    private var lastEventPkg: String? = null

    /**
     * Gói không phải "ứng dụng đang mở": thanh hệ thống và bàn phím.
     * Bàn phím (Gboard, bàn phím Xiaomi...) bật lên khi gõ/sửa nội dung; nếu coi nó là
     * ứng dụng foreground thì nút nổi bị ẩn nhầm khi bật "Chỉ hiện khi mở Zalo".
     */
    private fun isOverlayPkg(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg in imePackages()

    private var imeCache: Set<String>? = null
    private var imeCacheAt = 0L

    private fun imePackages(): Set<String> {
        val now = SystemClock.uptimeMillis()
        imeCache?.let { if (now - imeCacheAt < 60_000) return it }
        val set = HashSet<String>()
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.inputMethodList.forEach { set.add(it.packageName) }
        } catch (_: Exception) {}
        try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let { set.add(it) }
        } catch (_: Exception) {}
        imeCache = set
        imeCacheAt = now
        return set
    }

    /** Ứng dụng đang ở foreground: ưu tiên cửa sổ ứng dụng đang active, rồi mới tới cửa sổ active, rồi sự kiện. */
    private fun foregroundPkg(): String? {
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                    w.root?.packageName?.toString()?.takeIf { !isOverlayPkg(it) }?.let { return it }
                }
            }
        } catch (_: Exception) {}
        return safeRoot()?.packageName?.toString()?.takeIf { !isOverlayPkg(it) } ?: lastEventPkg
    }

    private val pkgCheck = Runnable {
        if (busy) return@Runnable
        val pkg = foregroundPkg()
        if (pkg != null && !isOverlayPkg(pkg)) currentPkg = pkg
        refreshBubble()
    }

    /** Kiểm tra định kỳ (1,5 s) để nút nổi luôn đúng trạng thái kể cả khi hệ thống bỏ lỡ sự kiện. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (::prefs.isInitialized && prefs.enabled && !busy) pkgCheck.run()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.K_ENABLED, Prefs.K_ONLY_ZALO, Prefs.K_SIZE -> handler.post { refreshBubble() }
        }
    }

    // ================================================================ nút nổi

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun refreshBubble() {
        if (!::prefs.isInitialized) return
        val show = prefs.enabled &&
            (!prefs.onlyInZalo || currentPkg == ZALO_PKG || currentPkg == packageName)
        val b = bubble
        if (b != null && !b.isAttachedToWindow && SystemClock.uptimeMillis() - bubbleAddedAt > 1000) {
            removeBubble() // bị hệ thống gỡ ngầm -> tạo lại
        }
        when {
            show && bubble == null -> addBubble()
            !show && bubble != null -> removeBubble()
            show -> applySize()
        }
    }

    private fun addBubble() {
        val size = dp(prefs.sizeDp)
        val v = BubbleView(this)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.posX < 0) screenW() - size - dp(6) else prefs.posX.coerceIn(0, screenW() - size)
            y = prefs.posY.coerceIn(dp(24), screenH() - size - dp(24))
        }
        v.setOnTouchListener(BubbleTouch(p))
        try {
            wm.addView(v, p)
            bubble = v
            lp = p
            bubbleAddedAt = SystemClock.uptimeMillis()
            v.scaleX = 0f; v.scaleY = 0f
            v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        } catch (e: Exception) {
            toast("Không tạo được nút nổi: ${e.message}")
        }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        lp = null
    }

    private fun applySize() {
        val b = bubble ?: return
        val p = lp ?: return
        val s = dp(prefs.sizeDp)
        if (p.width == s) return
        p.width = s
        p.height = s
        if (p.x > screenW() / 2) p.x = screenW() - s - dp(6)
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
    }

    private fun setBubbleTouchable(touchable: Boolean) {
        val b = bubble ?: return
        val p = lp ?: return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        b.busy = !touchable
        try { wm.updateViewLayout(b, p) } catch (_: Exception) {}
    }

    /** Hít vào mép màn hình gần nhất sau khi kéo. */
    private fun snapToEdge(v: View, p: WindowManager.LayoutParams) {
        val size = p.width
        val targetX = if (p.x + size / 2 < screenW() / 2) dp(6) else screenW() - size - dp(6)
        p.y = p.y.coerceIn(dp(24), screenH() - size - dp(24))
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                p.x = it.animatedValue as Int
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            start()
        }
        prefs.posX = targetX
        prefs.posY = p.y
    }

    private inner class BubbleTouch(val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longFired = false
        private var preparedTap: PreparedTap? = null
        private val slop = dp(10)
        private var view: View? = null
        private val longPressRunnable = Runnable {
            longFired = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onBubbleLongPress()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            view = v
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false; longFired = false
                    preparedTap = null
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    handler.postDelayed(longPressRunnable, 550)
                    // Tận dụng thời gian ngón tay còn đang ấn để tìm sẵn tin nhắn.
                    handler.post {
                        if (!moved && !longFired && !busy) preparedTap = prepareTap()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        preparedTap = null
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        p.x = (startX + dx).toInt()
                        p.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moved) {
                        snapToEdge(v, p)
                    } else if (!longFired) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onBubbleTap(preparedTap)
                    }
                    preparedTap = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    preparedTap = null
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            return true
        }
    }

    private fun onBubbleLongPress() {
        val list = prefs.templates
        if (list.size <= 1) {
            toast("Chỉ có 1 mẫu. Thêm mẫu trong app để đổi nhanh.")
            return
        }
        val next = (prefs.activeIndex + 1) % list.size
        prefs.activeIndex = next
        toast("Mẫu ${next + 1}/${list.size}: ${list[next].take(50)}")
    }

    // ================================================================ luồng quote

    private val busyTimeout = Runnable { fail("Quá thời gian chờ, đã huỷ") }

    private fun step(msg: String) {
        val elapsed = if (operationStartedAt > 0) SystemClock.uptimeMillis() - operationStartedAt else 0
        Diag.line("-> $msg (+${elapsed}ms)")
        if (prefs.showSteps) toast(msg)
    }

    private fun done() {
        handler.removeCallbacks(busyTimeout)
        busy = false
        setBubbleTouchable(true)
        Diag.save(this)
    }

    private fun fail(msg: String) {
        if (!busy) return
        Diag.line("THẤT BẠI: $msg")
        Diag.save(this)
        done()
        toast("$msg\n(Đã lưu báo cáo lỗi trong app)")
    }

    private fun prepareTap(): PreparedTap? {
        val root = safeRoot() ?: return null
        if (root.packageName?.toString() != ZALO_PKG) return null
        val input = findInput(root) ?: return null
        val inputTop = bounds(input).top
        return PreparedTap(
            createdAt = SystemClock.uptimeMillis(),
            inputTop = inputTop,
            target = pickTarget(root, inputTop, null),
            before = snapshot(root, input)
        )
    }

    private fun onBubbleTap(prepared: PreparedTap? = null) {
        if (busy) return
        val text = prefs.activeTemplate?.trim()
        if (text.isNullOrEmpty()) {
            toast("Chưa có nội dung mẫu. Mở app để thêm.")
            return
        }
        val ready = prepared?.takeIf { SystemClock.uptimeMillis() - it.createdAt <= 1_000L }
        val root = if (ready == null) safeRoot() else null
        if (ready == null && (root == null || root.packageName?.toString() != ZALO_PKG)) {
            toast("Hãy mở một cuộc trò chuyện trong Zalo")
            return
        }
        val input = root?.let { findInput(it) }
        if (ready == null && input == null) {
            toast("Chưa ở trong cuộc trò chuyện (không thấy ô nhập tin)")
            return
        }
        val inputTop = ready?.inputTop ?: bounds(input!!).top
        Diag.start(this)
        operationStartedAt = SystemClock.uptimeMillis()
        if (input != null) Diag.line("Ô nhập: ${Diag.describe(input)}")

        busy = true
        handler.postDelayed(busyTimeout, BUSY_TIMEOUT_MS)
        setBubbleTouchable(false)

        // Đường nhanh: đa số phiên bản Zalo cung cấp khung bong bóng đủ để nhận biết bên trái/phải.
        // Chỉ chụp màn hình khi cây trợ năng không xác định được tin của người khác.
        val fastTarget = ready?.target ?: root?.let { pickTarget(it, inputTop, null) }
        if (fastTarget != null) {
            Diag.line("Tìm tin bằng cây trợ năng, bỏ qua chụp màn hình")
            startQuote(fastTarget, text, ready?.before ?: snapshot(root, input))
            return
        }

        captureScreen { bmp ->
            if (!busy) {
                bmp?.recycle()
                return@captureScreen
            }
            Diag.line("Ảnh chụp màn hình: " + (bmp?.let { "${it.width}x${it.height}" } ?: "không có"))
            val r = safeRoot()
            val target = if (r != null) pickTarget(r, inputTop, bmp) else null
            bmp?.recycle()
            if (target == null) {
                Diag.dump("MÀN HÌNH (không tìm thấy tin nhắn)", allRoots())
                fail(
                    if (prefs.quoteOthersOnly) "Không thấy tin nhắn của người khác trên màn hình"
                    else "Không tìm thấy tin nhắn để quote"
                )
                return@captureScreen
            }
            startQuote(target, text, snapshot())
        }
    }

    private data class Target(
        val rect: Rect,
        val x: Float,
        val y: Float,
        val label: String?,
        val info: String,
        val node: AccessibilityNodeInfo
    )

    private fun startQuote(target: Target, text: String, before: Snapshot) {
        Diag.line("Tin nhắn chọn: ${target.info} | thao tác tại (${target.x.toInt()},${target.y.toInt()})")
        // Vuốt để trả lời nhanh hơn nhấn giữ -> đợi menu -> bấm Trả lời.
        trySwipe(target, text, before)
    }

    /** Còn đang ở trong cuộc trò chuyện Zalo (có ô nhập tin) hay không. */
    private fun stillInChat(): Boolean = chatInput() != null

    /** Ô nhập tin của Zalo, tìm trên mọi cửa sổ Zalo (không chỉ cửa sổ trên cùng). */
    private fun chatInput(): AccessibilityNodeInfo? {
        safeRoot()?.let { root ->
            if (root.packageName?.toString() == ZALO_PKG) findInput(root)?.let { return it }
        }
        for (r in allRoots()) {
            if (r.packageName?.toString() != ZALO_PKG) continue
            findInput(r)?.let { return it }
        }
        return null
    }

    /** Đường nhanh: vuốt trái trực tiếp trên tin nhắn để mở thanh trả lời. */
    private fun trySwipe(target: Target, text: String, before: Snapshot) {
        if (!busy) return
        step("Vuốt để trả lời")
        val tb = target.rect
        val y = tb.exactCenterY()
        val x1 = maxOf(tb.exactCenterX(), screenW() * 0.55f)
        val x2 = maxOf(x1 - dp(150), screenW() * 0.15f)
        swipe(x1, y, x2, y) { ok ->
            if (!busy) return@swipe
            if (!ok) {
                tryLongPress(target, text)
                return@swipe
            }
            waitForReply(before, target.label, tries = 14, interval = 15L) { input ->
                if (input != null) {
                    step("Đã vào chế độ trả lời")
                    fillInto(input, text)
                } else {
                    Diag.line("Vuốt chưa mở được thanh trả lời, chuyển sang nhấn giữ")
                    handler.postDelayed({
                        val lateInput = replyInput(before, target.label)
                        if (lateInput != null) fillInto(lateInput, text) else tryLongPress(target, text)
                    }, 30)
                }
            }
        }
    }

    /** Dự phòng cho phiên bản Zalo không hỗ trợ vuốt để trả lời. */
    private fun tryLongPress(target: Target, text: String) {
        if (!busy) return
        if (!stillInChat()) {
            fail("Đã rời khỏi cuộc trò chuyện, dừng thao tác")
            return
        }
        val before = snapshot()
        step("Nhấn giữ tin nhắn")
        openMessageMenu(target) { ok ->
            if (!busy) return@openMessageMenu
            if (!ok) {
                fail("Thao tác nhấn giữ bị huỷ")
                return@openMessageMenu
            }
            waitForNode(tries = 30, interval = 20L, finder = { findReplyButton() }) { btn ->
                if (btn == null) {
                    if (contextMenuOpen()) performGlobalAction(GLOBAL_ACTION_BACK)
                    Diag.dump("SAU KHI NHẤN GIỮ (không thấy nút Trả lời)", allRoots())
                    fail("Không quote được tin nhắn")
                    return@waitForNode
                }
                step("Chọn \"Trả lời\"")
                Diag.line("Nút trả lời: ${Diag.describe(btn)}")
                clickNode(btn)
                waitForReply(before, target.label, tries = 30, interval = 20L) { input ->
                    if (input != null) {
                        fillInto(input, text)
                    } else {
                        Diag.line(replyDebug(before, target.label))
                        fail("Chưa xác nhận được quote, dừng điền và gửi")
                    }
                }
            }
        }
    }

    /**
     * Chỉ xác nhận khi có thanh trả lời MỚI mở ra phía trên ô nhập:
     *  - bỏ qua mọi nút nằm trong danh sách tin nhắn (kể cả chính tin nhắn gốc nằm sát ô nhập),
     *  - bỏ qua nhãn đã có từ trước khi thao tác,
     *  - menu nhấn giữ phải đã đóng (tránh nhận nhầm chữ "Trả lời" của menu).
     * Không đủ bằng chứng => false, không bao giờ gửi tin thường.
     */
    private fun replyInput(before: Snapshot, targetLabel: String?): AccessibilityNodeInfo? {
        val root = safeRoot() ?: return null
        if (root.packageName?.toString() != ZALO_PKG) return null
        if (contextMenuOpen(root)) return null
        val input = findInput(root) ?: return null
        return input.takeIf {
            ReplyDetection.hasNewReply(before.replyLabels, replyBarLabels(root, input), targetLabel)
        }
    }

    /** Ghi lại vì sao chưa xác nhận được thanh trả lời (để chẩn đoán). */
    private fun replyDebug(before: Snapshot, targetLabel: String?): String {
        val root = safeRoot()
        val input = root?.let { findInput(it) }
        val list = if (root != null && input != null) findChatList(root, bounds(input).top) else null
        val after = if (root != null && input != null) replyBarLabels(root, input) else emptySet()
        return "KIỂM TRA THANH TRẢ LỜI: menu còn mở=${contextMenuOpen()} | cửa sổ=${root?.packageName}" +
            " | ô nhập=${input?.let { bounds(it) }} | khung chat=${list?.let { bounds(it) }}" +
            " | nhãn trước=${before.replyLabels} | nhãn sau=$after | tin gốc=${targetLabel?.take(40)}"
    }

    private fun contextMenuOpen(root: AccessibilityNodeInfo? = null): Boolean {
        val roots = if (root != null) listOf(root) else allRoots()
        return roots.any { r ->
        try {
            r.findAccessibilityNodeInfosByViewId("$ZALO_PKG:id/chat_context_menu_tv_title")
                ?.any { it.isVisibleToUser } == true
        } catch (_: Exception) {
            false
        }
    }
    }

    private fun replyBarLabels(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo): Set<String> {
        val inputTop = bounds(input).top
        // Không xác định được danh sách tin thì chưa đủ bằng chứng để tự gửi.
        val chatList = findChatList(root, inputTop) ?: return emptySet()
        val candidates = ArrayList<ReplyCandidate>()
        fun walk(n: AccessibilityNodeInfo, depth: Int, insideMessages: Boolean) {
            if (depth > 60) return
            val inList = insideMessages || n == chatList
            if (n.isVisibleToUser && !n.isEditable) {
                val b = bounds(n)
                // Chỉ đọc vùng mà thanh trả lời có thể xuất hiện.
                if (b.bottom > inputTop - dp(140) && b.top < inputTop) {
                    label(n)?.let { candidates.add(ReplyCandidate(it, b.top, b.bottom, inList)) }
                    val id = n.viewIdResourceName?.substringAfter(":id/")?.lowercase()
                    if (id != null && (id.contains("reply") || id.contains("quote"))) {
                        candidates.add(ReplyCandidate("trả lời [id=$id]", b.top, b.bottom, inList))
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, inList) }
        }
        walk(root, 0, false)
        return ReplyDetection.barLabels(candidates, inputTop, dp(140))
    }

    private fun waitForReply(
        before: Snapshot,
        targetLabel: String?,
        tries: Int,
        interval: Long,
        cb: (AccessibilityNodeInfo?) -> Unit
    ) {
        if (!busy) return
        val input = replyInput(before, targetLabel)
        if (input != null || tries <= 0) {
            cb(input)
            return
        }
        handler.postDelayed(
            { waitForReply(before, targetLabel, tries - 1, interval, cb) },
            interval
        )
    }

    private fun fillInto(input: AccessibilityNodeInfo, text: String) {
        step("Điền nội dung")
        // Giữ lại hàng ô nhập: Zalo 26.09.01 có thể dựng lại compose panel
        // sau ACTION_SET_TEXT và không đưa nút Gửi vào cây trợ năng.
        val inputBounds = bounds(input)
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            input.refresh()
            ok = (input.text?.toString() ?: "").contains(text.take(12))
        }
        if (!ok) ok = pasteViaClipboard(input, text)
        if (!ok) {
            fail("Không điền được nội dung vào ô nhập")
            return
        }
        if (!prefs.autoSend) {
            Diag.line("THÀNH CÔNG (chỉ điền)")
            done()
            toast("✓ Đã quote, kiểm tra rồi bấm Gửi")
            return
        }
        // Thử node Gửi trong 45 ms. Nếu Zalo ẩn node, chạm thẳng nút ngoài cùng
        // bên phải trên chính hàng ô nhập đã xác nhận.
        waitForNode(tries = 3, interval = 15L, finder = {
            val freshInput = safeRoot()?.let { findInput(it) } ?: input
            findSendButton(freshInput, strict = true)
        }) { send ->
            step("Bấm Gửi")
            if (send != null) {
                Diag.line("Nút gửi: ${Diag.describe(send)}")
                clickNode(send)
                sendDone()
            } else {
                val x = (screenW() - dp(24)).toFloat()
                val y = inputBounds.exactCenterY()
                Diag.line("Zalo ẩn node Gửi, chạm nhanh tại (${x.toInt()},${y.toInt()})")
                tap(x, y) { ok ->
                    if (ok) sendDone() else fail("Không bấm được nút Gửi")
                }
            }
        }
    }

    private fun sendDone() {
        Diag.line("THÀNH CÔNG")
        done()
        toast("✓ Đã quote và gửi")
    }

    private fun pasteViaClipboard(input: AccessibilityNodeInfo, text: String): Boolean = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zalo-quick-quote", text))
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    } catch (e: Exception) {
        false
    }

    // ================================================================ đọc màn hình

    private fun safeRoot(): AccessibilityNodeInfo? = try { rootInActiveWindow } catch (_: Exception) { null }

    /** Các cửa sổ ứng dụng (bỏ qua nút nổi của chính app). */
    private fun allRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == packageName) continue
                list.add(r)
            }
        } catch (_: Exception) {}
        if (list.isEmpty()) safeRoot()?.let { list.add(it) }
        return list
    }

    private fun snapshot(): Snapshot {
        val root = safeRoot()
        val input = root?.let { findInput(it) }
        return snapshot(root, input)
    }

    private fun snapshot(root: AccessibilityNodeInfo?, input: AccessibilityNodeInfo?): Snapshot =
        Snapshot(if (root != null && input != null) replyBarLabels(root, input) else emptySet())

    private fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    private fun norm(s: CharSequence?): String? {
        if (s == null) return null
        val t = Normalizer.normalize(s.toString(), Normalizer.Form.NFC).trim().lowercase()
        return t.ifEmpty { null }
    }

    private fun label(n: AccessibilityNodeInfo): String? = norm(n.text) ?: norm(n.contentDescription)

    private fun collect(
        root: AccessibilityNodeInfo,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 60) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            root.findAccessibilityNodeInfosByViewId("$ZALO_PKG:id/chatinput_text")
                ?.firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        } catch (_: Exception) {}
        return collect(root) { it.isEditable && it.isVisibleToUser }
            .maxByOrNull { bounds(it).bottom }
    }

    private fun findChatList(root: AccessibilityNodeInfo, inputTop: Int): AccessibilityNodeInfo? =
        collect(root) { it.isScrollable && it.isVisibleToUser }
            .filter { val b = bounds(it); b.top < inputTop && b.height() > dp(150) }
            .maxByOrNull { val b = bounds(it); b.width().toLong() * b.height() }

    private enum class Side { LEFT, RIGHT, UNKNOWN }

    /**
     * Chọn tin nhắn cần quote, duyệt từ dưới lên.
     *  - Nếu Zalo cho biết khung bong bóng (hẹp hơn 80% màn hình): lệch trái = tin người khác.
     *  - Nếu Zalo chỉ cho biết cả hàng (full chiều ngang): dùng ảnh chụp màn hình,
     *    xem bong bóng có màu nằm ở phía trái hay phía phải của hàng.
     */
    private fun pickTarget(root: AccessibilityNodeInfo, inputTop: Int, bmp: Bitmap?): Target? {
        val headerBottom = dp(96)
        val scope = findChatList(root, inputTop) ?: root
        val sb = bounds(scope)
        val areaLeft = if (sb.width() > 0) sb.left else 0
        val areaRight = if (sb.width() > 0) sb.right else screenW()
        val areaW = areaRight - areaLeft
        val othersOnly = prefs.quoteOthersOnly

        var cands = collect(scope) { n ->
            if (n == scope || !n.isVisibleToUser || n.isEditable) return@collect false
            val b = bounds(n)
            if (b.top < headerBottom || b.top > inputTop - dp(20)) return@collect false
            if (b.height() < dp(24) || b.width() < dp(36)) return@collect false
            if (b.right <= areaLeft + dp(64)) return@collect false // avatar
            val l = label(n)
            (l != null && !STATUS_REGEX.matches(l)) || n.isLongClickable
        }
        Diag.line("Khung chat: $sb | ứng viên: ${cands.size}")

        var guard = 0
        while (cands.isNotEmpty() && guard++ < 25) {
            val maxBottom = cands.maxOf { minOf(bounds(it).bottom, inputTop) }
            val group = cands.filter { minOf(bounds(it).bottom, inputTop) >= maxBottom - dp(4) }
            val rep = group.minByOrNull { val b = bounds(it); b.width().toLong() * b.height() } ?: break
            val rb = bounds(rep).also { if (it.bottom > inputTop) it.bottom = inputTop - dp(2) }
            val narrow = rb.width() < areaW * 0.8f
            val side = if (narrow) {
                if (rb.left - areaLeft < areaRight - rb.right) Side.LEFT else Side.RIGHT
            } else {
                pixelSide(rb, areaLeft, areaRight, bmp)
            }
            Diag.line("Hàng ${Diag.describe(rep)} -> ${if (narrow) "bong bóng" else "cả hàng"} $side")

            val accept = if (othersOnly) side == Side.LEFT else side != Side.UNKNOWN || !narrow
            if (accept) {
                val cy = rb.exactCenterY()
                val cx = when {
                    narrow -> rb.exactCenterX()
                    side == Side.LEFT -> (areaLeft + dp(84)).toFloat()
                    side == Side.RIGHT -> (areaRight - dp(60)).toFloat()
                    else -> rb.exactCenterX()
                }
                return Target(rb, cx, cy, label(rep), Diag.describe(rep) + " [$side]", rep)
            }
            // bỏ hàng này, xét hàng phía trên
            cands = cands.filter { bounds(it).bottom <= rb.top + dp(6) }
        }
        return null
    }

    /** Dựa vào ảnh chụp: bong bóng màu nằm bên phải (tin mình) hay bên trái (tin người khác). */
    private fun pixelSide(row: Rect, areaLeft: Int, areaRight: Int, bmp: Bitmap?): Side {
        if (bmp == null) return Side.UNKNOWN
        val scale = bmp.width.toFloat() / screenW()
        fun px(x: Int, y: Int): Int {
            val bx = (x * scale).toInt().coerceIn(0, bmp.width - 1)
            val by = (y * scale).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(bx, by)
        }
        fun dist(a: Int, b: Int): Int =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

        var left = 0
        var right = 0
        for (f in floatArrayOf(0.3f, 0.45f, 0.6f, 0.75f)) {
            val y = (row.top + row.height() * f).toInt()
            val bgL = px(areaLeft + dp(3), y)
            val bgR = px(areaRight - dp(3), y)
            fun differs(x: Int): Boolean {
                val c = px(x, y)
                return minOf(dist(c, bgL), dist(c, bgR)) > 30
            }
            if (differs(areaRight - dp(40))) right++
            else if (differs(areaLeft + dp(76))) left++
        }
        Diag.line("   màu hàng y=${row.top}..${row.bottom}: trái=$left phải=$right")
        return when {
            right >= 2 && right >= left -> Side.RIGHT
            left >= 2 -> Side.LEFT
            else -> Side.UNKNOWN
        }
    }

    // ---- Chụp màn hình dự phòng (Android 11+) ----

    private fun captureScreen(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null)
            return
        }
        rawShot(0, cb)
    }

    /** Ẩn nút nổi 1 khung hình rồi chụp; nếu bị giới hạn tần suất chụp thì thử lại 1 lần. */
    private fun rawShot(attempt: Int, cb: (Bitmap?) -> Unit) {
        bubble?.alpha = 0f
        handler.postDelayed({
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY, mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            var bmp: Bitmap? = null
                            try {
                                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                hw?.recycle()
                                result.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Diag.line("Lỗi đọc ảnh chụp: ${e.message}")
                            }
                            bubble?.alpha = 1f
                            cb(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (attempt == 0 &&
                                errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            ) {
                                handler.postDelayed({ rawShot(1, cb) }, 340)
                                return
                            }
                            Diag.line("Chụp màn hình lỗi, mã $errorCode")
                            bubble?.alpha = 1f
                            cb(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Diag.line("Chụp màn hình lỗi: ${e.message}")
                bubble?.alpha = 1f
                cb(null)
            }
        }, 32)
    }

    private fun findReplyButton(): AccessibilityNodeInfo? {
        for (r in allRoots()) {
            try {
                r.findAccessibilityNodeInfosByViewId("$ZALO_PKG:id/chat_context_menu_tv_title")
                    ?.firstOrNull { n -> n.isVisibleToUser && label(n)?.let { l -> REPLY_WORDS.any { l == it } } == true }
                    ?.let { return it }
            } catch (_: Exception) {}
        }
        for (r in allRoots()) {
            val hit = collect(r) { n ->
                if (!n.isVisibleToUser || n.isEditable) return@collect false
                val b = bounds(n)
                if (b.height() > dp(90)) return@collect false
                val l = label(n)
                val id = (n.viewIdResourceName ?: "").lowercase()
                (l != null && REPLY_WORDS.any { l == it }) ||
                    (id.contains("reply") && !id.contains("bar") && !id.contains("quote"))
            }.firstOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Nút Gửi cùng hàng với ô nhập.
     * strict = true: chỉ nhận khi nhãn/id rõ ràng là "gửi"/"send".
     * strict = false: thêm phương án nút ngoài cùng bên phải, bỏ qua mic/ảnh/sticker.
     */
    private fun findSendButton(input: AccessibilityNodeInfo, strict: Boolean): AccessibilityNodeInfo? {
        val ib = bounds(input)
        fun sameRow(b: Rect) = b.centerY() in (ib.top - dp(28))..(ib.bottom + dp(28))
        fun labelDeep(n: AccessibilityNodeInfo): String? {
            label(n)?.let { return it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> label(c)?.let { return it } }
            return null
        }
        fun id(n: AccessibilityNodeInfo) = (n.viewIdResourceName ?: "").lowercase()
        val avoid = listOf("mic", "voice", "record", "photo", "image", "gallery", "sticker", "emoji", "more", "camera")

        // Nút Gửi là anh em/họ hàng gần của ô nhập. Duyệt từ nhánh nhỏ ra ngoài
        // và dừng ngay khi tìm thấy, thay vì quét tất cả cửa sổ Zalo.
        var scope: AccessibilityNodeInfo? = input.parent
        var levels = 0
        while (scope != null && levels++ < 5) {
            val clickables = collect(scope) { it.isVisibleToUser && it.isClickable && !it.isEditable }
                .filter { sameRow(bounds(it)) }
            clickables.firstOrNull { n -> labelDeep(n)?.let { l -> SEND_WORDS.any { l == it } } == true }
                ?.let { return it }
            clickables.firstOrNull { id(it).contains("send") }?.let { return it }
            if (!strict) clickables
                .filter { n ->
                val b = bounds(n)
                b.left >= ib.right - dp(4) && b.width() < dp(90) && avoid.none { id(n).contains(it) }
                }
                .maxByOrNull { bounds(it).right }
                ?.let { return it }
            scope = scope.parent
        }
        return null
    }

    private fun waitForNode(
        tries: Int,
        interval: Long,
        finder: () -> AccessibilityNodeInfo?,
        cb: (AccessibilityNodeInfo?) -> Unit
    ) {
        if (!busy) return
        val n = finder()
        if (n != null || tries <= 0) {
            cb(n)
            return
        }
        handler.postDelayed({ waitForNode(tries - 1, interval, finder, cb) }, interval)
    }

    // ================================================================ cử chỉ

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val b = bounds(node)
        tap(b.exactCenterX(), b.exactCenterY())
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
    }

    private fun runGesture(g: GestureDescription, cb: ((Boolean) -> Unit)?) {
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post { cb?.invoke(true) }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post { cb?.invoke(false) }
            }
        }, null)
        if (!ok) cb?.invoke(false)
    }

    private fun tap(x: Float, y: Float, cb: ((Boolean) -> Unit)? = null) =
        runGesture(gesture(x, y, x + 1f, y + 1f, 50), cb)

    /** Gọi long-click trực tiếp nếu Zalo có cung cấp action, tránh phải chờ ngưỡng giữ của Android. */
    private fun openMessageMenu(target: Target, cb: (Boolean) -> Unit) {
        var node: AccessibilityNodeInfo? = target.node
        while (node != null) {
            try {
                if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    handler.post { cb(true) }
                    return
                }
            } catch (_: Exception) {}
            node = node.parent
        }
        longPress(target.x, target.y, cb)
    }

    private fun longPress(x: Float, y: Float, cb: (Boolean) -> Unit) =
        runGesture(gesture(x, y, x + 1f, y + 1f, longPressMs()), cb)

    /** Thời gian nhấn giữ = ngưỡng long-press của hệ thống + 60 ms dự phòng. */
    private fun longPressMs(): Long =
        (ViewConfiguration.getLongPressTimeout() + 60).toLong().coerceIn(350L, 700L)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, cb: (Boolean) -> Unit) =
        runGesture(gesture(x1, y1, x2, y2, 100), cb)

    // ================================================================ banner thông báo

    private var banner: TextView? = null
    private val hideBanner = Runnable { removeBanner() }

    private fun removeBanner() {
        banner?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        banner = null
    }

    /**
     * Thông báo dạng banner tự vẽ (một số ROM chặn Toast của dịch vụ nền,
     * nên không dùng Toast để đảm bảo người dùng luôn thấy phản hồi).
     */
    private fun toast(msg: String) {
        handler.post {
            if (!::wm.isInitialized) return@post
            removeBanner()
            handler.removeCallbacks(hideBanner)
            val tv = TextView(this).apply {
                text = msg
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xE6222831.toInt())
                }
                elevation = dp(6).toFloat()
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }
            try {
                wm.addView(tv, p)
                banner = tv
                tv.alpha = 0f
                tv.animate().alpha(1f).setDuration(150).start()
                handler.postDelayed(hideBanner, if (msg.length > 40) 3500L else 2200L)
            } catch (_: Exception) {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

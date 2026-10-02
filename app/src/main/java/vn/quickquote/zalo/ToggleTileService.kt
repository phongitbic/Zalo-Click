package vn.quickquote.zalo

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Ô "Cài đặt nhanh" (kéo thanh thông báo xuống) để bật/tắt nút nổi nhanh. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val p = Prefs(this)
        p.enabled = !p.enabled
        update()
        // Bật nút nổi nhưng dịch vụ Trợ năng đã bị hệ thống dừng -> nút sẽ không hiện.
        // Mở app để người dùng thấy cảnh báo và bật lại, thay vì im lặng không có gì xảy ra.
        if (p.enabled && !ZaloQuoteService.running) openApp()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        } catch (_: Exception) {}
    }

    private fun update() {
        val t = qsTile ?: return
        val on = Prefs(this).enabled
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                !ZaloQuoteService.running -> "Trợ năng bị dừng"
                on -> "Đang bật"
                else -> "Đang tắt"
            }
        }
        t.updateTile()
    }
}

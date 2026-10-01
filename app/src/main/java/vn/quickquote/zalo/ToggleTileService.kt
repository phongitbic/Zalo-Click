package vn.quickquote.zalo

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
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (Prefs(this).enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.tile_label)
        t.updateTile()
    }
}

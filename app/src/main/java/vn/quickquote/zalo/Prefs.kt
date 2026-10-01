package vn.quickquote.zalo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    var onlyInZalo: Boolean
        get() = sp.getBoolean(K_ONLY_ZALO, true)
        set(v) = sp.edit().putBoolean(K_ONLY_ZALO, v).apply()

    var sizeDp: Int
        get() = sp.getInt(K_SIZE, 56)
        set(v) = sp.edit().putInt(K_SIZE, v).apply()

    var autoSend: Boolean
        get() = sp.getBoolean(K_AUTO_SEND, true)
        set(v) = sp.edit().putBoolean(K_AUTO_SEND, v).apply()

    /** Chỉ quote tin nhắn của người khác (bỏ qua tin do mình gửi). */
    var quoteOthersOnly: Boolean
        get() = sp.getBoolean(K_OTHERS_ONLY, true)
        set(v) = sp.edit().putBoolean(K_OTHERS_ONLY, v).apply()

    /** Hiện thông báo từng bước khi chạy (để chẩn đoán). */
    var showSteps: Boolean
        get() = sp.getBoolean(K_DEBUG, false)
        set(v) = sp.edit().putBoolean(K_DEBUG, v).apply()

    var posX: Int
        get() = sp.getInt(K_POS_X, -1)
        set(v) = sp.edit().putInt(K_POS_X, v).apply()

    var posY: Int
        get() = sp.getInt(K_POS_Y, 500)
        set(v) = sp.edit().putInt(K_POS_Y, v).apply()

    var activeIndex: Int
        get() = sp.getInt(K_ACTIVE, 0)
        set(v) = sp.edit().putInt(K_ACTIVE, v).apply()

    var templates: List<String>
        get() {
            val raw = sp.getString(K_TEMPLATES, null) ?: return listOf(DEFAULT_TEMPLATE)
            return try {
                val a = JSONArray(raw)
                List(a.length()) { a.getString(it) }
            } catch (e: Exception) {
                listOf(DEFAULT_TEMPLATE)
            }
        }
        set(v) = sp.edit().putString(K_TEMPLATES, JSONArray(v).toString()).apply()

    val activeTemplate: String?
        get() {
            val t = templates
            if (t.isEmpty()) return null
            return t[activeIndex.coerceIn(0, t.size - 1)]
        }

    companion object {
        const val K_ENABLED = "enabled"
        const val K_ONLY_ZALO = "only_zalo"
        const val K_SIZE = "size_dp"
        const val K_AUTO_SEND = "auto_send"
        const val K_DEBUG = "debug"
        const val K_OTHERS_ONLY = "others_only"
        const val K_POS_X = "pos_x"
        const val K_POS_Y = "pos_y"
        const val K_ACTIVE = "active_index"
        const val K_TEMPLATES = "templates"
        const val DEFAULT_TEMPLATE = "Dạ em đã nhận được thông tin, em kiểm tra và phản hồi anh/chị ngay ạ."
    }
}

package com.qdvc.paperpod.prefs

import android.content.Context

/** Device-local display preferences. Nothing here is ever synced. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("paperpod", Context.MODE_PRIVATE)

    var payloadPath: String?
        get() = sp.getString(KEY_PATH, null)
        set(v) = sp.edit().putString(KEY_PATH, v).apply()

    /** Empty string means "use the manifest default". */
    var fontFamily: String
        get() = sp.getString(KEY_FAMILY, "") ?: ""
        set(v) = sp.edit().putString(KEY_FAMILY, v).apply()

    var bodySizeSp: Int
        get() = sp.getInt(KEY_SIZE, 0)
        set(v) = sp.edit().putInt(KEY_SIZE, v).apply()

    var lineSpacing: Float
        get() = sp.getFloat(KEY_SPACING, 0f)
        set(v) = sp.edit().putFloat(KEY_SPACING, v).apply()

    var railOnRight: Boolean
        get() = sp.getBoolean(KEY_RAIL_RIGHT, false)
        set(v) = sp.edit().putBoolean(KEY_RAIL_RIGHT, v).apply()

    var startModuleId: String
        get() = sp.getString(KEY_START, "") ?: ""
        set(v) = sp.edit().putString(KEY_START, v).apply()

    /** Blank a page fully every N turns to clear accumulated ghosting. */
    var fullRefreshEvery: Int
        get() = sp.getInt(KEY_FULL_REFRESH, 6)
        set(v) = sp.edit().putInt(KEY_FULL_REFRESH, v).apply()

    var readerMargin: Int
        get() = sp.getInt(KEY_MARGIN, 20)
        set(v) = sp.edit().putInt(KEY_MARGIN, v).apply()

    var lastSeenBuildId: String
        get() = sp.getString(KEY_LAST_BUILD, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_BUILD, v).apply()

    fun readerPage(docId: String): Int = sp.getInt("page:$docId", 0)

    fun setReaderPage(docId: String, page: Int) =
        sp.edit().putInt("page:$docId", page).apply()

    private companion object {
        const val KEY_PATH = "payloadPath"
        const val KEY_FAMILY = "fontFamily"
        const val KEY_SIZE = "bodySizeSp"
        const val KEY_SPACING = "lineSpacing"
        const val KEY_RAIL_RIGHT = "railOnRight"
        const val KEY_START = "startModule"
        const val KEY_FULL_REFRESH = "fullRefreshEvery"
        const val KEY_MARGIN = "readerMargin"
        const val KEY_LAST_BUILD = "lastSeenBuildId"
    }
}

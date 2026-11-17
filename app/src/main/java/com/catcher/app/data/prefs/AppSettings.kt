package com.catcher.app.data.prefs

import android.content.Context
import com.catcher.app.domain.model.Platform

/**
 * 轻量界面设置。对应电脑端的 data/catcher.yaml 里的那几个可改项：
 * 平台刷新勾选、显示口径这类配置存在这里，其余保持只读。
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("catcher_settings", Context.MODE_PRIVATE)

    /** 勾选参与「全部刷新」的平台；默认全选 */
    var enabledPlatforms: Set<Platform>
        get() {
            val raw = prefs.getString(KEY_ENABLED, null) ?: return Platform.ORDER.toSet()
            if (raw.isBlank()) return emptySet()
            return raw.split(',').mapNotNull { Platform.ofId(it) }.toSet()
        }
        set(value) {
            prefs.edit().putString(KEY_ENABLED, value.joinToString(",") { it.id }).apply()
        }

    /** 列表里是否显示已提交作业（默认隐藏） */
    var showSubmitted: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SUBMITTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SUBMITTED, value).apply()

    /** 只看本学期 */
    var termOnly: Boolean
        get() = prefs.getBoolean(KEY_TERM_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_TERM_ONLY, value).apply()

    private companion object {
        const val KEY_ENABLED = "enabled_platforms"
        const val KEY_SHOW_SUBMITTED = "show_submitted"
        const val KEY_TERM_ONLY = "term_only"
    }
}

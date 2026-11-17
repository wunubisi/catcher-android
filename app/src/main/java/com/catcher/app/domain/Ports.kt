package com.catcher.app.domain

import com.catcher.app.domain.model.FetchOptions
import com.catcher.app.domain.model.FetchResult
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.Progress
import com.catcher.app.domain.model.Session

/**
 * 平台抓取能力。每个平台适配器实现一个。
 *
 * 与电脑端的 domain.Fetcher 对应；[platform] 是稳定标识。
 */
interface Fetcher {
    val platform: Platform

    suspend fun fetch(
        session: Session,
        options: FetchOptions,
        progress: (Progress) -> Unit,
    ): FetchResult
}

/**
 * 平台的登录能力。
 *
 * 移动端的登录形态与电脑端不同：电脑端起一个本地窗口显示二维码，手机上没法扫自己屏幕，
 * 所以二维码平台改成「内嵌网页登录后取 cookie」，密码平台仍然直接调接口。
 */
interface PlatformAdapter : Fetcher {
    /** 手机号 + 密码登录（学习通） */
    val passwordAuth: PasswordAuthenticator? get() = null

    /** 导入浏览器/网页登录得到的 cookie 串 */
    val cookieImporter: CookieImporter? get() = null

    /** 导入平台自己的 session 值（学在重邮） */
    val sessionImporter: SessionImporter? get() = null

    /** 免登录续期；返回 null 表示该平台不支持 */
    val renewer: SessionRenewer? get() = null

    /** 校验一份会话是否仍然可用，并返回脱敏后的账号标识 */
    suspend fun validate(session: Session): String
}

interface PasswordAuthenticator {
    suspend fun loginWithPassword(phone: String, password: String): Session
}

interface CookieImporter {
    /** 传原始 cookie 串，例如 "PHPSESSID=xxx; _JUKU_USER=yyy" */
    suspend fun importCookies(rawCookie: String): Session
}

interface SessionImporter {
    suspend fun importSession(rawSession: String): Session
}

interface SessionRenewer {
    suspend fun renew(session: Session): Session
}

/** 各平台适配器的注册表，由 cmd/app 等价物 CatcherApp 注入 */
class AdapterRegistry(adapters: List<PlatformAdapter>) {
    private val byPlatform: Map<Platform, PlatformAdapter> = adapters.associateBy { it.platform }

    val all: List<PlatformAdapter> = Platform.values().mapNotNull { byPlatform[it] }

    fun of(platform: Platform): PlatformAdapter? = byPlatform[platform]
}

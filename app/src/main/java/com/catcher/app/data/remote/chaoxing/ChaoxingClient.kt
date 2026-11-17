package com.catcher.app.data.remote.chaoxing

import com.catcher.app.data.remote.CookieBag
import com.catcher.app.data.remote.DESKTOP_UA
import com.catcher.app.data.remote.HttpEngine
import com.catcher.app.data.remote.HttpResponse
import com.catcher.app.data.remote.classifyStatus
import com.catcher.app.data.remote.jsonNumberOrNull
import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.CookieImporter
import com.catcher.app.domain.ErrorKind
import com.catcher.app.domain.Fetcher
import com.catcher.app.domain.PasswordAuthenticator
import com.catcher.app.domain.PlatformAdapter
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.Cookie
import com.catcher.app.domain.model.Course
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.FetchOptions
import com.catcher.app.domain.model.FetchResult
import com.catcher.app.domain.model.IssueKind
import com.catcher.app.domain.model.IssueScope
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.Progress
import com.catcher.app.domain.model.Session
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.domain.model.chaoxingDedupKey
import com.catcher.app.domain.toIssueKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * 学习通适配器：密码登录、cookie 导入与作业抓取。
 *
 * 与电脑端 internal/infra/bridge/chaoxing 对应，几条刻意保留的陷阱：
 *
 * - 登录在 passport2.xuexitong.com，数据在 mooc1-api.chaoxing.com，域名分裂；
 * - 登录必须手工跟随 ≤10 跳并逐跳累积 Set-Cookie；
 * - cookie 表不按 host 过滤、不硬编码名字；JSESSIONID 是 host-only；
 * - 登录态只看课程列表的 result，不看 HTTP 状态码（失效时可能是 200 + 登录页 HTML）。
 */
class ChaoxingClient(private val http: HttpEngine) : PlatformAdapter {

    override val platform: Platform = Platform.CHAOXING

    private val baseUrl = "https://mooc1-api.chaoxing.com"
    private val loginBaseUrl = "https://passport2.xuexitong.com"

    private val userAgent = DESKTOP_UA

    override val passwordAuth: PasswordAuthenticator = object : PasswordAuthenticator {
        override suspend fun loginWithPassword(phone: String, password: String): Session =
            login(phone, password)
    }

    override val cookieImporter: CookieImporter = object : CookieImporter {
        override suspend fun importCookies(rawCookie: String): Session {
            val jar = CookieBag.parse(rawCookie)
            if (jar.toList().isEmpty()) {
                throw CatcherException.authRequired("没有解析到任何 cookie")
            }
            val session = newSession(jar)
            // 立即验证一次，让用户当场知道这份 cookie 能不能用
            fetchCourseEntries(jar)
            return session
        }
    }

    override suspend fun validate(session: Session): String {
        val secret = decodeSecret(session)
        fetchCourseEntries(CookieBag(secret.cookies))
        // 学习通没有稳定的展示名字段；界面上用「已登录」
        return ""
    }

    // ---------------------------------------------------------------- 登录

    /**
     * 手机号+密码登录，并累积整条 302 链的 Set-Cookie。
     */
    suspend fun login(phone: String, password: String): Session {
        val p = phone.trim()
        if (p.isEmpty() || password.isEmpty()) {
            throw CatcherException.authFailed("手机号和密码不能为空")
        }
        if (!Regex("""^\d{11}$""").matches(p)) {
            throw CatcherException.authFailed("学习通账号是 11 位手机号")
        }

        val uname = ChaoxingCrypto.encrypt(p)
        val encryptedPassword = ChaoxingCrypto.encrypt(password)

        val form = listOf(
            "fid" to "-1",
            "uname" to uname,
            "password" to encryptedPassword,
            "refer" to "https://i.xuexitong.com",
            "t" to "true",
            "forbidotherlogin" to "0",
            "validate" to "",
            "doubleFactorLogin" to "0",
            "independentId" to "0",
            "independentNameId" to "0",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val jar = CookieBag()
        val response = followLogin(form, jar)
        checkLoginResult(response.text)
        if (jar.toList().isEmpty()) {
            throw CatcherException.authFailed("登录成功但没有拿到 cookie")
        }
        return newSession(jar)
    }

    /**
     * 逐跳跟随登录重定向，每跳都收 Set-Cookie；超过 10 跳直接报错。
     */
    private suspend fun followLogin(formBody: String, jar: CookieBag): HttpResponse {
        var rawUrl = loginBaseUrl + "/fanyalogin"
        var method = "POST"
        var body: String? = formBody

        repeat(MAX_LOGIN_REDIRECTS) { hop ->
            val headers = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to userAgent,
            )
            if (method == "POST") {
                headers["Content-Type"] = "application/x-www-form-urlencoded"
                headers["Origin"] = loginBaseUrl
                headers["Referer"] = "$loginBaseUrl/"
            }
            if (jar.header().isNotEmpty()) headers["Cookie"] = jar.header()

            val resp = http.execute(
                method = method,
                url = rawUrl,
                headers = headers,
                body = body?.let { HttpEngine.formBody(it) },
            )
            jar.add(resp.setCookies)

            if (!resp.isRedirect) {
                if (resp.code >= 400) throw classifyStatus(resp.code, redirectMeansAuthExpired = false)
                return resp
            }
            if (hop == MAX_LOGIN_REDIRECTS - 1) {
                throw CatcherException.authFailed("登录重定向超过 $MAX_LOGIN_REDIRECTS 跳")
            }
            val next = resp.location
            if (next.isEmpty()) throw CatcherException.authFailed("登录重定向缺少 Location")
            rawUrl = next
            // 302/303 按浏览器语义转成 GET；307/308 保留 POST
            if (resp.code != 307 && resp.code != 308) {
                method = "GET"
                body = null
            }
        }
        throw CatcherException.authFailed("登录重定向超过 $MAX_LOGIN_REDIRECTS 跳")
    }

    /** 成功是 {"status": true}，失败读 msg2 */
    private fun checkLoginResult(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<")) {
            throw CatcherException.authFailed("登录返回页面而不是 JSON")
        }
        val out = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw CatcherException.authFailed("登录响应不是 JSON: ${e.message}")
        }
        if (out.optBoolean("status", false)) return
        val message = out.optString("msg2").trim().ifEmpty { out.optString("msg").trim() }
            .ifEmpty { "账号或密码错误" }
        throw CatcherException.authFailed(message)
    }

    // ---------------------------------------------------------------- 抓取

    override suspend fun fetch(
        session: Session,
        options: FetchOptions,
        progress: (Progress) -> Unit,
    ): FetchResult {
        val secret = decodeSecret(session)
        val jar = CookieBag(secret.cookies)
        val now = options.now()

        progress(Progress(platform, "courses", message = "读取课程列表"))
        val entries = fetchCourseEntries(jar)
        persistCookies(jar, options)

        val names = entries.filter { it.name.isNotBlank() }.associate { it.id to it.name }

        progress(Progress(platform, "assignments", message = "读取作业列表"))
        val page = fetchWorkList(jar)

        val warnings = page.warnings.toMutableList()
        val assignments = mutableListOf<Assignment>()
        val courses = mutableListOf<Course>()

        val seenCourseIds = mutableSetOf<String>()
        for (entry in entries) {
            if (entry.id.isEmpty() || !seenCourseIds.add(entry.id)) continue
            courses += Course(
                platform = platform,
                externalId = entry.id,
                name = entry.name.trim(),
                active = true,
            )
        }

        for ((index, item) in page.items.withIndex()) {
            progress(
                Progress(
                    platform = platform,
                    phase = "assignments",
                    current = index + 1,
                    total = page.items.size,
                    message = item.title,
                )
            )

            var detail: ChaoxingHtml.DetailDeadline? = null
            if (item.status != SubmissionStatus.SUBMITTED) {
                try {
                    detail = ChaoxingHtml.parseDetailDeadline(fetchDetail(item, jar))
                } catch (e: CatcherException) {
                    // 认证失效/取消是整次抓取的问题，其余错误只影响这一条
                    if (e.kind == ErrorKind.AUTH_EXPIRED || e.kind == ErrorKind.CANCELED) throw e
                    warnings += FetchIssue(
                        scope = IssueScope.ASSIGNMENT,
                        ref = item.courseId,
                        kind = e.kind.toIssueKind(),
                        message = "作业 \"${item.title}\" 详情抓取失败: ${e.message}",
                    )
                }
                // 详情页没有截止时间是常态，不算问题（有剩余时间的条目除外）
                if (detail == null && !item.hasRemaining) {
                    warnings += FetchIssue(
                        scope = IssueScope.ASSIGNMENT,
                        ref = item.courseId,
                        kind = IssueKind.PARSE,
                        message = "作业 \"${item.title}\" 详情页没有截止时间",
                    )
                }
            }

            var deadline: Long? = null
            var source: DeadlineSource = DeadlineSource.UNKNOWN
            var raw = ""
            if (item.status != SubmissionStatus.SUBMITTED || item.hasRemaining) {
                val resolved = ChaoxingHtml.resolveDeadline(item, detail, now)
                deadline = resolved.deadline
                source = resolved.source
                raw = resolved.raw
                warnings += resolved.warnings
            }
            // 已完成条目常常拿不到截止时间，按未知保留，不猜测

            var courseName = names[item.courseId].orEmpty()
            if (courseName.isEmpty()) {
                courseName = item.courseName.ifEmpty { "课程 ${item.courseId}" }
                warnings += FetchIssue(
                    scope = IssueScope.ASSIGNMENT,
                    ref = item.courseId,
                    kind = IssueKind.PARSE,
                    message = "课程 ${item.courseId} 未在课程列表中找到，使用列表页名称 \"$courseName\"",
                )
            }

            assignments += Assignment(
                platform = platform,
                courseExternalId = item.courseId,
                courseName = courseName,
                dedupKey = chaoxingDedupKey(item.courseId, item.title),
                title = item.title,
                status = item.status,
                deadline = deadline,
                deadlineSource = source,
                deadlineRaw = raw,
                detailUrl = item.detailUrl,
            )
        }

        // 逐条详情也可能轮换 cookie，结束时再落盘一次
        persistCookies(jar, options)

        progress(
            Progress(
                platform = platform,
                phase = "done",
                current = page.items.size,
                total = page.items.size,
                message = "抓取完成",
            )
        )
        return FetchResult(
            courses = courses,
            assignments = assignments,
            warnings = warnings,
            complete = page.complete,
        )
    }

    // ---------------------------------------------------------------- 接口

    data class CourseEntry(val id: String, val name: String)

    /**
     * 读取课程列表。它同时承担登录态判定：
     * result=1 已登录，result=0 会话失效；绝不能拿 HTTP 200 或课程数当判据。
     */
    private suspend fun fetchCourseEntries(jar: CookieBag): List<CourseEntry> {
        val resp = get(baseUrl + "/mycourse/backclazzdata?view=json&rss=1", jar)

        val trimmed = resp.text.trim()
        if (trimmed.startsWith("<")) {
            // 会话失效的真实形态之一：200 + 登录页 HTML
            throw CatcherException.authExpired("课程列表返回登录页")
        }
        val out = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw CatcherException.changed("课程列表不是 JSON: ${e.message}")
        }

        val hasResult = out.has("result") && !out.isNull("result")
        val resultRaw = if (!hasResult) "" else jsonNumberOrNull(out.opt("result")) ?: ""
        when (resultRaw) {
            "1" -> Unit
            "0" -> throw CatcherException.authExpired(
                out.optString("msg").trim().ifEmpty { "请重新登录" }
            )
            "" -> throw CatcherException.changed("课程列表缺少 result 字段")
            else -> throw CatcherException.changed("课程列表 result=$resultRaw")
        }

        val entries = mutableListOf<CourseEntry>()
        val channels = out.optJSONArray("channelList") ?: JSONArray()
        for (i in 0 until channels.length()) {
            val channel = channels.optJSONObject(i) ?: continue
            val data = channel.optJSONObject("content")
                ?.optJSONObject("course")
                ?.optJSONArray("data") ?: continue
            for (j in 0 until data.length()) {
                val entry = data.optJSONObject(j) ?: continue
                val id = jsonNumberOrNull(entry.opt("id")).orEmpty()
                if (id.isEmpty()) continue
                entries += CourseEntry(id = id, name = entry.optString("name"))
            }
        }
        return entries
    }

    /** 请求并解析作业列表。失败必须显式抛出，绝不返回空列表冒充成功。 */
    private suspend fun fetchWorkList(jar: CookieBag): ChaoxingHtml.WorkListPage {
        val resp = get(baseUrl + "/work/stu-work?ut=s", jar)
        val body = resp.text
        if (body.contains("请重新登录") || body.contains("fanyalogin")) {
            throw CatcherException.authExpired("作业列表返回登录页")
        }
        return ChaoxingHtml.parseWorkList(body)
    }

    /**
     * 取一条作业的详情页。
     *
     * 作业列表给的详情链接是 Android 接口路径 mtaskmsgspecial，服务器会 302 到
     * H5 页面 —— 这是正常跳转，不是登录失效；只有跳到 passport 登录页才算会话失效。
     */
    private suspend fun fetchDetail(item: ChaoxingHtml.WorkItem, jar: CookieBag): String {
        var rawUrl = item.detailUrl
        if (!rawUrl.startsWith("http")) {
            rawUrl = runCatching { URI(baseUrl).resolve(rawUrl).toString() }.getOrDefault(rawUrl)
        }
        repeat(MAX_DETAIL_REDIRECTS + 1) {
            val resp = get(rawUrl, jar)
            if (resp.isRedirect) {
                val location = resp.location
                if (location.isEmpty()) {
                    throw CatcherException.changed("详情页重定向缺少 Location（HTTP ${resp.code}）")
                }
                if (isLoginUrl(location)) {
                    throw CatcherException.authExpired("详情页被重定向到登录页")
                }
                rawUrl = location
                return@repeat
            }
            val body = resp.text
            if (body.contains("请重新登录") || body.contains("fanyalogin")) {
                throw CatcherException.authExpired("作业详情返回登录页")
            }
            return body
        }
        throw CatcherException.changed("详情页重定向超过 $MAX_DETAIL_REDIRECTS 跳")
    }

    private suspend fun get(rawUrl: String, jar: CookieBag): HttpResponse {
        val headers = mutableMapOf(
            "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
            "User-Agent" to userAgent,
        )
        if (jar.header().isNotEmpty()) headers["Cookie"] = jar.header()
        val resp = http.execute("GET", rawUrl, headers)
        jar.add(resp.setCookies)
        if (resp.code >= 400) throw classifyStatus(resp.code)
        return resp
    }

    private fun isLoginUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        return host.contains("passport") || path.contains("login")
    }

    // ---------------------------------------------------------------- 会话

    data class ChaoxingSecret(val cookies: List<Cookie>, val updatedAt: Long)

    private fun newSession(jar: CookieBag): Session {
        val json = JSONObject().apply {
            put("cookies", JSONArray().apply {
                jar.toList().forEach { c ->
                    put(JSONObject().apply {
                        put("name", c.name)
                        put("value", c.value)
                    })
                }
            })
            put("updated_at", System.currentTimeMillis())
        }
        return Session(platform = platform, secretJson = json.toString())
    }

    private fun decodeSecret(session: Session): ChaoxingSecret {
        if (session.secretJson.isEmpty()) throw CatcherException.authRequired("会话为空")
        val obj = try {
            JSONObject(session.secretJson)
        } catch (e: Exception) {
            throw CatcherException.authRequired("解析学习通会话失败: ${e.message}")
        }
        val arr = obj.optJSONArray("cookies") ?: JSONArray()
        val cookies = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("name")
            if (name.isEmpty()) null else Cookie(name, c.optString("value"))
        }
        if (cookies.isEmpty()) throw CatcherException.authRequired("会话缺少 cookie")
        return ChaoxingSecret(cookies, obj.optLong("updated_at", 0L))
    }

    /** 在 cookie 发生变化时立即交给上层加密保存 */
    private suspend fun persistCookies(jar: CookieBag, options: FetchOptions) {
        if (!jar.changed) return
        val handler = options.onSessionUpdated ?: return
        handler(newSession(jar))
        jar.markPersisted()
    }

    private companion object {
        const val MAX_LOGIN_REDIRECTS = 10
        const val MAX_DETAIL_REDIRECTS = 5
    }
}

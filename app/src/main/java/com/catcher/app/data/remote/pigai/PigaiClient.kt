package com.catcher.app.data.remote.pigai

import com.catcher.app.data.remote.CookieBag
import com.catcher.app.data.remote.DESKTOP_UA
import com.catcher.app.data.remote.HttpEngine
import com.catcher.app.data.remote.HttpResponse
import com.catcher.app.data.remote.classifyStatus
import com.catcher.app.data.remote.parseUnixSeconds
import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.CookieImporter
import com.catcher.app.domain.ErrorKind
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
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.Session
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.domain.model.normalizeTitle
import com.catcher.app.domain.model.pigaiDedupKey
import com.catcher.app.domain.toIssueKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 批改网适配器。
 *
 * 与电脑端 internal/infra/bridge/pigai 对应，几条刻意保留的陷阱：
 *
 * - 接口一律返回 HTTP 200，成败只看 JSON 信封的 error 字段；error=302 表示未登录；
 * - 所有请求必须带桌面浏览器 UA 和 _display=json；
 * - _JUKU_USER 是 URL 编码的 JSON，整体当不透明凭据回放，不解析；
 * - 详情响应里带老师的密码摘要、邮箱、IP 与 CSRF token，只解析白名单字段；
 * - 截止时间是 10 位 Unix 秒；"0" 表示未设置，不是 1970 年；
 * - 分页未验证，只抓第一页，所以 complete 恒为 false（不能触发软删除）。
 */
class PigaiClient(private val http: HttpEngine) : PlatformAdapter {

    override val platform: Platform = Platform.PIGAI

    private val baseUrl = "https://www.pigai.org"

    override val cookieImporter: CookieImporter = object : CookieImporter {
        override suspend fun importCookies(rawCookie: String): Session {
            val jar = CookieBag.parse(rawCookie)
            if (jar.toList().isEmpty()) throw CatcherException.authRequired("没有解析到任何 cookie")
            val session = newSession(jar)
            // 用未完成列表验证登录态：error=302 就是没登录
            fetchPending(jar)
            return session
        }
    }

    override suspend fun validate(session: Session): String {
        val secret = decodeSecret(session)
        fetchPending(CookieBag(secret.cookies))
        return ""
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
        val run = FetchRunState(now)

        // 未完成列表同时带出班级表，班级表只在这一个响应里出现
        progress(Progress(platform, "courses", message = "读取班级与未完成作业"))
        val pending = try {
            fetchPending(jar)
        } catch (e: CatcherException) {
            // 主数据源失败就整次失败，不能返回空列表冒充「没有作业」
            throw e
        }
        persistCookies(jar, options)
        run.registerClasses(pending.stduntClass)
        run.observeServerNow(pending.nowTime)

        progress(Progress(platform, "completed", message = "读取已完成作文"))
        val completed = try {
            fetchCompleted(jar)
        } catch (e: CatcherException) {
            if (e.kind == ErrorKind.AUTH_EXPIRED || e.kind == ErrorKind.CANCELED) throw e
            // 已完成列表失败不影响未完成部分，降级成警告
            run.warn(
                IssueScope.PLATFORM, "", e.kind,
                "已完成作文列表读取失败，本次只写入未完成作业: ${e.message}",
            )
            null
        }

        val items = mergeItems(pending.requestInfo, completed?.essayList ?: emptyList())
        val start = semesterStart(now)

        for ((index, item) in items.withIndex()) {
            progress(
                Progress(
                    platform = platform,
                    phase = "details",
                    current = index + 1,
                    total = items.size,
                    message = item.title,
                )
            )
            // 已完成列表的标题只是「第N号 作文」，只有本学期的条目才值得再调一次详情
            val wantDetail = item.status == SubmissionStatus.UNSUBMITTED || run.isCurrentTerm(item, start)
            var detail: DetailData? = null
            if (wantDetail) {
                detail = try {
                    fetchDetail(item.detailUrl, jar)
                } catch (e: CatcherException) {
                    if (e.kind == ErrorKind.AUTH_EXPIRED || e.kind == ErrorKind.CANCELED) throw e
                    // 单条详情失败不拖垮整次刷新，作业保留并退到兜底课程
                    run.warn(
                        IssueScope.ASSIGNMENT, pigaiDedupKey(item.requestId), e.kind,
                        "作业 \"${item.title}\" 详情抓取失败: ${e.message}",
                    )
                    null
                }
            }
            run.toAssignment(item, detail)
        }

        persistCookies(jar, options)

        progress(
            Progress(
                platform = platform,
                phase = "done",
                current = items.size,
                total = items.size,
                message = "抓取完成",
            )
        )
        return FetchResult(
            courses = run.courseEntities(),
            assignments = run.assignments,
            warnings = run.warnings,
            serverNow = run.serverNow,
            complete = false,
            courseListComplete = false,
            // 只抓得到第一页是平台限制而不是本次出错，刷新记录按成功记
            partialByDesign = true,
        )
    }

    // ---------------------------------------------------------------- 接口

    private data class RequestInfo(
        val requestId: String,
        val essayTitle: String,
        val createTime: String,
        val validEndTime: String,
    )

    private data class PigaiClass(val classId: String, val name: String, val isDel: String)

    private data class EssayItem(
        val essayId: String,
        val requestId: String,
        val title: String,
        val ctime: String,
        val score: String,
        val stuClass: String,
        val validEndTime: String,
        val createTime: String,
        val version: String,
    )

    private data class PendingData(
        val requestInfo: List<RequestInfo>,
        val stduntClass: List<PigaiClass>,
        val nowTime: Long,
    )

    private data class CompletedData(val essayList: List<EssayItem>)

    private data class DetailRequest(
        val essayTitle: String,
        val essayTopic: String,
        val validEndTime: String,
    )

    private data class DetailData(
        val request: DetailRequest,
        val tClass: List<PigaiClass>,
        val essayStuClass: String,
        val nowTime: Long,
    )

    private suspend fun fetchPending(jar: CookieBag): PendingData {
        val env = fetchEnvelope(
            apiUrl(mapOf("c" to "write", "a" to "waitComplete", "bf" to "4")), jar,
        )
        val data = env.data
        if (data == null) return PendingData(emptyList(), emptyList(), 0)
        val requestInfo = data.optJSONArray("requestInfo").toObjects().map { obj ->
            RequestInfo(
                requestId = obj.optString("request_id"),
                essayTitle = obj.optString("essay_title"),
                createTime = obj.optString("create_time"),
                validEndTime = obj.optString("valid_end_time"),
            )
        }
        val classes = data.optJSONArray("stduntClass").toObjects().map { obj ->
            PigaiClass(
                classId = obj.optString("class_id"),
                name = obj.optString("class"),
                isDel = obj.optString("isdel"),
            )
        }
        return PendingData(requestInfo, classes, flexLong(data.opt("now_time")))
    }

    private suspend fun fetchCompleted(jar: CookieBag): CompletedData {
        val env = fetchEnvelope(apiUrl(mapOf("c" to "write")), jar)
        val data = env.data ?: return CompletedData(emptyList())
        val essays = data.optJSONArray("essayList").toObjects().map { obj ->
            EssayItem(
                essayId = obj.optString("essay_id"),
                requestId = obj.optString("request_id"),
                title = obj.optString("title"),
                ctime = obj.optString("ctime"),
                score = obj.optString("score"),
                stuClass = obj.optString("stu_class"),
                validEndTime = obj.optString("valid_end_time"),
                createTime = obj.optString("create_time"),
                version = obj.optString("version"),
            )
        }
        return CompletedData(essays)
    }

    private suspend fun fetchDetail(detailUrl: String, jar: CookieBag): DetailData {
        // 详情链接存的是人看的页面，抓数据要换成 _display=json 的同一个地址
        val jsonUrl = withJsonDisplay(detailUrl)
        val env = fetchEnvelope(jsonUrl, jar)
        val data = env.data ?: throw CatcherException.changed("详情响应缺少 data")
        val request = data.optJSONObject("request")
        val essay = data.optJSONObject("essay")
        return DetailData(
            request = DetailRequest(
                essayTitle = request?.optString("essay_title").orEmpty(),
                essayTopic = request?.optString("essay_topic").orEmpty(),
                validEndTime = request?.optString("valid_end_time").orEmpty(),
            ),
            tClass = data.optJSONArray("tClass").toObjects().map { obj ->
                PigaiClass(
                    classId = obj.optString("class_id"),
                    name = obj.optString("class"),
                    isDel = obj.optString("isdel"),
                )
            },
            essayStuClass = essay?.optString("stu_class").orEmpty(),
            nowTime = flexLong(data.opt("now_time")),
        )
    }

    /** 批改网所有 JSON 接口的外层信封 */
    private class Envelope(val data: JSONObject?, val error: Int, val errorDes: String)

    private suspend fun fetchEnvelope(rawUrl: String, jar: CookieBag): Envelope {
        val resp = get(rawUrl, jar)
        val obj = try {
            JSONObject(resp.text)
        } catch (e: Exception) {
            throw CatcherException.changed("响应不是 JSON: ${e.message}")
        }
        val error = flexInt(obj.opt("error"))
        val env = Envelope(
            data = obj.optJSONObject("data"),
            error = error,
            errorDes = obj.optString("error_des").trim(),
        )
        when {
            error == 0 -> Unit
            // error=302 表示未登录，不能当成「今天没作业」
            error == 302 -> throw CatcherException.authExpired(env.describe())
            else -> throw CatcherException.business("${env.describe()} (error=$error)")
        }
        return env
    }

    private fun Envelope.describe(): String = errorDes.ifEmpty { "平台未给出原因" }

    private suspend fun get(rawUrl: String, jar: CookieBag): HttpResponse {
        val headers = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to DESKTOP_UA,
            "Referer" to "$baseUrl/",
        )
        if (jar.header().isNotEmpty()) headers["Cookie"] = jar.header()
        val resp = http.execute("GET", rawUrl, headers)
        jar.add(resp.setCookies)
        if (resp.code >= 400) throw classifyStatus(resp.code)
        return resp
    }

    /** 拼接带 _display=json 的接口地址 */
    private fun apiUrl(query: Map<String, String>): String {
        val q = LinkedHashMap<String, String>()
        q["_display"] = "json"
        q.putAll(query)
        return "$baseUrl/index.php?" + q.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    /** 拼接用户点开能看的页面地址，不带 _display */
    private fun pageUrl(query: Map<String, String>): String =
        "$baseUrl/index.php?" + query.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    /** 给页面地址补上 _display=json，同一套 query 换个视图取数据 */
    private fun withJsonDisplay(rawUrl: String): String {
        val separator = if (rawUrl.contains('?')) "&" else "?"
        if (rawUrl.contains("_display=json")) return rawUrl
        return rawUrl + separator + "_display=json"
    }

    // ---------------------------------------------------------------- 合并与映射

    private data class MergedItem(
        val requestId: String,
        val status: SubmissionStatus,
        val title: String,
        val essayId: String,
        val stuClass: String,
        val deadlineRaw: String,
        val createRaw: String,
        val detailUrl: String,
    )

    /** 按 request_id 合并两个列表：未完成优先，两边都有算未提交 */
    private fun mergeItems(pending: List<RequestInfo>, completed: List<EssayItem>): List<MergedItem> {
        val items = mutableListOf<MergedItem>()
        val index = mutableMapOf<String, MergedItem>()

        for (p in pending) {
            if (p.requestId.isEmpty() || index.containsKey(p.requestId)) continue
            val item = MergedItem(
                requestId = p.requestId,
                status = SubmissionStatus.UNSUBMITTED,
                title = p.essayTitle,
                essayId = "",
                stuClass = "",
                deadlineRaw = p.validEndTime,
                createRaw = p.createTime,
                detailUrl = pageUrl(mapOf("c" to "v2", "a" to "write", "rid" to p.requestId)),
            )
            index[p.requestId] = item
            items += item
        }

        // 同一个 request 出现多条时取最新一版：按提交时间、再按版本号挑选
        val best = mutableMapOf<String, EssayItem>()
        for (e in completed) {
            if (e.requestId.isEmpty()) continue
            val prev = best[e.requestId]
            if (prev == null || newerEssay(e, prev)) best[e.requestId] = e
        }

        val seen = mutableSetOf<String>()
        for (e in completed) {
            if (e.requestId.isEmpty() || !seen.add(e.requestId)) continue
            val chosen = best[e.requestId] ?: e

            val existing = index[e.requestId]
            if (existing != null) {
                // 未完成列表优先级更高，只补上班级名
                if (existing.stuClass.isEmpty() && chosen.stuClass.isNotEmpty()) {
                    index[e.requestId] = existing.copy(stuClass = chosen.stuClass)
                    val at = items.indexOfFirst { it.requestId == e.requestId }
                    if (at >= 0) items[at] = index[e.requestId]!!
                }
                continue
            }
            val item = MergedItem(
                requestId = e.requestId,
                status = SubmissionStatus.SUBMITTED,
                title = chosen.title,
                essayId = chosen.essayId,
                stuClass = chosen.stuClass,
                deadlineRaw = chosen.validEndTime,
                createRaw = chosen.createTime,
                detailUrl = pageUrl(
                    mapOf("c" to "v2", "a" to "view", "eid" to chosen.essayId, "rid" to e.requestId)
                ),
            )
            index[e.requestId] = item
            items += item
        }
        return items
    }

    /** 提交时间大的优先，其次版本号 */
    private fun newerEssay(a: EssayItem, b: EssayItem): Boolean {
        val at = numericOrZero(a.ctime)
        val bt = numericOrZero(b.ctime)
        if (at != bt) return at > bt
        return numericOrZero(a.version) > numericOrZero(b.version)
    }

    private fun numericOrZero(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        var out = 0L
        for (ch in s) {
            if (!ch.isDigit()) return 0
            out = out * 10 + (ch - '0')
        }
        return out
    }

    // ---------------------------------------------------------------- 抓取状态

    private inner class FetchRunState(private val now: Long) {
        val assignments = mutableListOf<Assignment>()
        val warnings = mutableListOf<FetchIssue>()
        var serverNow: Long? = null

        private val courses = LinkedHashMap<String, Course>()
        private val byName = mutableMapOf<String, Course>()
        private val order = mutableListOf<String>()

        fun warn(scope: IssueScope, ref: String, kind: ErrorKind, message: String) {
            warnings += FetchIssue(scope, ref, kind.toIssueKind(), message)
        }

        fun warn(scope: IssueScope, ref: String, kind: IssueKind, message: String) {
            warnings += FetchIssue(scope, ref, kind, message)
        }

        fun registerClasses(classes: List<PigaiClass>) {
            for (cls in classes) {
                if (cls.classId.isEmpty() || cls.isDel == "1") continue
                addCourse(cls.classId, cls.name)
            }
        }

        private fun addCourse(id: String, name: String): Course {
            val course = Course(platform = Platform.PIGAI, externalId = id, name = name.trim())
            courses[id] = course
            if (!order.contains(id)) order += id
            val key = normalizeTitle(name)
            if (key.isNotEmpty()) byName.putIfAbsent(key, course)
            return course
        }

        /** 用详情里的 class_id 定位班级；班级表里没有就按详情补一条 */
        private fun courseById(classId: String, className: String): Course {
            val id = classId.trim()
            if (id.isEmpty()) return unknownCourse()
            courses[id]?.let { return it }
            val name = className.trim().ifEmpty { "班级 $id" }
            warn(
                IssueScope.COURSE, id, IssueKind.PARSE,
                "班级 $id 不在班级列表中，已按作业详情补建",
            )
            return addCourse(id, name)
        }

        /** 用班级名配对。已完成列表只有名字，没有 class_id */
        private fun courseByName(name: String): Course {
            val key = normalizeTitle(name)
            if (key.isEmpty()) return unknownCourse()
            byName[key]?.let { return it }
            val id = "pigai:class:$key"
            courses[id]?.let { return it }
            warn(
                IssueScope.COURSE, id, IssueKind.PARSE,
                "班级 \"$key\" 未出现在班级列表中，已单独建课",
            )
            return addCourse(id, name)
        }

        private fun unknownCourse(): Course {
            courses[UNKNOWN_CLASS_ID]?.let { return it }
            warn(
                IssueScope.COURSE, UNKNOWN_CLASS_ID, IssueKind.PARSE,
                "有作业拿不到班级归属，已归入$UNKNOWN_CLASS_NAME",
            )
            return addCourse(UNKNOWN_CLASS_ID, UNKNOWN_CLASS_NAME)
        }

        fun courseEntities(): List<Course> = order.mapNotNull { courses[it] }

        fun observeServerNow(sec: Long) {
            if (sec <= 0 || serverNow != null) return
            serverNow = sec * 1000
        }

        /** 平台没有学期字段，按 9 月 1 日划线；时间拿不到的按本学期处理 */
        fun isCurrentTerm(item: MergedItem, start: Long): Boolean {
            var raw = item.deadlineRaw
            if (raw.isEmpty() || raw == "0") raw = item.createRaw
            val parsed = runCatching { parseUnixSeconds(raw) }.getOrNull() ?: return true
            return parsed >= start
        }

        fun toAssignment(item: MergedItem, detail: DetailData?) {
            var title = item.title.trim()
            var deadlineRaw = item.deadlineRaw
            var deadline = parseDeadline(item.deadlineRaw, pigaiDedupKey(item.requestId))
            var description = ""

            val course: Course
            if (item.status == SubmissionStatus.UNSUBMITTED) {
                var fromDetail: Course? = null
                if (detail != null) {
                    detail.request.essayTitle.trim().takeIf { it.isNotEmpty() }?.let { title = it }
                    description = stripHtml(detail.request.essayTopic)
                    detail.tClass.firstOrNull()?.let { cls ->
                        fromDetail = courseById(cls.classId, cls.name)
                    }
                }
                course = fromDetail ?: if (item.stuClass.isNotEmpty()) {
                    courseByName(item.stuClass)
                } else {
                    unknownCourse()
                }
            } else {
                if (detail != null) {
                    detail.request.essayTitle.trim().takeIf { it.isNotEmpty() }?.let { title = it }
                    val d = parseDeadline(detail.request.validEndTime, pigaiDedupKey(item.requestId))
                    if (d != null) {
                        deadline = d
                        deadlineRaw = detail.request.validEndTime
                    }
                    description = stripHtml(detail.request.essayTopic)
                }
                var className = item.stuClass.trim()
                if (className.isEmpty() && detail != null) className = detail.essayStuClass.trim()
                course = courseByName(className)
            }
            detail?.let { observeServerNow(it.nowTime) }

            if (title.isEmpty()) title = "第${item.requestId}号 作文"

            assignments += Assignment(
                platform = Platform.PIGAI,
                courseExternalId = course.externalId,
                courseName = course.name,
                externalId = item.requestId,
                dedupKey = pigaiDedupKey(item.requestId),
                title = title,
                status = item.status,
                deadline = deadline,
                deadlineSource = if (deadline != null) DeadlineSource.PLATFORM_EXACT else DeadlineSource.UNKNOWN,
                deadlineRaw = deadlineRaw,
                detailUrl = item.detailUrl,
                description = description,
            )
        }

        /** 解析截止时间。解析失败只记告警并按未知处理，不猜测 */
        private fun parseDeadline(raw: String, ref: String): Long? =
            try {
                parseUnixSeconds(raw)
            } catch (e: CatcherException) {
                warn(IssueScope.ASSIGNMENT, ref, IssueKind.PARSE, "截止时间无法解析: ${e.message}")
                null
            }
    }

    private fun stripHtml(s: String): String {
        if (s.isEmpty()) return ""
        val text = s.replace(Regex("(?s)<[^>]*>"), " ")
        return decodeEntities(text).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    // ---------------------------------------------------------------- 会话

    private data class PigaiSecret(val cookies: List<Cookie>, val updatedAt: Long)

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

    private fun decodeSecret(session: Session): PigaiSecret {
        if (session.secretJson.isEmpty()) throw CatcherException.authRequired("会话为空")
        val obj = try {
            JSONObject(session.secretJson)
        } catch (e: Exception) {
            throw CatcherException.authRequired("解析批改网会话失败: ${e.message}")
        }
        val arr = obj.optJSONArray("cookies") ?: JSONArray()
        val cookies = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("name")
            if (name.isEmpty()) null else Cookie(name, c.optString("value"))
        }
        if (cookies.isEmpty()) throw CatcherException.authRequired("会话缺少 cookie")
        return PigaiSecret(cookies, obj.optLong("updated_at", 0))
    }

    private suspend fun persistCookies(jar: CookieBag, options: FetchOptions) {
        if (!jar.changed) return
        val handler = options.onSessionUpdated ?: return
        handler(newSession(jar))
        jar.markPersisted()
    }

    private companion object {
        const val UNKNOWN_CLASS_ID = "pigai:class:unknown"
        const val UNKNOWN_CLASS_NAME = "批改网未分类"

        fun semesterStart(now: Long): Long {
            val z = Instant.ofEpochMilli(now).atZone(SHANGHAI)
            val year = if (z.monthValue < 9) z.year - 1 else z.year
            return ZonedDateTime.of(year, 9, 1, 0, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli()
        }
    }
}

/** org.json 的可空数组遍历助手 */
private fun JSONArray?.toObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        optJSONObject(i)?.let { out += it }
    }
    return out
}

/** 平台字段类型不稳定：数字、数字字符串、空串、null 都接受，读不出当 0 */
private fun flexInt(value: Any?): Int = when (value) {
    null, JSONObject.NULL -> 0
    is Number -> value.toInt()
    else -> value.toString().trim().toIntOrNull() ?: 0
}

private fun flexLong(value: Any?): Long = when (value) {
    null, JSONObject.NULL -> 0
    is Number -> value.toLong()
    else -> value.toString().trim().let { s ->
        if (s.isEmpty() || s == "null") 0
        else s.filter { it.isDigit() }.toLongOrNull() ?: 0
    }
}

package com.catcher.app.data.remote.tronclass

import com.catcher.app.data.remote.DESKTOP_UA
import com.catcher.app.data.remote.HttpEngine
import com.catcher.app.data.remote.HttpResponse
import com.catcher.app.data.remote.classifyStatus
import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.ErrorKind
import com.catcher.app.domain.PlatformAdapter
import com.catcher.app.domain.SessionImporter
import com.catcher.app.domain.model.Assignment
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
import com.catcher.app.domain.model.normalizeTitle
import com.catcher.app.domain.model.tronclassDedupKey
import com.catcher.app.domain.toIssueKind
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.Base64

/**
 * 学在重邮适配器：浏览器会话导入与作业抓取。
 *
 * 与电脑端 internal/infra/bridge/tronclass 对应，几条刻意保留的陷阱：
 *
 * - 数据全在 lms.tc.cqupt.edu.cn，站点只监听 80（明文），不做 CAS 自动登录；
 * - 每个 200 都轮换 session；401/302 下发的匿名 session 绝不能覆盖已保存值；
 * - 作业列表越界页会重复最后一页，翻页只能用 pages 终止；
 * - 作业项没有 course_id，课程归属只能从请求 URL 带出。
 */
class TronclassClient(private val http: HttpEngine) : PlatformAdapter {

    override val platform: Platform = Platform.TRONCLASS

    private val baseUrl = "http://lms.tc.cqupt.edu.cn"
    private val userAgent = DESKTOP_UA

    override val sessionImporter: SessionImporter = object : SessionImporter {
        override suspend fun importSession(rawSession: String): Session {
            val value = normalizeSessionValue(rawSession)
            if (value.isEmpty()) throw CatcherException.authRequired("会话不能为空")
            if (value.length > MAX_SESSION_BYTES) {
                throw CatcherException.authRequired("会话长度超过 $MAX_SESSION_BYTES 字节")
            }
            // 验证接口是 /api/todos：200 + JSON 才算登录有效
            val resp = request("GET", "/api/todos", null, value)
            if (!isJsonResponse(resp)) {
                throw CatcherException.changed("验证会话时返回的不是 JSON")
            }
            val latest = sessionFromSetCookies(resp.setCookies) ?: value
            return newSession(latest, parseUid(latest))
        }
    }

    override suspend fun validate(session: Session): String {
        val secret = decodeSecret(session)
        val resp = request("GET", "/api/todos", null, secret.session)
        if (!isJsonResponse(resp)) throw CatcherException.changed("验证会话时返回的不是 JSON")
        return secret.userId
    }

    // ---------------------------------------------------------------- 抓取

    override suspend fun fetch(
        session: Session,
        options: FetchOptions,
        progress: (Progress) -> Unit,
    ): FetchResult {
        val secret = decodeSecret(session)
        val state = FetchState(this, secret.session, secret.userId, options)

        progress(Progress(platform, "courses", message = "读取当前学期"))
        val semesterId = state.activeSemester()

        progress(Progress(platform, "courses", message = "读取课程列表"))
        val (courses, courseListComplete, courseWarnings) = state.listCourses(semesterId)

        val warnings = courseWarnings.toMutableList()
        val failed = mutableListOf<String>()
        val assignments = mutableListOf<Assignment>()

        for ((index, course) in courses.withIndex()) {
            progress(
                Progress(
                    platform = platform,
                    phase = "assignments",
                    current = index + 1,
                    total = courses.size,
                    message = course.name,
                )
            )
            try {
                val (items, warns) = state.listAssignments(course)
                warnings += warns
                assignments += items
            } catch (e: CatcherException) {
                // 认证失效、取消是整次抓取的问题，本次结果不落库
                if (e.kind == ErrorKind.AUTH_EXPIRED || e.kind == ErrorKind.CANCELED) throw e
                failed += course.externalId
                warnings += FetchIssue(
                    scope = IssueScope.COURSE,
                    ref = course.externalId,
                    kind = e.kind.toIssueKind(),
                    message = "课程 \"${course.name}\" 作业列表抓取失败: ${e.message}",
                )
            }
        }

        progress(
            Progress(
                platform = platform,
                phase = "done",
                current = courses.size,
                total = courses.size,
                message = "抓取完成",
            )
        )
        return FetchResult(
            courses = courses,
            assignments = assignments,
            warnings = warnings,
            courseListComplete = courseListComplete,
            // 课程列表不完整时不做任何软删除
            failedCourses = if (courseListComplete) failed else null,
            complete = courseListComplete && failed.isEmpty(),
        )
    }

    /** 维护单次抓取内轮换后的 session；只持久化成功响应里的值 */
    private inner class FetchState(
        private val client: TronclassClient,
        private var session: String,
        private val uid: String,
        private val options: FetchOptions,
    ) {
        suspend fun activeSemester(): Long {
            val data = doJson("GET", "/api/my-semesters")
            val semesters = data.optJSONArray("semesters") ?: JSONArray()
            val ids = mutableListOf<Long>()
            for (i in 0 until semesters.length()) {
                val item = semesters.optJSONObject(i) ?: continue
                if (item.optBoolean("is_active", false) && item.optLong("id", 0) > 0) {
                    ids += item.optLong("id")
                }
            }
            return when (ids.size) {
                1 -> ids[0]
                0 -> throw CatcherException.changed("没有找到当前学期")
                else -> throw CatcherException.changed("返回了 ${ids.size} 个当前学期")
            }
        }

        suspend fun listCourses(semesterId: Long): Triple<List<Course>, Boolean, List<FetchIssue>> {
            val courses = mutableListOf<Course>()
            val warnings = mutableListOf<FetchIssue>()
            var complete = true
            var page = 1
            while (true) {
                if (page > MAX_COURSE_PAGES) {
                    warnings += FetchIssue(
                        scope = IssueScope.PLATFORM,
                        kind = IssueKind.PARSE,
                        message = "课程列表超过 $MAX_COURSE_PAGES 页，已停止翻页",
                    )
                    complete = false
                    break
                }
                val body = JSONObject().apply {
                    put("page", page)
                    put("page_size", COURSE_PAGE_SIZE)
                    put("fields", COURSE_FIELDS)
                    put("conditions", JSONObject().put("semester_id", semesterId))
                }.toString()

                val data = doJson("POST", "/api/my-courses", body)
                val items = data.optJSONArray("courses") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val id = item.optLong("id", 0)
                    if (id <= 0) {
                        complete = false
                        warnings += FetchIssue(
                            scope = IssueScope.COURSE,
                            kind = IssueKind.PARSE,
                            message = "课程缺少有效 id，已跳过",
                        )
                        continue
                    }
                    val externalId = id.toString()
                    val semester = item.optJSONObject("semester")
                    if (semester == null || item.isNull("semester")) {
                        warnings += FetchIssue(
                            scope = IssueScope.COURSE,
                            ref = externalId,
                            kind = IssueKind.PARSE,
                            message = "课程 \"${item.optString("name")}\" 缺少学期信息，已跳过",
                        )
                        continue
                    }
                    val name = item.optString("name").trim().ifEmpty { "课程 $externalId" }
                    courses += Course(
                        platform = Platform.TRONCLASS,
                        externalId = externalId,
                        name = name,
                        semesterKey = semester.optString("code"),
                        active = true,
                    )
                }

                // 空课程列表返回 pages=0；越界页会重复最后一页，不能用空列表终止
                if (page >= data.optInt("pages", 0)) break
                page++
            }
            return Triple(courses, complete, warnings)
        }

        suspend fun listAssignments(course: Course): Pair<List<Assignment>, List<FetchIssue>> {
            val assignments = mutableListOf<Assignment>()
            val warnings = mutableListOf<FetchIssue>()
            var page = 1
            while (true) {
                if (page > MAX_HOMEWORK_PAGES) {
                    throw CatcherException.changed("作业列表超过 $MAX_HOMEWORK_PAGES 页")
                }
                val data = doJson(
                    "GET",
                    "/api/courses/${course.externalId}/homework-activities?page=$page&page_size=$HOMEWORK_PAGE_SIZE",
                )
                val activities = data.optJSONArray("homework_activities") ?: JSONArray()
                for (i in 0 until activities.length()) {
                    val activity = activities.optJSONObject(i) ?: continue
                    val parsed = parseActivity(course, activity)
                    warnings += parsed.second
                    parsed.first?.let { assignments += it }
                }
                if (page >= data.optInt("pages", 0)) break
                page++
            }
            return assignments to warnings
        }

        private suspend fun doJson(method: String, path: String, body: String? = null): JSONObject {
            val resp = client.request(method, path, body, session)
            sessionFromSetCookies(resp.setCookies)?.let { session = it }
            persist()
            if (!isJsonResponse(resp)) throw CatcherException.changed("响应不是 JSON")
            return try {
                JSONObject(resp.text)
            } catch (e: Exception) {
                throw CatcherException.changed("解析响应失败: ${e.message}")
            }
        }

        private suspend fun persist() {
            val handler = options.onSessionUpdated ?: return
            handler(client.newSession(session, uid))
        }
    }

    /** 一条作业 → 领域对象；无法落库的条目跳过并写 warning */
    private fun parseActivity(
        course: Course,
        activity: JSONObject,
    ): Pair<Assignment?, List<FetchIssue>> {
        val id = activity.optLong("id", 0)
        if (id <= 0) {
            return null to listOf(
                FetchIssue(
                    scope = IssueScope.COURSE,
                    ref = course.externalId,
                    kind = IssueKind.PARSE,
                    message = "作业缺少有效 id，已跳过",
                )
            )
        }
        val externalId = id.toString()
        val warnings = mutableListOf<FetchIssue>()

        var title = normalizeTitle(activity.optString("title"))
        if (title.isEmpty()) {
            title = "作业 $externalId"
            warnings += FetchIssue(
                scope = IssueScope.ASSIGNMENT,
                ref = course.externalId,
                kind = IssueKind.PARSE,
                message = "作业 $externalId 缺少标题",
            )
        }

        // 提交判据只用 submitted；缺失必须是 unknown，不能默认成未提交
        val status = when {
            !activity.has("submitted") || activity.isNull("submitted") -> {
                warnings += FetchIssue(
                    scope = IssueScope.ASSIGNMENT,
                    ref = course.externalId,
                    kind = IssueKind.PARSE,
                    message = "作业 \"$title\" 缺少提交状态，按未知处理",
                )
                SubmissionStatus.UNKNOWN
            }
            activity.optBoolean("submitted") -> SubmissionStatus.SUBMITTED
            else -> SubmissionStatus.UNSUBMITTED
        }

        var deadline: Long? = null
        var source = DeadlineSource.UNKNOWN
        val raw = activity.optString("deadline").trim()
        if (raw.isNotEmpty()) {
            val parsed = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
            if (parsed == null) {
                warnings += FetchIssue(
                    scope = IssueScope.ASSIGNMENT,
                    ref = course.externalId,
                    kind = IssueKind.PARSE,
                    message = "作业 \"$title\" 截止时间无法解析: $raw",
                )
            } else {
                deadline = parsed.toInstant().toEpochMilli()
                source = DeadlineSource.PLATFORM_EXACT
            }
        }

        return Assignment(
            platform = Platform.TRONCLASS,
            courseExternalId = course.externalId,
            courseName = course.name,
            externalId = externalId,
            dedupKey = tronclassDedupKey(course.externalId, externalId),
            title = title,
            status = status,
            deadline = deadline,
            deadlineSource = source,
            deadlineRaw = raw,
        ) to warnings
    }

    // ---------------------------------------------------------------- 传输

    /**
     * 发送一次请求，网络类错误退避后重试一次。
     * 只有传输失败与超时重试；状态码错误、取消都不重试。
     */
    suspend fun request(
        method: String,
        path: String,
        body: String?,
        session: String,
    ): HttpResponse {
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to userAgent,
            "Cookie" to "session=$session",
        )
        if (!body.isNullOrEmpty()) headers["Content-Type"] = "application/json"

        var lastError: CatcherException? = null
        for (attempt in 0..1) {
            try {
                val resp = http.execute(
                    method = method,
                    url = baseUrl + path,
                    headers = headers,
                    body = body?.let { HttpEngine.jsonBody(it) },
                )
                if (resp.code in 200..299) return resp
                throw classifyStatus(
                    resp.code,
                    redirectMeansAuthExpired = true,
                    serverErrorIsNetwork = false,
                )
            } catch (e: CatcherException) {
                if (e.kind == ErrorKind.CANCELED) throw e
                lastError = e
                if (attempt == 1 || e.kind != ErrorKind.NETWORK) throw e
            }
            delay(RETRY_DELAY_MS)
        }
        throw lastError ?: CatcherException.network("请求失败")
    }

    private fun isJsonResponse(resp: HttpResponse): Boolean {
        if (!resp.contentType.lowercase().contains("json")) return false
        return runCatching { JSONObject(resp.text) }.isSuccess
    }

    private fun sessionFromSetCookies(lines: List<String>): String? {
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            if (line.substring(0, eq).trim() != "session") continue
            var value = line.substring(eq + 1)
            val semi = value.indexOf(';')
            if (semi >= 0) value = value.substring(0, semi)
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return null
    }

    // ---------------------------------------------------------------- 会话

    data class TronclassSecret(val session: String, val userId: String)

    private fun newSession(value: String, uid: String): Session {
        val json = JSONObject().apply {
            put("session", value)
            put("user_id", uid)
        }
        return Session(platform = platform, secretJson = json.toString())
    }

    private fun decodeSecret(session: Session): TronclassSecret {
        if (session.secretJson.isEmpty()) throw CatcherException.authRequired("会话为空")
        val obj = try {
            JSONObject(session.secretJson)
        } catch (e: Exception) {
            throw CatcherException.authRequired("解析学在重邮会话失败: ${e.message}")
        }
        val value = obj.optString("session").trim()
        if (value.isEmpty()) throw CatcherException.authRequired("会话缺少 session")
        return TronclassSecret(value, obj.optString("user_id"))
    }

    private fun normalizeSessionValue(raw: String): String {
        var value = raw.trim()
        if (value.lowercase().startsWith("session=")) {
            value = value.substring("session=".length).trim()
        }
        return value
    }

    /** 从 session 第二段解出 uid；失败只影响显示名，不阻断导入 */
    private fun parseUid(session: String): String {
        val parts = session.split(".")
        if (parts.size < 2) return ""
        val segment = parts[1].trim()
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(segment.padEnd((segment.length + 3) / 4 * 4, '='))
        }.getOrNull() ?: return ""
        val uid = String(decoded, Charsets.UTF_8).trim()
        if (uid.isEmpty() || uid.length > 64) return ""
        if (uid.any { it.isISOControl() }) return ""
        return uid
    }

    private companion object {
        const val MAX_SESSION_BYTES = 4096
        const val RETRY_DELAY_MS = 250L
        const val COURSE_PAGE_SIZE = 100
        const val HOMEWORK_PAGE_SIZE = 100
        const val MAX_COURSE_PAGES = 50
        const val MAX_HOMEWORK_PAGES = 100
        const val COURSE_FIELDS = "id,name,course_code,semester,academic_year,instructors,department"
    }
}

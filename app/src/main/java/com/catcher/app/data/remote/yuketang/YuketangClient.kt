package com.catcher.app.data.remote.yuketang

import com.catcher.app.data.remote.HttpEngine
import com.catcher.app.data.remote.HttpResponse
import com.catcher.app.data.remote.classifyStatus
import com.catcher.app.data.remote.jsonNumberOrNull
import com.catcher.app.data.remote.parseUnixMillis
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
import com.catcher.app.domain.model.Session
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.domain.model.normalizeTitle
import com.catcher.app.domain.model.yuketangDedupKey
import com.catcher.app.domain.toIssueKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 雨课堂适配器。
 *
 * 与电脑端 internal/infra/bridge/yuketang 对应，几条刻意保留的陷阱：
 *
 * - 登录态只看响应体 op == "web_redirect"，不看 HTTP 200；
 * - activities 是按日期分组的嵌套数组，必须先摊平；
 * - score_d 是 13 位毫秒，create_time 是 10 位秒；
 * - 提交状态平台未暴露，全部落到 unknown（绝不推断成已交）。
 *
 * 移动端差异：电脑端用扫码登录，手机上没法扫自己屏幕，改成内嵌网页登录后取 sessionid。
 */
class YuketangClient(private val http: HttpEngine) : PlatformAdapter {

    override val platform: Platform = Platform.YUKETANG

    private val baseUrl = "https://changjiang.yuketang.cn"

    override val cookieImporter: CookieImporter = object : CookieImporter {
        override suspend fun importCookies(rawCookie: String): Session {
            val sessionId = extractSessionId(rawCookie)
                ?: throw CatcherException.authRequired("cookie 里没有 sessionid")
            val session = newSession(sessionId)
            // 立刻验证一次，让用户当场知道这份登录态能不能用
            fetchCourseItems(sessionId)
            return session
        }
    }

    override suspend fun validate(session: Session): String {
        fetchCourseItems(decodeSecret(session))
        return ""
    }

    private fun extractSessionId(raw: String): String? {
        raw.split(';', '\n').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            if (!part.substring(0, eq).trim().equals("sessionid", ignoreCase = true)) return@forEach
            val value = part.substring(eq + 1).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    // ---------------------------------------------------------------- 抓取

    override suspend fun fetch(
        session: Session,
        options: FetchOptions,
        progress: (Progress) -> Unit,
    ): FetchResult {
        val sessionId = decodeSecret(session)
        val now = options.now()

        progress(Progress(platform, "courses", message = "读取课程列表"))
        val items = fetchCourseItems(sessionId)

        val selection = selectCurrentSemester(items, now)
        var complete = selection.complete
        // 连一个学期前缀都读不到时不做软删除，避免把旧数据清空
        if (selection.semesterKey.isEmpty()) complete = false

        val warnings = selection.warnings.toMutableList()
        val courses = mutableListOf<Course>()
        val assignments = mutableListOf<Assignment>()

        for ((index, item) in selection.items.withIndex()) {
            progress(
                Progress(
                    platform = platform,
                    phase = "assignments",
                    current = index + 1,
                    total = selection.items.size,
                    message = courseDisplayName(item),
                )
            )
            val logs = try {
                fetchClassroomLogs(item, sessionId)
            } catch (e: CatcherException) {
                // 认证失效/取消立即中止，本次结果不落库
                if (e.kind == ErrorKind.AUTH_EXPIRED || e.kind == ErrorKind.CANCELED) throw e
                // 单门课失败：该课程数据保持不动，其余课程继续
                complete = false
                warnings += FetchIssue(
                    scope = IssueScope.COURSE,
                    ref = courseId(item),
                    kind = e.kind.toIssueKind(),
                    message = "课程 ${courseDisplayName(item)} 抓取失败: ${e.message}",
                )
                continue
            }

            val parsed = parseClassroomLogs(item, logs)
            courses += parsed.course
            assignments += parsed.assignments
            warnings += parsed.warnings
            if (!parsed.ok) complete = false
        }

        progress(
            Progress(
                platform = platform,
                phase = "done",
                current = selection.items.size,
                total = selection.items.size,
                message = "抓取完成",
            )
        )
        return FetchResult(
            courses = courses,
            assignments = assignments,
            warnings = warnings,
            complete = complete,
        )
    }

    data class CourseItem(
        val courseId: String,
        val classroomId: String,
        val courseName: String,
        val classroomName: String,
    ) {
        val displayName: String get() = courseName.ifEmpty { classroomName }
    }

    private fun courseId(item: CourseItem) = item.courseId

    private fun courseDisplayName(item: CourseItem) = item.displayName

    private suspend fun fetchCourseItems(sessionId: String): List<CourseItem> {
        val resp = get("/v/course_meta/learning_list/", sessionId)
        val out = parseJson(resp, "课程列表")

        if (out.optString("op") == "web_redirect") {
            throw CatcherException.authExpired("课程列表被重定向到登录页")
        }
        // 实测 2026-10 课程列表没有 success 字段，不能把「缺失」当成 false
        if (out.has("success") && !out.isNull("success") && !out.optBoolean("success")) {
            throw CatcherException.changed("课程列表 success=false msg=${out.optString("msg")}")
        }

        val data = out.optJSONArray("data") ?: throw CatcherException.changed("课程列表缺少 data")
        val items = mutableListOf<CourseItem>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            items += CourseItem(
                courseId = jsonNumberOrNull(item.opt("course_id")).orEmpty(),
                classroomId = jsonNumberOrNull(item.opt("classroom_id")).orEmpty(),
                courseName = item.optString("course_name"),
                classroomName = item.optString("classroom_name"),
            )
        }
        return items
    }

    private suspend fun fetchClassroomLogs(item: CourseItem, sessionId: String): JSONObject {
        val path = "/v/course_meta/classroom_logs?course_id=${item.courseId}" +
            "&classroom_id=${item.classroomId}&activity_type=-1"
        val resp = get(path, sessionId)
        val out = parseJson(resp, "作业列表")
        if (out.optString("op") == "web_redirect") {
            throw CatcherException.authExpired("作业列表被重定向到登录页")
        }
        if (out.has("success") && !out.isNull("success") && !out.optBoolean("success")) {
            throw CatcherException.changed("作业列表 success=false msg=${out.optString("msg")}")
        }
        return out
    }

    private fun parseJson(resp: HttpResponse, what: String): JSONObject =
        try {
            JSONObject(resp.text)
        } catch (e: Exception) {
            throw CatcherException.changed("$what 不是 JSON: ${e.message}")
        }

    private suspend fun get(path: String, sessionId: String): HttpResponse {
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Cookie" to "sessionid=$sessionId",
        )
        val resp = http.execute("GET", baseUrl + path, headers)
        if (resp.code >= 400) {
            throw classifyStatus(resp.code, redirectMeansExpired())
        }
        return resp
    }

    private fun redirectMeansExpired() = true

    // ---------------------------------------------------------------- 解析

    data class ParsedCourse(
        val course: Course,
        val assignments: List<Assignment>,
        val warnings: List<FetchIssue>,
        /** false 表示本次没完整解析，调用方不做软删除 */
        val ok: Boolean,
    )

    private fun parseClassroomLogs(item: CourseItem, logs: JSONObject): ParsedCourse {
        val semesterKey = semesterKeyOf(item.classroomName)
        val course = Course(
            platform = platform,
            externalId = item.courseId,
            classroomId = item.classroomId,
            name = item.displayName,
            semesterKey = semesterKey,
            active = true,
        )

        val assignments = mutableListOf<Assignment>()
        val warnings = mutableListOf<FetchIssue>()
        var ok = true

        val data = logs.optJSONObject("data")
        val groups = data?.optJSONArray("activities") ?: JSONArray()
        // 不摊平会稳定拿到空列表：这是文档实测复现过的静默失败
        for (g in 0 until groups.length()) {
            val group = groups.optJSONArray(g) ?: continue
            for (i in 0 until group.length()) {
                val act = group.optJSONObject(i) ?: continue
                val parsed = toAssignment(item, act)
                warnings += parsed.second
                if (!parsed.third) ok = false
                parsed.first?.let { assignments += it }
            }
        }
        return ParsedCourse(course, assignments, warnings, ok)
    }

    /**
     * 活动类型有两套编码，同一账号的课程里并存：
     * 旧课用 20（作业）/15（视频）；新课用 19（习题/练习/作业）和 17（视频）。
     */
    private fun isExerciseType(t: Int) = t == 19 || t == 20

    private fun isKnownActivityType(t: Int) = t in listOf(9, 14, 15, 17, 19, 20)

    private fun toAssignment(
        item: CourseItem,
        act: JSONObject,
    ): Triple<Assignment?, List<FetchIssue>, Boolean> {
        val activityId = jsonNumberOrNull(act.opt("id")).orEmpty()
        val type = act.optInt("type", 0)
        val warn: (String) -> FetchIssue = { msg ->
            FetchIssue(
                scope = IssueScope.ASSIGNMENT,
                ref = activityId,
                kind = IssueKind.PARSE,
                message = msg,
            )
        }

        val content = act.optJSONObject("content")
        if (content == null || act.isNull("content")) {
            if (isExerciseType(type)) {
                // 作业类活动与 content 100% 重合；缺 content 说明结构变了
                return Triple(null, listOf(warn("作业活动 $activityId 缺少 content")), false)
            }
            return Triple(null, emptyList(), true)
        }

        val warnings = mutableListOf<FetchIssue>()
        // 视频类（15/17）也是带期限的学习任务，保留且不算未知类型
        if (!isKnownActivityType(type)) {
            warnings += warn("未知活动类型 type=$type 带 content，请核对是否为作业")
        }

        val scoreRaw = jsonNumberOrNull(content.opt("score_d")).orEmpty()
        if (scoreRaw.isEmpty()) {
            if (!isExerciseType(type)) {
                // 带 content 的非作业类型（如授课记录）不落库
                return Triple(null, warnings, true)
            }
            warnings += warn("作业活动 $activityId 缺少 score_d")
            return Triple(
                buildAssignment(item, act, activityId, null, DeadlineSource.UNKNOWN, ""),
                warnings,
                false,
            )
        }

        if (activityId.isEmpty()) {
            warnings += warn("作业活动缺少 id")
            return Triple(null, warnings, false)
        }

        val createRaw = jsonNumberOrNull(act.opt("create_time")).orEmpty()
        if (createRaw.isEmpty()) {
            warnings += warn("作业活动 $activityId 缺少 create_time")
        } else if (createRaw.length != 10) {
            warnings += warn("create_time 不是 10 位秒: $createRaw")
        }

        // 断言 13 位毫秒，拿错会得到 1970 年
        val deadline = parseUnixMillis(scoreRaw)
        if (deadline == null) {
            warnings += warn("score_d 不是 13 位毫秒: $scoreRaw，已保留作业但截止时间未知")
            return Triple(
                buildAssignment(item, act, activityId, null, DeadlineSource.UNKNOWN, scoreRaw),
                warnings,
                false,
            )
        }

        return Triple(
            buildAssignment(
                item, act, activityId, deadline, DeadlineSource.PLATFORM_EXACT, scoreRaw,
            ),
            warnings,
            true,
        )
    }

    private fun buildAssignment(
        item: CourseItem,
        act: JSONObject,
        activityId: String,
        deadline: Long?,
        source: DeadlineSource,
        raw: String,
    ): Assignment = Assignment(
        platform = platform,
        courseExternalId = item.courseId,
        classroomId = item.classroomId,
        courseName = item.displayName,
        externalId = activityId,
        dedupKey = yuketangDedupKey(item.courseId, item.classroomId, activityId),
        title = normalizeTitle(act.optString("title")),
        // 平台没有提交状态字段；未知就是未知
        status = SubmissionStatus.UNKNOWN,
        deadline = deadline,
        deadlineSource = source,
        deadlineRaw = raw,
    )

    // ---------------------------------------------------------------- 学期

    /**
     * 学期前缀只挂在 classroom_name 上，course_name 不带学期。
     * 这是本地推断，不是平台 API 保证，所以找不到时要记 warning 且不做软删除。
     */
    private data class Semester(val year: Int, val season: String) {
        val key: String get() = "$year$season"
        val rank: Int get() = year * 10 + when (season) {
            "春" -> 1
            "夏" -> 2
            "秋" -> 3
            else -> 0
        }
    }

    private val semesterRegex = Regex("""^(\d{4})(春|夏|秋)-""")

    private fun parseSemester(classroomName: String): Semester? {
        val m = semesterRegex.find(classroomName) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        return Semester(year, m.groupValues[2])
    }

    private fun semesterKeyOf(classroomName: String) = parseSemester(classroomName)?.key ?: ""

    /** 9 月–次年 1 月为秋，2–8 月为春；1 月属于上一年秋 */
    private fun currentSemester(now: Long): Semester {
        val z = Instant.ofEpochMilli(now).atZone(SHANGHAI)
        val year = z.year
        val month = z.monthValue
        return when {
            month >= 9 -> Semester(year, "秋")
            month == 1 -> Semester(year - 1, "秋")
            else -> Semester(year, "春")
        }
    }

    private data class Selection(
        val items: List<CourseItem>,
        val semesterKey: String,
        val warnings: List<FetchIssue>,
        val complete: Boolean,
    )

    private fun selectCurrentSemester(items: List<CourseItem>, now: Long): Selection {
        val target = currentSemester(now)
        var matched = filterBySemester(items, target)
        val warnings = mutableListOf<FetchIssue>()
        var complete = true

        if (matched.isEmpty()) {
            val latest = latestSemester(items)
            if (latest == null) {
                warnings += FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "课程列表中没有可识别的学期前缀（如 2026秋-），本次不做软删除",
                )
                return Selection(emptyList(), "", warnings, false)
            }
            warnings += FetchIssue(
                scope = IssueScope.PLATFORM,
                kind = IssueKind.BUSINESS,
                message = "未找到当前学期 ${target.key}，退化为列表中最新的 ${latest.key}",
            )
            matched = filterBySemester(items, latest)
            return Selection(matched, latest.key, warnings, complete)
        }
        return Selection(matched, target.key, warnings, complete)
    }

    private fun filterBySemester(items: List<CourseItem>, target: Semester): List<CourseItem> =
        items.filter { item ->
            val s = parseSemester(item.classroomName)
            s != null && s.key == target.key
        }

    private fun latestSemester(items: List<CourseItem>): Semester? =
        items.mapNotNull { parseSemester(it.classroomName) }.maxByOrNull { it.rank }

    // ---------------------------------------------------------------- 会话

    private fun newSession(sessionId: String): Session {
        val json = JSONObject().apply {
            put("sessionid", sessionId)
            put("auth", "")
            put("user_id", "")
        }
        return Session(platform = platform, secretJson = json.toString())
    }

    private fun decodeSecret(session: Session): String {
        if (session.secretJson.isEmpty()) throw CatcherException.authRequired("会话为空")
        val obj = try {
            JSONObject(session.secretJson)
        } catch (e: Exception) {
            throw CatcherException.authRequired("解析雨课堂会话失败: ${e.message}")
        }
        val sid = obj.optString("sessionid")
        if (sid.isEmpty()) throw CatcherException.authRequired("会话缺少 sessionid")
        return sid
    }
}

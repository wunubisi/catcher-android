package com.catcher.app.data.remote.chaoxing

import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.IssueKind
import com.catcher.app.domain.model.IssueScope
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.domain.model.normalizeTitle
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 学习通作业列表页与详情页的纯函数解析。
 *
 * 与电脑端 chaoxing/worklist.go · detail.go 逐条对应，几条刻意保留的陷阱：
 *
 * - 状态与剩余时间只从 `aria-label` 取，三个定界符从右往左找；
 * - `redPoint` 是未读标记，不是未交，不能用来判状态；
 * - 标题里的日期不是截止时间；
 * - 剩余时间是相对值，必须在抓取当场换算成绝对时刻。
 */
object ChaoxingHtml {

    private val workItemPattern =
        Regex("""<li onclick="goTask\(this\);" data="([^"]+)">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)

    private val haveMorePattern = Regex("""var\s+haveMore\s*=\s*["']?(\d+)""")

    private val ariaLabelPattern = Regex("""aria-label="([^"]*)"""")

    private val remainingPattern = Regex("""(\d+)\s*(天|小时|分钟)""")

    /** 详情页 aria-label 带秒，精确到分钟也能用 */
    private val detailAriaPattern =
        Regex("""aria-label="截止时间(\d{1,2})月(\d{1,2})日(\d{1,2})时(\d{2})分(?:(\d{2})秒)?""")

    /** `<h4>截止时间：09-25 22:55</h4>` */
    private val detailH4Pattern = Regex("""截止时间[：:]\s*(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})""")

    /**
     * 「剩余时间推算」与「详情页截止时刻」允许的偏差。
     *
     * 剩余时间只到分钟，且以页面渲染时刻为基准，秒级到分钟级误差是正常的。
     */
    const val DEADLINE_TOLERANCE_MS: Long = 10 * 60 * 1000L

    data class WorkItem(
        val detailUrl: String,
        val title: String,
        val status: SubmissionStatus,
        val courseId: String,
        val courseName: String,
        val remainingMillis: Long = 0,
        val hasRemaining: Boolean = false,
        val remainingRaw: String = "",
        /** 图标 task-work-gray.png 表示已结束 */
        val ended: Boolean = false,
        val iconKnown: Boolean = false,
    )

    data class WorkListPage(
        val items: List<WorkItem>,
        val warnings: List<FetchIssue>,
        /** 只有完整枚举了列表才允许软删除旧数据 */
        val complete: Boolean,
    )

    /** 详情页的月-日时分；没有年份，需要结合列表页剩余时间补全 */
    data class DetailDeadline(
        val raw: String,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int = 0,
    )

    /** 解析作业列表。`haveMore` 缺失或非 0 都表示这次没抓全。 */
    fun parseWorkList(body: String): WorkListPage {
        val warnings = mutableListOf<FetchIssue>()
        var complete = true

        val haveMore = haveMorePattern.find(body)?.groupValues?.get(1)
        if (haveMore == null) {
            complete = false
            warnings += FetchIssue(
                scope = IssueScope.PLATFORM,
                kind = IssueKind.PARSE,
                message = "作业列表未找到 haveMore，本次不做软删除",
            )
        } else if (haveMore != "0") {
            complete = false
            warnings += FetchIssue(
                scope = IssueScope.PLATFORM,
                kind = IssueKind.BUSINESS,
                message = "作业列表 haveMore=$haveMore，可能还有下一页，本次不做软删除",
            )
        }

        val matches = workItemPattern.findAll(body).toList()
        if (matches.isEmpty()) {
            // 条目标记的固定开头。注意不能用裸的 "goTask" 当依据：
            // 页面里永远有 `function goTask(obj){...}`，空列表也会命中，会误报「结构已变化」。
            val hasItemMarkup = body.contains("<li onclick=\"goTask")
            // 「暂无作业」是平台给出的正常空列表，不是结构变化
            val emptyState = body.contains("暂无作业")
            if (hasItemMarkup && !emptyState) {
                complete = false
                warnings += FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "作业列表含有条目标记但正则没有匹配到，页面结构可能已变化",
                )
            }
        }

        val items = mutableListOf<WorkItem>()
        for (match in matches) {
            val parsed = parseWorkItem(match.groupValues[1], match.groupValues[2])
            warnings += parsed.second
            val item = parsed.first
            if (item == null) {
                // 有一条解析不了就无法断言完整枚举，保守地不软删除
                complete = false
                continue
            }
            items += item
        }
        return WorkListPage(items = items, warnings = warnings, complete = complete)
    }

    private fun parseWorkItem(rawData: String, inner: String): Pair<WorkItem?, List<FetchIssue>> {
        val warnings = mutableListOf<FetchIssue>()
        val dataUrl = unescapeHtml(rawData.trim())
        val base = WorkItem(
            detailUrl = dataUrl,
            title = "",
            status = SubmissionStatus.UNKNOWN,
            courseId = courseIdFromUrl(dataUrl),
            courseName = "",
        )

        val aria = pickAriaLabel(inner, "作业名称")
        if (aria.isEmpty()) {
            return null to listOf(
                FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "作业条目缺少 aria-label：${truncate(dataUrl, 80)}",
                )
            )
        }

        val parts = parseAriaLabel(aria)
        if (parts == null) {
            return null to listOf(
                FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "aria-label 无法解析：${truncate(aria, 120)}",
                )
            )
        }

        var title = normalizeTitle(parts.title)
        var hasRemaining = parts.hasRemaining
        val remainingRaw = parts.remainingRaw
        var remainingMillis = 0L

        if (base.courseId.isEmpty()) {
            return null to listOf(
                FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "作业 \"$title\" 的详情 URL 缺少 courseId",
                )
            )
        }
        if (title.isEmpty()) {
            return null to listOf(
                FetchIssue(
                    scope = IssueScope.PLATFORM,
                    kind = IssueKind.PARSE,
                    message = "aria-label 解析出的作业标题为空",
                )
            )
        }

        // 待批阅 = 已交、老师还没批改；真实环境里大量出现。它是已提交。
        val status = when (parts.statusText) {
            "未提交" -> SubmissionStatus.UNSUBMITTED
            "已完成" -> SubmissionStatus.SUBMITTED
            "待批阅" -> SubmissionStatus.SUBMITTED
            else -> {
                warnings += FetchIssue(
                    scope = IssueScope.ASSIGNMENT,
                    ref = base.courseId,
                    kind = IssueKind.PARSE,
                    message = "作业 \"$title\" 的状态无法识别：${truncate(parts.statusText, 40)}",
                )
                // 认不出的状态显式暴露，绝不默认成已完成
                SubmissionStatus.UNKNOWN
            }
        }

        if (hasRemaining) {
            val duration = parseRemaining(remainingRaw)
            if (duration != null) {
                remainingMillis = duration
            } else {
                hasRemaining = false
                warnings += FetchIssue(
                    scope = IssueScope.ASSIGNMENT,
                    ref = base.courseId,
                    kind = IssueKind.PARSE,
                    message = "作业 \"$title\" 的剩余时间无法解析：${truncate(remainingRaw, 40)}",
                )
            }
        }

        val icon = parseStatusIcon(inner)
        if (icon.second && icon.first && hasRemaining) {
            warnings += FetchIssue(
                scope = IssueScope.ASSIGNMENT,
                ref = base.courseId,
                kind = IssueKind.PARSE,
                message = "作业 \"$title\" 图标显示已结束，但仍有剩余时间 ${truncate(remainingRaw, 40)}",
            )
        }

        return base.copy(
            title = title,
            status = status,
            courseName = parts.courseName,
            remainingMillis = remainingMillis,
            hasRemaining = hasRemaining,
            remainingRaw = remainingRaw.trim(),
            ended = icon.first,
            iconKnown = icon.second,
        ) to warnings
    }

    data class AriaParts(
        val title: String,
        val statusText: String,
        val courseName: String,
        val remainingRaw: String,
        val hasRemaining: Boolean,
    )

    /**
     * 按「作业名称<标题>作业状态<状态>所属课程<课程>剩余时间<剩余>」拆解。
     *
     * 三个定界符都从右往左找：标题是自由文本，可能自带日期、括号、空格，
     * 理论上也可能自带同名字样；真正的定界符一定在标题之后。
     */
    fun parseAriaLabel(aria: String): AriaParts? {
        var end = aria.length

        var remainingRaw = ""
        var hasRemaining = false
        val idxRemaining = aria.lastIndexOf("剩余时间")
        if (idxRemaining >= 0) {
            remainingRaw = aria.substring(idxRemaining + "剩余时间".length)
            hasRemaining = remainingRaw.trim().isNotEmpty()
            end = idxRemaining
        }

        var courseName = ""
        val idxCourse = aria.substring(0, end).lastIndexOf("所属课程")
        if (idxCourse >= 0) {
            courseName = aria.substring(idxCourse + "所属课程".length, end).trim()
            end = idxCourse
        }

        val idxStatus = aria.substring(0, end).lastIndexOf("作业状态")
        if (idxStatus < 0) return null
        val statusText = aria.substring(idxStatus + "作业状态".length, end).trim()

        val idxTitle = aria.substring(0, idxStatus).indexOf("作业名称")
        if (idxTitle < 0) return null
        val title = aria.substring(idxTitle + "作业名称".length, idxStatus).trim()

        return AriaParts(
            title = title,
            statusText = statusText,
            courseName = courseName,
            remainingRaw = remainingRaw.trim(),
            hasRemaining = hasRemaining,
        )
    }

    /** 把「312小时55分钟」这类相对时间累加 */
    fun parseRemaining(raw: String): Long? {
        val matches = remainingPattern.findAll(raw).toList()
        if (matches.isEmpty()) return null
        var total = 0L
        for (m in matches) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            total += when (m.groupValues[2]) {
                "天" -> n * 24 * 60 * 60 * 1000
                "小时" -> n * 60 * 60 * 1000
                "分钟" -> n * 60 * 1000
                else -> 0L
            }
        }
        return total
    }

    /** 读图标文件名判断作业是否还在进行。不能用 redPoint：那是未读标记。 */
    fun parseStatusIcon(inner: String): Pair<Boolean, Boolean> = when {
        inner.contains("task-work-gray") -> true to true
        inner.contains("task-work") -> false to true
        else -> false to false
    }

    /** 优先读 aria-label（带秒），退化到 h4 */
    fun parseDetailDeadline(body: String): DetailDeadline? {
        detailAriaPattern.find(body)?.let { m ->
            return DetailDeadline(
                raw = m.value,
                month = m.groupValues[1].toIntOrNull() ?: 0,
                day = m.groupValues[2].toIntOrNull() ?: 0,
                hour = m.groupValues[3].toIntOrNull() ?: 0,
                minute = m.groupValues[4].toIntOrNull() ?: 0,
                second = m.groupValues[5].toIntOrNull() ?: 0,
            )
        }
        detailH4Pattern.find(body)?.let { m ->
            return DetailDeadline(
                raw = m.value,
                month = m.groupValues[1].toIntOrNull() ?: 0,
                day = m.groupValues[2].toIntOrNull() ?: 0,
                hour = m.groupValues[3].toIntOrNull() ?: 0,
                minute = m.groupValues[4].toIntOrNull() ?: 0,
            )
        }
        return null
    }

    data class ResolvedDeadline(
        val deadline: Long?,
        val source: DeadlineSource,
        val raw: String,
        val warnings: List<FetchIssue>,
    )

    /**
     * 按接口文档的年份补全规则算出绝对时刻。
     *
     * 进行中：`now + 剩余时间`（唯一带绝对信息的源），再用详情页月-日交叉校验；
     * 已截止：详情页月-日，月-日大于今天归去年，否则归今年。
     */
    fun resolveDeadline(item: WorkItem, detail: DetailDeadline?, now: Long): ResolvedDeadline {
        val nowZ = Instant.ofEpochMilli(now).atZone(SHANGHAI)

        if (item.hasRemaining) {
            val derivedMillis = now + item.remainingMillis
            var raw = item.remainingRaw
            val warnings = mutableListOf<FetchIssue>()
            var utc = derivedMillis

            if (detail != null) {
                raw = detail.raw
                val derivedZ = Instant.ofEpochMilli(derivedMillis).atZone(SHANGHAI)
                val exact = ZonedDateTime.of(
                    derivedZ.year, detail.month, detail.day,
                    detail.hour, detail.minute, detail.second, 0, SHANGHAI,
                )
                val diff = exact.toInstant().toEpochMilli() - derivedMillis
                if (diff <= DEADLINE_TOLERANCE_MS && diff >= -DEADLINE_TOLERANCE_MS) {
                    utc = exact.toInstant().toEpochMilli()
                } else {
                    warnings += FetchIssue(
                        scope = IssueScope.ASSIGNMENT,
                        ref = item.courseId,
                        kind = IssueKind.BUSINESS,
                        message = "作业 \"${item.title}\" 的剩余时间推算为 " +
                            "${formatMinute(derivedZ)}，详情页为 ${detail.raw}，以剩余时间为准",
                    )
                }
            }
            return ResolvedDeadline(utc, DeadlineSource.DERIVED_REMAINING, raw, warnings)
        }

        if (detail == null) {
            return ResolvedDeadline(
                deadline = null,
                source = DeadlineSource.UNKNOWN,
                raw = "",
                warnings = listOf(
                    FetchIssue(
                        scope = IssueScope.ASSIGNMENT,
                        ref = item.courseId,
                        kind = IssueKind.PARSE,
                        message = "作业 \"${item.title}\" 已截止但没有拿到详情页截止时间",
                    )
                ),
            )
        }

        var year = nowZ.year
        if (detail.month > nowZ.monthValue ||
            (detail.month == nowZ.monthValue && detail.day > nowZ.dayOfMonth)
        ) {
            year -= 1
        }
        val deadline = ZonedDateTime.of(
            year, detail.month, detail.day,
            detail.hour, detail.minute, detail.second, 0, SHANGHAI,
        ).toInstant().toEpochMilli()
        return ResolvedDeadline(deadline, DeadlineSource.INFERRED_YEAR, detail.raw, emptyList())
    }

    private fun formatMinute(z: ZonedDateTime): String =
        "%04d-%02d-%02d %02d:%02d".format(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute)

    /** 取包含指定前缀的 aria-label，避免误取列表里的其它标注 */
    fun pickAriaLabel(inner: String, prefix: String): String {
        for (m in ariaLabelPattern.findAll(inner)) {
            val value = unescapeHtml(m.groupValues[1])
            if (value.trim().startsWith(prefix)) return value
        }
        return ""
    }

    /** 用 java.net.URI 取查询参数，不做字符串切割；等价于电脑端的 url.Parse + Query().Get */
    fun courseIdFromUrl(raw: String): String {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return ""
        val query = uri.rawQuery ?: return ""
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=')
            if (name != "courseId") continue
            val value = pair.substringAfter('=', "")
            return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }
                .getOrDefault(value)
                .trim()
        }
        return ""
    }

    fun truncate(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n) + "…"

    private val numericEntity = Regex("""&#(\d+);""")
    private val hexEntity = Regex("""&#[xX]([0-9a-fA-F]+);""")

    /** 只解常见的几个实体，够用且不引入 Html.fromHtml 的兼容坑 */
    fun unescapeHtml(s: String): String {
        if (!s.contains('&')) return s
        var out = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        out = numericEntity.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        }
        out = hexEntity.replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        }
        return out
    }
}

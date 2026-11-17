package com.catcher.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 平台时间一律按这个时区解释；学习通/雨课堂/批改网的界面时间都是北京时间 */
val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

/** 一条作业。时间是 epoch millis（UTC），null 表示截止时间未知。 */
data class Assignment(
    val id: Long = 0,
    val accountId: Long = 0,
    val platform: Platform,
    val courseId: Long = 0,
    val courseExternalId: String = "",
    val classroomId: String = "",
    val courseName: String = "",
    val externalId: String = "",
    val dedupKey: String,
    val title: String,
    /** 平台给出的状态 */
    val status: SubmissionStatus,
    /** 用户手动标记的状态；null 表示没有手动覆盖 */
    val manualStatus: SubmissionStatus? = null,
    val manualStatusAt: Long? = null,
    val deadline: Long? = null,
    val deadlineSource: DeadlineSource = DeadlineSource.UNKNOWN,
    val deadlineRaw: String = "",
    val detailUrl: String = "",
    val description: String = "",
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val active: Boolean = true,
    val lastRunId: Long = 0,
) {
    /** 界面与统计使用的状态：有手动值以手动值为准 */
    val effectiveStatus: SubmissionStatus get() = manualStatus ?: status

    fun phase(now: Long = System.currentTimeMillis()): Phase {
        val d = deadline ?: return Phase.UNKNOWN
        return if (now < d) Phase.OPEN else Phase.CLOSED
    }

    fun deadlineLocalDate(): LocalDate? =
        deadline?.let { Instant.ofEpochMilli(it).atZone(SHANGHAI).toLocalDate() }
}

/** 一门课程。雨课堂用 (externalId, classroomId) 作为复合身份。 */
data class Course(
    val id: Long = 0,
    val accountId: Long = 0,
    val platform: Platform,
    val externalId: String,
    val classroomId: String = "",
    val name: String,
    val semesterKey: String = "",
    val active: Boolean = true,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
)

/** 某平台在本机的唯一账号 */
data class Account(
    val id: Long = 0,
    val platform: Platform,
    /** 只存脱敏后的标识，如 138****1234 */
    val displayName: String = "",
    val loginState: LoginState = LoginState.DISCONNECTED,
    val lastLoginAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * 持久化登录态。[secretJson] 是明文 JSON，落库前必须交给 SecretVault 加密，
 * 只在内存里短暂存在。
 */
data class Session(
    val platform: Platform,
    val secretJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 平台适配器执行一次抓取时用到的选项 */
data class FetchOptions(
    val includeHistory: Boolean = false,
    val now: () -> Long = { System.currentTimeMillis() },
    /** 平台轮换会话/累积 cookie 后立即调用，交给上层加密持久化 */
    val onSessionUpdated: (suspend (Session) -> Unit)? = null,
)

/**
 * 一次抓取的结果。
 *
 * [complete] 只在平台完整枚举了目标范围时为 true，也只有此时才允许软删除旧数据。
 */
data class FetchResult(
    val courses: List<Course> = emptyList(),
    val assignments: List<Assignment> = emptyList(),
    val warnings: List<FetchIssue> = emptyList(),
    val serverNow: Long? = null,
    val complete: Boolean = false,
    val courseListComplete: Boolean = false,
    /** 没抓全但原因是平台只暴露这么多（批改网第一页），不是本次出错 */
    val partialByDesign: Boolean = false,
    /** 作业列表抓取失败的课程 externalId；null 表示课程列表不完整，不能据此清理 */
    val failedCourses: List<String>? = null,
)

/** 一次部分失败或警告 */
data class FetchIssue(
    val scope: IssueScope = IssueScope.PLATFORM,
    val ref: String = "",
    val kind: IssueKind = IssueKind.INTERNAL,
    val message: String = "",
)

/** 刷新记录 */
data class FetchRun(
    val id: Long = 0,
    val accountId: Long = 0,
    val platform: Platform,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val coursesTotal: Int = 0,
    val coursesOk: Int = 0,
    val itemsSeen: Int = 0,
    val itemsUpserted: Int = 0,
    val warningCount: Int = 0,
    val errorSummary: String = "",
)

/** 刷新进度事件 */
data class Progress(
    val platform: Platform,
    val phase: String,
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}

/** 查询作业的条件。零值表示默认视图：只返回 active，并隐藏已提交作业。 */
data class AssignmentFilter(
    val platforms: Set<Platform> = emptySet(),
    val statuses: Set<SubmissionStatus> = emptySet(),
    val showSubmitted: Boolean = false,
    val keyword: String = "",
    val termOnly: Boolean = false,
    /** 只要截止时间不早于该时刻的作业；截止时间未知的保留 */
    val deadlineFrom: Long? = null,
)

/** 归一化标题：TrimSpace + 连续空白折叠。不要删除日期、括号、全角字符。 */
fun normalizeTitle(s: String): String =
    s.trim().replace(Regex("\\s+"), " ")

/** 学习通去重键：用户 + 平台 + 课程 + 标题，deadline 不参与 */
fun chaoxingDedupKey(courseId: String, title: String): String =
    "chaoxing:$courseId:${normalizeTitle(title)}"

/** 雨课堂用稳定的活动 id */
fun yuketangDedupKey(courseId: String, classroomId: String, activityId: String): String =
    "yuketang:$courseId:$classroomId:$activityId"

/** 学在重邮：课程 id + 活动 id */
fun tronclassDedupKey(courseId: String, activityId: String): String =
    "tronclass:$courseId:$activityId"

/** 批改网只用作业 id：换班后同一条作业不应重复入库 */
fun pigaiDedupKey(requestId: String): String = "pigai:$requestId"

/** 把 11 位手机号变成 138****1234 */
fun maskPhone(phone: String): String =
    if (phone.length != 11) phone else phone.substring(0, 3) + "****" + phone.substring(7)

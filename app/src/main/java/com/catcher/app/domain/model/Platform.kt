package com.catcher.app.domain.model

/**
 * 抓取目标平台的稳定标识。
 *
 * [id] 会原样落库，禁止随意改名；[color] 是平台在界面上的强调色。
 */
enum class Platform(
    val id: String,
    val displayName: String,
    val color: Long,
    /** 该平台还有未验证的关键路径，界面标注「实验性」 */
    val experimental: Boolean,
    /** 平台登录页，WebView 登录从这里开始 */
    val webLoginUrl: String,
    /** 取 cookie 的探测地址，必须落在平台自己的域名下 */
    val cookieProbeUrl: String,
) {
    CHAOXING(
        id = "chaoxing",
        displayName = "学习通",
        color = 0xFF2F6BF6,
        experimental = false,
        webLoginUrl = "https://passport2.chaoxing.com/login?fid=&refer=https%3A%2F%2Fi.xuexitong.com",
        cookieProbeUrl = "https://mooc1-api.chaoxing.com",
    ),
    YUKETANG(
        id = "yuketang",
        displayName = "雨课堂",
        color = 0xFF7C5CFC,
        experimental = true,
        webLoginUrl = "https://changjiang.yuketang.cn/",
        cookieProbeUrl = "https://changjiang.yuketang.cn",
    ),
    TRONCLASS(
        id = "tronclass",
        displayName = "学在重邮",
        color = 0xFF12A87A,
        experimental = false,
        webLoginUrl = "http://lms.tc.cqupt.edu.cn/",
        cookieProbeUrl = "http://lms.tc.cqupt.edu.cn",
    ),
    PIGAI(
        id = "pigai",
        displayName = "批改网",
        color = 0xFFE08A00,
        experimental = true,
        webLoginUrl = "https://www.pigai.org/",
        cookieProbeUrl = "https://www.pigai.org",
    );

    companion object {
        val ORDER: List<Platform> = entries.toList()

        fun ofId(id: String): Platform? = entries.firstOrNull { it.id == id }
    }
}

/** 作业提交状态。只有三态；认不出的状态必须是 unknown，不能推断成已交。 */
enum class SubmissionStatus(val id: String, val label: String) {
    SUBMITTED("submitted", "已提交"),
    UNSUBMITTED("unsubmitted", "未提交"),
    UNKNOWN("unknown", "未知");

    companion object {
        fun ofId(id: String): SubmissionStatus = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** 由截止时间与当前时间即时计算，不落库 */
enum class Phase { OPEN, CLOSED, UNKNOWN }

/** 截止时间是怎么来的，用于界面解释与排查 */
enum class DeadlineSource(val id: String, val label: String) {
    PLATFORM_EXACT("platform_exact", "平台精确"),
    DETAIL_EXACT("detail_exact", "详情精确"),
    DERIVED_REMAINING("derived_remaining", "剩余推算"),
    INFERRED_YEAR("inferred_year", "年份推断"),
    UNKNOWN("unknown", "未知");

    companion object {
        fun ofId(id: String): DeadlineSource = entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** 账号当前的登录态 */
enum class LoginState(val id: String, val label: String) {
    DISCONNECTED("disconnected", "未登录"),
    CONNECTED("connected", "已登录"),
    NEEDS_LOGIN("needs_login", "需要重新登录"),
    ERROR("error", "登录异常");

    companion object {
        fun ofId(id: String): LoginState = entries.firstOrNull { it.id == id } ?: DISCONNECTED
    }
}

/** 一次平台刷新任务的终态 */
enum class RunStatus(val id: String, val label: String) {
    RUNNING("running", "进行中"),
    SUCCESS("success", "成功"),
    PARTIAL("partial", "部分成功"),
    FAILED("failed", "失败"),
    CANCELED("canceled", "已取消");

    companion object {
        fun ofId(id: String): RunStatus = entries.firstOrNull { it.id == id } ?: FAILED
    }
}

/** 问题影响的范围 */
enum class IssueScope(val id: String) {
    PLATFORM("platform"), COURSE("course"), ASSIGNMENT("assignment");

    companion object {
        fun ofId(id: String): IssueScope = entries.firstOrNull { it.id == id } ?: PLATFORM
    }
}

/** 问题分类，决定界面提示与是否可重试 */
enum class IssueKind(val id: String, val label: String) {
    AUTH("auth", "登录态"),
    NETWORK("network", "网络"),
    PARSE("parse", "解析"),
    BUSINESS("business", "业务"),
    CANCELED("canceled", "取消"),
    INTERNAL("internal", "内部");

    companion object {
        fun ofId(id: String): IssueKind = entries.firstOrNull { it.id == id } ?: INTERNAL
    }
}

/** 一条 cookie（平台内全局，不按 host 过滤） */
data class Cookie(val name: String, val value: String)

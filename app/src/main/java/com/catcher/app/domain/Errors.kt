package com.catcher.app.domain

import com.catcher.app.domain.model.IssueKind

/** 领域错误分类，与电脑端的 entity.Err* 一一对应 */
enum class ErrorKind {
    /** 从未登录 */
    AUTH_REQUIRED,

    /** 登录态失效，需要用户重新登录 */
    AUTH_EXPIRED,

    /** 登录被平台拒绝（账号/密码/会话不对） */
    AUTH_FAILED,

    /** 网络层失败：超时、连接、5xx */
    NETWORK,

    /** 平台明确表示的业务失败 */
    BUSINESS,

    /** 平台页面/接口结构变化，解析失败 */
    PLATFORM_CHANGED,

    CANCELED,
    INTERNAL,
}

/**
 * 带分类的领域异常。
 *
 * 用异常而不是 Result，是为了让各平台适配器逐层包装错误时还能保留最原始的原因；
 * 界面只读 [kind] 与 [message]。
 */
class CatcherException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    companion object {
        fun authRequired(message: String) = CatcherException(ErrorKind.AUTH_REQUIRED, message)
        fun authExpired(message: String) = CatcherException(ErrorKind.AUTH_EXPIRED, message)
        fun authFailed(message: String) = CatcherException(ErrorKind.AUTH_FAILED, message)
        fun network(message: String, cause: Throwable? = null) = CatcherException(ErrorKind.NETWORK, message, cause)
        fun business(message: String) = CatcherException(ErrorKind.BUSINESS, message)
        fun changed(message: String) = CatcherException(ErrorKind.PLATFORM_CHANGED, message)
        fun internal(message: String, cause: Throwable? = null) = CatcherException(ErrorKind.INTERNAL, message, cause)
        fun canceled() = CatcherException(ErrorKind.CANCELED, "已取消")
    }
}

/** 把领域错误映射成问题分类 */
fun ErrorKind.toIssueKind(): IssueKind = when (this) {
    ErrorKind.AUTH_REQUIRED, ErrorKind.AUTH_EXPIRED, ErrorKind.AUTH_FAILED -> IssueKind.AUTH
    ErrorKind.NETWORK -> IssueKind.NETWORK
    ErrorKind.BUSINESS -> IssueKind.BUSINESS
    ErrorKind.PLATFORM_CHANGED -> IssueKind.PARSE
    ErrorKind.CANCELED -> IssueKind.CANCELED
    ErrorKind.INTERNAL -> IssueKind.INTERNAL
}

/** 错误码：与电脑端 service.ErrorCode 同构，方便对照排查 */
fun ErrorKind.code(): String = when (this) {
    ErrorKind.AUTH_REQUIRED -> "AUTH_REQUIRED"
    ErrorKind.AUTH_EXPIRED -> "AUTH_EXPIRED"
    ErrorKind.AUTH_FAILED -> "AUTH_FAILED"
    ErrorKind.NETWORK -> "NETWORK"
    ErrorKind.BUSINESS -> "BUSINESS"
    ErrorKind.PLATFORM_CHANGED -> "PLATFORM_CHANGED"
    ErrorKind.CANCELED -> "CANCELED"
    ErrorKind.INTERNAL -> "INTERNAL"
}

/** 把任意异常收敛成领域异常 */
fun Throwable.asCatcher(): CatcherException = when (this) {
    is CatcherException -> this
    else -> CatcherException(ErrorKind.INTERNAL, message ?: this::class.java.simpleName, this)
}

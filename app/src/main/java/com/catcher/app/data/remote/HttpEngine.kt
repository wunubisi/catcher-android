package com.catcher.app.data.remote

import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.model.Cookie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一次 HTTP 响应。状态码不在这里抛错，由各平台按自己的语义判定。 */
class HttpResponse(
    val code: Int,
    val headers: Headers,
    val url: String,
    val body: ByteArray,
) {
    val text: String get() = String(body, Charsets.UTF_8)

    /** 原始 Set-Cookie 行；cookie 表由平台自己维护，不用 OkHttp 的 CookieJar */
    val setCookies: List<String> get() = headers.values("Set-Cookie")

    val location: String get() = headers.get("Location").orEmpty().trim()

    val contentType: String get() = headers.get("Content-Type").orEmpty()

    val isRedirect: Boolean get() = code in 300..399
}

/**
 * 网络层。与电脑端的 internal/infra/sender 对应：
 *
 * - **恒不跟随重定向**：登录链与详情页必须逐跳手工跟随，才能逐跳累积 Set-Cookie；
 * - 也不做 host 过滤的 cookie 表 —— 见 [CookieBag]；
 * - 传输层失败统一折成 [CatcherException]（NETWORK），状态码交给调用方判定。
 */
class HttpEngine(
    connectTimeoutMs: Long = 15_000,
    readTimeoutMs: Long = 30_000,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 发送请求。[timeoutMs] 只影响本次调用（挂起等待），0 表示用客户端默认值
     */
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        timeoutMs: Long = 0,
    ): HttpResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val requestBody = if (method == "GET" || method == "HEAD") null else (body ?: EMPTY_BODY)
        builder.method(method, requestBody)
        val call = client.newCall(builder.build())
        return try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { call.await() }
            } else {
                call.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw CatcherException.network("请求超时（${timeoutMs}ms）", e)
        }
    }

    /** 把阻塞的 OkHttp 调用挂起化，并让协程取消能真的掐断连接 */
    private suspend fun Call.await(): HttpResponse =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    if (call.isCanceled()) {
                        cont.cancel(CancellationException("已取消"))
                        return
                    }
                    cont.resumeWithException(
                        CatcherException.network(
                            "网络请求失败: ${e.message ?: e.javaClass.simpleName}", e
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val bodyBytes = try {
                        response.body?.bytes() ?: ByteArray(0)
                    } catch (e: IOException) {
                        response.close()
                        if (cont.isActive) {
                            cont.resumeWithException(
                                CatcherException.network("读取响应失败: ${e.message}", e)
                            )
                        }
                        return
                    }
                    val result = HttpResponse(
                        code = response.code,
                        headers = response.headers,
                        url = response.request.url.toString(),
                        body = bodyBytes,
                    )
                    response.close()
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    companion object {
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        fun formBody(value: String): RequestBody =
            value.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType())

        fun jsonBody(value: String): RequestBody =
            value.toRequestBody("application/json; charset=UTF-8".toMediaType())

        fun textBody(value: String, contentType: String): RequestBody =
            value.toRequestBody(contentType.toMediaType())
    }
}

/** 桌面浏览器 UA。学习通/批改网会拦截 Go 或 OkHttp 的默认 UA。 */
const val DESKTOP_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

/**
 * 平台内全局 cookie 袋。与电脑端 chaoxing/pigai 的 cookieJar 完全一致：
 *
 * 刻意不做 host / path 过滤。登录链会跨 passport2 与 mooc1-api 两个域名，
 * 标准 cookiejar 会按 host-only 规则丢掉 JSESSIONID 这类凭据。
 */
class CookieBag(initial: List<Cookie> = emptyList()) {
    private val values = LinkedHashMap<String, String>()
    private val order = ArrayList<String>()

    var changed: Boolean = false
        private set

    init {
        initial.forEach { if (it.name.isNotEmpty()) set(it.name, it.value) }
        changed = false
    }

    /** 累积原始 Set-Cookie 行：只取第一段 name=value，不解析 Domain/Path/过期 */
    fun add(lines: List<String>) {
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val name = line.substring(0, eq).trim()
            if (name.isEmpty()) continue
            var value = line.substring(eq + 1)
            val semi = value.indexOf(';')
            if (semi >= 0) value = value.substring(0, semi)
            set(name, value.trim())
        }
    }

    fun set(name: String, value: String) {
        if (values[name] == value) return
        if (!values.containsKey(name)) order.add(name)
        values[name] = value
        changed = true
    }

    fun get(name: String): String? = values[name]

    /** 会话已经交接给上层加密保存，复位变更标记 */
    fun markPersisted() {
        changed = false
    }

    fun header(): String =
        if (order.isEmpty()) "" else order.joinToString("; ") { "$it=${values[it]}" }

    fun toList(): List<Cookie> = order.map { Cookie(it, values[it] ?: "") }

    companion object {
        /** 解析 "a=1; b=2" 这种粘贴进来的 cookie 串 */
        fun parse(raw: String): CookieBag {
            val bag = CookieBag()
            raw.split(';', '\n').forEach { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@forEach
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name.isNotEmpty()) bag.set(name, value)
            }
            bag.changed = false
            return bag
        }
    }
}

/**
 * 状态码 → 领域错误。各平台语义略有差异，用 [redirectMeansAuthExpired] 等开关区分。
 */
fun classifyStatus(
    code: Int,
    redirectMeansAuthExpired: Boolean = true,
    serverErrorIsNetwork: Boolean = true,
): CatcherException = when {
    code in 300..399 && redirectMeansAuthExpired ->
        CatcherException.authExpired("请求被重定向（HTTP $code）")
    code in 300..399 ->
        CatcherException.changed("请求被重定向（HTTP $code）")
    code == 401 || code == 403 -> CatcherException.authExpired("HTTP $code")
    code >= 500 && serverErrorIsNetwork -> CatcherException.network("服务端错误（HTTP $code）")
    code >= 500 -> CatcherException.changed("服务端错误（HTTP $code）")
    else -> CatcherException.changed("HTTP $code")
}

/** 传输层失败的统一兜底：把 Connection/Timeout 折成 NETWORK，取消折成 CANCELED */
fun Throwable.toCatcherNetwork(): CatcherException = when (this) {
    is CatcherException -> this
    is CancellationException -> CatcherException.canceled()
    else -> CatcherException.network(message ?: "网络请求失败", this)
}

/** JSON 数字字段：平台会把数字写成字符串、空串或 null，统一按「读不出就 null」处理 */
fun jsonNumberOrNull(value: Any?): String? {
    if (value == null) return null
    val text = value.toString().trim()
    if (text.isEmpty() || text == "null") return null
    return text
}

/** 只有 10 位才算 Unix 秒；位数不对宁可报错也不猜 */
fun parseUnixSeconds(raw: String?): Long? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s == "0") return null
    if (s.length != 10 || !s.all { it.isDigit() }) {
        throw CatcherException.changed("时间字段不是 10 位 Unix 秒: \"$raw\"")
    }
    return s.toLong() * 1000
}

/** 13 位毫秒 */
fun parseUnixMillis(raw: String?): Long? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    if (s.length != 13 || !s.all { it.isDigit() }) return null
    return s.toLong()
}

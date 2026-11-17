package com.catcher.app.data

import com.catcher.app.data.db.CatcherDao
import com.catcher.app.data.db.PersistInput
import com.catcher.app.data.prefs.AppSettings
import com.catcher.app.data.secret.SecretVault
import com.catcher.app.domain.AdapterRegistry
import com.catcher.app.domain.AssignmentQuery
import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.ErrorKind
import com.catcher.app.domain.PlatformAdapter
import com.catcher.app.domain.model.Account
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.FetchOptions
import com.catcher.app.domain.model.FetchResult
import com.catcher.app.domain.model.FetchRun
import com.catcher.app.domain.model.IssueScope
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.Progress
import com.catcher.app.domain.model.RunStatus
import com.catcher.app.domain.model.Session
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.domain.model.maskPhone
import com.catcher.app.domain.toIssueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 单平台刷新的结果，等价于电脑端的 FinishedEvent */
data class PlatformRefreshResult(
    val platform: Platform,
    val status: RunStatus,
    val runId: Long = 0,
    val coursesTotal: Int = 0,
    val coursesOk: Int = 0,
    val itemsSeen: Int = 0,
    val itemsUpserted: Int = 0,
    val warnings: List<FetchIssue> = emptyList(),
    val error: CatcherException? = null,
) {
    val isFailure: Boolean get() = status == RunStatus.FAILED
}

/**
 * 业务门面。对应电脑端 internal/service.Service：把适配器、存储、凭据加密串起来。
 *
 * 界面只跟它说话，不认识 SQL 也不认识 HTTP。
 */
class CatcherRepository(
    private val dao: CatcherDao,
    private val vault: SecretVault,
    private val registry: AdapterRegistry,
    val settings: AppSettings,
) {
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun bootstrap() = io { dao.ensurePlatforms(System.currentTimeMillis()) }

    suspend fun accounts(): Map<Platform, Account> =
        io { dao.listAccounts().associateBy { it.platform } }

    suspend fun loadAssignments(): List<Assignment> = io { dao.listActiveAssignments() }

    suspend fun lastRuns(): Map<Platform, FetchRun> = io { dao.latestRuns() }

    suspend fun issues(runId: Long): List<FetchIssue> = io { dao.listIssues(runId) }

    suspend fun hasSession(platform: Platform): Boolean = io {
        val account = dao.getAccount(platform) ?: return@io false
        dao.getSessionCipher(account.id) != null
    }

    // ---------------------------------------------------------------- 登录

    suspend fun loginWithPassword(platform: Platform, phone: String, password: String): String {
        val adapter = registry.of(platform)
            ?: throw CatcherException.internal("${platform.displayName} 适配器未注册")
        val auth = adapter.passwordAuth
            ?: throw CatcherException.business("${platform.displayName} 不支持密码登录")
        val session = auth.loginWithPassword(phone, password)
        return io {
            saveSessionLocked(platform, session, maskPhone(phone.trim()))
            maskPhone(phone.trim())
        }
    }

    suspend fun loginWithCookies(platform: Platform, rawCookie: String): String {
        val adapter = registry.of(platform)
            ?: throw CatcherException.internal("${platform.displayName} 适配器未注册")
        val importer = adapter.cookieImporter
            ?: throw CatcherException.business("${platform.displayName} 不支持 cookie 导入")
        val session = importer.importCookies(rawCookie)
        return io {
            saveSessionLocked(platform, session, "")
            ""
        }
    }

    suspend fun loginWithSession(platform: Platform, rawSession: String): String {
        val adapter = registry.of(platform)
            ?: throw CatcherException.internal("${platform.displayName} 适配器未注册")
        val importer = adapter.sessionImporter
            ?: throw CatcherException.business("${platform.displayName} 不支持 session 导入")
        val session = importer.importSession(rawSession)
        return io {
            saveSessionLocked(platform, session, "")
            ""
        }
    }

    /** 校验已保存的登录态是否还能用 */
    suspend fun validateSession(platform: Platform): String {
        val session = readSession(platform)
            ?: throw CatcherException.authRequired("尚未登录")
        val adapter = registry.of(platform)
            ?: throw CatcherException.internal("${platform.displayName} 适配器未注册")
        return adapter.validate(session)
    }

    private fun saveSessionLocked(platform: Platform, session: Session, displayName: String) {
        val account = dao.getAccount(platform)
            ?: throw CatcherException.internal("账号行不存在，请重启应用")
        val now = System.currentTimeMillis()
        dao.saveSession(account.id, vault.encrypt(session.secretJson), now)
        dao.setLoginState(platform, LoginState.CONNECTED, now)
        if (displayName.isNotEmpty()) dao.setDisplayName(platform, displayName, now)
    }

    suspend fun logout(platform: Platform) = io {
        val account = dao.getAccount(platform) ?: return@io
        dao.deleteSession(account.id)
        dao.setLoginState(platform, LoginState.DISCONNECTED, System.currentTimeMillis())
    }

    private suspend fun readSession(platform: Platform): Session? = io {
        val account = dao.getAccount(platform) ?: return@io null
        val cipher = dao.getSessionCipher(account.id) ?: return@io null
        Session(platform = platform, secretJson = vault.decrypt(cipher))
    }

    // ---------------------------------------------------------------- 作业

    suspend fun setManualStatus(id: Long, status: SubmissionStatus?): Assignment? = io {
        dao.setManualStatus(id, status, System.currentTimeMillis())
    }

    fun writeSnapshot(input: PersistInput): Int = dao.saveSnapshot(input)

    fun accountOf(platform: Platform): Account? = dao.getAccount(platform)

    fun createRun(platform: Platform, accountId: Long, startedAt: Long): Long =
        dao.createRun(platform, accountId, startedAt)

    fun finishRun(run: FetchRun, issues: List<FetchIssue>, at: Long) =
        dao.finishRun(run, issues, at)

    fun setLoginState(platform: Platform, state: LoginState, at: Long) =
        dao.setLoginState(platform, state, at)

    // ---------------------------------------------------------------- 刷新

    /**
     * 刷新单个平台。与电脑端 service.refreshPlatform 的流程逐段对应：
     * 取账号 → 解密会话 → 建刷新记录 → 抓取 → 落快照 → 记刷新结果 → 更新登录态。
     */
    suspend fun refresh(
        platform: Platform,
        onProgress: (Progress) -> Unit,
    ): PlatformRefreshResult = withContext(Dispatchers.IO) {
        val adapter = registry.of(platform)
            ?: return@withContext PlatformRefreshResult(
                platform = platform,
                status = RunStatus.FAILED,
                error = CatcherException.internal("该平台适配器尚未实现"),
            )

        val account = dao.getAccount(platform)
            ?: return@withContext PlatformRefreshResult(
                platform = platform,
                status = RunStatus.FAILED,
                error = CatcherException.internal("账号行不存在"),
            )

        val session = try {
            readSession(platform) ?: throw CatcherException.authRequired("尚未登录")
        } catch (e: CatcherException) {
            // 从来没登录过是「未登录」，不是「需要重新登录」——后者会让用户以为登录态失效了
            val state = if (e.kind == ErrorKind.AUTH_REQUIRED) {
                LoginState.DISCONNECTED
            } else {
                LoginState.NEEDS_LOGIN
            }
            dao.setLoginState(platform, state, System.currentTimeMillis())
            return@withContext PlatformRefreshResult(
                platform = platform,
                status = RunStatus.FAILED,
                warnings = listOf(
                    FetchIssue(IssueScope.PLATFORM, "", ErrorKind.AUTH_REQUIRED.toIssueKind(), "尚未登录")
                ),
                error = e,
            )
        }

        val startedAt = System.currentTimeMillis()
        val runId = dao.createRun(platform, account.id, startedAt)
        val options = FetchOptions(
            now = { System.currentTimeMillis() },
            onSessionUpdated = { updated ->
                dao.saveSession(account.id, vault.encrypt(updated.secretJson), System.currentTimeMillis())
                dao.setLoginState(platform, LoginState.CONNECTED, System.currentTimeMillis())
            },
        )

        var currentSession = session
        var result = try {
            fetchWithRetry(adapter, currentSession, options, onProgress)
        } catch (e: CatcherException) {
            // 自动续期只做一次，防止无限循环
            if (e.kind == ErrorKind.AUTH_EXPIRED) {
                val renewed = tryRenew(platform, currentSession)
                if (renewed != null) {
                    currentSession = renewed
                    dao.saveSession(
                        account.id,
                        vault.encrypt(renewed.secretJson),
                        System.currentTimeMillis(),
                    )
                    try {
                        fetchWithRetry(adapter, currentSession, options, onProgress)
                    } catch (retryError: CatcherException) {
                        return@withContext finishWithError(platform, runId, account.id, retryError)
                    }
                } else {
                    return@withContext finishWithError(
                        platform, runId, account.id,
                        CatcherException.authExpired("${e.message}（自动续期失败，请重新登录）"),
                    )
                }
            } else {
                return@withContext finishWithError(platform, runId, account.id, e)
            }
        }

        val upserted = try {
            dao.saveSnapshot(
                PersistInput(
                    accountId = account.id,
                    platform = platform,
                    runId = runId,
                    result = result,
                    at = System.currentTimeMillis(),
                )
            )
        } catch (e: Exception) {
            return@withContext finishWithError(
                platform, runId, account.id,
                CatcherException.internal("写入本地数据库失败: ${e.message}", e),
            )
        }

        // 列表没抓全才算部分失败；平台本来就只给这么多（partialByDesign）不算
        val incomplete = !result.complete && !result.partialByDesign
        val status = if (incomplete || result.warnings.isNotEmpty()) RunStatus.PARTIAL else RunStatus.SUCCESS
        val failedCourses = result.warnings
            .asSequence()
            .filter { it.scope == IssueScope.COURSE && it.ref.isNotEmpty() }
            .map { it.ref }
            .distinct()
            .count()

        val run = FetchRun(
            id = runId,
            accountId = account.id,
            platform = platform,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            status = status,
            coursesTotal = result.courses.size,
            coursesOk = (result.courses.size - failedCourses).coerceAtLeast(0),
            itemsSeen = result.assignments.size,
            itemsUpserted = upserted,
            warningCount = result.warnings.size,
        )
        dao.finishRun(run, result.warnings, System.currentTimeMillis())
        dao.setLoginState(platform, LoginState.CONNECTED, System.currentTimeMillis())

        PlatformRefreshResult(
            platform = platform,
            status = status,
            runId = runId,
            coursesTotal = run.coursesTotal,
            coursesOk = run.coursesOk,
            itemsSeen = run.itemsSeen,
            itemsUpserted = run.itemsUpserted,
            warnings = result.warnings,
        )
    }

    private suspend fun tryRenew(platform: Platform, session: Session): Session? {
        val renewer = registry.of(platform)?.renewer ?: return null
        return try {
            renewer.renew(session)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 抓取一次，网络类错误重试一次。
     *
     * 电脑端在固定网络下不重试；手机上换 Wi-Fi、切流量、DNS 抖动都很常见，
     * 一次 DNS 失败就整次刷新失败太脆，所以这里只对 NETWORK 重试一次，
     * 认证失效、解析失败、取消都不重试。
     */
    private suspend fun fetchWithRetry(
        adapter: PlatformAdapter,
        session: Session,
        options: FetchOptions,
        onProgress: (Progress) -> Unit,
    ): FetchResult = try {
        adapter.fetch(session, options, onProgress)
    } catch (e: CatcherException) {
        if (e.kind != ErrorKind.NETWORK) throw e
        delay(NETWORK_RETRY_DELAY_MS)
        adapter.fetch(session, options, onProgress)
    }

    private fun finishWithError(
        platform: Platform,
        runId: Long,
        accountId: Long,
        error: CatcherException,
    ): PlatformRefreshResult {
        val status = if (error.kind == ErrorKind.CANCELED) RunStatus.CANCELED else RunStatus.FAILED
        val issue = FetchIssue(
            scope = IssueScope.PLATFORM,
            kind = error.kind.toIssueKind(),
            message = error.message ?: error.kind.name,
        )
        val run = FetchRun(
            id = runId,
            accountId = accountId,
            platform = platform,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            status = status,
            warningCount = 1,
            errorSummary = issue.message,
        )
        runCatching { dao.finishRun(run, listOf(issue), System.currentTimeMillis()) }

        val loginState = when (error.kind) {
            ErrorKind.AUTH_REQUIRED -> LoginState.DISCONNECTED
            ErrorKind.AUTH_EXPIRED, ErrorKind.AUTH_FAILED -> LoginState.NEEDS_LOGIN
            ErrorKind.NETWORK -> LoginState.ERROR
            else -> null
        }
        if (loginState != null) {
            runCatching { dao.setLoginState(platform, loginState, System.currentTimeMillis()) }
        }
        return PlatformRefreshResult(
            platform = platform,
            status = status,
            runId = runId,
            warnings = listOf(issue),
            error = error,
        )
    }

    private companion object {
        const val NETWORK_RETRY_DELAY_MS = 1_200L
    }
}

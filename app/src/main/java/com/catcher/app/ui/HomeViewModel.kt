package com.catcher.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.catcher.app.data.CatcherRepository
import com.catcher.app.domain.AssignmentQuery
import com.catcher.app.domain.AssignmentSummary
import com.catcher.app.domain.CatcherException
import com.catcher.app.domain.model.Account
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.AssignmentFilter
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.FetchRun
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.Progress
import com.catcher.app.domain.model.RunStatus
import com.catcher.app.domain.model.SubmissionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 平台卡片的展示模型 */
data class PlatformUi(
    val platform: Platform,
    val account: Account? = null,
    val running: Progress? = null,
    val lastRun: FetchRun? = null,
    val pendingCount: Int = 0,
    val enabled: Boolean = true,
) {
    val loginState: LoginState get() = account?.loginState ?: LoginState.DISCONNECTED
    val displayName: String get() = account?.displayName.orEmpty()
}

/** 登录面板状态 */
data class LoginUi(
    val platform: Platform,
    val busy: Boolean = false,
    val error: String? = null,
)

data class UiState(
    val ready: Boolean = false,
    val platforms: List<PlatformUi> = Platform.ORDER.map { PlatformUi(it) },
    val all: List<Assignment> = emptyList(),
    val visible: List<Assignment> = emptyList(),
    val summary: AssignmentSummary = AssignmentSummary(),
    val filter: AssignmentFilter = AssignmentFilter(),
    val keyword: String = "",
    val searching: Boolean = false,
    val toast: String? = null,
    val detail: Assignment? = null,
    val login: LoginUi? = null,
    val settingsOpen: Boolean = false,
    val runSheet: Platform? = null,
    val runSheetIssues: List<FetchIssue> = emptyList(),
) {
    val refreshing: Boolean get() = platforms.any { it.running != null }
    val activeFilterCount: Int
        get() = filter.platforms.size +
            (if (keyword.isNotBlank()) 1 else 0) +
            (if (filter.showSubmitted) 1 else 0) +
            (if (filter.termOnly) 1 else 0)
}

/**
 * 界面状态机。所有副作用（网络、数据库、加密）都通过 [CatcherRepository]，
 * 这里只做状态编排，和电脑端前端 stores/app.ts 的角色一致。
 */
class HomeViewModel(private val repo: CatcherRepository) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 每平台最多一个刷新任务，等价于电脑端 service.running 表 */
    private val jobs = mutableMapOf<Platform, Job>()

    init {
        viewModelScope.launch {
            repo.bootstrap()
            reload()
            _state.update { it.copy(ready = true) }
        }
    }

    // ------------------------------------------------------------ 数据加载

    private suspend fun reload() {
        val accounts = repo.accounts()
        val all = repo.loadAssignments()
        val runs = repo.lastRuns()
        val now = System.currentTimeMillis()
        val enabled = repo.settings.enabledPlatforms

        _state.update { s ->
            val filter = s.filter.copy(
                showSubmitted = repo.settings.showSubmitted,
                termOnly = repo.settings.termOnly,
                deadlineFrom = if (repo.settings.termOnly) AssignmentQuery.semesterStart(now) else null,
            )
            val pending = AssignmentQuery.pendingByPlatform(all, now)
            val platforms = Platform.ORDER.map { p ->
                PlatformUi(
                    platform = p,
                    account = accounts[p],
                    running = s.platforms.firstOrNull { it.platform == p }?.running,
                    lastRun = runs[p],
                    pendingCount = pending[p] ?: 0,
                    enabled = p in enabled,
                )
            }
            s.copy(
                platforms = platforms,
                all = all,
                visible = AssignmentQuery.visible(all, filter, now),
                summary = AssignmentQuery.summarize(all, now),
                filter = filter,
            )
        }
    }

    // ------------------------------------------------------------ 刷新

    fun refreshPlatform(platform: Platform) {
        if (jobs[platform]?.isActive == true) return
        jobs[platform] = viewModelScope.launch {
            _state.update { s -> s.withProgress(Progress(platform, "start", message = "准备中")) }
            val result = repo.refresh(platform) { progress ->
                _state.update { s -> s.withProgress(progress) }
            }
            _state.update { s -> s.copy(platforms = s.platforms.map { if (it.platform == platform) it.copy(running = null) else it }) }

            val toast = when {
                result.status == RunStatus.CANCELED -> "${platform.displayName}：已取消"
                result.isFailure -> "${platform.displayName}：${result.error?.message ?: "刷新失败"}"
                result.status == RunStatus.PARTIAL ->
                    "${platform.displayName}：写入 ${result.itemsUpserted} 条，${result.warnings.size} 个提醒"
                else -> "${platform.displayName}：写入 ${result.itemsUpserted} 条作业"
            }
            reload()
            _state.update { it.copy(toast = toast) }
        }
    }

    fun refreshEnabled() {
        val enabled = repo.settings.enabledPlatforms
        viewModelScope.launch {
            // 只刷「勾选了而且确实登录过」的平台：
            // 没登录过的平台刷了只会产出三条「尚未登录」失败记录，没有信息量
            val ready = Platform.ORDER.filter { it in enabled && repo.hasSession(it) }
            if (ready.isEmpty()) {
                _state.update {
                    it.copy(toast = "还没有登录任何平台，长按平台卡可以登录")
                }
                return@launch
            }
            val skipped = Platform.ORDER.count { it in enabled } - ready.size
            if (skipped > 0) {
                _state.update { it.copy(toast = "已跳过 $skipped 个未登录的平台") }
            }
            ready.forEach { refreshPlatform(it) }
        }
    }

    fun cancelRefresh(platform: Platform) {
        jobs[platform]?.cancel()
        _state.update { s ->
            s.copy(
                platforms = s.platforms.map { if (it.platform == platform) it.copy(running = null) else it },
                toast = "${platform.displayName}：正在取消",
            )
        }
    }

    // ------------------------------------------------------------ 筛选

    /** 单选筛选：点中的平台成为唯一选中；再点同一个则取消（回到全部平台） */
    fun togglePlatformFilter(platform: Platform) {
        _state.update { s ->
            val onlyThis = s.filter.platforms == setOf(platform)
            val set = if (onlyThis) emptySet() else setOf(platform)
            applyFilter(s.copy(filter = s.filter.copy(platforms = set)))
        }
    }

    fun setKeyword(keyword: String) {
        _state.update { s -> applyFilter(s.copy(keyword = keyword, filter = s.filter.copy(keyword = keyword))) }
    }

    fun toggleSearch() {
        _state.update { s ->
            if (s.searching) {
                applyFilter(s.copy(searching = false, keyword = "", filter = s.filter.copy(keyword = "")))
            } else {
                s.copy(searching = true)
            }
        }
    }

    fun toggleShowSubmitted() {
        val next = !repo.settings.showSubmitted
        repo.settings.showSubmitted = next
        _state.update { s -> applyFilter(s.copy(filter = s.filter.copy(showSubmitted = next))) }
    }

    fun toggleTermOnly() {
        val next = !repo.settings.termOnly
        repo.settings.termOnly = next
        _state.update { s ->
            applyFilter(
                s.copy(
                    filter = s.filter.copy(
                        termOnly = next,
                        deadlineFrom = if (next) AssignmentQuery.semesterStart(System.currentTimeMillis()) else null,
                    )
                )
            )
        }
    }

    fun togglePlatformEnabled(platform: Platform) {
        val next = repo.settings.enabledPlatforms.toMutableSet()
        val enabled: Boolean
        if (platform in next) {
            next.remove(platform)
            enabled = false
        } else {
            next.add(platform)
            enabled = true
        }
        repo.settings.enabledPlatforms = next
        _state.update { s ->
            // 取消勾选时，如果该平台正在被筛选选中，一并移除，避免它仍过滤作业列表
            val filterPlatforms = if (enabled) s.filter.platforms else s.filter.platforms - platform
            applyFilter(
                s.copy(
                    platforms = s.platforms.map {
                        if (it.platform == platform) it.copy(enabled = enabled) else it
                    },
                    filter = s.filter.copy(platforms = filterPlatforms),
                )
            )
        }
    }

    fun setAllEnabled(enabled: Boolean) {
        val next = if (enabled) Platform.ORDER.toSet() else emptySet()
        repo.settings.enabledPlatforms = next
        _state.update { s ->
            applyFilter(
                s.copy(
                    platforms = s.platforms.map { it.copy(enabled = enabled) },
                    // 全不选要把筛选里选中的平台也清掉，否则列表仍被过滤
                    filter = if (enabled) s.filter else s.filter.copy(platforms = emptySet()),
                )
            )
        }
    }

    /** 一键清空：平台选中、关键词、搜索态，以及两个持久化的口径开关 */
    fun clearFilters() {
        // 这两个开关存在 SharedPreferences 里；清除时必须连设置一起归零，
        // 否则下一次 reload() 又会按旧设置把 chip 点回来
        repo.settings.showSubmitted = false
        repo.settings.termOnly = false
        _state.update { s ->
            applyFilter(
                s.copy(
                    searching = false,
                    keyword = "",
                    filter = AssignmentFilter(),
                )
            )
        }
    }

    private fun applyFilter(s: UiState): UiState {
        val now = System.currentTimeMillis()
        return s.copy(
            visible = AssignmentQuery.visible(s.all, s.filter, now),
            summary = AssignmentQuery.summarize(s.all, now),
        )
    }

    // ------------------------------------------------------------ 手动状态

    fun openDetail(assignment: Assignment) {
        _state.update { it.copy(detail = assignment) }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** status 为 null 表示恢复平台值 */
    fun setManualStatus(id: Long, status: SubmissionStatus?) {
        viewModelScope.launch {
            val updated = repo.setManualStatus(id, status)
            reload()
            _state.update { s ->
                val detail = if (s.detail?.id == id) updated ?: s.detail else s.detail
                s.copy(
                    detail = detail,
                    toast = when (status) {
                        null -> "已恢复平台状态"
                        SubmissionStatus.SUBMITTED -> "已手动标记为已提交"
                        SubmissionStatus.UNSUBMITTED -> "已手动标记为未提交"
                        SubmissionStatus.UNKNOWN -> "已手动标记为未知"
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------ 登录

    fun openLogin(platform: Platform) {
        _state.update { it.copy(login = LoginUi(platform)) }
    }

    fun closeLogin() {
        _state.update { it.copy(login = null) }
    }

    fun loginWithPassword(phone: String, password: String) {
        val target = _state.value.login ?: return
        viewModelScope.launch {
            updateLogin { it.copy(busy = true, error = null) }
            try {
                repo.loginWithPassword(target.platform, phone, password)
                updateLogin { null }
                reload()
                _state.update { it.copy(toast = "${target.platform.displayName}：登录成功") }
                refreshPlatform(target.platform)
            } catch (e: CatcherException) {
                updateLogin { it.copy(busy = false, error = e.message) }
            } catch (e: Exception) {
                updateLogin { it.copy(busy = false, error = e.message ?: "登录失败") }
            }
        }
    }

    fun loginWithCookies(rawCookie: String) {
        val target = _state.value.login ?: return
        viewModelScope.launch {
            updateLogin { it.copy(busy = true, error = null) }
            try {
                repo.loginWithCookies(target.platform, rawCookie)
                updateLogin { null }
                reload()
                _state.update { it.copy(toast = "${target.platform.displayName}：登录成功") }
                refreshPlatform(target.platform)
            } catch (e: CatcherException) {
                updateLogin { it.copy(busy = false, error = e.message) }
            } catch (e: Exception) {
                updateLogin { it.copy(busy = false, error = e.message ?: "登录失败") }
            }
        }
    }

    fun loginWithSession(rawSession: String) {
        val target = _state.value.login ?: return
        viewModelScope.launch {
            updateLogin { it.copy(busy = true, error = null) }
            try {
                repo.loginWithSession(target.platform, rawSession)
                updateLogin { null }
                reload()
                _state.update { it.copy(toast = "${target.platform.displayName}：登录成功") }
                refreshPlatform(target.platform)
            } catch (e: CatcherException) {
                updateLogin { it.copy(busy = false, error = e.message) }
            } catch (e: Exception) {
                updateLogin { it.copy(busy = false, error = e.message ?: "登录失败") }
            }
        }
    }

    /** 网页登录回来后直接导入 cookie */
    fun loginFromWeb(platform: Platform, cookies: String) {
        _state.update { it.copy(login = LoginUi(platform, busy = true)) }
        loginWithCookies(cookies)
    }

    fun logout(platform: Platform) {
        viewModelScope.launch {
            repo.logout(platform)
            reload()
            // 面板不关：账号变成未登录后，登录方式区块就地重新出现
            _state.update { s ->
                s.copy(
                    login = s.login?.copy(busy = false, error = null),
                    toast = "${platform.displayName}：已退出登录",
                )
            }
        }
    }

    private fun updateLogin(transform: (LoginUi) -> LoginUi?) {
        _state.update { s ->
            val current = s.login ?: return@update s
            val next = transform(current)
            if (next == null) s.copy(login = null) else s.copy(login = next)
        }
    }

    // ------------------------------------------------------------ 其他界面动作

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun openRunSheet(platform: Platform) {
        viewModelScope.launch {
            val run = _state.value.platforms.firstOrNull { it.platform == platform }?.lastRun
            val issues = if (run != null && run.id > 0) repo.issues(run.id) else emptyList()
            _state.update { it.copy(runSheet = platform, runSheetIssues = issues) }
        }
    }

    fun closeRunSheet() = _state.update { it.copy(runSheet = null, runSheetIssues = emptyList()) }

    fun clearToast() = _state.update { it.copy(toast = null) }

    // ------------------------------------------------------------ 状态拼装

    private fun UiState.withProgress(progress: Progress): UiState = copy(
        platforms = platforms.map { p ->
            if (p.platform == progress.platform) p.copy(running = progress) else p
        }
    )

    companion object {
        fun factory(repo: CatcherRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HomeViewModel(repo) as T
            }
    }
}

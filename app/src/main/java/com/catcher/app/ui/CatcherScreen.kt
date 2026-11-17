package com.catcher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.domain.AssignmentQuery
import com.catcher.app.domain.DeadlineBucket
import com.catcher.app.domain.model.Platform
import com.catcher.app.ui.components.AssignmentCard
import com.catcher.app.ui.components.EmptyState
import com.catcher.app.ui.components.PlatformCard
import com.catcher.app.ui.components.SectionHeader
import com.catcher.app.ui.components.SummaryHero
import com.catcher.app.ui.sheets.AssignmentDetailSheet
import com.catcher.app.ui.sheets.LoginSheet
import com.catcher.app.ui.sheets.PlatformActionSheet
import com.catcher.app.ui.sheets.RunSheet
import com.catcher.app.ui.sheets.SettingsSheet
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.Canvas
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.WarnAmber
import kotlinx.coroutines.delay

/**
 * 主界面。信息结构与电脑端一致：平台概览 → 汇总 → 作业列表，
 * 但按移动端习惯改成了「按截止时间分组的卡片流」。
 */
@Composable
fun CatcherScreen(vm: HomeViewModel, onWebLogin: (Platform) -> Unit) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<PlatformUi?>(null) }

    // 搜索框打开时，返回键先收搜索，不要把整个界面退出掉
    BackHandler(enabled = state.searching) { vm.toggleSearch() }

    // 每分钟走一次，让「今天 / 还有 3 小时」这类相对时间自己更新
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    LaunchedEffect(state.toast) {
        val message = state.toast
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.clearToast()
        }
    }

    Scaffold(
        containerColor = Canvas,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { RefreshFab(state, vm) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 110.dp),
        ) {
            item { Header(state, onToggleSearch = vm::toggleSearch, onOpenSettings = vm::openSettings) }

            item {
                SummaryHero(
                    summary = state.summary,
                    totalRows = state.all.size,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { PlatformSection(state, vm, onLongPress = { actionTarget = it }) }

            item { FilterRow(state, vm) }

            if (state.searching) {
                item {
                    SearchField(
                        keyword = state.keyword,
                        onChange = vm::setKeyword,
                        onClose = vm::toggleSearch,
                    )
                }
            }

            val sections = AssignmentQuery.sections(state.visible, now)
            if (sections.isEmpty()) {
                item {
                    EmptyState(
                        title = if (state.all.isEmpty()) "还没有作业" else "当前筛选下没有作业",
                        subtitle = if (state.all.isEmpty()) {
                            "长按平台卡登录，然后点右下角「刷新作业」"
                        } else {
                            "试试清空筛选条件，或者打开「含已提交」"
                        },
                    )
                }
            } else {
                sections.forEach { (bucket, list) ->
                    item(key = "h-${bucket.name}") {
                        SectionHeader(bucketTitle(bucket), list.size)
                    }
                    items(list, key = { it.id }) { assignment ->
                        AssignmentCard(assignment, now) { vm.openDetail(assignment) }
                    }
                }
            }

            item { FooterNote() }
        }
    }

    // ---------------------------------------------------------------- 弹层

    state.detail?.let { assignment ->
        AssignmentDetailSheet(
            assignment = assignment,
            now = now,
            onDismiss = vm::closeDetail,
            onSetStatus = { status -> vm.setManualStatus(assignment.id, status) },
        )
    }

    state.login?.let { login ->
        LoginSheet(
            login = login,
            account = state.platforms.firstOrNull { it.platform == login.platform }?.account,
            onDismiss = vm::closeLogin,
            onPassword = vm::loginWithPassword,
            onCookies = vm::loginWithCookies,
            onSession = vm::loginWithSession,
            onWebLogin = { onWebLogin(login.platform) },
            onLogout = { vm.logout(login.platform) },
        )
    }

    if (state.settingsOpen) {
        SettingsSheet(state = state, vm = vm, onDismiss = vm::closeSettings)
    }

    state.runSheet?.let { platform ->
        val run = state.platforms.firstOrNull { it.platform == platform }?.lastRun
        RunSheet(
            platform = platform,
            run = run,
            issues = state.runSheetIssues,
            onDismiss = vm::closeRunSheet,
        )
    }

    actionTarget?.let { target ->
        PlatformActionSheet(
            ui = state.platforms.firstOrNull { it.platform == target.platform } ?: target,
            onDismiss = { actionTarget = null },
            onLogin = { actionTarget = null; vm.openLogin(target.platform) },
            onRefresh = { actionTarget = null; vm.refreshPlatform(target.platform) },
            onRunDetail = { actionTarget = null; vm.openRunSheet(target.platform) },
        )
    }
}

// ---------------------------------------------------------------- 局部组件

@Composable
private fun Header(state: UiState, onToggleSearch: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Catcher",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    letterSpacing = 0.2.sp,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrandBlue.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("安卓版", fontSize = 9.sp, color = BrandBlue, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "catch your work",
                fontSize = 11.sp,
                color = InkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIconButton(
            icon = Icons.Filled.Search,
            contentDescription = "搜索",
            tint = if (state.searching) BrandBlue else InkSecondary,
            onClick = onToggleSearch,
        )
        Spacer(Modifier.width(8.dp))
        RoundIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = "设置",
            tint = InkSecondary,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(CardWhite)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun PlatformSection(
    state: UiState,
    vm: HomeViewModel,
    onLongPress: (PlatformUi) -> Unit,
) {
    val enabled = state.platforms.filter { it.enabled }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "平台概览",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = InkSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (enabled.size > 1) "点卡片筛选 · 长按管理登录" else "长按管理登录",
                fontSize = 10.sp,
                color = InkTertiary,
            )
        }
        if (enabled.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF2F3F8))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Column {
                    Text(
                        "还没有启用任何平台",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = InkSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "去设置里勾选需要抓取的平台，勾选后平台卡会出现在这里。",
                        fontSize = 11.sp,
                        color = InkTertiary,
                    )
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(enabled, key = { it.platform.id }) { ui ->
                    // 选中阴影的扩散空间直接做进条目本身：Box 不裁剪内容，
                    // 行视口高度随条目增长，阴影不可能再被行边界切成直边
                    Box(Modifier.padding(vertical = 10.dp)) {
                        PlatformCard(
                            ui = ui,
                            selected = ui.platform in state.filter.platforms,
                            modifier = Modifier.width(96.dp),
                            onClick = { vm.togglePlatformFilter(ui.platform) },
                            onLongClick = { onLongPress(ui) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(keyword: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    OutlinedTextField(
        value = keyword,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        placeholder = { Text("搜索作业标题或课程名", fontSize = 14.sp) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (keyword.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "清除",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onChange("") },
                )
            } else {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭搜索",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onClose),
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun FilterRow(state: UiState, vm: HomeViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.filter.termOnly,
            onClick = { vm.toggleTermOnly() },
            label = { Text("只看本学期", fontSize = 12.sp) },
            shape = RoundedCornerShape(10.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = BrandBlue.copy(alpha = 0.12f),
                selectedLabelColor = BrandBlue,
            ),
        )
        FilterChip(
            selected = state.filter.showSubmitted,
            onClick = { vm.toggleShowSubmitted() },
            label = { Text("含已提交", fontSize = 12.sp) },
            shape = RoundedCornerShape(10.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = BrandBlue.copy(alpha = 0.12f),
                selectedLabelColor = BrandBlue,
            ),
        )
        if (state.activeFilterCount > 0) {
            Text(
                "清除筛选",
                fontSize = 12.sp,
                color = BrandBlue,
                modifier = Modifier
                    .clickable { vm.clearFilters() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RefreshFab(state: UiState, vm: HomeViewModel) {
    val running = state.platforms.filter { it.running != null }
    ExtendedFloatingActionButton(
        onClick = {
            if (running.isEmpty()) {
                vm.refreshEnabled()
            } else {
                running.forEach { vm.cancelRefresh(it.platform) }
            }
        },
        containerColor = if (running.isEmpty()) BrandBlue else Color(0xFFE9EBF3),
        contentColor = if (running.isEmpty()) Color.White else Ink,
        icon = {
            if (running.isEmpty()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(19.dp))
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = BrandBlue,
                )
            }
        },
        text = {
            Text(
                if (running.isEmpty()) "刷新作业" else "刷新中 ${running.size} · 取消",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        },
    )
}

@Composable
private fun FooterNote() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HairLine)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "不监听本地端口、不上传任何服务器\n登录态用 Android Keystore 加密后存在本机",
            fontSize = 10.5.sp,
            color = InkTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 16.sp,
        )
    }
}

private fun bucketTitle(bucket: DeadlineBucket): String = when (bucket) {
    DeadlineBucket.OVERDUE -> "已过截止"
    DeadlineBucket.TODAY -> "今天截止"
    DeadlineBucket.TOMORROW -> "明天截止"
    DeadlineBucket.THIS_WEEK -> "本周内"
    DeadlineBucket.LATER -> "更晚"
    DeadlineBucket.NO_DEADLINE -> "没有截止时间"
}

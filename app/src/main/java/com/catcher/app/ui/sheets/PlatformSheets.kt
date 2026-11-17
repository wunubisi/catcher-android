package com.catcher.app.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.FetchRun
import com.catcher.app.domain.model.IssueKind
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.RunStatus
import com.catcher.app.ui.Format
import com.catcher.app.ui.PlatformUi
import com.catcher.app.ui.components.PlatformDot
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.DangerRed
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.SuccessGreen
import com.catcher.app.ui.theme.WarnAmber

/** 长按平台卡弹出的操作面板 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformActionSheet(
    ui: PlatformUi,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onRunDetail: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardWhite,
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformDot(ui.platform, 10)
                Spacer(Modifier.width(8.dp))
                Text(
                    ui.platform.displayName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (ui.loginState) {
                        LoginState.CONNECTED -> "已登录"
                        LoginState.DISCONNECTED -> "未登录"
                        LoginState.NEEDS_LOGIN -> "需要重新登录"
                        LoginState.ERROR -> "登录异常"
                    },
                    fontSize = 11.sp,
                    color = if (ui.loginState == LoginState.CONNECTED) SuccessGreen else InkTertiary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "待交 ${ui.pendingCount} 条" +
                    if (ui.lastRun != null) " · 上次刷新 ${Format.shortTime(ui.lastRun.finishedAt ?: ui.lastRun.startedAt)}"
                    else " · 还没刷新过",
                fontSize = 11.sp,
                color = InkTertiary,
            )

            Spacer(Modifier.height(16.dp))
            ActionRow("登录 / 更换账号", "网页登录、手机号密码或导入 cookie", onLogin)
            ActionRow("立即刷新这个平台", "会先校验登录态，失败会提示原因", onRefresh)
            ActionRow("查看上次刷新结果", "包含写入条数与每个提醒的原因", onRunDetail)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, color = InkTertiary)
        }
        Text("›", fontSize = 18.sp, color = InkTertiary)
    }
}

/** 上次刷新详情 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(
    platform: Platform,
    run: FetchRun?,
    issues: List<FetchIssue>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardWhite,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformDot(platform, 10)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${platform.displayName} · 刷新结果",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
            }
            Spacer(Modifier.height(14.dp))

            if (run == null) {
                Text("这个平台还没有刷新记录。", fontSize = 13.sp, color = InkTertiary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("状态", run.status.label, statusColor(run.status))
                    StatTile("课程", "${run.coursesOk}/${run.coursesTotal}", Ink)
                    StatTile("写入", "${run.itemsUpserted}", Ink)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("读到", "${run.itemsSeen}", InkSecondary)
                    StatTile("提醒", "${run.warningCount}", if (run.warningCount > 0) WarnAmber else InkSecondary)
                    StatTile("开始", Format.shortTime(run.startedAt), InkSecondary)
                }

                if (run.errorSummary.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        run.errorSummary,
                        fontSize = 12.sp,
                        color = DangerRed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFDEDED))
                            .padding(10.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "提醒 ${issues.size} 条",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = InkSecondary,
                )
                Spacer(Modifier.height(6.dp))
                if (issues.isEmpty()) {
                    Text(
                        if (run.itemsSeen == 0) {
                            "这次刷新没有读到作业：账号下暂时没有作业，或者课程还没布置。"
                        } else {
                            "这次刷新没有需要处理的问题。"
                        },
                        fontSize = 11.sp,
                        color = InkTertiary,
                        lineHeight = 17.sp,
                    )
                } else {
                    issues.forEach { issue ->
                        IssueRow(issue)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF5F6FB))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 9.5.sp, color = InkTertiary)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun IssueRow(issue: FetchIssue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(kindColor(issue.kind))
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                issue.kind.label + when (issue.scope) {
                    com.catcher.app.domain.model.IssueScope.PLATFORM -> " · 平台"
                    com.catcher.app.domain.model.IssueScope.COURSE -> " · 课程"
                    com.catcher.app.domain.model.IssueScope.ASSIGNMENT -> " · 作业"
                },
                fontSize = 9.5.sp,
                color = kindColor(issue.kind),
                fontWeight = FontWeight.Medium,
            )
            Text(issue.message, fontSize = 11.5.sp, color = InkSecondary, lineHeight = 17.sp)
        }
    }
}

private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.SUCCESS -> SuccessGreen
    RunStatus.PARTIAL -> WarnAmber
    RunStatus.FAILED -> DangerRed
    RunStatus.CANCELED -> InkTertiary
    RunStatus.RUNNING -> BrandBlue
}

private fun kindColor(kind: IssueKind): Color = when (kind) {
    IssueKind.AUTH -> DangerRed
    IssueKind.NETWORK -> WarnAmber
    IssueKind.PARSE -> Color(0xFF7C5CFC)
    IssueKind.BUSINESS -> WarnAmber
    IssueKind.CANCELED -> InkTertiary
    IssueKind.INTERNAL -> InkSecondary
}

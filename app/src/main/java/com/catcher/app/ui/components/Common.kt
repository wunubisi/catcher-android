package com.catcher.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.domain.AssignmentSummary
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.ui.Format
import com.catcher.app.ui.PlatformUi
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.DangerRed
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.SuccessGreen
import com.catcher.app.ui.theme.WarnAmber

/** 平台色点，卡片和作业条共用 */
@Composable
fun PlatformDot(platform: Platform, size: Int = 9) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(platform.color))
    )
}

/** 紧凑状态标签 */
@Composable
fun StatusChip(status: SubmissionStatus, manual: Boolean = false) {
    val (bg, fg, text) = when (status) {
        SubmissionStatus.SUBMITTED -> Triple(Color(0xFFE8F7EF), SuccessGreen, "已提交")
        SubmissionStatus.UNSUBMITTED -> Triple(Color(0xFFFFF1E1), WarnAmber, "未提交")
        SubmissionStatus.UNKNOWN -> Triple(Color(0xFFF0F1F6), InkSecondary, "状态未知")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        if (manual) {
            Text(
                "手动 · ",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = fg.copy(alpha = 0.8f),
            )
        }
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 顶部汇总卡：待交 / 已截止 / 已提交 */
@Composable
fun SummaryHero(summary: AssignmentSummary, totalRows: Int, modifier: Modifier = Modifier) {
    // 透明背景：去掉彩色渐变填充，文字用深色，统计 pill 用白底描边浮在页面上
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            // 透明背景 + 圆角描边框：让整块内容成为一个"无色卡片"
            .border(2.dp, InkTertiary.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            "待交作业",
            color = InkSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                summary.pending.toString(),
                color = Ink,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "项",
                color = InkSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 9.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroPill("已截止 ${summary.closed}")
            HeroPill("已提交 ${summary.submitted}")
            HeroPill("共 $totalRows 条")
        }
    }
}

@Composable
private fun HeroPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(CardWhite)
            .border(1.dp, HairLine, RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text, color = InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 平台卡：点击筛选，长按打开平台操作 */
@Composable
fun PlatformCard(
    ui: PlatformUi,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = Color(ui.platform.color)
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = modifier.height(116.dp),
        shape = shape,
        // 选中态实测下来：半透明底纹会被看成"卡里套了张长方形卡片"，去掉；
        // 确认感交给实色边框 + 圆角阴影 + 数字变强调色
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) accent else HairLine,
        ),
        elevation = CardDefaults.cardElevation(
            // 阴影扩散半径要小于卡片间距/行内边距，否则会被行边界和邻卡
            // 裁成直边，看上去就是"选中后赖着不走的一块长方形"
            defaultElevation = if (selected) 6.dp else 0.dp,
        ),
    ) {
        // 点击区域放在卡片内部：Surface 会把内容按 shape 裁剪，
        // 水波纹因此被圆角裁掉。如果挂在 Card 外层的 modifier 上，
        // 水波纹会按矩形边界绘制，在四个圆角外面露出一块长方形。
        Column(
            Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformDot(ui.platform, 8)
                Spacer(Modifier.width(5.dp))
                Text(
                    ui.platform.displayName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) accent else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    ui.pendingCount.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (ui.pendingCount > 0 || selected) accent else InkTertiary,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "待交",
                    fontSize = 10.sp,
                    color = InkTertiary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val running = ui.running
            if (running != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accent)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新中", fontSize = 10.sp, color = accent)
                }
                Spacer(Modifier.height(5.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (running.total > 0) running.fraction else 0.25f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(50)),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                )
            } else {
                // 登录态要如实显示：网络失败是「登录异常」，不是「未登录」
                val (stateText, stateColor) = when (ui.loginState) {
                    LoginState.CONNECTED -> "已登录" to SuccessGreen
                    LoginState.DISCONNECTED -> "未登录" to Color(0xFFC4C9D6)
                    LoginState.NEEDS_LOGIN -> "需要重新登录" to WarnAmber
                    LoginState.ERROR -> "登录异常，可重试" to DangerRed
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(stateColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stateText, fontSize = 10.sp, color = InkTertiary)
                }
            }
        }
    }
}

/** 一条作业 */
@Composable
fun AssignmentCard(assignment: Assignment, now: Long, onClick: () -> Unit) {
    val accent = Color(assignment.platform.color)
    val urgent = Format.isUrgent(assignment, now)
    val overdue = Format.isOverdue(assignment, now)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        border = BorderStroke(1.dp, HairLine),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformDot(assignment.platform, 6)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        assignment.courseName.ifEmpty { assignment.platform.displayName },
                        fontSize = 11.sp,
                        color = InkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(assignment.effectiveStatus, assignment.manualStatus != null)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    assignment.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = when {
                            overdue -> DangerRed
                            urgent -> WarnAmber
                            else -> InkTertiary
                        },
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        Format.deadlineRelative(assignment.deadline, now),
                        fontSize = 12.sp,
                        fontWeight = if (overdue || urgent) FontWeight.Medium else FontWeight.Normal,
                        color = when {
                            overdue -> DangerRed
                            urgent -> WarnAmber
                            else -> InkSecondary
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    SourceTag(assignment.deadlineSource)
                }
            }
        }
    }
}

/** 让左侧色条撑满卡片高度（配合 Row 的 IntrinsicSize.Min） */

@Composable
private fun SourceTag(source: DeadlineSource) {
    if (source == DeadlineSource.UNKNOWN) return
    Text(
        when (source) {
            DeadlineSource.DERIVED_REMAINING -> "推算"
            DeadlineSource.INFERRED_YEAR -> "推断"
            DeadlineSource.DETAIL_EXACT -> "详情"
            DeadlineSource.PLATFORM_EXACT -> "平台"
            DeadlineSource.UNKNOWN -> ""
        },
        fontSize = 9.sp,
        color = InkTertiary.copy(alpha = 0.85f),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFF2F4FA))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 分组标题 */
@Composable
fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = InkSecondary,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 11.sp, color = InkTertiary)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(HairLine)
        )
    }
}

@Composable
fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("空", fontSize = 18.sp, color = BrandBlue, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = InkTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

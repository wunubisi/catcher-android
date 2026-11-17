package com.catcher.app.ui.sheets

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.SubmissionStatus
import com.catcher.app.ui.Format
import com.catcher.app.ui.components.PlatformDot
import com.catcher.app.ui.components.StatusChip
import com.catcher.app.ui.theme.BrandBlue
import com.catcher.app.ui.theme.CardWhite
import com.catcher.app.ui.theme.HairLine
import com.catcher.app.ui.theme.Ink
import com.catcher.app.ui.theme.InkSecondary
import com.catcher.app.ui.theme.InkTertiary
import com.catcher.app.ui.theme.SuccessGreen

/** 作业详情：查看截止时间来源，并手动标记状态 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentDetailSheet(
    assignment: Assignment,
    now: Long,
    onDismiss: () -> Unit,
    onSetStatus: (SubmissionStatus?) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardWhite,
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformDot(assignment.platform, 9)
                Spacer(Modifier.width(6.dp))
                Text(
                    assignment.platform.displayName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(assignment.platform.color),
                )
                Text(" · ", fontSize = 12.sp, color = InkTertiary)
                Text(
                    assignment.courseName,
                    fontSize = 12.sp,
                    color = InkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFF2F3F8))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = InkSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                assignment.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                lineHeight = 28.sp,
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(assignment.effectiveStatus, assignment.manualStatus != null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (assignment.manualStatus != null) {
                        "平台值：${statusText(assignment.status)}"
                    } else {
                        "来自平台"
                    },
                    fontSize = 11.sp,
                    color = InkTertiary,
                )
            }

            Spacer(Modifier.height(16.dp))
            InfoBlock(
                label = "截止时间",
                value = Format.deadlineAbsolute(assignment.deadline),
                trailing = Format.deadlineRelative(assignment.deadline, now),
                trailingColor = if (Format.isOverdue(assignment, now)) Color(0xFFDC3E42) else InkSecondary,
            )
            Spacer(Modifier.height(10.dp))
            InfoBlock(
                label = "时间来源",
                value = Format.deadlineSourceLabel(assignment.deadlineSource),
                trailing = // 平台原始值：13 位毫秒/10 位秒这类纯数字对用户没有意义，不展示
                assignment.deadlineRaw.let { raw ->
                    val noise = raw.length >= 10 && raw.all { it.isDigit() }
                    if (raw.isNotBlank() && !noise) "原文 $raw" else ""
                },
                trailingColor = InkTertiary,
            )

            if (assignment.description.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("作业说明", fontSize = 11.sp, color = InkTertiary)
                Spacer(Modifier.height(4.dp))
                Text(
                    assignment.description,
                    fontSize = 13.sp,
                    color = InkSecondary,
                    lineHeight = 20.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("手动标记", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = InkSecondary)
            Spacer(Modifier.height(2.dp))
            Text(
                "平台状态拿不准或拿不到时，可以先自己标一下；随时能恢复成平台值。",
                fontSize = 11.sp,
                color = InkTertiary,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkButton(
                    text = "已提交",
                    active = assignment.manualStatus == SubmissionStatus.SUBMITTED,
                    onClick = { onSetStatus(SubmissionStatus.SUBMITTED) },
                )
                MarkButton(
                    text = "未提交",
                    active = assignment.manualStatus == SubmissionStatus.UNSUBMITTED,
                    onClick = { onSetStatus(SubmissionStatus.UNSUBMITTED) },
                )
                MarkButton(
                    text = "恢复平台值",
                    active = assignment.manualStatus == null,
                    onClick = { onSetStatus(null) },
                )
            }

            if (assignment.detailUrl.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(assignment.detailUrl))
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("在浏览器打开原作业页", fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoBlock(
    label: String,
    value: String,
    trailing: String,
    trailingColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.5.sp, color = InkTertiary, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(
                trailing,
                fontSize = 11.sp,
                color = trailingColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MarkButton(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) SuccessGreen.copy(alpha = 0.12f) else Color(0xFFF3F4F9))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) SuccessGreen else InkSecondary,
        )
    }
}

private fun statusText(status: SubmissionStatus): String = when (status) {
    SubmissionStatus.SUBMITTED -> "已提交"
    SubmissionStatus.UNSUBMITTED -> "未提交"
    SubmissionStatus.UNKNOWN -> "状态未知"
}

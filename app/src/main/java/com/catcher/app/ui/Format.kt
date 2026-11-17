package com.catcher.app.ui

import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.Phase
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 界面上的时间与状态文案。统一放这里，避免各组件各写一套。 */
object Format {

    private val monthDay = DateTimeFormatter.ofPattern("M月d日")
    private val monthDayTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")
    private val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun zone(): ZoneId = SHANGHAI

    /** 截止时间的相对描述，例：今天 22:00（还有 8 小时） */
    fun deadlineRelative(deadline: Long?, now: Long = System.currentTimeMillis()): String {
        if (deadline == null) return "没有截止时间"
        val target = Instant.ofEpochMilli(deadline).atZone(SHANGHAI)
        val today = Instant.ofEpochMilli(now).atZone(SHANGHAI).toLocalDate()
        val date = target.toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        val clock = " ${target.format(hourMinute)}"
        return when {
            deadline < now -> "已过 ${humanGap(now - deadline)}"
            days == 0L -> "今天$clock"
            days == 1L -> "明天$clock"
            days == 2L -> "后天$clock"
            days < 7 -> "${days}天后$clock"
            date.year == today.year -> date.format(monthDay) + clock
            else -> target.format(monthDayTime)
        }
    }

    /** 还有多久 / 过去多久 */
    fun humanGap(millis: Long): String {
        val abs = kotlin.math.abs(millis)
        val minutes = abs / 60_000
        val hours = abs / 3_600_000
        val days = abs / 86_400_000
        return when {
            minutes < 1 -> "不到 1 分钟"
            minutes < 60 -> "${minutes} 分钟"
            hours < 24 -> "${hours} 小时"
            days < 30 -> "${days} 天"
            else -> "${days / 30} 个月"
        }
    }

    fun deadlineAbsolute(deadline: Long?): String =
        if (deadline == null) "未知" else Instant.ofEpochMilli(deadline).atZone(SHANGHAI).format(full)

    fun deadlineSourceLabel(source: DeadlineSource): String = when (source) {
        DeadlineSource.PLATFORM_EXACT -> "平台直接给出"
        DeadlineSource.DETAIL_EXACT -> "详情页给出"
        DeadlineSource.DERIVED_REMAINING -> "由「剩余时间」推算"
        DeadlineSource.INFERRED_YEAR -> "由详情页月日 + 年份推断"
        DeadlineSource.UNKNOWN -> "平台未提供"
    }

    fun statusLabel(assignment: Assignment): String = when (assignment.effectiveStatus) {
        SubmissionStatus.SUBMITTED -> "已提交"
        SubmissionStatus.UNSUBMITTED -> "未提交"
        SubmissionStatus.UNKNOWN -> "状态未知"
    }

    /** 角标：已过截止但没交，要显眼 */
    fun isUrgent(assignment: Assignment, now: Long): Boolean {
        if (assignment.effectiveStatus == SubmissionStatus.SUBMITTED) return false
        val d = assignment.deadline ?: return false
        return d - now <= 24 * 3600 * 1000L
    }

    fun isOverdue(assignment: Assignment, now: Long): Boolean =
        assignment.effectiveStatus != SubmissionStatus.SUBMITTED &&
            assignment.phase(now) == Phase.CLOSED

    fun shortTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(SHANGHAI).format(monthDayTime)
}

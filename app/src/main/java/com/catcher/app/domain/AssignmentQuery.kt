package com.catcher.app.domain

import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.AssignmentFilter
import com.catcher.app.domain.model.Phase
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import java.time.Instant
import java.time.LocalDate

/**
 * 界面用的作业分组。移动端把电脑端的一张大表拆成「按截止时间分组」的列表，
 * 但排序与过滤规则和电脑端保持一致。
 */
enum class DeadlineBucket(val label: String) {
    OVERDUE("已过截止"),
    TODAY("今天截止"),
    TOMORROW("明天截止"),
    THIS_WEEK("本周内"),
    LATER("更晚"),
    NO_DEADLINE("没有截止时间"),
}

/** 汇总数字，对应电脑端平台概览上的计数 */
data class AssignmentSummary(
    val pending: Int = 0,
    val submitted: Int = 0,
    val closed: Int = 0,
    val unknownStatus: Int = 0,
) {
    val total: Int get() = pending + submitted + closed
}

/**
 * 过滤 + 排序 + 分组。纯函数，不碰数据库，方便直接推理与测试。
 */
object AssignmentQuery {

    /**
     * 默认视图：只返回 active 记录，并隐藏已提交作业。
     * 与电脑端 entity.AssignmentFilter 的零值语义一致。
     */
    fun visible(
        all: List<Assignment>,
        filter: AssignmentFilter,
        now: Long,
    ): List<Assignment> {
        val keyword = filter.keyword.trim()
        val from = filter.deadlineFrom
        return all.asSequence()
            .filter { it.active }
            .filter { filter.platforms.isEmpty() || it.platform in filter.platforms }
            .filter { filter.showSubmitted || it.effectiveStatus != SubmissionStatus.SUBMITTED }
            .filter { filter.statuses.isEmpty() || it.effectiveStatus in filter.statuses }
            .filter {
                keyword.isEmpty() ||
                    it.title.contains(keyword, ignoreCase = true) ||
                    it.courseName.contains(keyword, ignoreCase = true)
            }
            .filter { from == null || it.deadline == null || it.deadline >= from }
            .sortedWith(comparator(now))
            .toList()
    }

    /** 待处理在前；同组内按截止时间升序，没有截止时间的排最后 */
    private fun comparator(now: Long): Comparator<Assignment> {
        fun priority(a: Assignment): Int = when {
            a.effectiveStatus == SubmissionStatus.SUBMITTED -> 2
            a.phase(now) == Phase.CLOSED -> 1
            else -> 0
        }
        return compareBy<Assignment>(
            { priority(it) },
            { it.deadline == null },
            { it.deadline ?: Long.MAX_VALUE },
            { it.title },
        )
    }

    fun summarize(all: List<Assignment>, now: Long): AssignmentSummary {
        var pending = 0
        var submitted = 0
        var closed = 0
        var unknown = 0
        for (a in all) {
            if (!a.active) continue
            when {
                a.effectiveStatus == SubmissionStatus.SUBMITTED -> submitted++
                a.phase(now) == Phase.CLOSED -> closed++
                a.effectiveStatus == SubmissionStatus.UNKNOWN -> {
                    pending++
                    unknown++
                }
                else -> pending++
            }
        }
        return AssignmentSummary(pending, submitted, closed, unknown)
    }

    fun bucketOf(a: Assignment, now: Long): DeadlineBucket {
        val deadline = a.deadline ?: return DeadlineBucket.NO_DEADLINE
        if (deadline < now) return DeadlineBucket.OVERDUE
        val today = localDate(now)
        val target = localDate(deadline)
        return when {
            target == today -> DeadlineBucket.TODAY
            target == today.plusDays(1) -> DeadlineBucket.TOMORROW
            target.isBefore(today.plusDays(7)) -> DeadlineBucket.THIS_WEEK
            else -> DeadlineBucket.LATER
        }
    }

    /** 按分组顺序拆成可渲染的段落 */
    fun sections(items: List<Assignment>, now: Long): List<Pair<DeadlineBucket, List<Assignment>>> {
        val grouped = items.groupBy { bucketOf(it, now) }
        return DeadlineBucket.entries.mapNotNull { bucket ->
            val list = grouped[bucket] ?: return@mapNotNull null
            if (list.isEmpty()) null else bucket to list
        }
    }

    fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(SHANGHAI).toLocalDate()

    /** 本学期起点：9 月及以后归当年，1—8 月归上一年（与电脑端 SemesterStart 一致） */
    fun semesterStart(now: Long): Long {
        val z = Instant.ofEpochMilli(now).atZone(SHANGHAI)
        val year = if (z.monthValue < 9) z.year - 1 else z.year
        return java.time.ZonedDateTime.of(year, 9, 1, 0, 0, 0, 0, SHANGHAI).toInstant().toEpochMilli()
    }

    /** 平台维度的待交数量，用于平台卡上的角标 */
    fun pendingByPlatform(all: List<Assignment>, now: Long): Map<Platform, Int> {
        val out = mutableMapOf<Platform, Int>()
        for (a in all) {
            if (!a.active || a.effectiveStatus == SubmissionStatus.SUBMITTED) continue
            if (a.phase(now) == Phase.CLOSED) continue
            out[a.platform] = (out[a.platform] ?: 0) + 1
        }
        return out
    }
}

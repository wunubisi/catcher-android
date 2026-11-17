package com.catcher.app.domain

import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.AssignmentFilter
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 列表口径的回归测试：默认视图隐藏已提交、平台/关键词筛选、分组、学期起点、平台角标。
 */
class AssignmentQueryTest {

    private val now = at(2026, 11, 5, 12, 0)

    private fun assignment(
        id: Long,
        title: String,
        course: String = "课程",
        platform: Platform = Platform.CHAOXING,
        status: SubmissionStatus = SubmissionStatus.UNSUBMITTED,
        deadline: Long? = null,
        manual: SubmissionStatus? = null,
    ) = Assignment(
        id = id,
        platform = platform,
        courseExternalId = "c$id",
        courseName = course,
        dedupKey = "k$id",
        title = title,
        status = status,
        manualStatus = manual,
        deadline = deadline,
        deadlineSource = if (deadline == null) DeadlineSource.UNKNOWN else DeadlineSource.PLATFORM_EXACT,
        active = true,
    )

    private val data = listOf(
        assignment(1, "高数作业", deadline = at(2026, 11, 5, 22, 0)),
        assignment(2, "编译原理实验", course = "编译原理", platform = Platform.TRONCLASS, deadline = at(2026, 11, 7, 23, 59)),
        assignment(3, "英语作文", course = "大学英语3", platform = Platform.PIGAI,
            status = SubmissionStatus.SUBMITTED, deadline = at(2026, 11, 12, 23, 59)),
        assignment(4, "已过期的作业", deadline = at(2026, 11, 1, 23, 59)),
        assignment(5, "没有截止时间", status = SubmissionStatus.UNKNOWN),
    )

    @Test
    fun `默认视图隐藏已提交`() {
        val visible = AssignmentQuery.visible(data, AssignmentFilter(), now)
        assertEquals(4, visible.size)
        assertTrue(visible.none { it.effectiveStatus == SubmissionStatus.SUBMITTED })
    }

    @Test
    fun `含已提交时全部可见`() {
        val visible = AssignmentQuery.visible(data, AssignmentFilter(showSubmitted = true), now)
        assertEquals(5, visible.size)
    }

    @Test
    fun `待处理排在前面，同组按截止时间升序，无截止时间排本组最后`() {
        val visible = AssignmentQuery.visible(data, AssignmentFilter(showSubmitted = true), now)
        val ids = visible.map { it.id }
        // 待处理组：1(今天) → 2(7号) → 5(无截止时间)；
        // 之后才是已截止(4)与已提交(3) —— 没有截止时间的待办仍然算要交的，不能沉到已截止下面
        assertEquals(listOf(1L, 2L, 5L, 4L, 3L), ids)
    }

    @Test
    fun `按平台筛选`() {
        val visible = AssignmentQuery.visible(
            data, AssignmentFilter(platforms = setOf(Platform.PIGAI), showSubmitted = true), now,
        )
        assertEquals(listOf(3L), visible.map { it.id })
    }

    @Test
    fun `按标题或课程名搜索`() {
        val byTitle = AssignmentQuery.visible(data, AssignmentFilter(keyword = "编译"), now)
        assertEquals(listOf(2L), byTitle.map { it.id })

        val byCourse = AssignmentQuery.visible(data, AssignmentFilter(keyword = "英语", showSubmitted = true), now)
        assertEquals(listOf(3L), byCourse.map { it.id })
    }

    @Test
    fun `手动状态优先于平台状态`() {
        val item = assignment(9, "手动标记过", deadline = at(2026, 11, 6, 9, 0))
            .copy(manualStatus = SubmissionStatus.SUBMITTED)
        // 手动标成已提交后，默认视图应把它隐藏
        assertTrue(AssignmentQuery.visible(listOf(item), AssignmentFilter(), now).isEmpty())
        assertEquals(1, AssignmentQuery.visible(listOf(item), AssignmentFilter(showSubmitted = true), now).size)
    }

    @Test
    fun `截止时间分组`() {
        assertEquals(DeadlineBucket.TODAY, AssignmentQuery.bucketOf(data[0], now))
        assertEquals(DeadlineBucket.THIS_WEEK, AssignmentQuery.bucketOf(data[1], now))
        assertEquals(DeadlineBucket.OVERDUE, AssignmentQuery.bucketOf(data[3], now))
        assertEquals(DeadlineBucket.NO_DEADLINE, AssignmentQuery.bucketOf(data[4], now))
        assertEquals(
            DeadlineBucket.TOMORROW,
            AssignmentQuery.bucketOf(assignment(10, "明天", deadline = at(2026, 11, 6, 8, 0)), now),
        )
        assertEquals(
            DeadlineBucket.LATER,
            AssignmentQuery.bucketOf(assignment(11, "很远", deadline = at(2026, 12, 1, 8, 0)), now),
        )
    }

    @Test
    fun `汇总口径：已截止单独计数，不计入待交`() {
        val summary = AssignmentQuery.summarize(data, now)
        // 1、2 待交；4 已截止；3 已提交；5 unknown 但仍算待交
        assertEquals(3, summary.pending)
        assertEquals(1, summary.closed)
        assertEquals(1, summary.submitted)
        assertEquals(5, summary.total)
    }

    @Test
    fun `学期起点按 9 月 1 日划线`() {
        assertEquals(
            at(2026, 9, 1, 0, 0),
            AssignmentQuery.semesterStart(at(2026, 11, 5, 12, 0)),
        )
        assertEquals(
            at(2025, 9, 1, 0, 0),
            AssignmentQuery.semesterStart(at(2026, 3, 1, 12, 0)),
        )
        assertEquals(
            at(2025, 9, 1, 0, 0),
            AssignmentQuery.semesterStart(at(2026, 1, 1, 12, 0)),
        )
    }

    @Test
    fun `平台角标不统计已提交与已截止`() {
        val pending = AssignmentQuery.pendingByPlatform(data, now)
        // 学习通：1(今天) + 5(无截止时间但仍是待办)；4 已截止不计
        assertEquals(2, pending[Platform.CHAOXING])
        assertEquals(1, pending[Platform.TRONCLASS])
        // 批改网唯一一条已提交，不出现在角标里
        assertEquals(null, pending[Platform.PIGAI])
    }

    @Test
    fun `只看本学期时保留没有截止时间的作业`() {
        val filter = AssignmentFilter(
            termOnly = true,
            deadlineFrom = AssignmentQuery.semesterStart(now),
        )
        val visible = AssignmentQuery.visible(data, filter, now)
        assertTrue(visible.any { it.id == 5L })
        assertTrue(visible.none { it.id == 3L && it.deadline == null })
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SHANGHAI)
            .toInstant().toEpochMilli()
}

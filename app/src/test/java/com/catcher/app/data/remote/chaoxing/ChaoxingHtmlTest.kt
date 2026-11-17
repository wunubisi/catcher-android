package com.catcher.app.data.remote.chaoxing

import com.catcher.app.domain.model.SHANGHAI
import com.catcher.app.domain.model.SubmissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/**
 * 学习通 HTML 解析的回归测试。
 *
 * 用一段仿真的作业列表页锁定几条最容易踩坑的规则：
 * aria-label 从右往左拆、状态三态、剩余时间换算、`&amp;` 反转义、haveMore 判定。
 */
class ChaoxingHtmlTest {

    private val listHtml = """
        <html><body>
        <script>var haveMore = "0";</script>
        <ul>
        <li onclick="goTask(this);" data="https://mooc1-api.chaoxing.com/work/stu-work?courseId=214986481&amp;workId=1">
          <img src="/mooc2/work/images/task-work.png">
          <span aria-label="作业名称第三章 导数与微分 课后作业作业状态未提交所属课程高等数学A(上)剩余时间312小时55分钟">x</span>
        </li>
        <li onclick="goTask(this);" data="https://mooc1-api.chaoxing.com/work/stu-work?courseId=215004418&amp;workId=2">
          <img src="/mooc2/work/images/task-work-gray.png">
          <span aria-label="作业名称第5章 指令系统 作业作业状态已完成所属课程计算机组成原理A">x</span>
        </li>
        </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun `没有作业的空列表不算结构变化`() {
        // 真实页面（账号确实没有作业）：
        // 里面一定会有 function goTask(obj) 的定义和 haveMore，但没有条目标记
        val html = """
            <html><body>
            <input type="hidden" id="vx" value="0" />
            <p class="empty">暂无作业</p>
            </body>
            <script>
            var haveMore = "0";
            function goTask(obj) {
                var url = ${'$'}(obj).attr("data");
                processOpenUrl(url, "", 1);
            }
            </script></html>
        """.trimIndent()
        val page = ChaoxingHtml.parseWorkList(html)
        assertTrue(page.items.isEmpty())
        // 平台确实把空列表给全了，所以允许软删除；也不该报警告
        assertTrue(page.complete)
        assertTrue(page.warnings.isEmpty())
    }

    @Test
    fun `有条目标记却解析不出来才算结构变化`() {
        // 有 <li onclick="goTask... 的开头，但没有闭合的 </li>，条目正则匹配不到
        val html = """
            <script>var haveMore = "0";</script>
            <ul><li onclick="goTask(this);" data="https://x/work?courseId=1"></ul>
        """.trimIndent()
        val page = ChaoxingHtml.parseWorkList(html)
        assertFalse(page.complete)
        assertTrue(page.warnings.any { it.message.contains("页面结构可能已变化") })
    }

    @Test
    fun `完整列表解析出两条作业`() {
        val page = ChaoxingHtml.parseWorkList(listHtml)
        assertTrue(page.complete)
        assertEquals(2, page.items.size)

        val first = page.items[0]
        assertEquals("第三章 导数与微分 课后作业", first.title)
        assertEquals(SubmissionStatus.UNSUBMITTED, first.status)
        assertEquals("高等数学A(上)", first.courseName)
        assertEquals("214986481", first.courseId)
        assertTrue(first.hasRemaining)
        assertEquals((312L * 3600 + 55 * 60) * 1000, first.remainingMillis)
        assertFalse(first.ended)

        val second = page.items[1]
        assertEquals(SubmissionStatus.SUBMITTED, second.status)
        assertTrue(second.ended)
        assertFalse(second.hasRemaining)
    }

    @Test
    fun `haveMore 非 0 时不能做软删除`() {
        val html = listHtml.replace("var haveMore = \"0\";", "var haveMore = \"1\";")
        val page = ChaoxingHtml.parseWorkList(html)
        assertFalse(page.complete)
        assertTrue(page.warnings.any { it.message.contains("haveMore=1") })
    }

    @Test
    fun `缺少 haveMore 时也不能做软删除`() {
        val html = listHtml.replace("var haveMore = \"0\";", "")
        val page = ChaoxingHtml.parseWorkList(html)
        assertFalse(page.complete)
        assertTrue(page.warnings.any { it.message.contains("未找到 haveMore") })
    }

    @Test
    fun `待批阅算已提交，认不出的状态落 unknown`() {
        val html = """
            <script>var haveMore = "0";</script>
            <li onclick="goTask(this);" data="https://x/work?courseId=1">
              <span aria-label="作业名称实验报告作业状态待批阅所属课程大学物理实验">x</span>
            </li>
            <li onclick="goTask(this);" data="https://x/work?courseId=2">
              <span aria-label="作业名称神秘作业作业状态审核中所属课程未知课">x</span>
            </li>
        """.trimIndent()
        val page = ChaoxingHtml.parseWorkList(html)
        assertEquals(SubmissionStatus.SUBMITTED, page.items[0].status)
        assertEquals(SubmissionStatus.UNKNOWN, page.items[1].status)
        assertTrue(page.warnings.any { it.message.contains("状态无法识别") })
    }

    @Test
    fun `标题里自带同名字样时定界符要从右往左找`() {
        val aria = "作业名称第二次作业（含作业状态说明）作业状态未提交所属课程编译原理剩余时间2天6小时"
        val parts = ChaoxingHtml.parseAriaLabel(aria)
        assertNotNull(parts)
        assertEquals("第二次作业（含作业状态说明）", parts!!.title)
        assertEquals("未提交", parts.statusText)
        assertEquals("编译原理", parts.courseName)
        assertTrue(parts.hasRemaining)
        assertEquals((2L * 24 + 6) * 3600 * 1000, ChaoxingHtml.parseRemaining(parts.remainingRaw))
    }

    @Test
    fun `剩余时间解析`() {
        assertEquals((2L * 24 + 6) * 3600 * 1000, ChaoxingHtml.parseRemaining("2天6小时"))
        assertEquals(55L * 60 * 1000, ChaoxingHtml.parseRemaining("55分钟"))
        assertNull(ChaoxingHtml.parseRemaining("已过期"))
    }

    @Test
    fun `进行中的作业用当前时间加剩余时间推算截止时刻`() {
        val now = at(2026, 11, 5, 12, 0)
        val item = ChaoxingHtml.WorkItem(
            detailUrl = "https://x/work?courseId=1",
            title = "作业",
            status = SubmissionStatus.UNSUBMITTED,
            courseId = "1",
            courseName = "课程",
            remainingMillis = 3 * 3600_000L,
            hasRemaining = true,
            remainingRaw = "3小时",
        )
        val resolved = ChaoxingHtml.resolveDeadline(item, null, now)
        assertEquals(now + 3 * 3600_000L, resolved.deadline)
        assertEquals("derived_remaining", resolved.source.id)
    }

    @Test
    fun `详情页时刻在容差内时用详情页的精确值`() {
        val now = at(2026, 11, 5, 12, 0)
        val item = ChaoxingHtml.WorkItem(
            detailUrl = "https://x/work?courseId=1",
            title = "作业",
            status = SubmissionStatus.UNSUBMITTED,
            courseId = "1",
            courseName = "课程",
            remainingMillis = 3 * 3600_000L,
            hasRemaining = true,
            remainingRaw = "3小时",
        )
        val detail = ChaoxingHtml.DetailDeadline(
            raw = "11月5日15时00分30秒",
            month = 11, day = 5, hour = 15, minute = 0, second = 30,
        )
        val resolved = ChaoxingHtml.resolveDeadline(item, detail, now)
        assertEquals(at(2026, 11, 5, 15, 0, 30), resolved.deadline)
        assertTrue(resolved.warnings.isEmpty())
    }

    @Test
    fun `已截止条目按今天反推年份`() {
        val now = at(2026, 11, 5, 12, 0)
        val item = ChaoxingHtml.WorkItem(
            detailUrl = "https://x/work?courseId=1",
            title = "作业",
            status = SubmissionStatus.UNSUBMITTED,
            courseId = "1",
            courseName = "课程",
        )
        // 12 月 20 日 > 今天 → 属于去年
        val lastYear = ChaoxingHtml.resolveDeadline(
            item,
            ChaoxingHtml.DetailDeadline("12-20 23:59", 12, 20, 23, 59),
            now,
        )
        assertEquals(at(2025, 12, 20, 23, 59), lastYear.deadline)
        assertEquals("inferred_year", lastYear.source.id)

        // 3 月 1 日 < 今天 → 属于今年
        val thisYear = ChaoxingHtml.resolveDeadline(
            item,
            ChaoxingHtml.DetailDeadline("03-01 08:00", 3, 1, 8, 0),
            now,
        )
        assertEquals(at(2026, 3, 1, 8, 0), thisYear.deadline)
    }

    @Test
    fun `详情页 aria-label 带秒时优先`() {
        val body = """<div aria-label="截止时间11月5日15时00分30秒"></div>"""
        val parsed = ChaoxingHtml.parseDetailDeadline(body)
        assertNotNull(parsed)
        assertEquals(30, parsed!!.second)
        assertEquals(15, parsed.hour)
    }

    @Test
    fun `详情页兜底读 h4 的月日时分`() {
        val body = """<h4>截止时间：09-25 22:55</h4>"""
        val parsed = ChaoxingHtml.parseDetailDeadline(body)
        assertNotNull(parsed)
        assertEquals(9, parsed!!.month)
        assertEquals(25, parsed.day)
        assertEquals(22, parsed.hour)
        assertEquals(55, parsed.minute)
    }

    @Test
    fun `详情链接里取 courseId`() {
        assertEquals(
            "214986481",
            ChaoxingHtml.courseIdFromUrl(
                "https://mooc1-api.chaoxing.com/work/stu-work?courseId=214986481&workId=1"
            ),
        )
        assertEquals("", ChaoxingHtml.courseIdFromUrl("https://x/work?workId=1"))
    }

    /** 构造北京时间 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, SHANGHAI)
            .toInstant().toEpochMilli()
}

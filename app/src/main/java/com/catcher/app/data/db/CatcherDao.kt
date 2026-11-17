package com.catcher.app.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.catcher.app.domain.model.Account
import com.catcher.app.domain.model.Assignment
import com.catcher.app.domain.model.Course
import com.catcher.app.domain.model.DeadlineSource
import com.catcher.app.domain.model.FetchIssue
import com.catcher.app.domain.model.FetchResult
import com.catcher.app.domain.model.FetchRun
import com.catcher.app.domain.model.IssueKind
import com.catcher.app.domain.model.IssueScope
import com.catcher.app.domain.model.LoginState
import com.catcher.app.domain.model.Platform
import com.catcher.app.domain.model.RunStatus
import com.catcher.app.domain.model.SubmissionStatus

/** 一次抓取结果落库所需的全部上下文 */
data class PersistInput(
    val accountId: Long,
    val platform: Platform,
    val runId: Long,
    val result: FetchResult,
    val at: Long,
)

/**
 * 全部 SQL。与电脑端 internal/infra/sqlite 的 repo/snapshot 对应。
 *
 * 刻意保留的核心语义：**快照写入 + 软删除**。
 * 只有平台完整枚举了目标范围（complete）才允许把没出现在本次结果里的作业置为 active=0；
 * 课程列表完整但部分课程失败时，只清理没失败的课程。
 */
class CatcherDao(private val helper: CatcherDatabase) {

    private fun writable(): SQLiteDatabase = helper.writableDatabase

    private fun readable(): SQLiteDatabase = helper.readableDatabase

    // ---------------------------------------------------------------- accounts

    fun ensurePlatforms(now: Long) {
        val db = writable()
        db.beginTransaction()
        try {
            for (platform in Platform.ORDER) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM accounts WHERE platform = ? LIMIT 1",
                    arrayOf(platform.id),
                ).use { it.moveToFirst() }
                if (exists) continue
                db.insert(
                    "accounts", null,
                    ContentValues().apply {
                        put("platform", platform.id)
                        put("display_name", "")
                        put("login_state", LoginState.DISCONNECTED.id)
                        put("created_at", now)
                        put("updated_at", now)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listAccounts(): List<Account> =
        readable().rawQuery("SELECT * FROM accounts", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toAccount()) }
        }

    fun getAccount(platform: Platform): Account? =
        readable().rawQuery("SELECT * FROM accounts WHERE platform = ? LIMIT 1", arrayOf(platform.id))
            .use { c -> if (c.moveToFirst()) c.toAccount() else null }

    fun setLoginState(platform: Platform, state: LoginState, at: Long) {
        writable().update(
            "accounts",
            ContentValues().apply {
                put("login_state", state.id)
                put("updated_at", at)
            },
            "platform = ?", arrayOf(platform.id),
        )
    }

    /** 只写入脱敏标识，并同时记录最近登录时间 */
    fun setDisplayName(platform: Platform, name: String, at: Long) {
        writable().update(
            "accounts",
            ContentValues().apply {
                put("display_name", name)
                put("last_login_at", at)
                put("updated_at", at)
            },
            "platform = ?", arrayOf(platform.id),
        )
    }

    // ---------------------------------------------------------------- sessions

    fun getSessionCipher(accountId: Long): String? =
        readable().rawQuery(
            "SELECT secret_cipher FROM sessions WHERE account_id = ? LIMIT 1",
            arrayOf(accountId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun saveSession(accountId: Long, cipher: String, at: Long) {
        writable().insertWithOnConflict(
            "sessions", null,
            ContentValues().apply {
                put("account_id", accountId)
                put("secret_cipher", cipher)
                put("updated_at", at)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteSession(accountId: Long) {
        writable().delete("sessions", "account_id = ?", arrayOf(accountId.toString()))
    }

    // ---------------------------------------------------------------- 刷新记录

    fun createRun(platform: Platform, accountId: Long, startedAt: Long): Long =
        writable().insert(
            "fetch_runs", null,
            ContentValues().apply {
                put("account_id", accountId)
                put("platform", platform.id)
                put("started_at", startedAt)
                put("status", RunStatus.RUNNING.id)
            },
        )

    fun finishRun(run: FetchRun, issues: List<FetchIssue>, at: Long) {
        val db = writable()
        db.beginTransaction()
        try {
            db.update(
                "fetch_runs",
                ContentValues().apply {
                    put("finished_at", at)
                    put("status", run.status.id)
                    put("courses_total", run.coursesTotal)
                    put("courses_ok", run.coursesOk)
                    put("items_seen", run.itemsSeen)
                    put("items_upserted", run.itemsUpserted)
                    put("warning_count", run.warningCount)
                    put("error_summary", run.errorSummary)
                },
                "id = ?", arrayOf(run.id.toString()),
            )
            for (issue in issues) {
                db.insert(
                    "fetch_issues", null,
                    ContentValues().apply {
                        put("run_id", run.id)
                        put("scope", issue.scope.id)
                        put("ref", issue.ref)
                        put("kind", issue.kind.id)
                        put("message", issue.message)
                        put("raw_path", "")
                        put("created_at", at)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 每个平台最近一次刷新记录 */
    fun latestRuns(): Map<Platform, FetchRun> {
        val out = mutableMapOf<Platform, FetchRun>()
        readable().rawQuery(
            """
            SELECT r.* FROM fetch_runs r
            JOIN (SELECT platform, MAX(started_at) AS s FROM fetch_runs GROUP BY platform) m
              ON r.platform = m.platform AND r.started_at = m.s
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val run = c.toRun()
                out[run.platform] = run
            }
        }
        return out
    }

    fun listIssues(runId: Long): List<FetchIssue> =
        readable().rawQuery(
            "SELECT * FROM fetch_issues WHERE run_id = ? ORDER BY id",
            arrayOf(runId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FetchIssue(
                            scope = IssueScope.ofId(c.getString(c.getColumnIndexOrThrow("scope"))),
                            ref = c.getString(c.getColumnIndexOrThrow("ref")),
                            kind = IssueKind.ofId(c.getString(c.getColumnIndexOrThrow("kind"))),
                            message = c.getString(c.getColumnIndexOrThrow("message")),
                        )
                    )
                }
            }
        }

    // ---------------------------------------------------------------- 快照写入

    /** 在事务里 upsert 课程与作业；返回写入的作业条数 */
    fun saveSnapshot(input: PersistInput): Int {
        val db = writable()
        val result = input.result
        val at = input.at
        db.beginTransaction()
        try {
            // 1. 课程 upsert，顺便建立 externalId -> course.id 的映射
            val courseIds = mutableMapOf<String, Long>()
            for (course in result.courses) {
                if (course.externalId.isEmpty()) continue
                val key = courseKey(course.externalId, course.classroomId)
                val id = upsertCourse(db, input.accountId, input.platform, course, at)
                courseIds[key] = id
            }

            // 2. 作业 upsert：稳定去重键，手动状态不覆盖
            var upserted = 0
            for (assignment in result.assignments) {
                val courseId = courseIds[courseKey(assignment.courseExternalId, assignment.classroomId)]
                val existingId = findAssignmentId(db, input.accountId, input.platform, assignment.dedupKey)
                val values = ContentValues().apply {
                    put("account_id", input.accountId)
                    put("platform", input.platform.id)
                    if (courseId != null) put("course_id", courseId) else putNull("course_id")
                    put("course_external_id", assignment.courseExternalId)
                    put("classroom_id", assignment.classroomId)
                    put("course_name", assignment.courseName)
                    put("external_id", assignment.externalId)
                    put("dedup_key", assignment.dedupKey)
                    put("title", assignment.title)
                    put("status", assignment.status.id)
                    put("deadline_at", assignment.deadline)
                    put("deadline_source", assignment.deadlineSource.id)
                    put("deadline_raw", assignment.deadlineRaw)
                    put("detail_url", assignment.detailUrl)
                    put("description", assignment.description)
                    put("last_seen_at", at)
                    put("active", 1)
                    put("last_run_id", input.runId)
                }
                if (existingId != null) {
                    db.update("assignments", values, "id = ?", arrayOf(existingId.toString()))
                } else {
                    values.put("first_seen_at", at)
                    db.insert("assignments", null, values)
                }
                upserted++
            }

            // 3. 软删除：只有完整枚举时才清理本次没见到的旧记录
            val failed = result.failedCourses
            when {
                result.complete -> softDeleteAssignments(db, input, at, failedCourses = null)
                result.courseListComplete && failed != null ->
                    softDeleteAssignments(db, input, at, failedCourses = failed)
                // 其余情况只做 upsert，保留历史数据
                else -> Unit
            }

            // 4. 课程列表完整时同步清理旧课程
            if (result.courseListComplete) {
                db.execSQL(
                    "UPDATE courses SET active = 0 WHERE account_id = ? AND platform = ? " +
                        "AND last_seen_at < ? AND active = 1",
                    arrayOf(input.accountId.toString(), input.platform.id, at.toString()),
                )
            }

            db.setTransactionSuccessful()
            return upserted
        } finally {
            db.endTransaction()
        }
    }

    private fun softDeleteAssignments(
        db: SQLiteDatabase,
        input: PersistInput,
        at: Long,
        failedCourses: List<String>?,
    ) {
        val args = mutableListOf(input.accountId.toString(), input.platform.id, input.runId.toString())
        var sql = "UPDATE assignments SET active = 0 " +
            "WHERE account_id = ? AND platform = ? AND active = 1 " +
            "AND (last_run_id IS NULL OR last_run_id <> ?)"
        if (failedCourses != null && failedCourses.isNotEmpty()) {
            val placeholders = failedCourses.joinToString(",") { "?" }
            sql += " AND course_external_id NOT IN ($placeholders)"
            failedCourses.forEach { args += it }
        }
        db.execSQL(sql, args.toTypedArray())
    }

    private fun upsertCourse(
        db: SQLiteDatabase,
        accountId: Long,
        platform: Platform,
        course: Course,
        at: Long,
    ): Long {
        val existing = db.rawQuery(
            "SELECT id FROM courses WHERE account_id = ? AND platform = ? " +
                "AND external_id = ? AND classroom_id = ? LIMIT 1",
            arrayOf(accountId.toString(), platform.id, course.externalId, course.classroomId),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        val values = ContentValues().apply {
            put("account_id", accountId)
            put("platform", platform.id)
            put("external_id", course.externalId)
            put("classroom_id", course.classroomId)
            put("name", course.name)
            put("semester_key", course.semesterKey)
            put("active", 1)
            put("last_seen_at", at)
        }
        return if (existing != null) {
            db.update("courses", values, "id = ?", arrayOf(existing.toString()))
            existing
        } else {
            values.put("first_seen_at", at)
            db.insert("courses", null, values)
        }
    }

    private fun findAssignmentId(
        db: SQLiteDatabase,
        accountId: Long,
        platform: Platform,
        dedupKey: String,
    ): Long? = db.rawQuery(
        "SELECT id FROM assignments WHERE account_id = ? AND platform = ? AND dedup_key = ? LIMIT 1",
        arrayOf(accountId.toString(), platform.id, dedupKey),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun courseKey(externalId: String, classroomId: String) = "$externalId\u0000$classroomId"

    // ---------------------------------------------------------------- 读取

    /** 读取全部未软删除的作业；过滤与排序交给 domain 层的纯函数处理 */
    fun listActiveAssignments(): List<Assignment> =
        readable().rawQuery(
            "SELECT * FROM assignments WHERE active = 1 ORDER BY (deadline_at IS NULL), deadline_at ASC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toAssignment()) } }

    fun getAssignment(id: Long): Assignment? =
        readable().rawQuery("SELECT * FROM assignments WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toAssignment() else null }

    /** status 为 null 表示恢复平台值 */
    fun setManualStatus(id: Long, status: SubmissionStatus?, at: Long): Assignment? {
        val values = ContentValues().apply {
            if (status == null) {
                putNull("manual_status")
                putNull("manual_status_at")
            } else {
                put("manual_status", status.id)
                put("manual_status_at", at)
            }
        }
        writable().update("assignments", values, "id = ?", arrayOf(id.toString()))
        return getAssignment(id)
    }

    // ---------------------------------------------------------------- 行映射

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""

    private fun Cursor.longOrNull(name: String): Long? {
        val idx = getColumnIndexOrThrow(name)
        return if (isNull(idx)) null else getLong(idx)
    }

    private fun Cursor.toAccount(): Account {
        val platform = Platform.ofId(str("platform")) ?: Platform.CHAOXING
        return Account(
            id = getLong(getColumnIndexOrThrow("id")),
            platform = platform,
            displayName = str("display_name"),
            loginState = LoginState.ofId(str("login_state")),
            lastLoginAt = longOrNull("last_login_at"),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun Cursor.toRun(): FetchRun {
        val platform = Platform.ofId(str("platform")) ?: Platform.CHAOXING
        return FetchRun(
            id = getLong(getColumnIndexOrThrow("id")),
            accountId = getLong(getColumnIndexOrThrow("account_id")),
            platform = platform,
            startedAt = getLong(getColumnIndexOrThrow("started_at")),
            finishedAt = longOrNull("finished_at"),
            status = RunStatus.ofId(str("status")),
            coursesTotal = getInt(getColumnIndexOrThrow("courses_total")),
            coursesOk = getInt(getColumnIndexOrThrow("courses_ok")),
            itemsSeen = getInt(getColumnIndexOrThrow("items_seen")),
            itemsUpserted = getInt(getColumnIndexOrThrow("items_upserted")),
            warningCount = getInt(getColumnIndexOrThrow("warning_count")),
            errorSummary = str("error_summary"),
        )
    }

    private fun Cursor.toAssignment(): Assignment {
        val platform = Platform.ofId(str("platform")) ?: Platform.CHAOXING
        val manualRaw = str("manual_status")
        return Assignment(
            id = getLong(getColumnIndexOrThrow("id")),
            accountId = getLong(getColumnIndexOrThrow("account_id")),
            platform = platform,
            courseId = longOrNull("course_id") ?: 0,
            courseExternalId = str("course_external_id"),
            classroomId = str("classroom_id"),
            courseName = str("course_name"),
            externalId = str("external_id"),
            dedupKey = str("dedup_key"),
            title = str("title"),
            status = SubmissionStatus.ofId(str("status")),
            manualStatus = if (manualRaw.isEmpty()) null else SubmissionStatus.ofId(manualRaw),
            manualStatusAt = longOrNull("manual_status_at"),
            deadline = longOrNull("deadline_at"),
            deadlineSource = DeadlineSource.ofId(str("deadline_source")),
            deadlineRaw = str("deadline_raw"),
            detailUrl = str("detail_url"),
            description = str("description"),
            firstSeenAt = getLong(getColumnIndexOrThrow("first_seen_at")),
            lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at")),
            active = getInt(getColumnIndexOrThrow("active")) == 1,
            lastRunId = longOrNull("last_run_id") ?: 0,
        )
    }
}

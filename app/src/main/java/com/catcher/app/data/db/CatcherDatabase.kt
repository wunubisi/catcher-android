package com.catcher.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库。表结构与电脑端 internal/infra/sqlite/migrations/001_init.sql 对齐。
 *
 * 约定：
 * - 所有时间列都是 epoch millis（UTC），与电脑端的 RFC3339 文本等价但更好比较；
 * - `assignments.active = 0` 表示软删除，不物理删除；
 * - 唯一键 (account_id, platform, dedup_key) 是作业去重的唯一依据；
 * - `classroom_id` 用空串而不是 NULL，避免 UNIQUE 对 NULL 失效（雨课堂复合身份）。
 */
class CatcherDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE accounts (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                platform      TEXT    NOT NULL UNIQUE,
                display_name  TEXT    NOT NULL DEFAULT '',
                login_state   TEXT    NOT NULL DEFAULT 'disconnected',
                last_login_at INTEGER,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                account_id    INTEGER PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                secret_cipher TEXT    NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE credentials (
                account_id      INTEGER PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
                credential_cipher TEXT  NOT NULL,
                updated_at      INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE fetch_runs (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id     INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                platform       TEXT    NOT NULL,
                started_at     INTEGER NOT NULL,
                finished_at    INTEGER,
                status         TEXT    NOT NULL DEFAULT 'running',
                courses_total  INTEGER NOT NULL DEFAULT 0,
                courses_ok     INTEGER NOT NULL DEFAULT 0,
                items_seen     INTEGER NOT NULL DEFAULT 0,
                items_upserted INTEGER NOT NULL DEFAULT 0,
                warning_count  INTEGER NOT NULL DEFAULT 0,
                error_summary  TEXT    NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE fetch_issues (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id     INTEGER NOT NULL REFERENCES fetch_runs(id) ON DELETE CASCADE,
                scope      TEXT    NOT NULL,
                ref        TEXT    NOT NULL DEFAULT '',
                kind       TEXT    NOT NULL,
                message    TEXT    NOT NULL DEFAULT '',
                raw_path   TEXT    NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE courses (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id    INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                platform      TEXT    NOT NULL,
                external_id   TEXT    NOT NULL,
                classroom_id  TEXT    NOT NULL DEFAULT '',
                name          TEXT    NOT NULL DEFAULT '',
                semester_key  TEXT    NOT NULL DEFAULT '',
                active        INTEGER NOT NULL DEFAULT 1,
                first_seen_at INTEGER NOT NULL,
                last_seen_at  INTEGER NOT NULL,
                UNIQUE (account_id, platform, external_id, classroom_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE assignments (
                id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id         INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                platform           TEXT    NOT NULL,
                course_id          INTEGER REFERENCES courses(id) ON DELETE SET NULL,
                course_external_id TEXT    NOT NULL DEFAULT '',
                classroom_id       TEXT    NOT NULL DEFAULT '',
                course_name        TEXT    NOT NULL DEFAULT '',
                external_id        TEXT    NOT NULL DEFAULT '',
                dedup_key          TEXT    NOT NULL,
                title              TEXT    NOT NULL DEFAULT '',
                status             TEXT    NOT NULL,
                manual_status      TEXT,
                manual_status_at   INTEGER,
                deadline_at        INTEGER,
                deadline_source    TEXT    NOT NULL DEFAULT 'unknown',
                deadline_raw       TEXT    NOT NULL DEFAULT '',
                detail_url         TEXT    NOT NULL DEFAULT '',
                description        TEXT    NOT NULL DEFAULT '',
                first_seen_at      INTEGER NOT NULL,
                last_seen_at       INTEGER NOT NULL,
                active             INTEGER NOT NULL DEFAULT 1,
                last_run_id        INTEGER,
                UNIQUE (account_id, platform, dedup_key)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_courses_account_active ON courses (account_id, platform, active)")
        db.execSQL("CREATE INDEX idx_assignments_active_deadline ON assignments (active, deadline_at)")
        db.execSQL(
            "CREATE INDEX idx_assignments_account_platform " +
                "ON assignments (account_id, platform, active, status, deadline_at)"
        )
        db.execSQL("CREATE INDEX idx_assignments_course ON assignments (course_id, active)")
        db.execSQL("CREATE INDEX idx_fetch_issues_run ON fetch_issues (run_id)")
        db.execSQL(
            "CREATE INDEX idx_fetch_runs_account_started " +
                "ON fetch_runs (account_id, platform, started_at DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 只有这一个版本；后续迁移在这里按版本号增量执行，禁止 DROP TABLE
    }

    companion object {
        const val DB_NAME = "catcher.db"
        const val DB_VERSION = 1
    }
}

package com.dailyroll.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dailyroll.core.domain.transaction.ExclusionPolicy

/**
 * 로컬 SQLite 저장소.
 *
 * Room 대신 SQLiteOpenHelper 를 쓰는 이유: 이 코드는 Expo 로컬 모듈(라이브러리)로 빌드되며 EAS 클라우드
 * 빌드에서 KSP/Room 컴파일러 버전을 Expo·RN 의 Kotlin 버전과 맞추는 부담을 없애기 위해서다.
 * 대신 다음을 직접 지킨다.
 * - 마이그레이션은 [MIGRATIONS] 에 버전별로 추가한다(기존 버전 수정 금지, destructive migration 금지).
 * - 금액은 INTEGER(원), 시각은 epoch millis INTEGER.
 * - 거래 저장과 원장 반영은 하나의 트랜잭션으로 묶는다([LedgerRepository]).
 * - 알림 원문·카드 전체 번호·계좌번호는 저장하지 않는다.
 */
class DailyRollDatabase(context: Context, name: String? = NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (v in 1..VERSION) MIGRATIONS.getValue(v)(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in (oldVersion + 1)..newVersion) {
            val step = MIGRATIONS[v] ?: error("마이그레이션 누락: v$v")
            step(db)
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 데이터 보호: 다운그레이드 시 삭제하지 않고 실패시킨다.
        throw IllegalStateException("DB 다운그레이드는 지원하지 않습니다 ($oldVersion → $newVersion)")
    }

    companion object {
        const val NAME = "dailyroll.db"
        const val VERSION = 1

        val MIGRATIONS: Map<Int, (SQLiteDatabase) -> Unit> = mapOf(
            1 to { db ->
                db.execSQL(
                    """
                    CREATE TABLE transactions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      source_package TEXT NOT NULL,
                      source_event_key TEXT UNIQUE,
                      amount INTEGER,
                      type TEXT NOT NULL,
                      merchant_name TEXT,
                      occurred_at INTEGER NOT NULL,
                      received_at INTEGER NOT NULL,
                      confidence TEXT NOT NULL,
                      review_status TEXT NOT NULL,
                      review_reasons TEXT NOT NULL DEFAULT '',
                      matched_transaction_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL,
                      budget_inclusion TEXT NOT NULL,
                      card_hint TEXT,
                      partial_cancel INTEGER NOT NULL DEFAULT 0,
                      merged_sources TEXT NOT NULL DEFAULT '',
                      is_simulated INTEGER NOT NULL DEFAULT 0,
                      parsed_amount INTEGER,
                      parsed_type TEXT,
                      parsed_merchant TEXT,
                      user_edited INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX idx_tx_occurred_at ON transactions(occurred_at)")
                db.execSQL("CREATE INDEX idx_tx_review_status ON transactions(review_status)")
                db.execSQL("CREATE INDEX idx_tx_matched ON transactions(matched_transaction_id)")

                db.execSQL(
                    """
                    CREATE TABLE budget_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      occurred_at INTEGER NOT NULL,
                      event_type TEXT NOT NULL,
                      amount INTEGER NOT NULL CHECK (amount >= 0),
                      budget_impact INTEGER NOT NULL,
                      source_transaction_id INTEGER REFERENCES transactions(id) ON DELETE CASCADE,
                      memo TEXT,
                      idempotency_key TEXT NOT NULL UNIQUE,
                      created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX idx_be_occurred_at ON budget_events(occurred_at)")
                db.execSQL("CREATE INDEX idx_be_source_tx ON budget_events(source_transaction_id)")

                db.execSQL(
                    """
                    CREATE TABLE excluded_rules (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      keyword TEXT NOT NULL UNIQUE,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE allowance_history (
                      effective_from TEXT PRIMARY KEY,
                      amount INTEGER NOT NULL CHECK (amount >= 0),
                      created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // P1(정기구독 D-3 알림)용. 예정 금액은 원장에 자동 반영하지 않는다.
                db.execSQL(
                    """
                    CREATE TABLE subscriptions (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      service_name TEXT NOT NULL,
                      billing_day INTEGER NOT NULL CHECK (billing_day BETWEEN 1 AND 31),
                      amount INTEGER NOT NULL CHECK (amount >= 0),
                      enabled INTEGER NOT NULL DEFAULT 1,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                val now = System.currentTimeMillis()
                for (k in ExclusionPolicy.DEFAULT_KEYWORDS) {
                    db.insert("excluded_rules", null, ContentValues().apply {
                        put("keyword", k); put("enabled", 1); put("created_at", now)
                    })
                }
            },
        )
    }
}

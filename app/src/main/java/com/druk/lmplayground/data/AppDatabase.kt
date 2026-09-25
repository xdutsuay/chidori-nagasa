package com.druk.lmplayground.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.druk.lmplayground.cloud.CloudProviderDao
import com.druk.lmplayground.cloud.CloudProviderEntity

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SystemPromptEntity::class,
        PromptUsage::class,
        CloudProviderEntity::class,
        UsageEventEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    abstract fun systemPromptDao(): SystemPromptDao

    abstract fun cloudProviderDao(): CloudProviderDao

    abstract fun usageEventDao(): UsageEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN contextSize INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN temperature REAL NOT NULL DEFAULT 0.8")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topP REAL NOT NULL DEFAULT 0.95")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN repetitionPenalty REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topK INTEGER NOT NULL DEFAULT 40")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN minP REAL NOT NULL DEFAULT 0.05")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN seed INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN thinkingBudget INTEGER NOT NULL DEFAULT 1024")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN responseDurationSeconds REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * System-prompt feature in one shot. The intermediate schemas 5/6
         * never shipped to real users, so we collapse the three dev-only
         * migration steps into a single 4 → 5 migration.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS system_prompts (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "text TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0" +
                        ")"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS prompt_usage (" +
                        "promptId TEXT NOT NULL, " +
                        "modelFilename TEXT NOT NULL, " +
                        "lastUsedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY (promptId, modelFilename), " +
                        "FOREIGN KEY (promptId) REFERENCES system_prompts(id) ON DELETE CASCADE" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_usage_modelFilename " +
                        "ON prompt_usage (modelFilename)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}'"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN imagePath TEXT")
            }
        }

        /**
         * Cloud providers + usage dashboard + cloud route columns on sessions.
         * Existing sessions become LOCAL with null cloud fields.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN route TEXT NOT NULL DEFAULT 'LOCAL'",
                )
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN cloudProviderId TEXT")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN cloudModel TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS cloud_providers (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "baseUrl TEXT NOT NULL, " +
                        "defaultModel TEXT NOT NULL, " +
                        "allowHttp INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL" +
                        ")",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS usage_events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "route TEXT NOT NULL, " +
                        "provider TEXT NOT NULL, " +
                        "model TEXT NOT NULL, " +
                        "promptTokens INTEGER, " +
                        "completionTokens INTEGER, " +
                        "costMicros INTEGER, " +
                        "estimated INTEGER NOT NULL, " +
                        "sessionId TEXT" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_createdAt ON usage_events (createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_route ON usage_events (route)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_model ON usage_events (model)",
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lmplayground.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}

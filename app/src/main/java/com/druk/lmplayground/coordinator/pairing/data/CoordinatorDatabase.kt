package com.druk.lmplayground.coordinator.pairing.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Deliberately separate from `data/AppDatabase` (a different .db file) so
 * `net/coordinator` has zero schema/compile coupling to `inference/`'s
 * persistence, per `CHIDORI_PROTOCOL.md` §3.4. Version 1 — this is a first
 * draft against WIRE_CONTRACT.md's not-yet-reconciled pairing shape, so
 * expect an early migration once the real contract settles.
 */
@Database(
    entities = [PairedInstanceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CoordinatorDatabase : RoomDatabase() {

    abstract fun pairedInstanceDao(): PairedInstanceDao

    companion object {
        @Volatile
        private var INSTANCE: CoordinatorDatabase? = null

        fun getInstance(context: Context): CoordinatorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoordinatorDatabase::class.java,
                    "chidori_coordinator.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}

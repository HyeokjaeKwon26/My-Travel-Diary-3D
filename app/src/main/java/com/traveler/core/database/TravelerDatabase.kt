package com.traveler.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.traveler.core.database.dao.*
import com.traveler.core.database.entity.*

@Database(
    entities = [
        TripEntity::class,
        VisitEntity::class,
        MovementSegmentEntity::class,
        TripMediaEntity::class,
        UserOverrideEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class TravelerDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun visitDao(): VisitDao
    abstract fun movementSegmentDao(): MovementSegmentDao
    abstract fun tripMediaDao(): TripMediaDao
    abstract fun userOverrideDao(): UserOverrideDao

    companion object {
        @Volatile
        private var INSTANCE: TravelerDatabase? = null

        fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(tableName)).use { cursor ->
                return cursor.count > 0
            }
        }

        fun columnExists(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
            if (!tableExists(db, tableName)) return false
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Recreate visits with composite primary key (tripId, sourceId)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `visits_new` (
                        `tripId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `placeName` TEXT,
                        `placeAddress` TEXT,
                        `placeId` TEXT,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `startTimestampEpochMs` INTEGER NOT NULL,
                        `endTimestampEpochMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `isUserOverride` INTEGER NOT NULL,
                        PRIMARY KEY(`tripId`, `sourceId`)
                    )
                """.trimIndent())
                if (tableExists(db, "visits")) {
                    db.execSQL("""
                        INSERT OR IGNORE INTO `visits_new` (`tripId`, `sourceId`, `placeName`, `placeAddress`, `placeId`, `latitude`, `longitude`, `startTimestampEpochMs`, `endTimestampEpochMs`, `confidence`, `isUserOverride`)
                        SELECT `tripId`, `id`, `placeName`, `placeAddress`, `placeId`, `latitude`, `longitude`, `startTimestampEpochMs`, `endTimestampEpochMs`, `confidence`, `isUserOverride` FROM `visits`
                    """.trimIndent())
                    db.execSQL("DROP TABLE IF EXISTS `visits`")
                }
                db.execSQL("ALTER TABLE `visits_new` RENAME TO `visits`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_tripId` ON `visits` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_sourceId` ON `visits` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_startTimestampEpochMs` ON `visits` (`startTimestampEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_visits_endTimestampEpochMs` ON `visits` (`endTimestampEpochMs`)")

                // 2. Recreate movement_segments with composite primary key (tripId, sourceId)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `movement_segments_new` (
                        `tripId` TEXT NOT NULL,
                        `sourceId` TEXT NOT NULL,
                        `startTimestampEpochMs` INTEGER NOT NULL,
                        `endTimestampEpochMs` INTEGER NOT NULL,
                        `startLat` REAL NOT NULL,
                        `startLng` REAL NOT NULL,
                        `endLat` REAL NOT NULL,
                        `endLng` REAL NOT NULL,
                        `distanceMeters` REAL NOT NULL,
                        `durationMillis` INTEGER NOT NULL,
                        `predictedTransportMode` TEXT NOT NULL,
                        `predictedConfidence` REAL NOT NULL,
                        `predictedReason` TEXT NOT NULL,
                        `userOverrideTransportMode` TEXT,
                        `polylineJson` TEXT NOT NULL,
                        `isUserOverride` INTEGER NOT NULL,
                        PRIMARY KEY(`tripId`, `sourceId`)
                    )
                """.trimIndent())
                if (tableExists(db, "movement_segments")) {
                    db.execSQL("""
                        INSERT OR IGNORE INTO `movement_segments_new` (`tripId`, `sourceId`, `startTimestampEpochMs`, `endTimestampEpochMs`, `startLat`, `startLng`, `endLat`, `endLng`, `distanceMeters`, `durationMillis`, `predictedTransportMode`, `predictedConfidence`, `predictedReason`, `userOverrideTransportMode`, `polylineJson`, `isUserOverride`)
                        SELECT `tripId`, `id`, `startTimestampEpochMs`, `endTimestampEpochMs`, `startLat`, `startLng`, `endLat`, `endLng`, `distanceMeters`, `durationMillis`, `predictedTransportMode`, `predictedConfidence`, `predictedReason`, `userOverrideTransportMode`, `polylineJson`, `isUserOverride` FROM `movement_segments`
                    """.trimIndent())
                    db.execSQL("DROP TABLE IF EXISTS `movement_segments`")
                }
                db.execSQL("ALTER TABLE `movement_segments_new` RENAME TO `movement_segments`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movement_segments_tripId` ON `movement_segments` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movement_segments_sourceId` ON `movement_segments` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movement_segments_startTimestampEpochMs` ON `movement_segments` (`startTimestampEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movement_segments_endTimestampEpochMs` ON `movement_segments` (`endTimestampEpochMs`)")

                // 3. Create trip_media table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `trip_media` (
                        `tripId` TEXT NOT NULL,
                        `mediaKey` TEXT NOT NULL,
                        `contentUriString` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `timestampEpochMs` INTEGER,
                        `timestampConfidence` TEXT NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `locationConfidence` TEXT NOT NULL,
                        `confidenceScore` REAL NOT NULL,
                        `matchedVisitId` TEXT,
                        `matchedSegmentId` TEXT,
                        `isRepresentative` INTEGER NOT NULL,
                        `isUserLocationOverride` INTEGER NOT NULL,
                        PRIMARY KEY(`tripId`, `mediaKey`)
                    )
                """.trimIndent())
                if (tableExists(db, "media_items")) {
                    db.execSQL("""
                        INSERT OR IGNORE INTO `trip_media` (`tripId`, `mediaKey`, `contentUriString`, `fileName`, `mimeType`, `timestampEpochMs`, `timestampConfidence`, `latitude`, `longitude`, `locationConfidence`, `confidenceScore`, `matchedVisitId`, `matchedSegmentId`, `isRepresentative`, `isUserLocationOverride`)
                        SELECT `tripId`, `id`, `contentUriString`, `fileName`, `mimeType`, `timestampEpochMs`, `timestampConfidence`, `latitude`, `longitude`, `locationConfidence`, `confidenceScore`, `matchedVisitId`, `matchedSegmentId`, `isRepresentative`, `isUserLocationOverride` FROM `media_items`
                    """.trimIndent())
                    db.execSQL("DROP TABLE IF EXISTS `media_items`")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_tripId` ON `trip_media` (`tripId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_mediaKey` ON `trip_media` (`mediaKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_timestampEpochMs` ON `trip_media` (`timestampEpochMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_matchedVisitId` ON `trip_media` (`matchedVisitId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_matchedSegmentId` ON `trip_media` (`matchedSegmentId`)")

                // 4. Update user_overrides
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_overrides_new` (
                        `id` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetSourceId` TEXT NOT NULL,
                        `overrideValue` TEXT NOT NULL,
                        `overriddenAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                if (tableExists(db, "user_overrides")) {
                    val hasTargetId = columnExists(db, "user_overrides", "targetId")
                    if (hasTargetId) {
                        db.execSQL("""
                            INSERT OR IGNORE INTO `user_overrides_new` (`id`, `targetType`, `targetSourceId`, `overrideValue`, `overriddenAtEpochMs`)
                            SELECT `id`, `targetType`, `targetId`, `overrideValue`, `overriddenAtEpochMs` FROM `user_overrides`
                        """.trimIndent())
                    } else {
                        db.execSQL("""
                            INSERT OR IGNORE INTO `user_overrides_new` (`id`, `targetType`, `targetSourceId`, `overrideValue`, `overriddenAtEpochMs`)
                            SELECT `id`, `targetType`, `targetSourceId`, `overrideValue`, `overriddenAtEpochMs` FROM `user_overrides`
                        """.trimIndent())
                    }
                    db.execSQL("DROP TABLE IF EXISTS `user_overrides`")
                }
                db.execSQL("ALTER TABLE `user_overrides_new` RENAME TO `user_overrides`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_overrides_targetSourceId` ON `user_overrides` (`targetSourceId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_overrides_targetType_targetSourceId` ON `user_overrides` (`targetType`, `targetSourceId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "trip_media") && !columnExists(db, "trip_media", "captureTimezoneId")) {
                    db.execSQL("ALTER TABLE `trip_media` ADD COLUMN `captureTimezoneId` TEXT")
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "visits") && !columnExists(db, "visits", "timezoneId")) {
                    db.execSQL("ALTER TABLE `visits` ADD COLUMN `timezoneId` TEXT")
                }
                if (tableExists(db, "movement_segments") && !columnExists(db, "movement_segments", "startTimezoneId")) {
                    db.execSQL("ALTER TABLE `movement_segments` ADD COLUMN `startTimezoneId` TEXT")
                }
                if (tableExists(db, "movement_segments") && !columnExists(db, "movement_segments", "endTimezoneId")) {
                    db.execSQL("ALTER TABLE `movement_segments` ADD COLUMN `endTimezoneId` TEXT")
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "movement_segments") && !columnExists(db, "movement_segments", "geometryProvenance")) {
                    db.execSQL("ALTER TABLE `movement_segments` ADD COLUMN `geometryProvenance` TEXT NOT NULL DEFAULT 'UNKNOWN'")
                }
                if (tableExists(db, "trip_media") && !columnExists(db, "trip_media", "assignedDayIso")) {
                    db.execSQL("ALTER TABLE `trip_media` ADD COLUMN `assignedDayIso` TEXT")
                }
                if (tableExists(db, "trip_media") && !columnExists(db, "trip_media", "dayAssignmentConfidence")) {
                    db.execSQL("ALTER TABLE `trip_media` ADD COLUMN `dayAssignmentConfidence` TEXT NOT NULL DEFAULT 'UNKNOWN'")
                }
                if (tableExists(db, "trip_media") && !columnExists(db, "trip_media", "dayAssignmentProvenance")) {
                    db.execSQL("ALTER TABLE `trip_media` ADD COLUMN `dayAssignmentProvenance` TEXT")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_media_assignedDayIso` ON `trip_media` (`assignedDayIso`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE visits ADD COLUMN altitudeMeters REAL")
                db.execSQL("ALTER TABLE movement_segments ADD COLUMN startAltitudeMeters REAL")
                db.execSQL("ALTER TABLE movement_segments ADD COLUMN endAltitudeMeters REAL")
                db.execSQL("ALTER TABLE movement_segments ADD COLUMN rawPointsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): TravelerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TravelerDatabase::class.java,
                    "traveler_database.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

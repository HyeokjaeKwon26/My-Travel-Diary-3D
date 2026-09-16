package com.traveler.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationAndroidTest {

    private val TEST_DB = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TravelerDatabase::class.java
    )


    @Test
    fun migrate5To6_preservesJourneyAndAddsNullableElevation() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL("INSERT INTO trips VALUES('t6', 'Kept trip', '2026-07-01', '2026-07-02', 100.0, '[]', '[]', 0, 1000)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, TravelerDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT title FROM trips WHERE id='t6'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("Kept trip", c.getString(0))
            }
        }
    }

    @Test
    fun migrate2To3_containsCaptureTimezoneId() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("INSERT INTO trips VALUES('t1', 'Trip 1', '2026-07-01', '2026-07-02', 100.0, '[]', '[]', 0, 1000)")
            execSQL("INSERT INTO trip_media VALUES('t1', 'm1', 'content://m1', 'f1.jpg', 'image/jpeg', 1000, 'EXIF_EXACT', 40.0, -74.0, 'GPS_EXACT', 0.9, null, null, 0, 0)")
            close()
        }

        val db3 = helper.runMigrationsAndValidate(TEST_DB, 3, true, TravelerDatabase.MIGRATION_2_3)
        val cursor = db3.query("SELECT * FROM trip_media WHERE mediaKey='m1'")
        assertTrue(cursor.moveToFirst())
        assertTrue(cursor.columnNames.contains("captureTimezoneId"))
        assertNull("captureTimezoneId should default to null", cursor.getString(cursor.getColumnIndexOrThrow("captureTimezoneId")))
        assertEquals("f1.jpg", cursor.getString(cursor.getColumnIndexOrThrow("fileName")))
        cursor.close()
        db3.close()
    }

    @Test
    fun migrate3To4_containsTimezoneColumns() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO trips VALUES('t1', 'Trip 1', '2026-07-01', '2026-07-02', 100.0, '[]', '[]', 0, 1000)")
            execSQL("INSERT INTO visits VALUES('t1', 'v1', 'Place 1', 'Addr 1', 'p1', 40.0, -74.0, 1000, 2000, 0.9, 0)")
            execSQL("INSERT INTO movement_segments VALUES('t1', 's1', 2000, 3000, 40.0, -74.0, 40.1, -73.9, 5000.0, 1000000, 'DRIVING', 0.9, 'Car', null, '[]', 0)")
            close()
        }

        val db4 = helper.runMigrationsAndValidate(TEST_DB, 4, true, TravelerDatabase.MIGRATION_3_4)
        val visitCursor = db4.query("SELECT * FROM visits WHERE sourceId='v1'")
        assertTrue(visitCursor.moveToFirst())
        assertTrue(visitCursor.columnNames.contains("timezoneId"))
        assertNull(visitCursor.getString(visitCursor.getColumnIndexOrThrow("timezoneId")))
        assertEquals("Place 1", visitCursor.getString(visitCursor.getColumnIndexOrThrow("placeName")))
        visitCursor.close()

        val segmentCursor = db4.query("SELECT * FROM movement_segments WHERE sourceId='s1'")
        assertTrue(segmentCursor.moveToFirst())
        assertTrue(segmentCursor.columnNames.contains("startTimezoneId"))
        assertTrue(segmentCursor.columnNames.contains("endTimezoneId"))
        assertNull(segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("startTimezoneId")))
        assertNull(segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("endTimezoneId")))
        assertEquals("DRIVING", segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("predictedTransportMode")))
        segmentCursor.close()

        db4.close()
    }

    @Test
    fun migrate4To5_containsGeometryProvenanceAndDayAssignment() {
        // Create DB at version 4 with rich relational records across all 5 tables (P1-07)
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO trips VALUES('trip_45', 'Euro Journey', '2026-07-01', '2026-07-10', 450000.0, '[\"Paris\", \"Rome\"]', '[\"France\", \"Italy\"]', 1, 1782800000000)")
            execSQL("INSERT INTO visits VALUES('trip_45', 'v_45', 'Eiffel Tower', 'Champ de Mars, Paris', 'place_eiffel', 48.8584, 2.2945, 1782820000000, 1782827200000, 0.98, 'Europe/Paris', 1)")
            execSQL("INSERT INTO movement_segments VALUES('trip_45', 's_45', 1782827200000, 1782830800000, 48.8584, 2.2945, 48.8606, 2.3376, 3500.0, 3600000, 'TRANSIT', 0.95, 'Metro', 'Europe/Paris', 'Europe/Paris', 'SUBWAY', '[{\"latitude\":48.8584,\"longitude\":2.2945}]', 1)")
            execSQL("INSERT INTO trip_media VALUES('trip_45', 'm_45', 'content://media/eiffel', 'eiffel.jpg', 'image/jpeg', 1782822000000, 'EXIF_EXACT', 'Europe/Paris', 48.8584, 2.2945, 'GPS_EXACT', 0.99, 'v_45', null, 1, 0)")
            execSQL("INSERT INTO user_overrides VALUES('override_45', 'VISIT_NAME', 'v_45', 'Tour Eiffel Paris', 1782825000000)")
            close()
        }

        // Migrate to version 5
        val db5 = helper.runMigrationsAndValidate(TEST_DB, 5, true, TravelerDatabase.MIGRATION_4_5)

        // Validate trips
        val tripCursor = db5.query("SELECT id, title, totalDistanceMeters FROM trips WHERE id='trip_45'")
        assertTrue(tripCursor.moveToFirst())
        assertEquals("Euro Journey", tripCursor.getString(tripCursor.getColumnIndexOrThrow("title")))
        assertEquals(450000.0, tripCursor.getDouble(tripCursor.getColumnIndexOrThrow("totalDistanceMeters")), 0.001)
        tripCursor.close()

        // Validate visits
        val visitCursor = db5.query("SELECT placeName, timezoneId, isUserOverride FROM visits WHERE sourceId='v_45'")
        assertTrue(visitCursor.moveToFirst())
        assertEquals("Eiffel Tower", visitCursor.getString(visitCursor.getColumnIndexOrThrow("placeName")))
        assertEquals("Europe/Paris", visitCursor.getString(visitCursor.getColumnIndexOrThrow("timezoneId")))
        assertEquals(1, visitCursor.getInt(visitCursor.getColumnIndexOrThrow("isUserOverride")))
        visitCursor.close()

        // Validate movement_segments and default geometryProvenance
        val segmentCursor = db5.query("SELECT sourceId, userOverrideTransportMode, geometryProvenance FROM movement_segments WHERE sourceId='s_45'")
        assertTrue(segmentCursor.moveToFirst())
        assertTrue(segmentCursor.columnNames.contains("geometryProvenance"))
        assertEquals("s_45", segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("sourceId")))
        assertEquals("SUBWAY", segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("userOverrideTransportMode")))
        assertEquals("UNKNOWN", segmentCursor.getString(segmentCursor.getColumnIndexOrThrow("geometryProvenance")))
        segmentCursor.close()

        // Validate trip_media and default dayAssignment fields
        val mediaCursor = db5.query("SELECT mediaKey, captureTimezoneId, assignedDayIso, dayAssignmentConfidence, dayAssignmentProvenance FROM trip_media WHERE mediaKey='m_45'")
        assertTrue(mediaCursor.moveToFirst())
        assertTrue(mediaCursor.columnNames.contains("assignedDayIso"))
        assertTrue(mediaCursor.columnNames.contains("dayAssignmentConfidence"))
        assertTrue(mediaCursor.columnNames.contains("dayAssignmentProvenance"))
        assertEquals("m_45", mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("mediaKey")))
        assertEquals("Europe/Paris", mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("captureTimezoneId")))
        assertNull(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("assignedDayIso")))
        assertEquals("UNKNOWN", mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("dayAssignmentConfidence")))
        assertNull(mediaCursor.getString(mediaCursor.getColumnIndexOrThrow("dayAssignmentProvenance")))
        mediaCursor.close()

        // Validate user_overrides
        val overrideCursor = db5.query("SELECT id, overrideValue FROM user_overrides WHERE id='override_45'")
        assertTrue(overrideCursor.moveToFirst())
        assertEquals("Tour Eiffel Paris", overrideCursor.getString(overrideCursor.getColumnIndexOrThrow("overrideValue")))
        overrideCursor.close()

        db5.close()
    }

    @Test
    fun migrate3To5_multiVersionMigrationPreservesData() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO trips VALUES('t3', 'Trip 3', '2026-07-01', '2026-07-02', 100.0, '[]', '[]', 0, 1000)")
            execSQL("INSERT INTO visits VALUES('t3', 'v3', 'Kyoto Shrine', 'Kyoto, Japan', 'p3', 35.0, 135.7, 1000, 2000, 0.95, 0)")
            execSQL("INSERT INTO movement_segments VALUES('t3', 's3', 2000, 3000, 35.0, 135.7, 34.6, 135.5, 50000.0, 1800000, 'TRAIN', 0.99, 'Shinkansen', null, '[]', 0)")
            execSQL("INSERT INTO trip_media VALUES('t3', 'm3', 'content://m3', 'kyoto.jpg', 'image/jpeg', 1500, 'EXIF_EXACT', 'Asia/Tokyo', 35.0, 135.7, 'GPS_EXACT', 0.99, 'v3', null, 1, 0)")
            close()
        }

        val db5 = helper.runMigrationsAndValidate(TEST_DB, 5, true, TravelerDatabase.MIGRATION_3_4, TravelerDatabase.MIGRATION_4_5)
        val cursor = db5.query("SELECT id, title FROM trips WHERE id='t3'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Trip 3", cursor.getString(1))
        cursor.close()

        val visitCursor = db5.query("SELECT placeName FROM visits WHERE sourceId='v3'")
        assertTrue(visitCursor.moveToFirst())
        assertEquals("Kyoto Shrine", visitCursor.getString(0))
        visitCursor.close()

        val segCursor = db5.query("SELECT geometryProvenance FROM movement_segments WHERE sourceId='s3'")
        assertTrue(segCursor.moveToFirst())
        assertEquals("UNKNOWN", segCursor.getString(0))
        segCursor.close()

        val mediaCursor = db5.query("SELECT fileName, captureTimezoneId, dayAssignmentConfidence FROM trip_media WHERE mediaKey='m3'")
        assertTrue(mediaCursor.moveToFirst())
        assertEquals("kyoto.jpg", mediaCursor.getString(0))
        assertEquals("Asia/Tokyo", mediaCursor.getString(1))
        assertEquals("UNKNOWN", mediaCursor.getString(2))
        mediaCursor.close()

        db5.close()
    }

    @Test
    fun migrate2To5_fullChainMigrationPreservesData() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("INSERT INTO trips VALUES('t2', 'Trip 2', '2026-07-01', '2026-07-02', 100.0, '[]', '[]', 0, 1000)")
            execSQL("INSERT INTO visits VALUES('t2', 'v2', 'Seoul Tower', 'Seoul', 'p2', 37.5, 127.0, 1000, 2000, 0.9, 0)")
            execSQL("INSERT INTO trip_media VALUES('t2', 'm2', 'content://m2', 'seoul.jpg', 'image/jpeg', 1200, 'EXIF_EXACT', 37.5, 127.0, 'GPS_EXACT', 0.95, 'v2', null, 0, 0)")
            close()
        }

        val db5 = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            TravelerDatabase.MIGRATION_2_3,
            TravelerDatabase.MIGRATION_3_4,
            TravelerDatabase.MIGRATION_4_5
        )
        val cursor = db5.query("SELECT id, title FROM trips WHERE id='t2'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Trip 2", cursor.getString(1))
        cursor.close()

        val mediaCursor = db5.query("SELECT fileName, captureTimezoneId, dayAssignmentConfidence FROM trip_media WHERE mediaKey='m2'")
        assertTrue(mediaCursor.moveToFirst())
        assertEquals("seoul.jpg", mediaCursor.getString(0))
        assertNull("captureTimezoneId should be null from v2 migration", mediaCursor.getString(1))
        assertEquals("UNKNOWN", mediaCursor.getString(2))
        mediaCursor.close()

        db5.close()
    }
}

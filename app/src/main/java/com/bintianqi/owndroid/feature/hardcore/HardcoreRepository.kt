package com.bintianqi.owndroid.feature.hardcore

import android.content.ContentValues
import com.bintianqi.owndroid.MyDbHelper
import com.bintianqi.owndroid.feature.time_blocker.TimeWindow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HardcoreRepository(private val dbHelper: MyDbHelper) {
    private val json = Json { ignoreUnknownKeys = true }

    // === Allowlist ===

    fun getAllowlist(): Set<String> {
        val packages = mutableSetOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT package_name FROM hardcore_allowlist", null).use {
            while (it.moveToNext()) {
                packages += it.getString(0)
            }
        }
        return packages
    }

    fun addToAllowlist(packageName: String) {
        val cv = ContentValues()
        cv.put("package_name", packageName)
        dbHelper.writableDatabase.insertWithOnConflict(
            "hardcore_allowlist", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeFromAllowlist(packageName: String) {
        dbHelper.writableDatabase.delete("hardcore_allowlist", "package_name = ?", arrayOf(packageName))
    }

    // === Schedules ===

    fun getSchedules(): List<HardcoreSchedule> {
        val schedules = mutableListOf<HardcoreSchedule>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT id, start_minutes, end_minutes, days_of_week, enabled FROM hardcore_schedules", null
        ).use {
            while (it.moveToNext()) {
                val days: Set<Int> = try {
                    json.decodeFromString<List<Int>>(it.getString(3)).toSet()
                } catch (_: Exception) {
                    (1..7).toSet()
                }
                schedules += HardcoreSchedule(
                    id = it.getInt(0),
                    window = TimeWindow(
                        startMinutes = it.getInt(1),
                        endMinutes = it.getInt(2),
                        daysOfWeek = days
                    ),
                    enabled = it.getInt(4) == 1
                )
            }
        }
        return schedules
    }

    fun addSchedule(schedule: HardcoreSchedule): Int {
        val cv = ContentValues()
        cv.put("start_minutes", schedule.window.startMinutes)
        cv.put("end_minutes", schedule.window.endMinutes)
        cv.put("days_of_week", json.encodeToString(schedule.window.daysOfWeek.toList()))
        cv.put("enabled", if (schedule.enabled) 1 else 0)
        return dbHelper.writableDatabase.insert("hardcore_schedules", null, cv).toInt()
    }

    fun updateSchedule(schedule: HardcoreSchedule) {
        val cv = ContentValues()
        cv.put("start_minutes", schedule.window.startMinutes)
        cv.put("end_minutes", schedule.window.endMinutes)
        cv.put("days_of_week", json.encodeToString(schedule.window.daysOfWeek.toList()))
        cv.put("enabled", if (schedule.enabled) 1 else 0)
        dbHelper.writableDatabase.update("hardcore_schedules", cv, "id = ?", arrayOf(schedule.id.toString()))
    }

    fun deleteSchedule(id: Int) {
        dbHelper.writableDatabase.delete("hardcore_schedules", "id = ?", arrayOf(id.toString()))
    }

    fun getEnabledSchedules(): List<HardcoreSchedule> = getSchedules().filter { it.enabled }

    // === Snapshot (for DNS/restriction state restore) ===

    fun setSnapshot(key: String, value: String) {
        val cv = ContentValues()
        cv.put("key", key)
        cv.put("value", value)
        dbHelper.writableDatabase.insertWithOnConflict(
            "hardcore_snapshot", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setSnapshotBatch(entries: Map<String, String>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for ((key, value) in entries) {
                val cv = ContentValues()
                cv.put("key", key)
                cv.put("value", value)
                db.insertWithOnConflict("hardcore_snapshot", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllSnapshots(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        dbHelper.readableDatabase.rawQuery("SELECT key, value FROM hardcore_snapshot", null).use {
            while (it.moveToNext()) {
                map[it.getString(0)] = it.getString(1)
            }
        }
        return map
    }

    fun clearSnapshot() {
        dbHelper.writableDatabase.delete("hardcore_snapshot", null, null)
    }

    // === Suspended tracking (crash recovery) ===

    fun getSuspendedByUs(): Set<String> {
        val packages = mutableSetOf<String>()
        dbHelper.readableDatabase.rawQuery("SELECT package_name FROM hardcore_suspended", null).use {
            while (it.moveToNext()) {
                packages += it.getString(0)
            }
        }
        return packages
    }

    fun setSuspendedByUs(packages: Set<String>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("hardcore_suspended", null, null)
            for (pkg in packages) {
                val cv = ContentValues()
                cv.put("package_name", pkg)
                db.insert("hardcore_suspended", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

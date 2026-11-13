package de.cmile.openexactfit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.cmile.openexactfit.ble.Protocol
import de.cmile.openexactfit.ble.Protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** one row per reading. same columns as the python tool's sqlite file, so exports line up. */
data class StoredReading(
    val id: Long,
    val device: String,
    val user: String?,
    val measuredAt: String?,
    val receivedAt: String,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val flags: String,
    val rawHex: String,
    /** what the owner wrote about this reading; not from the monitor. */
    val note: String? = null,
)

class MeasurementStore(context: Context) : SQLiteOpenHelper(context, "measurements.sqlite", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """create table measurements (
                id integer primary key,
                device text,
                user text,
                measured_at text,
                received_at text not null,
                systolic integer not null,
                diastolic integer not null,
                map_field integer not null,
                pulse integer,
                flags text,
                raw_hex text not null,
                note text
            )"""
        )
        // one row per reading. the monitor sends the same reading twice with different
        // raw bytes (live and from storage, bytes 5..6 differ), so raw_hex cannot be the key.
        db.execSQL(
            """create unique index measurements_reading on measurements (
                device, coalesce(user, ''), coalesce(measured_at, ''),
                systolic, diastolic, coalesce(pulse, -1)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // version 1 keyed rows on (device, raw_hex) as a table constraint, which
            // sqlite cannot drop: rebuild, collapsing readings that were stored twice.
            db.execSQL("alter table measurements rename to measurements_old")
            onCreate(db)
            db.execSQL(
                """insert or ignore into measurements (device, user, measured_at, received_at,
                    systolic, diastolic, map_field, pulse, flags, raw_hex)
                   select device, user, measured_at, received_at, systolic, diastolic,
                    map_field, pulse, flags, raw_hex from measurements_old order by id"""
            )
            db.execSQL("drop table measurements_old")
        } else if (oldVersion < 3) {
            // version 3 adds the owner's note; the rebuild above creates it already
            db.execSQL("alter table measurements add column note text")
        }
    }

    /**
     * stores a reading; false if this reading from this monitor is already there.
     * [receivedAt] defaults to now; an import passes the original time and the note along.
     */
    fun add(m: Protocol.Measurement, device: String, receivedAt: String? = null, note: String? = null): Boolean {
        val values = ContentValues().apply {
            put("device", device)
            put("user", m.user)
            put("measured_at", m.timestamp?.format(FORMAT))
            put("received_at", receivedAt ?: LocalDateTime.now().format(FORMAT))
            put("systolic", m.systolic)
            put("diastolic", m.diastolic)
            put("map_field", m.mapField)
            if (m.pulse != null) put("pulse", m.pulse) else putNull("pulse")
            put("flags", m.flags.joinToString(","))
            put("raw_hex", m.raw.toHex())
            if (note.isNullOrBlank()) putNull("note") else put("note", note.trim())
        }
        val fresh = writableDatabase.insertWithOnConflict("measurements", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (fresh) changed()
        return fresh
    }

    fun all(): List<StoredReading> = readableDatabase.rawQuery(
        "select id, device, user, measured_at, received_at, systolic, diastolic, pulse, flags, raw_hex, note " +
            "from measurements order by coalesce(measured_at, received_at) desc, id desc", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                StoredReading(
                    id = c.getLong(0), device = c.getString(1), user = c.getString(2),
                    measuredAt = c.getString(3), receivedAt = c.getString(4),
                    systolic = c.getInt(5), diastolic = c.getInt(6),
                    pulse = if (c.isNull(7)) null else c.getInt(7),
                    flags = c.getString(8) ?: "", rawHex = c.getString(9),
                    note = c.getString(10),
                )
            )
        }
    }

    /** removes one reading from this database only; the monitor is never touched. */
    fun delete(id: Long): Boolean {
        val gone = writableDatabase.delete("measurements", "id = ?", arrayOf(id.toString())) == 1
        if (gone) changed()
        return gone
    }

    /** the owner's note on a reading; blank clears it. */
    fun setNote(id: Long, note: String?): Boolean {
        val values = ContentValues().apply { if (note.isNullOrBlank()) putNull("note") else put("note", note.trim()) }
        val done = writableDatabase.update("measurements", values, "id = ?", arrayOf(id.toString())) == 1
        if (done) changed()
        return done
    }

    /** gives the stored copy of [m] this note, unless it already has one. */
    private fun fillNote(m: Protocol.Measurement, device: String, note: String) {
        writableDatabase.execSQL(
            "update measurements set note = ? where device = ? and coalesce(user, '') = ? and coalesce(measured_at, '') = ? " +
                "and systolic = ? and diastolic = ? and coalesce(pulse, -1) = ? and coalesce(note, '') = ''",
            arrayOf<Any>(note, device, m.user ?: "", m.timestamp?.format(FORMAT) ?: "", m.systolic, m.diastolic, m.pulse ?: -1),
        )
        changed()
    }

    fun csv(): String = buildString {
        appendLine("id,device,user,measured_at,received_at,systolic,diastolic,pulse,flags,raw_hex,note")
        for (r in all().asReversed()) appendLine(
            listOf(r.id, r.device, r.user ?: "", r.measuredAt ?: "", r.receivedAt, r.systolic, r.diastolic,
                r.pulse ?: "", r.flags, r.rawHex, r.note ?: "").joinToString(",") { csvField(it.toString()) }
        )
    }

    data class ImportResult(val fresh: Int, val duplicates: Int, val unreadable: Int)

    /**
     * reads an export of either client back in. every reading is re-parsed from its
     * `raw_hex`, so the python export (which has a `map_field` column) and our own both
     * work, and the usual deduplication applies. device, receipt time and note come from the
     * file; a reading that is already stored without a note gets the file's.
     */
    fun importCsv(text: String): ImportResult {
        var fresh = 0
        var duplicates = 0
        var unreadable = 0
        for (r in csvRecords(text)) {
            val m = try {
                Protocol.parseMeasurement(Protocol.hex(r["raw_hex"].orEmpty()))
            } catch (e: Exception) {
                unreadable++
                continue
            }
            val device = r["device"].orEmpty().ifBlank { "?" }
            val note = r["note"].orEmpty().trim()
            if (add(m, device, r["received_at"]?.takeIf { it.isNotBlank() }, note)) {
                fresh++
            } else {
                duplicates++
                if (note.isNotEmpty()) fillNote(m, device, note)
            }
        }
        return ImportResult(fresh, duplicates, unreadable)
    }

    private fun changed() = _changes.update { it + 1 }

    companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val _changes = MutableStateFlow(0)

        /**
         * bumps whenever any instance in this process adds or deletes a row, so the screen
         * can reload after the background service stored a reading.
         */
        val changes: StateFlow<Int> = _changes
    }
}

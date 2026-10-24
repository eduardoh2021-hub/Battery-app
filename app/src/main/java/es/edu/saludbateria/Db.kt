package es.edu.saludbateria

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.abs

/** Subida mínima de nivel (en puntos de %) para que una carga sirva para estimar la capacidad. */
const val MIN_DELTA_LEVEL = 15

/** Una sesión de carga: desde que se enchufa hasta que se desenchufa. */
class Session(
    val id: Long,
    val startTs: Long,
    var endTs: Long?,
    var lastTs: Long,
    val startLevel: Int,
    var endLevel: Int,
    val startCounter: Long?,
    var endCounter: Long?,
    /** mAh integrados a partir de la corriente, con el signo del fabricante. */
    var mahIntegrated: Double,
    var lastCurrentUa: Long?,
    var maxTempDeci: Int,
    var plug: Int,
    var cycles: Int?,
) {
    val deltaLevel: Int get() = endLevel - startLevel

    /** mAh que han entrado según el contador de carga del chip. */
    val mahCounter: Double?
        get() {
            val a = startCounter
            val b = endCounter
            return if (a != null && b != null && b > a) (b - a) / 1000.0 else null
        }

    /** mAh que han entrado midiendo la corriente (coulomb counting). */
    val mahCurrent: Double? get() = abs(mahIntegrated).takeIf { it > 1 }

    val mahBest: Double? get() = mahCurrent ?: mahCounter

    val estCounter: Double?
        get() = if (deltaLevel >= MIN_DELTA_LEVEL) mahCounter?.let { it * 100 / deltaLevel } else null

    val estCurrent: Double?
        get() = if (deltaLevel >= MIN_DELTA_LEVEL) mahCurrent?.let { it * 100 / deltaLevel } else null

    val estimate: Double? get() = estCurrent ?: estCounter

    /** Copia los datos de una lectura a la sesión abierta. */
    fun apply(s: Snapshot) {
        lastTs = s.time
        if (s.currentUa != null) lastCurrentUa = s.currentUa
        if (s.level >= 0) endLevel = s.level
        if (s.counterUah != null) endCounter = s.counterUah
        if (s.tempDeci > maxTempDeci) maxTempDeci = s.tempDeci
        if (s.plugged != 0) plug = s.plugged
        if (s.cycles != null) cycles = s.cycles
    }
}

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "bateria.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_ts INTEGER NOT NULL,
                end_ts INTEGER,
                last_ts INTEGER NOT NULL,
                start_level INTEGER NOT NULL,
                end_level INTEGER NOT NULL,
                start_counter INTEGER,
                end_counter INTEGER,
                mah_integrated REAL NOT NULL DEFAULT 0,
                last_current INTEGER,
                max_temp INTEGER NOT NULL DEFAULT 0,
                plug INTEGER NOT NULL DEFAULT 0,
                cycles INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private val cols =
        "id,start_ts,end_ts,last_ts,start_level,end_level,start_counter,end_counter," +
            "mah_integrated,last_current,max_temp,plug,cycles"

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    private fun Cursor.toSession() = Session(
        id = getLong(0),
        startTs = getLong(1),
        endTs = longOrNull(2),
        lastTs = getLong(3),
        startLevel = getInt(4),
        endLevel = getInt(5),
        startCounter = longOrNull(6),
        endCounter = longOrNull(7),
        mahIntegrated = getDouble(8),
        lastCurrentUa = longOrNull(9),
        maxTempDeci = getInt(10),
        plug = getInt(11),
        cycles = intOrNull(12),
    )

    fun openSession(): Session? =
        readableDatabase.rawQuery(
            "SELECT $cols FROM sessions WHERE end_ts IS NULL ORDER BY id DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    /** Sesiones de la más reciente a la más antigua. */
    fun sessions(limit: Int): List<Session> =
        readableDatabase.rawQuery(
            "SELECT $cols FROM sessions ORDER BY start_ts DESC LIMIT $limit", null
        ).use { c ->
            val out = ArrayList<Session>()
            while (c.moveToNext()) out.add(c.toSession())
            out
        }

    fun create(s: Snapshot): Session {
        val v = ContentValues()
        v.put("start_ts", s.time)
        v.put("last_ts", s.time)
        v.put("start_level", s.level)
        v.put("end_level", s.level)
        v.put("start_counter", s.counterUah)
        v.put("end_counter", s.counterUah)
        v.put("mah_integrated", 0.0)
        v.put("last_current", s.currentUa)
        v.put("max_temp", s.tempDeci)
        v.put("plug", s.plugged)
        v.put("cycles", s.cycles)
        val id = writableDatabase.insert("sessions", null, v)
        return Session(
            id, s.time, null, s.time, s.level, s.level, s.counterUah, s.counterUah,
            0.0, s.currentUa, s.tempDeci, s.plugged, s.cycles
        )
    }

    fun update(s: Session) {
        val v = ContentValues()
        v.put("end_ts", s.endTs)
        v.put("last_ts", s.lastTs)
        v.put("end_level", s.endLevel)
        v.put("end_counter", s.endCounter)
        v.put("mah_integrated", s.mahIntegrated)
        v.put("last_current", s.lastCurrentUa)
        v.put("max_temp", s.maxTempDeci)
        v.put("plug", s.plug)
        v.put("cycles", s.cycles)
        writableDatabase.update("sessions", v, "id=?", arrayOf(s.id.toString()))
    }

    /**
     * Cierra una sesión. Si la lectura de desenchufe es reciente se usa como punto final.
     * Las conexiones de unos segundos sin subida de nivel se descartan.
     */
    fun close(s: Session, unplug: Snapshot?) {
        if (unplug != null && unplug.time - s.lastTs < 5 * 60_000L) {
            if (unplug.level >= 0) s.endLevel = unplug.level
            if (unplug.counterUah != null) s.endCounter = unplug.counterUah
        }
        val end = s.lastTs
        if (end - s.startTs < 3 * 60_000L && s.deltaLevel < 1) {
            delete(s.id)
            return
        }
        s.endTs = end
        update(s)
    }

    fun delete(id: Long) {
        writableDatabase.delete("sessions", "id=?", arrayOf(id.toString()))
    }
}

package bg.drone.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import bg.drone.rid.GuardEngine
import bg.drone.rid.GuardEvent
import bg.drone.rid.GuardEventType
import bg.drone.rid.GuardZone
import bg.drone.rid.GuardZones
import bg.drone.rid.Target
import bg.drone.rid.TargetSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuardLogEntry(val id: Long, val timeMs: Long, val zone: String, val target: String, val kind: String, val type: String, val distanceM: Double, val altitudeM: Double?)

/**
 * Режим „охрана на обект“: зони, засичане на нарушения, известия и дневник.
 * Вика се и от приложението (всички източници), и от фоновата услуга (само Remote ID),
 * за да работи и когато екранът е изключен.
 */
object Guard {
    private val engine = GuardEngine()
    private val _zones = MutableStateFlow<List<GuardZone>>(emptyList())
    val zones: StateFlow<List<GuardZone>> = _zones.asStateFlow()
    @Volatile private var loaded = false
    private var db: GuardDb? = null
    const val CHANNEL = "guard"

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _zones.value = GuardZones.fromJson(Settings(context).guardZones)
            db = GuardDb(context.applicationContext)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Охрана на обект: нарушения", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) },
            )
            loaded = true
        }
    }

    fun zones(context: Context): List<GuardZone> { ensure(context); return _zones.value }

    fun save(context: Context, zones: List<GuardZone>) {
        ensure(context)
        _zones.value = zones
        Settings(context).guardZones = GuardZones.toJson(zones)
    }

    fun check(context: Context, targets: List<Target>, scope: Set<TargetSource>? = null) {
        ensure(context)
        val zones = _zones.value
        if (zones.none { it.active }) return
        val events = engine.evaluate(zones, targets, System.currentTimeMillis(), scope)
        for (e in events) {
            db?.log(e)
            if (e.type == GuardEventType.ENTER) notify(context, e)
        }
    }

    fun insideCount(zoneId: String) = engine.insideCount(zoneId)
    fun log(context: Context, limit: Int = 200): List<GuardLogEntry> { ensure(context); return db?.recent(limit).orEmpty() }
    fun clearLog(context: Context) { ensure(context); db?.clear() }

    private fun notify(context: Context, e: GuardEvent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, ("guard" + e.target.id).hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT, e.target.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drone)
            .setContentTitle("⚠ Нарушение: ${e.zone.name}")
            .setContentText("${e.target.kind.label} ${e.target.title} на ${Format.distance(e.distanceM)} от центъра · ${Format.altitude(e.target.altitudeM)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${e.target.kind.label} ${e.target.title}\n${e.target.subtitle ?: ""}\n" +
                    "${Format.distance(e.distanceM)} от центъра на „${e.zone.name}“ · височина ${Format.altitude(e.target.altitudeM)} · ${e.target.source.label}",
            ))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(("guard" + e.zone.id + e.target.id).hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

private class GuardDb(context: Context) : SQLiteOpenHelper(context, "guard.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, zone TEXT NOT NULL, target TEXT NOT NULL,
               kind TEXT NOT NULL, type TEXT NOT NULL, distance REAL NOT NULL, altitude REAL, lat REAL, lon REAL)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun log(e: GuardEvent) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("time", e.timeMs); put("zone", e.zone.name); put("target", e.target.title); put("kind", e.target.kind.label)
            put("type", e.type.label); put("distance", e.distanceM); e.target.altitudeM?.let { put("altitude", it) }
            put("lat", e.target.lat); put("lon", e.target.lon)
        })
    }

    fun recent(limit: Int): List<GuardLogEntry> = readableDatabase.rawQuery(
        "SELECT id,time,zone,target,kind,type,distance,altitude FROM events ORDER BY time DESC LIMIT ?", arrayOf(limit.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(GuardLogEntry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getDouble(6), if (c.isNull(7)) null else c.getDouble(7)))
        }
    }

    fun clear() { writableDatabase.delete("events", null, null) }
}

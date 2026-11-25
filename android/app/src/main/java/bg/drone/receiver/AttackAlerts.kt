package bg.drone.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import bg.drone.rid.AlertItem
import bg.drone.rid.AlertRegion
import bg.drone.rid.Alerts
import bg.drone.rid.News
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Фонова проверка за нови сигнали за атаки (на всеки 30 мин, и при затворено приложение). */
class AttackAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Settings(applicationContext).attackAlerts) return Result.success()
        val r = withContext(Dispatchers.IO) { runCatching { News.fetchAll(Alerts.SOURCES) } }
        val items = r.getOrNull()?.items ?: return Result.retry()
        AttackAlerts.process(applicationContext, Alerts.fromNews(items))
        return Result.success()
    }
}

object AttackAlerts {
    private const val CHANNEL = "attacks"
    private const val WORK = "attack-alerts"
    private const val MAX_AGE_MS = 6 * 3_600_000L

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<AttackAlertWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

    fun sync(context: Context) {
        if (Settings(context).attackAlerts) schedule(context) else cancel(context)
    }

    private fun key(a: AlertItem) = a.news.title.lowercase().filter { it.isLetterOrDigit() }.take(48)

    /**
     * Известява за нови сигнали в избраните региони. При първото пускане само запомня текущите,
     * за да не залее телефона със стари новини.
     */
    @Synchronized
    fun process(context: Context, items: List<AlertItem>) {
        val s = Settings(context)
        val seen = s.seenAlerts.split('\n').filter { it.isNotBlank() }.toMutableList()
        val firstRun = seen.isEmpty()
        val regions = s.attackRegions
        val now = System.currentTimeMillis()
        val fresh = items.filter { a ->
            key(a) !in seen &&
                (a.news.publishedMs?.let { now - it < MAX_AGE_MS } ?: true) &&
                a.regions.any { it in regions }
        }
        items.forEach { a -> key(a).let { if (it !in seen) seen += it } }
        s.seenAlerts = seen.takeLast(400).joinToString("\n")
        if (!firstRun && s.attackAlerts) fresh.take(5).forEach { notify(context, it) }
    }

    private fun notify(context: Context, a: AlertItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Сигнали за атаки с дронове и ракети", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, a.news.link.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(a.news.link)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "⚠ ${a.kind}" + (a.regions.firstOrNull()?.let { " · ${it.label}" } ?: "")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drone)
            .setContentTitle(title)
            .setContentText(a.news.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${a.news.title}\n${a.news.source}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(a.news.link.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }

    val DEFAULT_REGIONS = setOf(AlertRegion.BLACK_SEA, AlertRegion.BULGARIA, AlertRegion.ROMANIA)
}

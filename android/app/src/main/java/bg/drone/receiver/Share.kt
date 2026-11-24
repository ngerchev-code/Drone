package bg.drone.receiver

import android.content.Context
import android.content.Intent
import bg.drone.rid.NewsItem
import bg.drone.rid.Target
import java.util.Locale

/** Споделяне през системното меню на Android (Viber, Messenger, имейл…). */
object Share {
    const val APP_LINK = "https://github.com/ngerchev-code/Drone/releases/latest/download/DroneRadar.apk"

    fun text(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, "Сподели").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun target(context: Context, t: Target) {
        val map = String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.5f&mlon=%.5f#map=13/%.5f/%.5f", t.lat, t.lon, t.lat, t.lon)
        val lines = listOfNotNull(
            "${t.kind.label}: ${t.title}",
            t.subtitle,
            "Височина ${Format.altitude(t.altitudeM)} · скорост ${Format.speed(t.speedMs)} · курс ${Format.heading(t.track)}",
            "Източник: ${t.source.label} · ${Format.ago(t.lastSeenMs)}",
            map,
            "",
            "Засечено с DroneRadar: $APP_LINK",
        )
        text(context, lines.joinToString("\n"), "DroneRadar: ${t.title}")
    }

    fun news(context: Context, n: NewsItem) = text(context, "${n.title}\n${n.link}\n\nот DroneRadar · ${n.source}", n.title)
}

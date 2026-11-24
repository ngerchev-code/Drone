package bg.drone.receiver

import android.app.Application
import org.maplibre.android.MapLibre

class DroneRadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this) // трябва да е преди първия MapView
    }
}

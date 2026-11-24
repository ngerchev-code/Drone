package bg.drone.receiver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    /** Разрешенията, които искаме при старт (зависят от версията на Android). */
    fun required(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasScan(ctx: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(ctx, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasLocation(ctx) // до Android 11 BLE сканирането изисква локация
    }

    /** Минимумът за старт: foreground service тип „location“ изисква разрешение за локация. */
    fun canStart(ctx: Context) = hasLocation(ctx)
}

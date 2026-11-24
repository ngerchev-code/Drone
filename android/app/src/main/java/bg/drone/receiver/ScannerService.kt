package bg.drone.receiver

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import bg.drone.rid.DemoFleet
import bg.drone.rid.IngestBatcher
import bg.drone.rid.RID_SERVICE_UUID
import bg.drone.rid.RidTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service, който:
 *  1. сканира BLE (legacy + long range) и WiFi beacon за Remote ID
 *  2. подава кадрите към локалния DroneTracker (работи и офлайн)
 *  3. по избор праща суровите кадри към DroneRadar сървъра на всяка секунда
 */
class ScannerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settings: Settings
    private var bleScanner: BluetoothLeScanner? = null
    private var wifiReceiverRegistered = false
    private var locationListener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ReceiverState.status.value.running) return START_STICKY

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // напр. липсва разрешение за локация на Android 14+
            ReceiverState.updateStatus { it.copy(running = false, message = "Не може да стартира: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        ReceiverState.batcher = IngestBatcher(settings.receiverId)
        val demo = settings.demoMode
        ReceiverState.updateStatus { ReceiverStatus(running = true, demo = demo) }

        if (demo) startDemo() else {
            startBle()
            startWifi()
        }
        startLocation()
        startLoops()
        return START_STICKY
    }

    override fun onDestroy() {
        stopBle()
        stopWifi()
        stopLocation()
        scope.cancel()
        ReceiverState.updateStatus { it.copy(running = false, bleActive = false, wifiActive = false) }
        super.onDestroy()
    }

    // ---------------- BLE ----------------

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            ReceiverState.updateStatus { it.copy(bleActive = false, message = "BLE сканирането спря (код $errorCode)") }
        }
    }

    private fun handle(r: ScanResult) {
        val longRange = r.primaryPhy == BluetoothDevice.PHY_LE_CODED
        val record = r.scanRecord ?: return
        val frame = RidTransport.fromBleServiceData(record.getServiceData(RID_UUID), longRange)
            ?: record.bytes?.let { RidTransport.fromBleAdvertisement(it, longRange) }
            ?: return
        ReceiverState.onFrame(r.device.address, frame, r.rssi)
    }

    @SuppressLint("MissingPermission") // проверено в Permissions.hasScan()
    private fun startBle() {
        if (!Permissions.hasScan(this)) {
            ReceiverState.updateStatus { it.copy(message = "Няма разрешение за Bluetooth сканиране") }
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            ReceiverState.updateStatus { it.copy(message = "Bluetooth е изключен") }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        val longRange = adapter.isLeCodedPhySupported
        val filters = listOf(ScanFilter.Builder().setServiceData(RID_UUID, ByteArray(0)).build())
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setLegacy(false) // включва и Bluetooth 5 extended advertising
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        try {
            scanner.startScan(filters, scanSettings, bleCallback)
            bleScanner = scanner
            ReceiverState.updateStatus { it.copy(bleActive = true, longRangeSupported = longRange) }
        } catch (e: SecurityException) {
            ReceiverState.updateStatus { it.copy(message = "BLE: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try {
            bleScanner?.stopScan(bleCallback)
        } catch (_: Exception) {
        }
        bleScanner = null
    }

    // ---------------- WiFi Beacon ----------------
    // Android ограничава startScan() до 4 пъти на 2 минути, затова сканираме на всеки 30 s.
    // Vendor IE-тата са достъпни от Android 11 (API 30).

    private val wifiReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val wifi = getSystemService(WifiManager::class.java) ?: return
            val results = try { wifi.scanResults } catch (_: SecurityException) { return }
            for (r in results) {
                for (ie in r.informationElements) {
                    val buf = ie.bytes
                    val bytes = ByteArray(buf.remaining()).also { buf.duplicate().get(it) }
                    val frame = RidTransport.fromWifiVendorIe(ie.id, bytes) ?: continue
                    ReceiverState.onFrame(r.BSSID, frame, r.level)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // WifiManager.startScan() няма заместител за активно сканиране
    private fun startWifi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Permissions.hasLocation(this)) return
        ContextCompat.registerReceiver(
            this, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        wifiReceiverRegistered = true
        ReceiverState.updateStatus { it.copy(wifiActive = true) }
        scope.launch {
            val wifi = getSystemService(WifiManager::class.java)
            while (isActive) {
                try {
                    wifi?.startScan()
                } catch (_: SecurityException) {
                }
                delay(30_000)
            }
        }
    }

    private fun stopWifi() {
        if (wifiReceiverRegistered) {
            unregisterReceiver(wifiReceiver)
            wifiReceiverRegistered = false
        }
    }

    // ---------------- Демо ----------------

    private fun startDemo() {
        scope.launch {
            // демо дроновете кръжат около телефона, ако позицията е известна (иначе около София)
            val here = ReceiverState.location.value ?: lastKnownLocation()
            val fleet = if (here != null) DemoFleet(here.first, here.second) else DemoFleet()
            val start = System.currentTimeMillis()
            while (isActive) {
                val t = (System.currentTimeMillis() - start) / 1000.0
                fleet.frames(t).forEach { (src, f) -> ReceiverState.onFrame(src, f, -55) }
                delay(1000)
            }
        }
    }

    // ---------------- Локация на приемника ----------------

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Pair<Double, Double>? {
        if (!Permissions.hasLocation(this)) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!Permissions.hasLocation(this)) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        ReceiverState.location.value = lastKnownLocation()
        val listener = LocationListener { loc: Location -> ReceiverState.location.value = loc.latitude to loc.longitude }
        for (p in lm.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }) {
            try {
                lm.requestLocationUpdates(p, 10_000L, 25f, listener, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        locationListener = listener
    }

    private fun stopLocation() {
        locationListener?.let { getSystemService(LocationManager::class.java)?.removeUpdates(it) }
        locationListener = null
    }

    // ---------------- UI опресняване и качване ----------------

    private fun startLoops() {
        scope.launch {
            while (isActive) {
                ReceiverState.refresh()
                delay(1000)
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (!settings.uploadEnabled) continue
                val batcher = ReceiverState.batcher ?: continue
                val loc = ReceiverState.location.value
                val batch = batcher.drain(loc?.first, loc?.second) ?: continue
                val ok = withContext(Dispatchers.IO) { post("${settings.serverUrl}/api/ingest", batch.json) }
                if (ok) {
                    ReceiverState.updateStatus { it.copy(uploadedTotal = it.uploadedTotal + batch.items.size, lastUploadOk = true) }
                } else {
                    batcher.restore(batch)
                    ReceiverState.updateStatus { it.copy(lastUploadOk = false) }
                    delay(4000) // backoff при липса на мрежа / сървър
                }
            }
        }
    }

    private fun post(url: String, body: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..299
    } catch (e: Exception) {
        ReceiverState.updateStatus { it.copy(message = "Сървърът не отговаря: ${e.javaClass.simpleName}") }
        false
    }

    // ---------------- Известие ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScannerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "scanner"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "bg.drone.receiver.STOP"
        private val RID_UUID: ParcelUuid = ParcelUuid.fromString(RID_SERVICE_UUID)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ScannerService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ScannerService::class.java))
    }
}

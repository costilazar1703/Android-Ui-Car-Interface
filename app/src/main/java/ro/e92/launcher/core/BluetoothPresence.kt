package ro.e92.launcher.core

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Numele telefonului conectat (audio sau apeluri), pentru dala Bluetooth.
 *
 * **Limita, cunoscută dinainte:** pe unitățile la care Bluetooth-ul e un modul
 * separat legat de MCU (BC6), Android nu vede telefonul deloc — atunci numele
 * rămâne `null`, iar dala arată pur și simplu iconița, fără text. Nu inventăm
 * o stare pe care n-o putem citi.
 */
object BluetoothPresence {

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val connected = LinkedHashMap<String, String>()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                    connected.clear()
                    publish()
                }
                return
            }
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED -> connected[device.address] = nameOf(device)
                BluetoothProfile.STATE_DISCONNECTED -> connected.remove(device.address)
            }
            publish()
        }
    }

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        val adapter = runCatching {
            (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: return
        started = true
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        // Ce era deja conectat înainte să pornim: broadcast-urile vin doar la schimbări.
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            runCatching {
                adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices }.getOrNull()?.forEach {
                            connected[it.address] = nameOf(it)
                        }
                        publish()
                        adapter.closeProfileProxy(p, proxy)
                    }
                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
    }

    private fun nameOf(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.address

    private fun publish() {
        _deviceName.value = connected.values.firstOrNull()
    }
}

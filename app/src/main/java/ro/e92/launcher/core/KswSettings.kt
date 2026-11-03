package ro.e92.launcher.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Setările unității KSW (SysVarProvider din EventCenter): un tabel cheie →
 * valoare, exportat fără permisiune. Citit și scris exact ca SysProviderOpt
 * al vendorului - aceleași coloane, aceeași selecție - ca aplicațiile lor să
 * vadă valoarea noastră ca pe una pusă din meniul lor de Factory.
 */
object KswSettings {

    private val URI: Uri = Uri.parse("content://com.szchoiceway.eventcenter.SysVarProvider/SysVar")
    private const val TAG = "KswSettings"

    /** Tipul modulului Bluetooth, citit de bTSuite la pornire (openSerialPort, setul de comenzi). */
    const val BT_TYPE = "Sys_BTDeviceType"

    /**
     * Valorile din pagina „BT Module" a aplicației Factory (BTSetFragment):
     * 1 SudingBC5/WenqingBC5, 2 SudingBC8, 3 WenqiangBC6, 4 WenqiangBC9.
     *
     * Cu 1-5 bTSuite vorbește cu modulul prin /dev/goc_serial, puntea pe care
     * o face stiva Bluetooth Feasycom (/oem/bin/blueware, cip CSR pe ttyS4).
     * Modulul din mașină e un cip HCI: fără blueware nu răspunde la nimic,
     * deci deschiderea directă a ttyS4 (tipul 0) nu ajută - verificat pe
     * unitate 2026-11-03. Vezi [KswBluetooth.ensureBridge].
     */
    const val BT_TYPE_BC6 = "3"

    fun read(context: Context, key: String): String? = runCatching {
        context.contentResolver.query(URI, arrayOf("keyvalue"), "keyname=?", arrayOf(key), null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.onFailure { Log.w(TAG, "nu pot citi $key", it) }.getOrNull()

    fun write(context: Context, key: String, value: String): Boolean = runCatching {
        val values = ContentValues().apply { put("keyvalue", value) }
        val updated = context.contentResolver.update(URI, values, "keyname=?", arrayOf(key))
        if (updated == 0) {
            context.contentResolver.insert(URI, ContentValues().apply {
                put("keyname", key)
                put("keyvalue", value)
            })
        }
        true
    }.onFailure { Log.w(TAG, "nu pot scrie $key", it) }.getOrDefault(false)
}

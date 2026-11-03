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
    const val BT_TYPE_BC5 = "1"
    const val BT_TYPE_FEIYITONG = "5"

    /** Ordinea din meniul Factory → BT Module; toate trei merg prin puntea Feasycom. */
    val BT_TYPES = listOf(BT_TYPE_BC6, BT_TYPE_BC5, BT_TYPE_FEIYITONG)

    fun btTypeLabel(value: String): String = when (value) {
        BT_TYPE_BC6 -> "WenqiangBC6"
        BT_TYPE_BC5 -> "WenqiangBC5"
        BT_TYPE_FEIYITONG -> "Feiyitong"
        else -> value
    }

    /**
     * „Reia ultima sursă la pornire" (EventCenter: m_bModeMemory, "0"/"1").
     * Cu ea pornită, la fiecare contact unitatea redeschidea ecranul Bluetooth
     * KSW peste salutul nostru.
     */
    const val MODE_MEMORY = "Sys_Mode_Memory"

    /**
     * Tipul mașinii din Factory → Car Type (/oem/app/CarType.xml). EventCenter
     * îl spune MCU-ului; de el depinde cum sunt citite butoanele de volan și
     * datele mașinii. Se aplică după o repornire.
     */
    const val CAR_TYPE = "KESAIWEI_RECORD_CAR_TYPE"

    val CAR_TYPES = linkedMapOf(
        "1" to "X1 2016, 6.5\" half screen",
        "2" to "E60 small screen (full)",
        "3" to "E60 big screen (full)",
        "4" to "5 Series 2012 / X3 2011, 6.5\" half",
        "5" to "5 Series 2012 / X3 2011, 6.5\" full",
        "6" to "X5 2009 / 5 Series 2011 navi (8.8/10.25)",
        "7" to "X1 2016, 6.5\" full screen",
        "8" to "7 Series 2011 navi (8.8/10.25)",
        "9" to "X1 2016 navi (8.8/10.25)"
    )

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

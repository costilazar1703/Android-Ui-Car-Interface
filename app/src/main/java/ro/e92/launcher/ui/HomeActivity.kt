package ro.e92.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ActivityHomeBinding
import ro.e92.launcher.focus.FocusDirection
import ro.e92.launcher.focus.FocusEngine
import ro.e92.launcher.input.KeyEventLog
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.input.RotaryAccelerator
import ro.e92.launcher.nav.NavLauncher
import ro.e92.launcher.net.ConnectivityMonitor
import ro.e92.launcher.ui.screens.AppDrawerScreen
import ro.e92.launcher.ui.screens.BluetoothMenuScreen
import ro.e92.launcher.ui.screens.CarInfoMenuScreen
import ro.e92.launcher.ui.screens.CarPlayMenuScreen
import ro.e92.launcher.ui.screens.ConnectedDriveMenuScreen
import ro.e92.launcher.ui.screens.DashboardMenuScreen
import ro.e92.launcher.ui.screens.DashboardScreen
import ro.e92.launcher.ui.screens.DiagnosticsScreen
import ro.e92.launcher.ui.screens.MediaMenuScreen
import ro.e92.launcher.ui.screens.MediaScreen
import ro.e92.launcher.ui.screens.MessagesScreen
import ro.e92.launcher.ui.screens.NavigationMenuScreen
import ro.e92.launcher.ui.screens.SettingsMenuScreen
import ro.e92.launcher.ui.screens.SettingsScreen
import ro.e92.launcher.ui.screens.TelephoneScreen
import ro.e92.launcher.ui.screens.TileGridScreen
import ro.e92.launcher.ui.screens.WeatherMenuScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singurul Activity al aplicației. Fără Navigation Component, fără back stack
 * standard: navigarea e dictată de butoanele fizice, iar [ScreenStack] o modelează
 * direct.
 */
class HomeActivity : ComponentActivity(), ScreenHost {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var stack: ScreenStack

    private val focus = FocusEngine()
    private val rotary = RotaryAccelerator()

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockDate = Date()

    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    override val activity: Activity get() = this

    // --------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(this)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupImmersive()

        stack = ScreenStack(
            container = binding.screenContainer,
            focus = focus,
            inflater = layoutInflater,
            animationsEnabled = { Services.prefs.animationsEnabled }
        ).apply {
            onTitleChanged = { binding.screenTitle.text = it }
            onBackgroundChanged = ::showBackground
        }

        stack.setRoot(TileGridScreen(this))

        // BACK de la touch / sistem: aceeași cale ca BACK-ul fizic.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Un launcher nu se închide niciodată din BACK — dacă suntem pe
                // rădăcină, pur și simplu nu se întâmplă nimic.
                stack.pop()
            }
        })

        requestRuntimePermissionsIfNeeded()
        observeStatusBar()
    }

    override fun onStart() {
        super.onStart()
        Services.vehicle.start()
        Services.media.start()
        Services.net.start()
        Services.apps.startWatching()

        registerReceiver(timeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateClock()

        stack.onActivityStart()

        if (Services.net.state.value.status != ConnectivityMonitor.Status.ONLINE) {
            Services.net.startAutoConnect(lifecycleScope)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(timeTickReceiver) }
        stack.onActivityStop()
        Services.apps.stopWatching()
        Services.net.stop()
        Services.media.stop()
        // Sursele CAN rămân pornite: sunt ieftine și vrem date proaspete instant
        // când utilizatorul revine din Waze.
        super.onStop()
    }

    override fun onDestroy() {
        stack.destroyAll()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Apăsarea butonului HOME în timp ce suntem deja în față: înapoi la rădăcină.
        goHome()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ------------------------------------------------------------------ input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Trebuie citit ÎNAINTE de resolve(): resolve consumă tasta și golește
        // modul "învață". Fără asta, legarea tastei BACK ar închide launcher-ul
        // în același gest.
        val wasLearning = Services.keys.pendingLearn != null

        val action = Services.keys.resolve(keyCode)
        if (action == null) {
            KeyEventLog.record(keyCode, null, consumed = wasLearning)
            return if (wasLearning) true else super.onKeyDown(keyCode, event)
        }
        val handled = handleAction(action)
        KeyEventLog.record(keyCode, action, handled)
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consumăm și UP-ul pentru tastele pe care le tratăm, altfel sistemul
        // reacționează la ele (BACK, MENU) după ce noi am acționat deja pe DOWN.
        if (Services.keys.isMapped(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleAction(action: LauncherAction): Boolean {
        // Ecranul curent are drept de prim refuz.
        if (stack.current?.onAction(action) == true) return true

        return when (action) {
            LauncherAction.ROTARY_CW -> { focus.next(rotary.step(+1)); true }
            LauncherAction.ROTARY_CCW -> { focus.prev(rotary.step(-1)); true }
            LauncherAction.SELECT -> { focus.activate(); true }

            LauncherAction.TILT_UP -> focus.move(FocusDirection.UP)
            LauncherAction.TILT_DOWN -> focus.move(FocusDirection.DOWN)
            LauncherAction.TILT_LEFT -> focus.move(FocusDirection.LEFT)
            LauncherAction.TILT_RIGHT -> focus.move(FocusDirection.RIGHT)

            LauncherAction.HOME -> { goHome(); true }
            LauncherAction.BACK -> { stack.pop(); true }
            LauncherAction.OPTION -> focus.option()

            // Butonul hard NAV lansează DIRECT aplicația de navigație — o singură
            // apăsare în mers. Pop-up-ul cu Waze/Chrome e pentru intrarea din meniu,
            // unde ai timp să alegi.
            LauncherAction.NAV -> { NavLauncher.launch(this, Services.prefs); true }
            LauncherAction.MEDIA -> { openTile(TileAction.MEDIA); true }
            LauncherAction.PHONE -> { openTile(TileAction.TELEPHONE); true }
            LauncherAction.APPS -> { pushUnique(AppDrawerScreen::class.java) { AppDrawerScreen(this) }; true }
            LauncherAction.DIAGNOSTICS -> { pushUnique(DiagnosticsScreen::class.java) { DiagnosticsScreen(this) }; true }

            LauncherAction.RADIO -> { openOemRadio(); true }

            LauncherAction.MEDIA_NEXT -> { Services.media.next(); true }
            LauncherAction.MEDIA_PREV -> { Services.media.previous(); true }
            LauncherAction.MEDIA_PLAY_PAUSE -> { Services.media.playPause(); true }
            LauncherAction.VOICE -> { launchAssistant(); true }
        }
    }

    override fun dispatch(action: LauncherAction) {
        handleAction(action)
    }

    // ------------------------------------------------- meniul principal (10)

    override fun openTile(action: TileAction) {
        when (action) {
            // Dalele cu alegeri reale deschid un meniu cu rotita (nivel 2).
            TileAction.NAVIGATION ->
                pushUnique(NavigationMenuScreen::class.java) { NavigationMenuScreen(this) }

            TileAction.MEDIA ->
                pushUnique(MediaMenuScreen::class.java) { MediaMenuScreen(this) }

            TileAction.BLUETOOTH ->
                pushUnique(BluetoothMenuScreen::class.java) { BluetoothMenuScreen(this) }

            TileAction.CAR_INFO ->
                pushUnique(CarInfoMenuScreen::class.java) { CarInfoMenuScreen(this) }

            TileAction.DASHBOARD ->
                pushUnique(DashboardMenuScreen::class.java) { DashboardMenuScreen(this) }

            TileAction.CARPLAY ->
                pushUnique(CarPlayMenuScreen::class.java) { CarPlayMenuScreen(this) }

            TileAction.CONNECTED_DRIVE ->
                pushUnique(ConnectedDriveMenuScreen::class.java) { ConnectedDriveMenuScreen(this) }

            TileAction.WEATHER ->
                pushUnique(WeatherMenuScreen::class.java) { WeatherMenuScreen(this) }

            // Telefonul si Mesajele SUNT deja meniuri (categorii in stanga,
            // detaliu in dreapta), iar App drawer-ul e o grila - toate trei au
            // propria forma si n-ar castiga nimic dintr-un meniu cu rotita in
            // fata lor. Setarile, in schimb, au primit unul: erau singura dala
            // care se deschidea altfel decat celelalte unsprezece.
            TileAction.TELEPHONE ->
                pushUnique(TelephoneScreen::class.java) { TelephoneScreen(this) }

            TileAction.MESSAGES ->
                pushUnique(MessagesScreen::class.java) { MessagesScreen(this) }

            TileAction.SETTINGS ->
                pushUnique(SettingsMenuScreen::class.java) { SettingsMenuScreen(this) }

            TileAction.APPS ->
                pushUnique(AppDrawerScreen::class.java) { AppDrawerScreen(this) }
        }
    }

    override fun dispatchConnectedDrive() = openConnectedDrive()

    /**
     * Lansează o aplicație terță atribuită din Setări. Cele două moduri de eșec —
     * neatribuită și dezinstalată — sunt distincte și spun utilizatorului exact
     * ce să facă, în loc să pară că butonul nu merge.
     */
    private fun launchAssigned(packageName: String, menuLabel: String) {
        if (packageName.isEmpty()) {
            Toast.makeText(
                this,
                "$menuLabel: ${getString(R.string.app_not_set)}",
                Toast.LENGTH_SHORT
            ).show()
            pushUnique(SettingsScreen::class.java) { SettingsScreen(this) }
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(
                this,
                getString(R.string.app_not_installed, packageName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }

    private fun openConnectedDrive() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Services.prefs.connectedDriveUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val browser = Services.prefs.browserPackage
        // Preferăm browser-ul configurat; dacă lipsește, lăsăm sistemul să aleagă.
        if (packageManager.getLaunchIntentForPackage(browser) != null) {
            intent.setPackage(browser)
        }
        if (intent.resolveActivity(packageManager) == null) {
            intent.setPackage(null)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(
                this,
                getString(R.string.app_not_installed, browser),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ----------------------------------------------------------- ScreenHost

    override fun push(screen: Screen) = stack.push(screen)

    override fun pop(): Boolean = stack.pop()

    override fun goHome() {
        rotary.reset()
        if (stack.current is TileGridScreen && stack.depth == 1) return
        stack.setRoot(TileGridScreen(this))
    }

    override fun rebuildFocus(keepId: String?) = stack.rebuildFocus(keepId)

    private fun <T : Screen> pushUnique(type: Class<T>, factory: () -> T) {
        if (type.isInstance(stack.current)) return
        stack.push(factory())
    }

    // ---------------------------------------------------------- fundal

    /** Drawable-ul afisat acum; fara el am reporni fade-ul la fiecare push. */
    private var currentBackground = 0

    /**
     * Schimba fotografia de fundal cu un fade incrucisat.
     *
     * Doua ImageView-uri suprapuse: cel de deasupra primeste imaginea noua si
     * urca de la alpha 0 la 1, apoi valorile se copiaza in cel de dedesubt si
     * cel de sus se goleste. Asa nu exista niciun cadru in care ecranul sa fie
     * negru intre doua fotografii.
     *
     * Bitmap-ul nou se pune ÎNAINTE de animatie, nu in timpul ei: decodarea unui
     * JPEG de 1280x480 dureaza cateva milisecunde si ar manca primele cadre.
     */
    private fun showBackground(resId: Int) {
        if (resId == currentBackground) return
        currentBackground = resId

        // 0 = ecran fara fotografie. Stingem imaginea si eliberam bitmap-ul:
        // pe 2 GB nu are rost sa tinem 2,4 MB decodati pentru ceva ce nu se vede.
        if (resId == 0) {
            binding.screenBackground.animate().cancel()
            binding.screenBackground.setImageDrawable(null)
            return
        }

        if (!Services.prefs.animationsEnabled) {
            binding.screenBackground.setImageResource(resId)
            return
        }

        binding.screenBackgroundNext.setImageResource(resId)
        binding.screenBackgroundNext.alpha = 0f
        binding.screenBackgroundNext.animate()
            .alpha(1f)
            .setDuration(BACKGROUND_FADE_MS)
            .withEndAction {
                binding.screenBackground.setImageResource(resId)
                binding.screenBackgroundNext.alpha = 0f
                binding.screenBackgroundNext.setImageDrawable(null)
            }
            .start()
    }

    // ------------------------------------------------------------- top bar

    private fun observeStatusBar() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Services.net.state.collect { binding.statusNet.text = describeNet(it) }
                }
                launch {
                    Services.media.snapshot.collect { media ->
                        binding.statusMedia.text =
                            if (media.hasSession) media.title.orEmpty() else ""
                    }
                }
            }
        }
    }

    private fun describeNet(state: ConnectivityMonitor.NetState): String = when (state.status) {
        ConnectivityMonitor.Status.ONLINE ->
            state.ssid ?: getString(R.string.net_online)
        ConnectivityMonitor.Status.CONNECTED_NO_INTERNET ->
            getString(R.string.net_no_internet)
        ConnectivityMonitor.Status.CONNECTING -> getString(R.string.net_connecting)
        ConnectivityMonitor.Status.WIFI_OFF -> getString(R.string.net_wifi_off)
        ConnectivityMonitor.Status.DISCONNECTED -> getString(R.string.net_offline)
    }

    private fun updateClock() {
        clockDate.time = System.currentTimeMillis()
        binding.clock.text = clockFormat.format(clockDate)
    }

    // ------------------------------------------------------------- helpers

    /**
     * True full screen: fără status bar, fără navigation bar.
     *
     * Se aplică în trei momente pentru că unitățile astea readuc barele în situații
     * în care Android n-ar face-o: după un dialog de permisiuni, după revenirea
     * dintr-o aplicație terță, uneori la un simplu touch. IMMERSIVE_STICKY singur
     * nu e suficient, de aici și listener-ul de mai jos.
     *
     * ATENȚIE: dacă bara cu „Back / Home" rămâne vizibilă și după asta, NU e bara
     * de sistem Android — e un overlay al vendorului (un serviciu propriu care
     * desenează peste tot, prin SYSTEM_ALERT_WINDOW). Aceea nu poate fi ascunsă
     * de aici; se dezactivează din setările unității sau cu `pm disable` pe
     * pachetul respectiv, ceea ce cere root. Ecranul de Diagnostic arată
     * dimensiunea reală a ferestrei — dacă e 1280x480 complet, noi ne-am făcut
     * treaba și ce rămâne deasupra e overlay străin.
     */
    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun setupImmersive() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // Bit-ul stins înseamnă că barele au reapărut. Reaplicăm după o
            // scurtă întârziere: imediat ar fi anulat de tranziția în curs.
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.postDelayed(::applyImmersive, IMMERSIVE_REAPPLY_MS)
            }
        }
        applyImmersive()
    }

    /**
     * Sursa "Radio OEM" din selectorul de media depinde de Open Question 5:
     * dacă unitatea de 10.25" a înlocuit fizic CIC-ul, radioul e o aplicație
     * vendor lansabilă; dacă CIC-ul a rămas în paralel, butonul RADIO probabil
     * nici nu ajunge la Android. Până atunci, deschidem aplicația vendor dacă
     * o găsim după intent, altfel nu facem nimic zgomotos.
     */
    private fun openOemRadio() {
        val candidates = listOf("com.hardware.radio", "com.zx.radio", "com.android.fmradio")
        for (pkg in candidates) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        pushUnique(DiagnosticsScreen::class.java) { DiagnosticsScreen(this) }
    }

    private fun launchAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val needed = ArrayList<String>(2)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Necesară pentru: viteza de fallback din GPS + citirea SSID-ului pe 8.1.
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            // Sursele se re-evaluează: GPS-ul devine utilizabil abia acum.
            Services.rebuildCanSources()
            Services.net.refresh()
        }
    }

    private companion object {
        const val REQ_PERMISSIONS = 1001
        const val IMMERSIVE_REAPPLY_MS = 400L
        const val BACKGROUND_FADE_MS = 260L
    }
}

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
import android.os.SystemClock
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
import ro.e92.launcher.core.CrashGuard
import ro.e92.launcher.core.Services
import ro.e92.launcher.core.SettingsBackup
import ro.e92.launcher.databinding.ActivityHomeBinding
import ro.e92.launcher.focus.FocusDirection
import ro.e92.launcher.focus.FocusEngine
import ro.e92.launcher.input.KeyEventLog
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.input.RotaryAccelerator
import ro.e92.launcher.net.ConnectivityMonitor
import ro.e92.launcher.ui.screens.AppDrawerScreen
import ro.e92.launcher.ui.widget.BootIntroView
import ro.e92.launcher.ui.screens.BluetoothMenuScreen
import ro.e92.launcher.ui.screens.CarInfoMenuScreen
import ro.e92.launcher.ui.screens.CarPlayMenuScreen
import ro.e92.launcher.ui.screens.ConnectedDriveMenuScreen
import ro.e92.launcher.ui.screens.DashboardMenuScreen
import ro.e92.launcher.ui.screens.DashboardScreen
import ro.e92.launcher.ui.screens.DiagnosticsScreen
import ro.e92.launcher.ui.screens.DriveSportScreen
import ro.e92.launcher.ui.screens.MediaMenuScreen
import ro.e92.launcher.ui.screens.MessagesMenuScreen
import ro.e92.launcher.ui.screens.TelephoneMenuScreen
import ro.e92.launcher.ui.screens.MediaScreen
import ro.e92.launcher.ui.screens.NavigationMenuScreen
import ro.e92.launcher.ui.screens.SettingsMenuScreen
import ro.e92.launcher.ui.screens.SettingsScreen
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
    private val backgrounds by lazy { BackgroundCache(resources) }
    private val rotary = RotaryAccelerator()

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
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
            onTitleChanged = {
                binding.screenTitle.text = it
                binding.barTitle.text = it
            }
            onTopChanged = { renderBarFor(it) }
            onBackgroundChanged = ::showBackground
        }

        stack.setRoot(TileGridScreen(this))
        setupBottomChips()

        // BACK de la touch / sistem: aceeași cale ca BACK-ul fizic.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Un launcher nu se închide niciodată din BACK — dacă suntem pe
                // rădăcină, pur și simplu nu se întâmplă nimic.
                stack.pop()
            }
        })

        if (savedInstanceState == null) armGuard()
        maybePlayIntro(savedInstanceState)
        observeCalls()
        observeMusicHost()
        registerReceiver(kswKeyReceiver, IntentFilter().apply {
            addAction(KSW_KNOB_ACTION)
            addAction(KSW_CAN_KEY_ACTION)
        })
        registerReceiver(wakeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })

        requestRuntimePermissionsIfNeeded()
        observeStatusBar()
        observeDriveMode()
    }

    override fun onStart() {
        super.onStart()
        Services.vehicle.start()
        Services.media.start()
        Services.net.start()
        Services.kswBt.start()
        Services.trip.start { Services.vehicle }
        Services.apps.startWatching()

        // Nu doar TIME_TICK: ora se poate schimba brusc (sincronizare GPS/rețea,
        // fus orar schimbat din Setări) și atunci nu vine niciun tick pe loc.
        registerReceiver(timeTickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()

        stack.onActivityStart()

        // NU pornim conectarea automata la Wi-Fi. Pe unitatea asta Wi-Fi-ul e
        // legat de adaptorul OBD, care nu da internet - deci tableta nu e
        // niciodata „online", iar conectarea automata rula la fiecare intoarcere
        // in meniu, apeland enableNetwork(..., disableOthers = true) pe prima
        // retea salvata. Asta putea dezactiva exact reteaua OBD si lasa
        // aplicatia de Car Info fara date. Android se reconecteaza oricum singur
        // la retelele salvate; un launcher n-are ce cauta in controlul Wi-Fi-ului.
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
        runCatching { unregisterReceiver(wakeReceiver) }
        runCatching { unregisterReceiver(kswKeyReceiver) }
        stack.destroyAll()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Revenirea noastră (după ecranul KSW) păstrează ecranul la care erai.
        if (intent.getBooleanExtra(EXTRA_RECLAIM, false)) return
        // Apăsarea butonului HOME în timp ce suntem deja în față: înapoi la rădăcină.
        goHome()
    }

    override fun onResume() {
        super.onResume()
        keepAnimationsOn()
        resumedNow = true
        updateClock()
        if (greetOnResume) {
            greetOnResume = false
            if (intro == null) playIntro()
        }
        applyImmersive()
        if (hostPending) {
            hostPending = false
            binding.rootLayout.postDelayed({ hostKswMusic(hostPendingReason) }, HOST_AFTER_RESUME_MS)
        }
    }

    override fun onPause() {
        resumedNow = false
        super.onPause()
        guardFront()
    }

    // ------------------------------------------------------------ start guard
    //
    // La pornire și la trezire unitatea KSW redeschide singură ecranul ei de
    // Bluetooth (sursa ținută minte de MCU), peste salut. Câteva secunde după
    // pornire, dacă ceva ne acoperă fără ca șoferul să fi atins ceva, revenim
    // în față cu intentul de HOME - exact ca butonul HOME, care lasă muzica să
    // cânte (BACK ar închide ecranul KSW și ar opri-o).

    private var guardUntil = 0L
    private var guardReclaims = 0
    private var lastInputAt = 0L

    private fun armGuard() {
        guardUntil = SystemClock.elapsedRealtime() + GUARD_WINDOW_MS
        guardReclaims = 0
    }

    private fun noteInput() {
        lastInputAt = SystemClock.elapsedRealtime()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        noteInput()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInput()
        return super.dispatchKeyEvent(event)
    }

    private fun guardFront() {
        if (!Services.prefs.startGuard) return
        val now = SystemClock.elapsedRealtime()
        if (now > guardUntil || guardReclaims >= GUARD_MAX_RECLAIMS) return
        // Am fost acoperiți chiar de o alegere a șoferului: nu ne băgăm.
        if (now - lastInputAt < GUARD_INPUT_GRACE_MS) return
        if (Services.vehicle.state.value.reverseGear == true) return
        if (Services.kswBt.state.value.call != ro.e92.launcher.core.KswBluetooth.Call.NONE) return
        guardReclaims++
        ro.e92.launcher.core.EventLog.log("Home", "acoperit la pornire fără intervenția șoferului - revin în față")
        binding.rootLayout.postDelayed({ if (!resumedNow) bringToFront(0) }, GUARD_RECLAIM_DELAY_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ------------------------------------------------------------------ input

    /** Unele rotițe trimit derulare, nu taste: și aceea sare salutul. */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        intro?.let { it.skip(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Rotița și butoanele de volan pe KSW: broadcast-uri, nu taste (vezi
     * [ro.e92.launcher.input.HardKeyRouter.KSW_KNOB_BASE]). Doar cât suntem în
     * față - altfel aplicația din față le primește direct de la KSW.
     */
    private val kswKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!resumedNow || intent == null) return
            val code = when (intent.action) {
                KSW_KNOB_ACTION -> {
                    val v = intent.getIntExtra(KSW_KNOB_THIRD_PARTY, -1)
                        .takeIf { it >= 0 } ?: intent.getIntExtra(KSW_KNOB_DATA, -1)
                    if (v < 0) return
                    ro.e92.launcher.input.HardKeyRouter.KSW_KNOB_BASE + v
                }
                KSW_CAN_KEY_ACTION -> {
                    val v = intent.getIntExtra(KSW_CAN_KEY_EXTRA, -1)
                    if (v < 0) return
                    ro.e92.launcher.input.HardKeyRouter.KSW_CAN_BASE + v
                }
                else -> return
            }
            noteInput()
            dispatchLauncherKey(code)
        }
    }

    /** Punctul comun pentru taste Android și pentru rotița KSW. */
    private fun dispatchLauncherKey(keyCode: Int): Boolean {
        callView?.let { return handleCallKey(it, keyCode) }
        intro?.let { it.skip(); return true }
        val wasLearning = Services.keys.pendingLearn != null
        val action = Services.keys.resolve(keyCode)
        if (action == null) {
            KeyEventLog.record(keyCode, null, consumed = wasLearning)
            return wasLearning
        }
        val handled = handleAction(action)
        KeyEventLog.record(keyCode, action, handled)
        return handled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Cat ruleaza animatia de pornire, orice tasta doar o sare - nu ajunge si
        // la meniul din spate, altfel prima rotire ar muta focusul „pe nevazute".
        callView?.let { return handleCallKey(it, keyCode) }
        intro?.let { it.skip(); return true }

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
            // Ca MEDIA: butonul deschide meniul dalei, cu Waze, Google Maps și
            // CarPlay la o rotire distanță, nu o singură aplicație fixă.
            LauncherAction.NAV -> { openTile(TileAction.NAVIGATION); true }
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

            // Telefonul si Mesajele au si ele rotita, ca toate dalele; ecranele
            // split raman un nivel mai jos. Doar App drawer-ul e o grila.
            TileAction.TELEPHONE ->
                pushUnique(TelephoneMenuScreen::class.java) { TelephoneMenuScreen(this) }

            TileAction.MESSAGES ->
                pushUnique(MessagesMenuScreen::class.java) { MessagesMenuScreen(this) }

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
    /**
     * Parallax-ul fotografiei principale, ca fractiune 0..1 din drumul total.
     *
     * Dalele se muta cu o pagina intreaga (~1280 px), fotografia cu doar
     * [PARALLAX_SHIFT] din latime. Diferenta de viteza da senzatia de adancime:
     * dalele par mai aproape decat orasul din spatele lor.
     *
     * Fotografia e marita usor ca sa aiba de unde sa se miste - altfel deplasarea
     * ar descoperi o banda neagra pe dreapta. Pivotul e pe muchia din stanga,
     * deci toata marirea ramane disponibila pentru miscare.
     */
    private var homeParallax = 0f

    override fun setHomeParallax(fraction: Float, animate: Boolean) {
        homeParallax = fraction.coerceIn(0f, 1f)
        if (currentBackground == R.drawable.bg_home) {
            placeBackground(binding.screenBackground, R.drawable.bg_home, animate)
        }
    }

    /**
     * Pune transformarea potrivita fotografiei [resId] pe un singur view.
     *
     * Pe view, nu pe amandoua: in timpul fade-ului, view-ul de dedesubt inca
     * arata fotografia veche. Daca primea si el transformarea noua, masina ar fi
     * sarit brusc la marimea normala chiar sub ochii tai.
     */
    private fun placeBackground(v: android.view.View, resId: Int, animate: Boolean) {
        val home = resId == R.drawable.bg_home
        val w = v.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val scale = if (home) 1f + PARALLAX_SHIFT else 1f
        val tx = if (home) -homeParallax * PARALLAX_SHIFT * w else 0f
        v.pivotX = 0f
        v.pivotY = (v.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) / 2f
        v.scaleX = scale
        v.scaleY = scale
        if (animate) {
            // Aceeași durată și curbă ca banda de dale: fundalul și meniul se
            // opresc împreună, nu unul după altul.
            v.animate().translationX(tx).setDuration(PARALLAX_ANIM_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
        } else {
            v.animate().cancel()
            v.translationX = tx
        }
    }

    private fun showBackground(resId: Int) {
        if (resId == currentBackground) return
        currentBackground = resId

        // 0 = ecran fara fotografie. Stingem imaginea si eliberam bitmap-ul:
        // pe 2 GB nu are rost sa tinem memorie pentru ceva ce nu se vede.
        if (resId == 0) {
            binding.screenBackground.animate().cancel()
            binding.screenBackground.setImageDrawable(null)
            return
        }

        // Un singur Bitmap pentru ambele ImageView-uri - vezi BackgroundCache.
        val bitmap = backgrounds.get(resId) ?: return

        if (!Services.prefs.animationsEnabled) {
            binding.screenBackground.setImageBitmap(bitmap)
            placeBackground(binding.screenBackground, resId, animate = false)
            return
        }

        binding.screenBackgroundNext.setImageBitmap(bitmap)
        placeBackground(binding.screenBackgroundNext, resId, animate = false)
        binding.screenBackgroundNext.alpha = 0f
        binding.screenBackgroundNext.animate()
            .alpha(1f)
            .setDuration(BACKGROUND_FADE_MS)
            .withEndAction {
                binding.screenBackground.setImageBitmap(bitmap)
                placeBackground(binding.screenBackground, resId, animate = false)
                binding.screenBackgroundNext.alpha = 0f
                binding.screenBackgroundNext.setImageDrawable(null)
            }
            .start()
    }

    // ------------------------------------------------------------ pornire

    private var intro: BootIntroView? = null

    /**
     * Animatia de pornire, doar la pornirea LA RECE a procesului - adica la
     * contact. Nu la fiecare HOME, nu la recrearea Activity-ului (rotire,
     * schimbare de configuratie) si nu cand animatiile sunt oprite din Setari.
     *
     * Meniul se construieste normal DEDESUBT cat ruleaza animatia, deci la final
     * nu mai e nimic de asteptat: stingerea animatiei il dezvaluie gata facut.
     */
    private fun maybePlayIntro(savedInstanceState: Bundle?) {
        if (introPlayedThisProcess || savedInstanceState != null) return
        introPlayedThisProcess = true
        playIntro()
    }

    /**
     * Unitatea KSW nu repornește la contact: la ACC oprit doar stinge ecranul și
     * adoarme, iar procesul nostru supraviețuiește. „Pornirea la rece" de mai sus
     * n-ar mai veni niciodată, deci salutul se leagă și de aprinderea ecranului —
     * doar după o pauză lungă, ca o stingere scurtă a ecranului să nu-l repete.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> {
                    updateClock()
                    val off = screenOffAt ?: return
                    screenOffAt = null
                    if (SystemClock.elapsedRealtime() - off < WAKE_GREETING_AFTER_MS) return
                    ro.e92.launcher.core.EventLog.log("Home", "trezire după ${(SystemClock.elapsedRealtime() - off) / 1000}s")
                    armGuard()
                    goHome()
                    carStatus.newDrive()
                    if (resumedNow) {
                        if (intro == null) playIntro()
                    } else {
                        // Salutul se joacă abia în onResume, cât se vede.
                        greetOnResume = true
                        bringToFront(0)
                    }
                }
            }
        }
    }

    /**
     * Ca butonul HOME: doar intentul de HOME aduce stiva home peste aplicația
     * lăsată deschisă (REORDER_TO_FRONT nu). Android ignoră însă pornirile din
     * fundal în primele secunde după aprinderea ecranului - pe emulator prima
     * încercare se pierdea - deci mai încercăm de câteva ori până apucăm.
     */
    private fun bringToFront(attempt: Int) {
        if (resumedNow || attempt >= WAKE_RETRY_DELAYS_MS.size) return
        // Șoferul a atins ceva între timp: alegerea lui contează, nu revenirea noastră.
        if (attempt > 0 && SystemClock.elapsedRealtime() - lastInputAt < GUARD_INPUT_GRACE_MS) return
        // Niciodată peste camera de marșarier.
        if (Services.vehicle.state.value.reverseGear == true) return
        startActivity(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setClass(this, HomeActivity::class.java)
            .putExtra(EXTRA_RECLAIM, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (attempt + 1 < WAKE_RETRY_DELAYS_MS.size) {
            binding.rootLayout.postDelayed({ bringToFront(attempt + 1) }, WAKE_RETRY_DELAYS_MS[attempt + 1])
        }
    }


    private var resumedNow = false
    private var greetOnResume = false
    private var screenOffAt: Long? = null

    private fun playIntro() {
        if (!Services.prefs.animationsEnabled) return

        // Animatia se termina exact pe pozitia fundalului principal - aceeasi
        // pagina pe care se va deschide meniul (ultima folosita).
        val pages = ro.e92.launcher.ui.TileCatalog.pageCount
        val fraction = if (pages <= 1) 0f else
            Services.prefs.lastMenuPage.coerceIn(0, pages - 1) / (pages - 1f)
        val w = resources.displayMetrics.widthPixels

        val view = BootIntroView(
            this,
            photo = backgrounds.get(R.drawable.bg_home),
            endScale = 1f + PARALLAX_SHIFT,
            endTranslationX = -fraction * PARALLAX_SHIFT * w,
            greetingName = Services.prefs.greetingName
        )
        view.onFinished = {
            binding.rootLayout.removeView(view)
            intro = null
        }
        binding.rootLayout.addView(
            view,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        )
        intro = view

        // Pornire la primul moment LIBER al firului principal, nu acum. Imediat
        // dupa primul cadru urmeaza blocajul de pornire (servicii, construirea
        // meniului) si animatorul, care merge dupa ceas, sarea peste ~670 ms -
        // linia de lumina nu se vedea niciodata. Pana atunci ecranul ramane
        // negru, ca momentul de dinainte de contact.
        android.os.Looper.myQueue().addIdleHandler {
            view.start()
            false
        }
    }

    // ------------------------------------------------------------- top bar

    private val carStatus by lazy {
        CarStatus(
            binding.statusTemp, binding.statusRange, binding.statusFuelPct, binding.fuelBar,
            binding.rangeBlock, binding.alertBanner
        )
    }

    // ---------------------------------------------------------------- apeluri
    //
    // Colectat cât trăiește Activity-ul, nu doar cât e în față: un apel care
    // vine cât ești în Waze trebuie să ne aducă înapoi, peste ecranul de apel
    // al vendorului.

    private var callView: ro.e92.launcher.ui.widget.CallView? = null

    private fun observeCalls() {
        lifecycleScope.launch {
            Services.kswBt.state.collect { st ->
                if (st.call == ro.e92.launcher.core.KswBluetooth.Call.NONE) {
                    callView?.let { v ->
                        callView = null
                        v.hide { binding.rootLayout.removeView(v) }
                    }
                    return@collect
                }
                // Doar peste meniul nostru: nu scoatem din față ecranul de apel al
                // vendorului. Pe KSW sunetul ține de aplicația lui (muzica se auzea
                // doar cu tab-ul Music deschis), iar la apel nu riscăm asta.
                val view = callView ?: createCallView()
                view.update(st)
            }
        }
    }

    private fun createCallView(): ro.e92.launcher.ui.widget.CallView {
        intro?.skip()
        val view = ro.e92.launcher.ui.widget.CallView(this)
        view.onButton = { b ->
            when (b) {
                ro.e92.launcher.ui.widget.CallView.Button.ANSWER -> Services.kswBt.answer()
                ro.e92.launcher.ui.widget.CallView.Button.DECLINE,
                ro.e92.launcher.ui.widget.CallView.Button.HANG_UP -> Services.kswBt.hangUp()
                ro.e92.launcher.ui.widget.CallView.Button.TO_PHONE -> Services.kswBt.transferToPhone()
            }
        }
        binding.rootLayout.addView(
            view,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
            )
        )
        view.elevation = 20f
        view.show()
        callView = view
        return view
    }

    /** Rotița alege butonul, apăsarea îl activează, TEL răspunde sau închide. */
    private fun handleCallKey(view: ro.e92.launcher.ui.widget.CallView, keyCode: Int): Boolean {
        when (Services.keys.resolve(keyCode)) {
            LauncherAction.ROTARY_CW, LauncherAction.TILT_RIGHT -> view.move(+1)
            LauncherAction.ROTARY_CCW, LauncherAction.TILT_LEFT -> view.move(-1)
            LauncherAction.SELECT -> view.activate()
            LauncherAction.PHONE ->
                if (Services.kswBt.state.value.call == ro.e92.launcher.core.KswBluetooth.Call.INCOMING) {
                    Services.kswBt.answer()
                } else {
                    Services.kswBt.hangUp()
                }
            else -> Unit
        }
        return true
    }

    // ------------------------------------------------------- gazda muzicii KSW
    //
    // BTService (bTSuite) verifică în fiecare secundă dacă ecranul lui de
    // Bluetooth există; dacă nu (închis cu BACK sau nedeschis de la pornire),
    // oprește muzica imediat după play. Cu ecranul lăsat în spate (ca la HOME)
    // cântă oricât. Așa că îl deschidem noi o clipă și revenim în față: când
    // se conectează telefonul și când muzica e oprită singură la scurt timp
    // după pornire. Doar cât meniul nostru e în față - nu scoatem șoferul din
    // Waze; altfel o facem la următoarea revenire.

    private var hostWasConnected = false
    private var streamingSince = 0L
    private var hostPending = false
    private var hostPendingReason = ""

    private fun observeMusicHost() {
        lifecycleScope.launch {
            Services.kswBt.state.collect { st ->
                val connected = st.phoneConnected
                // NU la conectare: dacă ecranul KSW există deja, un intent nou îl
                // trimite în onNewIntent/onResume, unde bTSuite crapă (NPE în
                // FragmentBase.refreshFocusView_KSW) - procesul moare, BTService
                // repornește și rupe legătura (2026-11-03 22:00-22:09, crash log).
                // Doar când muzica e oprită imediat după play: atunci ecranul
                // sigur nu există și pornirea lui trece prin onCreate.
                hostWasConnected = connected

                val now = SystemClock.elapsedRealtime()
                if (st.audioStreaming) {
                    if (streamingSince == 0L) streamingSince = now
                } else if (streamingSince != 0L) {
                    val lasted = now - streamingSince
                    streamingSince = 0L
                    val byDriver = now - Services.kswBt.lastPlayPauseAt < HOST_DRIVER_PAUSE_MS
                    if (connected && lasted < HOST_CUT_SHORT_MS && !byDriver) {
                        hostKswMusic("muzica oprită după ${lasted / 1000.0}s", resume = true)
                    }
                }
            }
        }
    }

    private fun hostKswMusic(reason: String, resume: Boolean = false) {
        // Motorul E92 n-are nevoie de ecranul KSW: nimeni nu mai oprește muzica.
        if (Services.prefs.btOwnEngine) return
        // Oprit: ca în sesiunea care a mers (2026-11-03 21:55), launcher-ul nu
        // mai deschide deloc ecranul KSW - orice intent spre el poate prăbuși
        // bTSuite. Ecranul îl deschide șoferul o dată, din meniul KSW, cu HOME.
        if (!HOST_ENABLED) {
            ro.e92.launcher.core.EventLog.log("Home", "muzica oprită de KSW ($reason) - nu deschid ecranul KSW")
            return
        }
        if (!Services.kswBt.isInstalled) return
        if (Services.kswBt.state.value.call != ro.e92.launcher.core.KswBluetooth.Call.NONE) return
        if (Services.vehicle.state.value.reverseGear == true) return
        if (!resumedNow) {
            hostPending = true
            hostPendingReason = reason
            return
        }
        // Peste salut nu: după el.
        if (intro != null) {
            binding.rootLayout.postDelayed({ hostKswMusic(reason, resume) }, HOST_AFTER_RESUME_MS)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastHostAt < HOST_COOLDOWN_MS) return
        lastHostAt = now
        ro.e92.launcher.core.EventLog.log("Home", "deschid Bluetooth KSW în spate ($reason)")
        val opened = runCatching {
            startActivity(Intent()
                .setClassName(ro.e92.launcher.core.KswBluetooth.PACKAGE, ro.e92.launcher.core.KswBluetooth.MAIN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.isSuccess
        if (!opened) return
        // Pe unitate ecranul KSW apare mai încet decât pe emulator: dacă încă
        // suntem în față, mai verificăm de câteva ori până ne acoperă.
        for (delayMs in HOST_RETURN_CHECKS_MS) {
            binding.rootLayout.postDelayed({ if (!resumedNow) bringToFront(0) }, delayMs)
        }
        if (resume) binding.rootLayout.postDelayed({
            if (!Services.kswBt.state.value.audioStreaming) Services.kswBt.playPause()
        }, HOST_RETURN_CHECKS_MS.last() + HOST_RESUME_PLAY_MS)
    }

    /** Turațiile trec de la oprit la mers: anunțăm Bluetooth-ul să se verifice. */
    private var engineRunning = false

    private fun watchEngineStart(rpm: Int?) {
        val running = (rpm ?: 0) >= ENGINE_RUNNING_RPM
        if (running && !engineRunning) Services.kswBt.onEngineStart()
        engineRunning = running
    }

    // ------------------------------------------------------- chip-urile de jos
    //
    // Telefonul conectat (stânga) și ce cântă (dreapta), pe orice ecran. Pe
    // meniul principal stau lângă indicatorul de pagini; celelalte ecrane își
    // lasă jos [R.dimen.bottom_chips_space] liber, ca să nu intre sub ele.

    private fun renderBarFor(top: ro.e92.launcher.ui.Screen) {
        val isMenu = top is ro.e92.launcher.ui.screens.TileGridScreen
        val bottom = if (isMenu) 0 else resources.getDimensionPixelSize(R.dimen.bottom_chips_space)
        binding.screenContainer.setPadding(0, 0, 0, bottom)
        binding.barPages.visibility = if (isMenu) View.VISIBLE else View.GONE
        binding.barTitle.visibility = if (isMenu) View.GONE else View.VISIBLE
    }

    override val barPages: android.view.ViewGroup get() = binding.barPages

    private fun setupBottomChips() {
        binding.barBack.setOnClickListener { stack.pop() }
        binding.barHome.setOnClickListener { goHome() }
        // Fără fundal apăsat: iconița se micșorează puțin, pe placa barei.
        for (button in listOf(binding.barBack, binding.barHome)) {
            button.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(BAR_PRESSED_SCALE).scaleY(BAR_PRESSED_SCALE).setDuration(BAR_PRESS_MS).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(BAR_PRESS_MS).start()
                }
                false
            }
        }
        binding.miniPlay.setOnClickListener { Services.media.playPause() }
        binding.miniPrev.setOnClickListener { Services.media.previous() }
        binding.miniNext.setOnClickListener { Services.media.next() }
        binding.miniPlayer.setOnClickListener {
            pushUnique(ro.e92.launcher.ui.screens.MediaScreen::class.java) { ro.e92.launcher.ui.screens.MediaScreen(this) }
        }
        binding.phoneChip.setOnClickListener {
            pushUnique(ro.e92.launcher.ui.screens.BluetoothScreen::class.java) { ro.e92.launcher.ui.screens.BluetoothScreen(this) }
        }
        // Doar de atins: focusabile, ar prinde DPAD_CENTER înaintea FocusEngine
        // și ar desena dreptunghiul gri de focus peste ele.
        for (v in listOf(binding.miniPlayer, binding.miniPrev, binding.miniPlay, binding.miniNext, binding.phoneChip, binding.barBack, binding.barHome)) {
            v.isFocusable = false
            v.defaultFocusHighlightEnabled = false
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Services.media.snapshot.collect { renderMiniPlayer() } }
                launch { Services.kswBt.state.collect { renderPhoneChip(it) } }
            }
        }
    }

    private fun renderPhoneChip(st: ro.e92.launcher.core.KswBluetooth.State) {
        val connected = st.phoneConnected && st.phoneName.isNotBlank()
        binding.phoneName.text = when {
            st.restartNeeded -> getString(R.string.bt_status_restart_short)
            st.moduleSilent -> getString(R.string.bt_status_silent_short)
            else -> st.phoneName
        }
        binding.phoneChip.visibility =
            if (connected || st.moduleSilent || st.restartNeeded) View.VISIBLE else View.GONE
    }

    private fun renderMiniPlayer() {
        val media = Services.media.snapshot.value
        if (!media.hasSession || media.title.isNullOrBlank()) {
            binding.miniPlayer.visibility = View.GONE
            return
        }
        binding.miniPlayer.visibility = View.VISIBLE
        binding.miniTitle.text = media.title
        binding.miniArtist.visibility = View.GONE
        binding.miniPlay.setImageResource(
            if (media.isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play
        )
    }

    private fun observeStatusBar() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Services.net.state.collect { net ->
                        val connected = net.status == ConnectivityMonitor.Status.ONLINE ||
                            net.status == ConnectivityMonitor.Status.CONNECTED_NO_INTERNET
                        binding.icWifi.alpha = if (connected) ICON_ON else ICON_OFF
                    }
                }
                launch {
                    Services.vehicle.state.collect {
                        carStatus.render(it)
                        watchEngineStart(it.rpm)
                    }
                }
            }
        }
    }

    /**
     * Salutul de mod de condus.
     *
     * Se declanseaza pe TRANZITIE, nu pe stare: `false -> true`. Pe stare pura,
     * fiecare frame CAN care repeta „sport=1" ar redeschide ecranul la
     * nesfarsit. `null` inseamna „unitatea nu raporteaza asa ceva" si nu conteaza
     * ca tranzitie - de aceea prima valoare nu declanseaza nimic.
     *
     * Traieste in Activity, nu intr-un ecran: modul se poate schimba oricand,
     * indiferent ce e afisat, iar salutul trebuie sa apara peste orice.
     */
    private fun observeDriveMode() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous: Boolean? = null
                Services.vehicle.state.collect { state ->
                    val now = state.sportMode
                    if (now == true && previous == false) showDriveMode()
                    if (now != null) previous = now
                }
            }
        }
    }

    /** Nu stivuim doua saluturi daca semnalul palpaie. */
    fun showDriveMode() {
        if (stack.current is DriveSportScreen) return
        stack.push(DriveSportScreen(this))
    }

    private fun updateClock() {
        // SimpleDateFormat își fixează fusul orar la construcție; fără asta,
        // după schimbarea lui din Setări ceasul rămânea pe cel vechi până la
        // repornirea aplicației - pe KSW, care doar adoarme, adică niciodată.
        val zone = java.util.TimeZone.getDefault()
        clockFormat.timeZone = zone
        dateFormat.timeZone = zone
        // După o repornire ceasul unității e în 2013 până se sincronizează;
        // GpsClock dă ora din GPS, iar fără ea arătăm „--:--", nu o oră falsă.
        val now = ro.e92.launcher.core.GpsClock.now()
        if (now == null) {
            binding.clock.text = "--:--"
            binding.dateText.text = ""
            return
        }
        clockDate.time = now
        binding.clock.text = clockFormat.format(clockDate)
        binding.dateText.text = dateFormat.format(clockDate)
    }

    // --------------------------------------------------------- bluetooth

    /**
     * Pe unitate „animator duration scale" e 0 din fabrică, deci orice animație
     * sare direct la capăt: rotița nu se mai rotea, doar își schimba poziția.
     * Setarea e a sistemului; aici o suprascriem doar pentru procesul nostru, cu
     * metoda ascunsă din ValueAnimator (disponibilă pe API 27). Android o
     * rescrie când apare prima fereastră, de aceea e chemată în onResume.
     */
    private fun keepAnimationsOn() {
        runCatching {
            android.animation.ValueAnimator::class.java
                .getMethod("setDurationScale", Float::class.javaPrimitiveType)
                .invoke(null, 1f)
        }.onFailure { android.util.Log.w("Home", "scara animațiilor", it) }
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
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Backup-ul si jurnalele - vezi Storage.
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
            // La o instalare pe curat, permisiunea de stocare vine abia acum -
            // prima incercare de restaurare, din Application, n-avea cum sa vada
            // backup-ul din /sdcard. Daca il gaseste acum, repornim ca setarile
            // restaurate sa fie citite de la zero de toate componentele.
            if (SettingsBackup.restoreIfFreshInstall(this)) {
                Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_LONG).show()
                window.decorView.postDelayed({ CrashGuard.restart(this) }, 1200)
                return
            }
            // Sursele se re-evaluează: GPS-ul devine utilizabil abia acum.
            Services.rebuildCanSources()
            Services.net.refresh()
        }
    }

    private companion object {
        /** Pe proces, nu pe Activity: Activity-ul se poate recrea, procesul nu. */
        var introPlayedThisProcess = false

        const val REQ_PERMISSIONS = 1001
        const val IMMERSIVE_REAPPLY_MS = 400L

        /** Iconitele de stare: aprinse cand e conectat, estompate cand nu. */
        const val ICON_ON = 1f
        const val ICON_OFF = 0.3f

        /** Cat se misca fotografia principala intre prima si ultima pagina. */
        const val PARALLAX_SHIFT = 0.035f

        /** Sub atât, o stingere a ecranului nu e un contact nou. */
        const val WAKE_GREETING_AFTER_MS = 60_000L

        const val KSW_KNOB_ACTION = "com.choiceway.eventcenter.EventUtils.ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_EVT"
        const val KSW_KNOB_DATA = "com.choiceway.eventcenter.EventUtils.ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_DATA"
        const val KSW_KNOB_THIRD_PARTY = "com.choiceway.eventcenter.EventUtils.ZXW_ORIGINAL_MCU_KEY_FOCUS_MOVE_THIRD_PARTY_DATA"
        const val KSW_CAN_KEY_ACTION = "com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT"
        const val KSW_CAN_KEY_EXTRA = "com.choiceway.eventcenter.EventUtils.ZXW_CAN_KEY_EVT_EXTRA"

        /** Motorul pornit: peste ralanti-ul de demaror. */
        /** Rămas-bun doar după un drum, nu după o pornire de câteva secunde. */


        /** Întârzierea fiecărei încercări de a reveni în față după contact. */
        val WAKE_RETRY_DELAYS_MS = longArrayOf(0L, 1_500L, 3_500L, 6_000L)
        const val ENGINE_RUNNING_RPM = 400

        /** Cât după pornire/trezire ne apărăm locul în față. */
        const val BAR_PRESSED_SCALE = 0.86f
        const val BAR_PRESS_MS = 90L
        const val GUARD_WINDOW_MS = 40_000L
        const val EXTRA_RECLAIM = "ro.e92.launcher.RECLAIM"
        /** Cât stă ecranul KSW deschis până revenim: destul cât să-și facă onCreate. */
        val HOST_RETURN_CHECKS_MS = longArrayOf(900L, 1_800L, 3_000L, 4_500L)
        const val HOST_RESUME_PLAY_MS = 1_500L
        const val HOST_AFTER_RESUME_MS = 1_200L
        /** Oprirea făcută de BTService vine în ~1 s de la play; peste 4 s e altceva. */
        const val HOST_CUT_SHORT_MS = 4_000L
        const val HOST_DRIVER_PAUSE_MS = 3_000L
        const val HOST_COOLDOWN_MS = 20_000L
        const val HOST_ENABLED = false
        /** Pe proces, nu pe instanță: o a doua instanță a activității n-o mai dublează. */
        var lastHostAt = 0L
        const val GUARD_MAX_RECLAIMS = 3
        /** O atingere sau o tastă atât de recentă înseamnă că șoferul a ales să plece. */
        const val GUARD_INPUT_GRACE_MS = 2_500L
        const val GUARD_RECLAIM_DELAY_MS = 400L
        /** Aceeasi durata ca alunecarea paginii, ca sa se miste impreuna. */
        const val PARALLAX_ANIM_MS = 460L
        const val BACKGROUND_FADE_MS = 260L
    }
}

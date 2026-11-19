package ro.e92.launcher.ui.screens

import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.KswBluetooth
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ScreenHomeWidgetsBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.input.LauncherAction
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import ro.e92.launcher.ui.TileAction
import ro.e92.launcher.ui.widget.scaleOnPress
import kotlin.math.abs

/**
 * Ecranul Home, deschis de MENU și la pornire: în stil iOS / iDrive 7.
 *
 * Stânga: bara verticală cu meniurile rapide (MEDIA, CAR, DASH, SETUP), doar pe ecranul
 * ăsta. Mijloc: mașina din fotografia de fundal. Dreapta: widget-ul Media (piesa și
 * butoanele) și widget-ul Bluetooth (telefonul). Fără dale.
 *
 * Rotița trece prin bara verticală, apoi prin cele două widget-uri; după ultimul, sau cu
 * swipe spre stânga, urmează paginile de dale ([TileGridScreen]), de unde un swipe spre
 * dreapta pe prima pagină (sau rotița înapoi de pe prima dală) revine aici.
 */
class HomeWidgetsScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "HOME"
    /** Negru: mașina e acum widget-ul care se rotește ([ro.e92.launcher.ui.widget.CarSpinView]). */
    override val backgroundRes: Int get() = 0

    private lateinit var binding: ScreenHomeWidgetsBinding
    private val railViews = ArrayList<View>(RAIL.size)
    private var focusedId: String? = null
    private lateinit var swipe: GestureDetector

    /**
     * CAR și DASH sar peste meniul cu rotiță, direct în ecranul folosit mereu: Trip, respectiv
     * ceasurile (cerut 2026-11-15). Meniurile rămân din dalele de pe pagina a doua.
     */
    private fun openRail(action: TileAction) {
        when (action) {
            TileAction.CAR_INFO -> host.push(TripScreen(host))
            TileAction.DASHBOARD -> host.push(DashboardScreen(host))
            else -> host.openTile(action)
        }
    }

    private class RailItem(val id: String, val icon: Int, val label: Int, val action: TileAction)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenHomeWidgetsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        host.showHomePage(0)
        if (railViews.isEmpty()) buildRail()
        setupTouch()
        screenScope.launch { Services.media.snapshot.collect { renderMedia() } }
        screenScope.launch { Services.kswBt.state.collect { renderBluetooth(); renderMedia() } }
        screenScope.launch { Services.reverse.display.collect { renderDriveMode() } }
        screenScope.launch { Services.reverse.driftMode.collect { binding.driftBadge.setOn(it == true) } }
        // Linia de progres: de două ori pe secundă, doar cât e Home pe ecran.
        screenScope.launch {
            while (true) {
                renderProgress()
                kotlinx.coroutines.delay(PROGRESS_TICK_MS)
            }
        }
        // Focusul îl pune stiva (primul rând la deschidere, ultimul folosit la întoarcere).
    }

    /** Acoperit de un meniu: mașina eliberează cadrele decodate (se refac la întoarcere). */
    override fun onHide() {
        super.onHide()
        binding.carSpin.trimMemory()
    }

    override fun focusTargets(): List<FocusTarget> {
        if (railViews.isEmpty()) return emptyList()
        val rail = RAIL.mapIndexed { i, item ->
            FocusTarget(
                id = item.id,
                view = railViews[i],
                onActivate = { openRail(item.action) },
                onFocus = { if (it) focusedId = item.id },
                up = RAIL.getOrNull(i - 1)?.id,
                down = RAIL.getOrNull(i + 1)?.id,
                right = ID_MEDIA
            )
        }
        val widgets = listOf(
            FocusTarget(
                id = ID_MEDIA,
                view = binding.mediaCard,
                onActivate = { host.openTile(TileAction.MEDIA) },
                onFocus = { if (it) focusedId = ID_MEDIA },
                down = ID_BT,
                left = RAIL.first().id
            ),
            FocusTarget(
                id = ID_BT,
                view = binding.btCard,
                onActivate = { host.openTile(TileAction.BLUETOOTH) },
                onFocus = { if (it) focusedId = ID_BT },
                up = ID_MEDIA,
                left = RAIL.first().id
            )
        )
        return rail + widgets
    }

    /**
     * Rotița: după ultimul widget mergi la dale (nu înapoi la începutul listei); înainte de
     * primul rând din bară nu se întâmplă nimic.
     */
    override fun onAction(action: LauncherAction): Boolean = when (action) {
        LauncherAction.ROTARY_CW, LauncherAction.TILT_RIGHT ->
            if (focusedId == ID_BT || (action == LauncherAction.TILT_RIGHT && focusedId in WIDGET_IDS)) {
                openTiles(); true
            } else false
        LauncherAction.ROTARY_CCW -> focusedId == RAIL.first().id
        else -> false
    }

    private fun openTiles() = host.push(TileGridScreen(host))

    // ------------------------------------------------------------- bara verticală

    private fun buildRail() {
        val density = context.resources.displayMetrics.density
        for (item in RAIL) {
            val row = FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_rail_item)
                isClickable = true
                setOnClickListener {
                    host.rebuildFocus(item.id)
                    openRail(item.action)
                }
            }
            // Linia portocalie din stânga rândului selectat, ca în iDrive 7.
            val marker = View(context).apply {
                tag = TAG_MARKER
                setBackgroundResource(R.drawable.bg_rail_marker)
                isDuplicateParentStateEnabled = true
            }
            row.addView(marker, FrameLayout.LayoutParams((3 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            // Starea „selectat" a rândului coboară la iconiță și text: se colorează în accent.
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isDuplicateParentStateEnabled = true
            }
            column.addView(ImageView(context).apply {
                tag = TAG_ICON
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
                imageTintList = ContextCompat.getColorStateList(context, R.color.home_rail_tint)
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams((ICON_DP * density).toInt(), (ICON_DP * density).toInt()))
            column.addView(TextView(context).apply {
                tag = TAG_LABEL
                setText(item.label)
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColorStateList(context, R.color.home_rail_label_tint))
                gravity = Gravity.CENTER
                isSingleLine = true
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            })
            row.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            binding.rail.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            railViews.add(row)
        }
    }

    // ------------------------------------------------------------- widget-uri

    /** Modul cutiei sub mașină; ascuns cât nu-l știm. Prima valoare apare fără animație. */
    private fun renderDriveMode() {
        val d = Services.reverse.display.value
        val gear = when (d?.selector) {
            ro.e92.launcher.can.Selector.P -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.PARK
            ro.e92.launcher.can.Selector.N -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.NEUTRAL
            ro.e92.launcher.can.Selector.D -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.COMFORT
            ro.e92.launcher.can.Selector.DS -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.SPORT
            ro.e92.launcher.can.Selector.M -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.MANUAL
            else -> shownGear
        }
        binding.modeStrip.setMode(gear, animate = shownGear != null, gear = d?.gear ?: 0)
        applySportTheme(gear == ro.e92.launcher.ui.widget.DriveModeStripView.Gear.SPORT ||
            gear == ro.e92.launcher.ui.widget.DriveModeStripView.Gear.MANUAL)
        shownGear = gear
    }

    private var shownGear: ro.e92.launcher.ui.widget.DriveModeStripView.Gear? = null

    private var sportTheme: Boolean? = null

    /**
     * În Dynamic Sport și Manual Home-ul prinde o tentă portocalie (cerut 2026-11-19): ramele
     * widget-urilor și a barei, iconițele din bară și din widget-uri, iar ce e alb în widget-uri
     * (titlul piesei, telefonul, butoanele). Titlurile gri rămân gri; ceasul și autonomia, albe.
     * Selecția are același stil ca în modul normal, în griul titlurilor din bara din stânga. Ce e selectat nu mai poate fi
     * portocaliu printre portocalii, așa că trece în alb: iconița, eticheta și linia din stânga,
     * peste o lumină portocalie mai puternică; la widget-uri, rama albă.
     */
    private fun applySportTheme(on: Boolean) {
        if (sportTheme == on) return
        sportTheme = on
        val card = if (on) R.drawable.bg_widget_card_sport else R.drawable.bg_widget_card
        binding.mediaCard.setBackgroundResource(card)
        binding.btCard.setBackgroundResource(card)
        binding.railFrame.setBackgroundResource(if (on) R.drawable.bg_rail_frame_sport else R.drawable.bg_rail_frame)
        // Iconițele din widget-uri prind și ele tenta; textele rămân albe (cerut 2026-11-19).
        val accent = if (on) android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent_focus)) else null
        binding.mediaLogo.imageTintList = accent
        binding.btIcon.imageTintList = accent
        // Ce e alb în widget-uri trece pe portocaliu: titlul piesei, numele telefonului, butoanele.
        // Titlurile gri (NOW PLAYING, DEVICE...) și rândurile secundare rămân gri.
        val primary = if (on) ContextCompat.getColor(context, R.color.accent_focus) else ContextCompat.getColor(context, R.color.text_primary)
        binding.mediaTitle.setTextColor(primary)
        binding.btName.setTextColor(primary)
        for (b in listOf(binding.mediaPrev, binding.mediaPlay, binding.mediaNext)) b.imageTintList = accent
        val iconTint = ContextCompat.getColorStateList(context, if (on) R.color.home_rail_tint_sport else R.color.home_rail_tint)
        val labelTint = ContextCompat.getColorStateList(context, if (on) R.color.home_rail_label_tint_sport else R.color.home_rail_label_tint)
        for (row in railViews) {
            row.setBackgroundResource(if (on) R.drawable.bg_rail_item_sport else R.drawable.bg_rail_item)
            row.findViewWithTag<View>(TAG_MARKER)?.setBackgroundResource(
                if (on) R.drawable.bg_rail_marker_sport else R.drawable.bg_rail_marker
            )
            row.findViewWithTag<ImageView>(TAG_ICON)?.imageTintList = iconTint
            row.findViewWithTag<TextView>(TAG_LABEL)?.setTextColor(labelTint)
        }
    }

    /**
     * Cardul de mod a intrat peste afișaj: îl schimbăm pe modul nou fără animație, cât e acoperit
     * de tot (cardul e opac după [SWAP_UNDER_CARD_MS]). Când cardul pleacă, modul nou e deja acolo.
     * Ecranul e acoperit, deci observatorii lui sunt opriți: de aceea direct, nu prin flux.
     */
    fun swapModeUnderCard(event: ro.e92.launcher.can.ModeEvent) {
        val gear = when (event.scene) {
            ro.e92.launcher.can.ModeScene.PARK -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.PARK
            ro.e92.launcher.can.ModeScene.NEUTRAL -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.NEUTRAL
            ro.e92.launcher.can.ModeScene.DRIVE -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.COMFORT
            ro.e92.launcher.can.ModeScene.SPORT -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.SPORT
            ro.e92.launcher.can.ModeScene.MANUAL -> ro.e92.launcher.ui.widget.DriveModeStripView.Gear.MANUAL
            else -> return
        }
        binding.modeStrip.postDelayed({
            binding.modeStrip.setMode(gear, animate = false, gear = event.gear)
            applySportTheme(gear == ro.e92.launcher.ui.widget.DriveModeStripView.Gear.SPORT ||
                gear == ro.e92.launcher.ui.widget.DriveModeStripView.Gear.MANUAL)
            shownGear = gear
        }, SWAP_UNDER_CARD_MS)
    }


    /**
     * Zona peste care apare cardul de mod pe Home, în coordonatele ferestrei: lățimea zonei cu
     * mașina, fundul afișajului permanent al modului. Null dacă ecranul nu e încă așezat.
     */
    fun modeAnchor(): android.graphics.RectF? {
        val car = binding.carSpin
        val strip = binding.modeStrip
        if (car.width == 0) return null
        val loc = IntArray(2)
        car.getLocationInWindow(loc)
        val left = loc[0].toFloat()
        val right = left + car.width
        val rootLoc = IntArray(2)
        binding.root.getLocationInWindow(rootLoc)
        val bottom = rootLoc[1] + binding.root.height - (strip.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        return android.graphics.RectF(left, (bottom - strip.layoutParams.height).toFloat(), right, bottom.toFloat())
    }

    private fun renderMedia() {
        val media = Services.media.snapshot.value
        val bt = Services.kswBt.state.value
        val hasSong = media.hasSession && !media.title.isNullOrBlank()
        // Pe Home iconițele sunt desenate în linii, nu metalice (cele metalice: dale și meniuri).
        binding.mediaLogo.setImageResource(R.drawable.ic_home_media)
        binding.mediaTitle.text = if (hasSong) media.title else context.getString(R.string.home_no_media)
        val source = when {
            !media.hasSession && !bt.audioConnected -> ""
            media.packageName == KswBluetooth.PACKAGE -> context.getString(R.string.home_source_bt)
            else -> appLabel(media.packageName)
        }
        // Sus: NOW PLAYING / MEDIA. Sub titlu: artistul și sursa (Bluetooth, Spotify...).
        binding.mediaArtist.text = listOfNotNull(media.artist?.takeIf { hasSong && it.isNotBlank() }, source.ifBlank { null })
            .joinToString(" · ")
        binding.mediaArtist.visibility = if (binding.mediaArtist.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.mediaPlay.setImageResource(if (media.isPlaying) R.drawable.ic_cp_pause else R.drawable.ic_cp_play)
    }

    /**
     * Cât a cântat din piesă. Din telefon: durata vine pe Bluetooth, poziția o socotește
     * [ro.e92.launcher.media.TrackClock]. Din aplicațiile unității: poziția reală.
     */
    private fun renderProgress() {
        val media = Services.media.snapshot.value
        val bt = Services.kswBt.state.value
        val fromPhone = media.packageName == KswBluetooth.PACKAGE
        val duration = if (fromPhone) bt.songDurationMs else media.durationMs
        val position = if (fromPhone) ro.e92.launcher.media.TrackClock.positionMs() else media.positionNow()
        val show = media.hasSession && !media.title.isNullOrBlank() && duration > 0
        binding.mediaProgressRow.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) return
        val pos = position.coerceIn(0L, duration)
        binding.mediaProgress.progress = (pos * 1000 / duration).toInt()
        binding.mediaElapsed.text = clock(pos)
        binding.mediaDuration.text = clock(duration)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun renderBluetooth() {
        val st = Services.kswBt.state.value
        binding.btName.text = when {
            st.phoneConnected -> st.phoneName.ifBlank { context.getString(R.string.bt_phone_connected) }
            else -> context.getString(R.string.home_no_phone)
        }
        binding.btStatus.text = context.getString(
            when {
                st.audioStreaming -> R.string.home_bt_playing
                st.audioConnected -> R.string.home_bt_music
                st.phoneConnected -> R.string.home_bt_connected
                else -> R.string.home_bt_connect
            }
        )
        binding.btIcon.alpha = if (st.phoneConnected) 1f else 0.45f
    }

    private fun appLabel(pkg: String?): String {
        if (pkg == null) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
    }

    // ------------------------------------------------------------- atingere

    private fun setupTouch() {
        swipe = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < MIN_FLING) return false
                if (vx < 0) openTiles()          // degetul spre stânga = pagina din dreapta
                return true
            }
        })
        binding.homeRoot.setOnTouchListener { _, e -> swipe.onTouchEvent(e); true }
        // Pe elementele care se apasă, gestul se ascultă fără să strice click-ul.
        val listen = View.OnTouchListener { _, e -> swipe.onTouchEvent(e); false }
        for (v in railViews + listOf(binding.mediaCard, binding.btCard, binding.mediaPrev, binding.mediaPlay, binding.mediaNext)) {
            v.setOnTouchListener(listen)
        }
        binding.mediaPrev.setOnClickListener { Services.media.previous() }
        binding.mediaPlay.setOnClickListener { Services.media.playPause() }
        binding.mediaNext.setOnClickListener { Services.media.next() }
        binding.mediaCard.setOnClickListener { host.rebuildFocus(ID_MEDIA); host.openTile(TileAction.MEDIA) }
        binding.btCard.setOnClickListener { host.rebuildFocus(ID_BT); host.openTile(TileAction.BLUETOOTH) }
        for (b in listOf(binding.mediaPrev, binding.mediaPlay, binding.mediaNext)) b.scaleOnPress()
        // Focusul îl desenează FocusEngine (activated). Focusul nativ al Android-ului ar pune
        // peste rânduri un dreptunghi gri care rămâne pe MEDIA după ce rotești mai departe.
        for (v in railViews + listOf(binding.mediaCard, binding.btCard, binding.mediaPrev, binding.mediaPlay, binding.mediaNext)) {
            ro.e92.launcher.ui.widget.noNativeFocus(v)
        }
    }

    companion object {
        private const val ID_MEDIA = "home_media"
        private const val TAG_MARKER = "rail_marker"
        private const val TAG_ICON = "rail_icon"
        private const val TAG_LABEL = "rail_label"
        private const val ID_BT = "home_bt"
        private val WIDGET_IDS = setOf(ID_MEDIA, ID_BT)
        private const val ICON_DP = 46
        private const val LABEL_SP = 11f
        private const val MIN_FLING = 600f
        private const val PROGRESS_TICK_MS = 500L

        /** Ordinea cerută: media, bluetooth, car (Car Info), dashboard, settings. */
        private val RAIL = listOf(
            RailItem("rail_media", R.drawable.ic_home_media, R.string.home_rail_media, TileAction.MEDIA),
            RailItem("rail_bt", R.drawable.ic_home_bt, R.string.home_rail_bt, TileAction.BLUETOOTH),
            RailItem("rail_car", R.drawable.ic_home_car, R.string.home_rail_car, TileAction.CAR_INFO),
            RailItem("rail_dash", R.drawable.ic_home_dash, R.string.home_rail_dash, TileAction.DASHBOARD),
            RailItem("rail_setup", R.drawable.ic_home_setup, R.string.home_rail_setup, TileAction.SETTINGS)
        )
    }
}

/** Cardul de mod e opac peste afișaj după ~380 ms; schimbăm afișajul puțin după. */
private const val SWAP_UNDER_CARD_MS = 450L

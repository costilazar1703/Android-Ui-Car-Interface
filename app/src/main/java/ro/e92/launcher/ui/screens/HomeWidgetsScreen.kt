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

    private class RailItem(val id: String, val icon: Int, val label: Int, val action: TileAction)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenHomeWidgetsBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        host.showHomePage(0)
        if (railViews.isEmpty()) buildRail()
        setupTouch()
        screenScope.launch { Services.media.snapshot.collect { renderMedia() } }
        screenScope.launch { Services.kswBt.state.collect { renderBluetooth(); renderMedia() } }
        // Focusul îl pune stiva (primul rând la deschidere, ultimul folosit la întoarcere).
    }

    override fun focusTargets(): List<FocusTarget> {
        if (railViews.isEmpty()) return emptyList()
        val rail = RAIL.mapIndexed { i, item ->
            FocusTarget(
                id = item.id,
                view = railViews[i],
                onActivate = { host.openTile(item.action) },
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
                    host.openTile(item.action)
                }
            }
            // Linia portocalie din stânga rândului selectat, ca în iDrive 7.
            val marker = View(context).apply {
                setBackgroundResource(R.drawable.bg_rail_marker)
                isDuplicateParentStateEnabled = true
            }
            row.addView(marker, FrameLayout.LayoutParams((3 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            column.addView(ImageView(context).apply {
                setImageResource(item.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = null
            }, LinearLayout.LayoutParams((ICON_DP * density).toInt(), (ICON_DP * density).toInt()))
            column.addView(TextView(context).apply {
                setText(item.label)
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
                letterSpacing = 0.08f
                setTextColor(ContextCompat.getColor(context, R.color.home_rail_label))
                gravity = Gravity.CENTER
                isSingleLine = true
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            })
            row.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            binding.rail.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            railViews.add(row)
        }
    }

    // ------------------------------------------------------------- widget-uri

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
        binding.mediaArtist.text = listOfNotNull(media.artist?.takeIf { hasSong && it.isNotBlank() }, source.ifBlank { null })
            .joinToString(" · ")
        binding.mediaPlay.setImageResource(if (media.isPlaying) R.drawable.ic_cp_pause else R.drawable.ic_cp_play)
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
            v.isFocusable = false
            v.defaultFocusHighlightEnabled = false
        }
    }

    private companion object {
        const val ID_MEDIA = "home_media"
        const val ID_BT = "home_bt"
        val WIDGET_IDS = setOf(ID_MEDIA, ID_BT)
        const val ICON_DP = 46
        const val LABEL_SP = 11f
        const val MIN_FLING = 600f

        /** Ordinea cerută: media, bluetooth, car (Car Info), dashboard, settings. */
        val RAIL = listOf(
            RailItem("rail_media", R.drawable.ic_home_media, R.string.home_rail_media, TileAction.MEDIA),
            RailItem("rail_bt", R.drawable.ic_home_bt, R.string.home_rail_bt, TileAction.BLUETOOTH),
            RailItem("rail_car", R.drawable.ic_home_car, R.string.home_rail_car, TileAction.CAR_INFO),
            RailItem("rail_dash", R.drawable.ic_home_dash, R.string.home_rail_dash, TileAction.DASHBOARD),
            RailItem("rail_setup", R.drawable.ic_home_setup, R.string.home_rail_setup, TileAction.SETTINGS)
        )
    }
}

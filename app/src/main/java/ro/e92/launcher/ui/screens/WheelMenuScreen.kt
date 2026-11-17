package ro.e92.launcher.ui.screens

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ItemMenuBinding
import ro.e92.launcher.databinding.ScreenHomeBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import kotlin.math.abs

/** Un rând dintr-un meniu cu rotiță. */
class WheelEntry(
    val id: String,
    @DrawableRes val icon: Int,
    val title: String,
    val value: String = "",
    val onActivate: () -> Unit = {},
    /** Butonul OPTION pe rândul selectat. De obicei „șterge / dezleagă". */
    val onOption: (() -> Unit)? = null,
    /** Bară de reglaj în rând, în locul săgeții de submeniu. */
    val slider: WheelSlider? = null,
    /** Conținut propriu în rând (titlul piesei, butoane), în locul săgeții. */
    val inline: (() -> View)? = null
)

/** Reglajul dintr-un rând: se trage cu degetul sau se mișcă din rotiță, vezi [step]. */
class WheelSlider(val max: Int, var value: Int, val min: Int = 0, val onChange: (Int) -> Unit) {
    internal var view: ro.e92.launcher.ui.widget.VolumeLineView? = null
    internal var label: android.widget.TextView? = null

    internal fun chosen(level: Int) {
        val v = level.coerceIn(min, max)
        // Trasă cu degetul sub minim: bara revine la minim.
        if (v != level) view?.setValue(v)
        value = v
        label?.text = v.toString()
        onChange(v)
    }

    fun step(delta: Int) {
        val next = (value + delta).coerceIn(min, max)
        if (next == value) return
        view?.setValue(next)
        chosen(next)
    }

    var adjusting: Boolean
        get() = view?.adjusting == true
        set(on) {
            view?.adjusting = on
        }
}

/**
 * Al doilea nivel al interfeței: rotița în stânga, rândurile meniului în dreapta.
 *
 * Ăsta era ecranul principal înainte să intre grila de dale ID6 deasupra lui.
 * Acum e un ȘABLON: fiecare dală din [ro.e92.launcher.ui.TileCatalog] își
 * deschide propriul meniu cu aceeași înfățișare, dar cu rânduri proprii.
 *
 * Trei elemente se mișcă împreună la fiecare mutare de focus, conduse dintr-un
 * singur loc ([onItemFocused]):
 *   1. iconița din centrul rotiței devine iconița rândului focusat;
 *   2. segmentul aprins de pe inel se rotește la unghiul indexului focusat;
 *   3. conectorul se re-trasează spre mijlocul rândului focusat.
 *
 * Paginarea nu e un gest separat: focusul e sursa de adevăr, pagina îl urmează.
 *
 * [entries] e o funcție, nu o listă: rândurile arată valori care se schimbă
 * (aplicația atribuită, piesa care cântă), iar [reload] le recitește fără să
 * reconstruiască ecranul.
 */
abstract class WheelMenuScreen(host: ScreenHost) : Screen(host) {

    /** Textul de sub iconița din centrul rotiței, când nu e nimic focusat. */
    protected abstract val menuTitle: String

    protected abstract fun entries(): List<WheelEntry>

    private lateinit var binding: ScreenHomeBinding

    private var rows: List<WheelEntry> = emptyList()
    private val rowViews = ArrayList<View>(MAX_ON_PAGE)
    private var pageSize = MIN_ON_PAGE
    private val dotViews = ArrayList<View>(2)

    private var currentPage = 0
    private var pageWidth = 0
    private var pageCount = 1
    private var built = false

    // Reutilizate la fiecare mutare de focus — rotița poate genera zeci de
    // evenimente pe secundă și nimic de aici nu trebuie să aloce.
    private val rowLocation = IntArray(2)
    private val connectorLocation = IntArray(2)

    private lateinit var swipeDetector: GestureDetector

    override val title: String get() = menuTitle.uppercase()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenHomeBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        setupSwipe()
        binding.viewport.post {
            if (!built) buildPages()
        }
    }

    override fun focusTargets(): List<FocusTarget> {
        if (!built) return emptyList()
        return rows.mapIndexed { index, entry ->
            FocusTarget(
                id = entry.id,
                view = rowViews[index],
                onActivate = entry.onActivate,
                onOption = entry.onOption,
                onFocus = { focused -> if (focused) onItemFocused(index) },
                up = rows.getOrNull(index - 1)?.id,
                down = rows.getOrNull(index + 1)?.id
            )
        }
    }

    /**
     * Recitește rândurile păstrând focusul. De folosit după o acțiune care
     * schimbă ce scrie pe ele (o aplicație atribuită, un play/pause).
     */
    protected fun reload(keepId: String? = null) {
        if (!built) return
        val restore = keepId ?: rowViews.indices
            .firstOrNull { rowViews[it].isActivated }
            ?.let { rows[it].id }
        built = false
        buildPages(restore)
    }

    // ------------------------------------------------------------ construcție

    private fun buildPages(restoreId: String? = null) {
        val viewportWidth = binding.viewport.width
        if (viewportWidth <= 0) return

        rows = entries()
        // Rândurile au mărimea din vechiul meniu de trei, în toate meniurile, și stau jos;
        // un meniu cu mai multe rânduri crește în sus, spre locul liber de sub bara de stare.
        binding.gContentTop.setGuidelinePercent(TOP_MIN)
        // Meniurile scurte (cele din Home: Media, BT, Car, Dash, Setup au câte trei rânduri)
        // primesc rânduri mai înalte și text mai mare (cerut 2026-11-17).
        val big = rows.size <= MIN_ON_PAGE
        val rowHeight = rowHeightPx(if (big) TOP_FOR_THREE_BIG else TOP_FOR_THREE)
        pageSize = rowsThatFit(rowHeight)
        pageCount = maxOf(1, (rows.size + pageSize - 1) / pageSize)
        pageWidth = viewportWidth

        val inflater = LayoutInflater.from(context)
        binding.pageStrip.removeAllViews()
        binding.pageStrip.translationX = 0f
        rowViews.clear()
        currentPage = 0

        for (page in 0 until pageCount) {
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val first = page * pageSize
            val last = minOf(first + pageSize, rows.size)

            for (index in first until last) {
                val entry = rows[index]
                val rowBinding = ItemMenuBinding.inflate(inflater, column, false)
                rowBinding.menuIcon.setImageResource(entry.icon)
                rowBinding.menuTitle.text = entry.title
                if (big) {
                    rowBinding.menuTitle.textSize = BIG_TITLE_SP
                    rowBinding.menuValue.textSize = BIG_VALUE_SP
                    rowBinding.menuIcon.layoutParams = rowBinding.menuIcon.layoutParams.apply {
                        val s = (BIG_ICON_DP * context.resources.displayMetrics.density).toInt()
                        width = s
                        height = s
                    }
                    // Titlul mai mare e mai lat: bara sau conținutul din rând pornesc mai la dreapta.
                    (rowBinding.menuSlider.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                        context.resources.getDimensionPixelSize(R.dimen.menu_slider_start) +
                            (BIG_SLIDER_SHIFT_DP * context.resources.displayMetrics.density).toInt()
                }
                rowBinding.menuValue.text = entry.value
                entry.slider?.let { slider ->
                    val line = ro.e92.launcher.ui.widget.VolumeLineView(context, slider.max).apply {
                        setValue(slider.value)
                        onChange = { level -> slider.chosen(level) }
                    }
                    slider.view = line
                    slider.label = rowBinding.menuValue
                    rowBinding.menuValue.text = slider.value.toString()
                    rowBinding.menuValue.textSize = SLIDER_VALUE_SP
                    rowBinding.menuChevron.visibility = View.GONE
                    (rowBinding.menuValue.layoutParams as ViewGroup.MarginLayoutParams).marginEnd =
                        context.resources.getDimensionPixelSize(R.dimen.menu_row_padding_h)
                    rowBinding.menuSlider.visibility = View.VISIBLE
                    rowBinding.menuSlider.addView(line)
                }
                entry.inline?.let { build ->
                    rowBinding.menuChevron.visibility = View.GONE
                    (rowBinding.menuValue.layoutParams as ViewGroup.MarginLayoutParams).marginEnd =
                        context.resources.getDimensionPixelSize(R.dimen.menu_row_padding_h)
                    rowBinding.menuSlider.visibility = View.VISIBLE
                    rowBinding.menuSlider.addView(build())
                }
                rowBinding.root.setOnClickListener {
                    host.rebuildFocus(entry.id)
                    entry.onActivate()
                }
                ro.e92.launcher.ui.widget.noNativeFocus(rowBinding.root)
                // Swipe-ul se ascultă pe rânduri, nu pe viewport: rândurile sunt
                // clickable, deci consumă ACTION_DOWN și părintele n-ar mai vedea
                // gestul. Returnăm false ca să nu stricăm click-ul.
                rowBinding.root.setOnTouchListener { _, event ->
                    swipeDetector.onTouchEvent(event)
                    false
                }

                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
                    .apply { topMargin = if (index == first) 0 else rowGapPx() }
                column.addView(rowBinding.root, lp)
                rowViews.add(rowBinding.root)
            }

            binding.pageStrip.addView(column)
        }

        // Vezi TileGridScreen: wrap_content într-un FrameLayout se măsoară cu
        // AT_MOST, deci paginile de după prima ar fi tăiate la desenare.
        binding.pageStrip.layoutParams = binding.pageStrip.layoutParams.also {
            it.width = pageWidth * pageCount
        }

        buildDots()
        built = true
        host.rebuildFocus(restoreId ?: rows.firstOrNull()?.id)
    }

    private fun buildDots() {
        binding.pageDots.removeAllViews()
        dotViews.clear()
        // Un singur punct nu spune nimic: dacă meniul încape pe o pagină,
        // indicatorul dispare de tot.
        if (pageCount < 2) return

        val size = context.resources.getDimensionPixelSize(R.dimen.page_dot_size)
        for (page in 0 until pageCount) {
            val dot = View(context).apply {
                setBackgroundResource(R.drawable.bg_page_dot)
                isActivated = page == currentPage
            }
            val lp = LinearLayout.LayoutParams(size, size)
            if (page > 0) lp.marginStart = size
            binding.pageDots.addView(dot, lp)
            dotViews.add(dot)
        }
    }

    /** Înălțimea unui rând: cea din vechiul meniu de trei (lista pornea de la [TOP_FOR_THREE]). */
    private fun rowHeightPx(topForThree: Float): Int {
        // Mărimea rândurilor e cea aleasă cu bara de jos încă pe ecran (2026-11-08). Bara a
        // dispărut, deci scădem înălțimea ei: rândurile rămân la fel, doar urcă mai puțin.
        val removedBar = context.resources.getDimensionPixelSize(R.dimen.bottom_chips_space)
        val parentHeight = binding.root.height - removedBar
        val bottom = binding.pageDots.top - removedBar
        val gap = rowGapPx()
        return ((bottom - parentHeight * topForThree - (MIN_ON_PAGE - 1) * gap) / MIN_ON_PAGE)
            .toInt().coerceAtLeast(1)
    }

    /** Câte rânduri de [rowHeight] încap până sus; mai multe trec pe pagina următoare. */
    private fun rowsThatFit(rowHeight: Int): Int {
        val space = binding.pageDots.top - binding.root.height * TOP_MIN
        val gap = rowGapPx()
        return ((space + gap) / (rowHeight + gap)).toInt().coerceIn(MIN_ON_PAGE, MAX_ON_PAGE)
    }

    private fun rowGapPx(): Int =
        context.resources.getDimensionPixelSize(R.dimen.menu_row_gap)

    private fun setupSwipe() {
        swipeDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (abs(velocityX) < abs(velocityY)) return false
                if (abs(velocityX) < MIN_FLING_VELOCITY) return false
                val next = if (velocityX < 0) currentPage + 1 else currentPage - 1
                if (next < 0 || next >= pageCount) return false
                val entry = rows.getOrNull(next * pageSize) ?: return false
                host.rebuildFocus(entry.id)
                return true
            }
        })
    }

    // --------------------------------------------------------------- focus

    private fun onItemFocused(index: Int) {
        val entry = rows.getOrNull(index) ?: return

        binding.wheelIcon.setImageResource(entry.icon)
        binding.wheelLabel.text = entry.title
        binding.wheel.setSelection(index, rows.size)

        goToPage(index / pageSize)
        updateConnector(index)
    }

    private fun goToPage(page: Int) {
        if (page == currentPage || page !in 0 until pageCount) return
        currentPage = page
        dotViews.forEachIndexed { i, dot -> dot.isActivated = i == page }

        val targetX = -(pageWidth.toFloat() * page)
        if (Services.prefs.animationsEnabled) {
            binding.pageStrip.animate()
                .translationX(targetX)
                .setDuration(PAGE_ANIM_MS)
                .start()
        } else {
            binding.pageStrip.translationX = targetX
        }
    }

    /**
     * Ținta conectorului e mijlocul rândului focusat, convertit din coordonatele
     * lui în cele ale ConnectorView-ului. Rândurile sunt în alt părinte (și
     * translatate la schimbarea paginii), deci diferența de poziții pe ecran e
     * singura conversie corectă.
     */
    private fun updateConnector(index: Int) {
        val row = rowViews.getOrNull(index) ?: return
        if (row.height == 0) row.post { applyConnector(row) } else applyConnector(row)
    }

    private fun applyConnector(row: View) {
        row.getLocationInWindow(rowLocation)
        binding.connector.getLocationInWindow(connectorLocation)
        binding.connector.setTargetY(rowLocation[1] + row.height / 2f - connectorLocation[1])
    }

    protected companion object {
        /** Cel mult cinci rânduri pe pagină, cât încap, de jos în sus. */
        const val MAX_ON_PAGE = 5
        const val MIN_ON_PAGE = 3
        /** Unde începea lista de trei rânduri: de acolo vine înălțimea unui rând. */
        const val TOP_FOR_THREE = 0.36f
        /** La meniurile de trei rânduri lista pornește mai sus: rânduri mai înalte. */
        const val TOP_FOR_THREE_BIG = 0.24f
        const val BIG_TITLE_SP = 22f
        const val BIG_VALUE_SP = 16f
        const val BIG_ICON_DP = 34f
        const val BIG_SLIDER_SHIFT_DP = 40f
        /** Cât de sus poate urca lista, sub bara de stare. */
        const val TOP_MIN = 0.02f

        const val PAGE_ANIM_MS = 180L
        const val SLIDER_VALUE_SP = 17f
        const val MIN_FLING_VELOCITY = 600f
    }
}

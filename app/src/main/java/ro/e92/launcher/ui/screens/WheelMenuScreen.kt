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
    val onOption: (() -> Unit)? = null
)

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
    private val rowViews = ArrayList<View>(PAGE_SIZE * 2)
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
        pageCount = maxOf(1, (rows.size + PAGE_SIZE - 1) / PAGE_SIZE)
        pageWidth = viewportWidth

        val inflater = LayoutInflater.from(context)
        binding.pageStrip.removeAllViews()
        binding.pageStrip.translationX = 0f
        rowViews.clear()
        currentPage = 0

        for (page in 0 until pageCount) {
            // Cele 5 rânduri împart înălțimea cu weight: exact 5 vizibile
            // indiferent ce densitate raportează unitatea.
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val first = page * PAGE_SIZE
            val last = minOf(first + PAGE_SIZE, rows.size)

            for (index in first until last) {
                val entry = rows[index]
                val rowBinding = ItemMenuBinding.inflate(inflater, column, false)
                rowBinding.menuIcon.setImageResource(entry.icon)
                rowBinding.menuTitle.text = entry.title
                rowBinding.menuValue.text = entry.value
                rowBinding.root.setOnClickListener {
                    host.rebuildFocus(entry.id)
                    entry.onActivate()
                }
                // Swipe-ul se ascultă pe rânduri, nu pe viewport: rândurile sunt
                // clickable, deci consumă ACTION_DOWN și părintele n-ar mai vedea
                // gestul. Returnăm false ca să nu stricăm click-ul.
                rowBinding.root.setOnTouchListener { _, event ->
                    swipeDetector.onTouchEvent(event)
                    false
                }

                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    .apply { topMargin = if (index == first) 0 else rowGapPx() }
                column.addView(rowBinding.root, lp)
                rowViews.add(rowBinding.root)
            }

            // Pagină incompletă: umplem cu spațiu, ca rândurile să nu se întindă.
            repeat(PAGE_SIZE - (last - first)) {
                column.addView(
                    View(context),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                )
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
                val entry = rows.getOrNull(next * PAGE_SIZE) ?: return false
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

        goToPage(index / PAGE_SIZE)
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
        /** Cinci rânduri pe pagină: pe 480 px verticali, al șaselea ar fi ilizibil. */
        const val PAGE_SIZE = 5

        const val PAGE_ANIM_MS = 180L
        const val MIN_FLING_VELOCITY = 600f
    }
}

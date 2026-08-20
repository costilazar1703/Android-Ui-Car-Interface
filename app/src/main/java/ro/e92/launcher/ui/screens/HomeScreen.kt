package ro.e92.launcher.ui.screens

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ItemMenuBinding
import ro.e92.launcher.databinding.ScreenHomeBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.MainMenuAction
import ro.e92.launcher.ui.MenuCatalog
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import kotlin.math.abs

/**
 * Meniul principal: rotița 3D în stânga, cele 10 meniuri în dreapta,
 * 5 pe pagină, două pagini.
 *
 * Trei elemente vizuale se mișcă împreună la fiecare mutare de focus și sunt
 * conduse dintr-un singur loc ([onItemFocused]):
 *   1. iconița din centrul rotiței devine iconița meniului focusat;
 *   2. marcajul aprins de pe rim se rotește la unghiul indexului focusat;
 *   3. conectorul se re-trasează spre mijlocul rândului focusat.
 *
 * Paginarea nu e un gest separat de navigare: focusul e sursa de adevăr, iar
 * pagina îl urmează. Rotești peste elementul 5 → pagina alunecă singură.
 * Swipe-ul cu degetul e doar o scurtătură care mută focusul, nu un al doilea
 * model de stare.
 */
class HomeScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "MENU"

    private lateinit var binding: ScreenHomeBinding

    private val rowViews = ArrayList<View>(MenuCatalog.items.size)
    private val pageContainers = ArrayList<LinearLayout>(MenuCatalog.pageCount)
    private val dotViews = ArrayList<View>(MenuCatalog.pageCount)

    private var currentPage = 0
    private var pageWidth = 0
    private var built = false

    // Reutilizate la fiecare mutare de focus — rotița poate genera zeci de
    // evenimente pe secundă și nimic de aici nu trebuie să aloce.
    private val rowLocation = IntArray(2)
    private val connectorLocation = IntArray(2)

    private lateinit var swipeDetector: GestureDetector

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup) =
        ScreenHomeBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        buildDots()
        setupSwipe()

        // Lățimea unei pagini = lățimea viewport-ului, cunoscută abia după măsurare.
        binding.viewport.post {
            if (!built) buildPages()
        }
    }

    override fun focusTargets(): List<FocusTarget> {
        if (!built) return emptyList()
        return MenuCatalog.items.mapIndexed { index, item ->
            FocusTarget(
                id = item.id,
                view = rowViews[index],
                onActivate = { activate(item.action) },
                onFocus = { focused -> if (focused) onItemFocused(index) },
                // Tilt sus/jos = vecinul din aceeași pagină; la capete trece
                // în pagina alăturată, exact ca rotirea.
                up = MenuCatalog.items.getOrNull(index - 1)?.id,
                down = MenuCatalog.items.getOrNull(index + 1)?.id
            )
        }
    }

    // ------------------------------------------------------------ construcție

    private fun buildPages() {
        val viewportWidth = binding.viewport.width
        if (viewportWidth <= 0) return

        pageWidth = viewportWidth
        val inflater = LayoutInflater.from(context)

        binding.pageStrip.removeAllViews()
        rowViews.clear()
        pageContainers.clear()

        for (page in 0 until MenuCatalog.pageCount) {
            // Cele 5 rânduri împart înălțimea cu weight. Un nivel de LinearLayout
            // cu 5 copii se măsoară de două ori, dar e singurul mod de a garanta
            // fix 5 rânduri vizibile indiferent ce densitate raportează unitatea.
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val first = page * MenuCatalog.PAGE_SIZE
            val last = minOf(first + MenuCatalog.PAGE_SIZE, MenuCatalog.items.size)

            for (index in first until last) {
                val item = MenuCatalog.items[index]
                val rowBinding = ItemMenuBinding.inflate(inflater, column, false)
                rowBinding.menuIcon.setImageResource(item.icon)
                rowBinding.menuTitle.setText(item.title)
                rowBinding.root.setOnClickListener {
                    host.rebuildFocus(item.id)
                    activate(item.action)
                }
                // Swipe-ul se ascultă pe rânduri, nu pe viewport: rândurile sunt
                // clickable, deci consumă ACTION_DOWN și părintele n-ar mai vedea
                // gestul. Returnăm false ca să nu stricăm click-ul.
                rowBinding.root.setOnTouchListener { _, event ->
                    swipeDetector.onTouchEvent(event)
                    false
                }

                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = if (index == first) 0 else rowGapPx() }
                column.addView(rowBinding.root, lp)
                rowViews.add(rowBinding.root)
            }

            // Pagină incompletă: umplem cu spațiu, ca rândurile să nu se întindă.
            repeat(MenuCatalog.PAGE_SIZE - (last - first)) {
                column.addView(
                    View(context),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                )
            }

            binding.pageStrip.addView(column)
            pageContainers.add(column)
        }

        built = true

        // Graful de focus există abia acum, când rândurile au view-uri reale.
        val restoreId = MenuCatalog.items
            .getOrNull(Services.prefs.lastMenuPage * MenuCatalog.PAGE_SIZE)?.id
        host.rebuildFocus(restoreId)
    }

    private fun buildDots() {
        binding.pageDots.removeAllViews()
        dotViews.clear()
        val size = context.resources.getDimensionPixelSize(R.dimen.page_dot_size)
        for (page in 0 until MenuCatalog.pageCount) {
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
                // Swipe stânga = pagina următoare.
                val next = if (velocityX < 0) currentPage + 1 else currentPage - 1
                return focusFirstItemOfPage(next)
            }
        })
    }

    /** @return true dacă pagina cerută există și focusul s-a mutat acolo. */
    private fun focusFirstItemOfPage(page: Int): Boolean {
        if (page < 0 || page >= MenuCatalog.pageCount) return false
        val index = page * MenuCatalog.PAGE_SIZE
        val item = MenuCatalog.items.getOrNull(index) ?: return false
        host.rebuildFocus(item.id)
        return true
    }

    // --------------------------------------------------------------- focus

    private fun onItemFocused(index: Int) {
        val item = MenuCatalog.items[index]

        binding.wheelIcon.setImageResource(item.icon)
        binding.wheelLabel.setText(item.title)
        binding.wheel.setSelection(index, MenuCatalog.items.size)

        goToPage(MenuCatalog.pageOf(index))
        updateConnector(index)
    }

    private fun goToPage(page: Int) {
        if (page == currentPage || page !in 0 until MenuCatalog.pageCount) return
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
        // Rândurile sunt deja așezate în cazul normal (rotire prin meniu); post-ul
        // e doar pentru primul focus, imediat după construcția paginilor.
        if (row.height == 0) row.post { applyConnector(row) } else applyConnector(row)
    }

    private fun applyConnector(row: View) {
        row.getLocationInWindow(rowLocation)
        binding.connector.getLocationInWindow(connectorLocation)
        binding.connector.setTargetY(rowLocation[1] + row.height / 2f - connectorLocation[1])
    }

    // -------------------------------------------------------------- acțiuni

    private fun activate(action: MainMenuAction) {
        Services.prefs.lastMenuPage = currentPage
        host.openMenu(action)
    }

    private companion object {
        const val PAGE_ANIM_MS = 180L
        const val MIN_FLING_VELOCITY = 600f
    }
}

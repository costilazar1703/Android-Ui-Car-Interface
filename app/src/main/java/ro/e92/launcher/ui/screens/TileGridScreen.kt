package ro.e92.launcher.ui.screens

import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import ro.e92.launcher.R
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ItemTileBinding
import ro.e92.launcher.databinding.ScreenTileGridBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import ro.e92.launcher.ui.Tile
import ro.e92.launcher.ui.TileCatalog
import kotlin.math.abs

/**
 * Meniul principal EVO ID5/ID6: 6 dale pe pagină, două pagini.
 *
 * **Perspectiva.** Dalele nu stau plat, ci pe un arc: cele de la margini sunt
 * rotite spre interior, ca într-un perete curbat în jurul șoferului. Se face cu
 * `rotationY` + `cameraDistance` pe view-uri obișnuite — fără OpenGL, fără
 * bibliotecă. Unghiul e STATIC (depinde de poziția dalei în pagină, nu de
 * focus), deci se calculează o singură dată, la construcție: un arc care s-ar
 * recalcula la fiecare mișcare a rotiței ar costa un relayout pe fiecare pas.
 *
 * `cameraDistance` e setat explicit pentru că valoarea implicită a Android-ului
 * e legată de densitatea ecranului. Pe o unitate care raportează 120 dpi aceeași
 * rotație ar ieși mult mai agresivă decât pe una cu 160 — dalele de la margini
 * ar părea rupte. Îl fixăm în funcție de lățimea dalei, deci arcul arată la fel
 * indiferent ce raportează unitatea.
 *
 * **Ce se mișcă la focus** e doar scara dalei și starea ei `activated` (glow-ul
 * vine din `bg_tile.xml`). Poziția și unghiul rămân neatinse.
 */
class TileGridScreen(host: ScreenHost) : Screen(host) {

    override val title: String get() = "MENU"

    /** Nivelul 1 are masina; tot ce se deschide din el are motorul. */
    override val backgroundRes: Int get() = R.drawable.bg_home

    private lateinit var binding: ScreenTileGridBinding

    private val tileViews = ArrayList<View>(TileCatalog.items.size)
    private val dotViews = ArrayList<View>(TileCatalog.pageCount)

    private var currentPage = 0
    private var pageWidth = 0
    private var built = false

    private lateinit var swipeDetector: GestureDetector

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenTileGridBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        buildDots()
        setupSwipe()

        // Lățimea unei pagini se știe abia după măsurare.
        binding.tileViewport.post {
            if (!built) buildPages()
        }
    }

    override fun focusTargets(): List<FocusTarget> {
        if (!built) return emptyList()
        return TileCatalog.items.mapIndexed { index, tile ->
            FocusTarget(
                id = tile.id,
                view = tileViews[index],
                onActivate = { activate(tile) },
                onFocus = { focused -> onTileFocus(index, focused) },
                // Tilt stânga/dreapta = dala vecină, inclusiv peste granița de
                // pagină: la a 6-a dală, dreapta trece pe pagina următoare, exact
                // ca rotirea. Sus/jos nu inventează nimic pe un singur rând.
                left = TileCatalog.items.getOrNull(index - 1)?.id,
                right = TileCatalog.items.getOrNull(index + 1)?.id
            )
        }
    }

    // ------------------------------------------------------------ construcție

    private fun buildPages() {
        val viewportWidth = binding.tileViewport.width
        val viewportHeight = binding.tileViewport.height
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        pageWidth = viewportWidth
        val inflater = LayoutInflater.from(context)
        val gap = context.resources.getDimensionPixelSize(R.dimen.tile_gap)
        val marginV = context.resources.getDimensionPixelSize(R.dimen.tile_grid_margin_v)

        // Spațiile dintre dale se scad ÎNAINTE de împărțire. Altfel a șasea dală
        // iese din viewport cu exact suma spațiilor și e tăiată de clip.
        val totalGaps = gap * (TileCatalog.PAGE_SIZE - 1)
        val tileWidth = (pageWidth - totalGaps) / TileCatalog.PAGE_SIZE

        // Pătrat, nu dreptunghi: dala e la fel de lată pe cât e de înaltă, iar
        // dacă înălțimea disponibilă nu ajunge, ea comandă și lățimea rămâne
        // doar spațiu liber între dale.
        val labelHeight = (tileWidth * LABEL_HEIGHT_RATIO).toInt()
        // Umbra ocupă spațiu real sub pătrat: dacă n-o scădem aici, pătratul
        // crește până umple înălțimea și umbra iese din viewport.
        val shadowHeight = (tileWidth * SHADOW_HEIGHT_RATIO).toInt()
        val available = viewportHeight - 2 * marginV - labelHeight - shadowHeight
        val bodySize = minOf(tileWidth, available)
        val iconSize = (bodySize * ICON_RATIO).toInt()
        val iconMargin = context.resources.getDimensionPixelSize(R.dimen.tile_body_inset) * 3
        val labelTextPx = bodySize * LABEL_TEXT_RATIO

        binding.tileStrip.removeAllViews()
        tileViews.clear()

        for (page in 0 until TileCatalog.pageCount) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                // Pătratele se centrează pe verticală în banda disponibilă.
                gravity = Gravity.CENTER_VERTICAL
                // Fără clip: dalele rotite ies puțin din dreptunghiul lor, iar
                // halo-ul celei selectate se difuzează peste vecine.
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    pageWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val first = page * TileCatalog.PAGE_SIZE
            val last = minOf(first + TileCatalog.PAGE_SIZE, TileCatalog.items.size)

            for (index in first until last) {
                val tile = TileCatalog.items[index]
                val tileBinding = ItemTileBinding.inflate(inflater, row, false)
                tileBinding.tileIcon.setImageResource(tile.icon)
                tileBinding.tileLabel.setText(tile.title)

                // Dimensiunile în pixeli măsurați, nu în dp — vezi comentariul
                // din item_tile.xml: densitatea unității e necunoscută.
                tileBinding.tileLabel.layoutParams.height = labelHeight
                tileBinding.tileLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelTextPx)
                tileBinding.tileBody.layoutParams.height = bodySize
                tileBinding.tileShadow.layoutParams.height = shadowHeight
                tileBinding.tileIcon.layoutParams.also {
                    // Plafonat la corpul dalei minus rama: o iconita cu
                    // iconScale mare ar iesi altfel din patrat si ar fi taiata.
                    val scaled = (iconSize * tile.iconScale).toInt()
                    it.width = minOf(scaled, bodySize - iconMargin)
                    it.height = scaled
                }

                tileBinding.root.setOnClickListener {
                    host.rebuildFocus(tile.id)
                    activate(tile)
                }
                tileBinding.root.setOnTouchListener { _, event ->
                    swipeDetector.onTouchEvent(event)
                    false
                }

                val lp = LinearLayout.LayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = if (index == first) 0 else gap }
                row.addView(tileBinding.root, lp)
                tileViews.add(tileBinding.root)

                applyPerspective(tileBinding.root, index - first, tileWidth)
            }

            binding.tileStrip.addView(row)
        }

        // Banda trebuie să fie lată cât toate paginile. wrap_content nu ajunge:
        // într-un FrameLayout copilul e măsurat cu AT_MOST = lățimea viewport-ului,
        // deci a doua pagină ar cădea în afara propriilor margini și ar fi tăiată
        // la desenare — invizibilă, oricât am translata.
        binding.tileStrip.layoutParams = binding.tileStrip.layoutParams.also {
            it.width = pageWidth * TileCatalog.pageCount
        }

        built = true
        host.rebuildFocus(restoreId())
    }

    /** Focusul revine pe prima dală a paginii pe care a rămas utilizatorul. */
    private fun restoreId(): String? =
        TileCatalog.items.getOrNull(Services.prefs.lastMenuPage * TileCatalog.PAGE_SIZE)?.id

    /**
     * Arcul: dala din stânga e rotită spre interior, cea din dreapta la fel în
     * oglindă, iar cele din mijloc aproape deloc. Dalele mai depărtate de centru
     * primesc și o scădere de scară, ca să pară că se duc în adâncime.
     */
    private fun applyPerspective(view: View, positionInPage: Int, tileWidth: Int) {
        val offset = depthOffset(positionInPage)            // -1 .. +1

        view.cameraDistance = tileWidth * CAMERA_DISTANCE_FACTOR

        // Arc CONCAV, curbat în jurul șoferului: fiecare dală se întoarce cu fața
        // spre centrul ecranului, deci muchia ei din AFARĂ vine spre privitor și
        // cea dinspre centru fuge în spate.
        //
        // Semnul e minus, iar asta se verifică măsurând, nu din cap: cu plus,
        // muchiile interioare ies în față și peretele devine convex — dalele ar
        // privi în afară, ca pe exteriorul unui butoi.
        view.rotationY = -offset * MAX_TILT_DEG

        // Pivot central (implicit): dalele se rotesc fiecare în jurul axei
        // proprii, ca niște panouri pe un perete curbat. Un pivot pe muchie le-ar
        // face să se depărteze una de alta pe măsură ce se rotesc.
        val shrink = 1f - abs(offset) * DEPTH_SHRINK
        view.scaleX = shrink
        view.scaleY = shrink
    }

    private fun buildDots() {
        binding.tilePageDots.removeAllViews()
        dotViews.clear()
        val size = context.resources.getDimensionPixelSize(R.dimen.page_dot_size)
        for (page in 0 until TileCatalog.pageCount) {
            val dot = View(context).apply {
                setBackgroundResource(R.drawable.bg_page_dot)
                isActivated = page == currentPage
            }
            val lp = LinearLayout.LayoutParams(size, size)
            if (page > 0) lp.marginStart = size
            binding.tilePageDots.addView(dot, lp)
            dotViews.add(dot)
        }
    }

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
                return focusFirstTileOfPage(next)
            }
        })
    }

    private fun focusFirstTileOfPage(page: Int): Boolean {
        if (page < 0 || page >= TileCatalog.pageCount) return false
        val tile = TileCatalog.items.getOrNull(page * TileCatalog.PAGE_SIZE) ?: return false
        host.rebuildFocus(tile.id)
        return true
    }

    // ---------------------------------------------------------------- focus

    private fun onTileFocus(index: Int, focused: Boolean) {
        val view = tileViews.getOrNull(index) ?: return

        // Dala focusată iese în față. Scara de bază vine din arc, deci o
        // înmulțim, nu o suprascriem — altfel dalele de la margini ar sări la
        // dimensiunea celor din centru când le focusezi.
        val base = 1f - abs(depthOffset(index % TileCatalog.PAGE_SIZE)) * DEPTH_SHRINK
        val target = if (focused) base * FOCUS_SCALE else base

        if (Services.prefs.animationsEnabled) {
            view.animate().scaleX(target).scaleY(target).setDuration(FOCUS_ANIM_MS).start()
        } else {
            view.scaleX = target
            view.scaleY = target
        }

        if (focused) goToPage(TileCatalog.pageOf(index))
    }

    /** -1 pentru dala din stanga paginii, +1 pentru cea din dreapta. */
    private fun depthOffset(positionInPage: Int): Float {
        val center = (TileCatalog.PAGE_SIZE - 1) / 2f
        return (positionInPage - center) / center
    }

    private fun goToPage(page: Int) {
        if (page == currentPage || page !in 0 until TileCatalog.pageCount) return
        currentPage = page
        dotViews.forEachIndexed { i, dot -> dot.isActivated = i == page }

        val targetX = -(pageWidth.toFloat() * page)
        if (Services.prefs.animationsEnabled) {
            binding.tileStrip.animate()
                .translationX(targetX)
                .setDuration(PAGE_ANIM_MS)
                .start()
        } else {
            binding.tileStrip.translationX = targetX
        }
    }

    // -------------------------------------------------------------- acțiuni

    private fun activate(tile: Tile) {
        Services.prefs.lastMenuPage = currentPage
        host.openTile(tile.action)
    }

    private companion object {
        const val PAGE_ANIM_MS = 200L
        const val FOCUS_ANIM_MS = 140L
        const val MIN_FLING_VELOCITY = 600f

        /** Unghiul dalelor de la marginea paginii. Peste ~26° textul se încețoșează. */
        const val MAX_TILT_DEG = 22f

        /** Cât se micșorează dala de la margine față de cea din centru. */
        const val DEPTH_SHRINK = 0.06f

        /** Cât iese în față dala focusată. */
        const val FOCUS_SCALE = 1.07f

        /**
         * Distanța camerei, ca multiplu de lățime de dală. Sub ~6 perspectiva
         * devine de fisheye; peste ~12 rotația nu se mai vede deloc.
         */
        const val CAMERA_DISTANCE_FACTOR = 8f

        /** Proportii fata de latimea dalei, ca sa nu depinda de densitate. */
        const val LABEL_HEIGHT_RATIO = 0.20f
        const val LABEL_TEXT_RATIO = 0.125f
        const val ICON_RATIO = 0.44f
        const val SHADOW_HEIGHT_RATIO = 0.16f
    }
}

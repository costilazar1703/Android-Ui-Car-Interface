package ro.e92.launcher.ui.screens

import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.coroutines.launch
import ro.e92.launcher.R
import ro.e92.launcher.core.BluetoothPresence
import ro.e92.launcher.core.Services
import ro.e92.launcher.databinding.ItemTileBinding
import ro.e92.launcher.databinding.ScreenTileGridBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost
import ro.e92.launcher.ui.Tile
import ro.e92.launcher.ui.TileCatalog
import ro.e92.launcher.nav.NavNotificationRelay
import ro.e92.launcher.ui.widget.PageIndicatorView
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
    /** Dalele care pot afișa ceva viu, după id: media, bt, nav. */
    private val tileBindings = HashMap<String, ItemTileBinding>()
    /** Cât urcă iconița când sub ea apare textul viu. */
    private var liveIconShift = 0f
    private var indicator: PageIndicatorView? = null

    /**
     * Geometria semicercului, pe pozitie in pagina (0..5): marimea fiecarei dale
     * si cat trebuie mutata pe orizontala ca spatiile dintre ele sa ramana egale.
     * Calculate o singura data, la constructie.
     */
    private var arcScale = FloatArray(TileCatalog.PAGE_SIZE) { 1f }
    private var arcShift = FloatArray(TileCatalog.PAGE_SIZE)

    private var currentPage = 0
    private var pageWidth = 0
    private var built = false

    private lateinit var swipeDetector: GestureDetector

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenTileGridBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        buildDots()
        setupSwipe()
        setupMiniPlayer()

        BluetoothPresence.start(context)
        screenScope.launch { Services.media.snapshot.collect { renderMedia() } }
        screenScope.launch { BluetoothPresence.deviceName.collect { renderLive() } }
        screenScope.launch { NavNotificationRelay.guidance.collect { renderLive() } }

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

        computeArc(tileWidth, gap)

        val liveTextPx = bodySize * LIVE_TEXT_RATIO
        val liveInset = (bodySize * LIVE_INSET_RATIO).toInt()
        val eqSize = (bodySize * EQ_RATIO).toInt()
        liveIconShift = bodySize * LIVE_ICON_SHIFT_RATIO
        tileBindings.clear()

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
                tileBinding.tileShadow.setSource(tile.icon, iconSize, bodySize)
                tileBinding.tileLive.setTextSize(TypedValue.COMPLEX_UNIT_PX, liveTextPx)
                tileBinding.tileLive.setPadding(liveInset, 0, liveInset, 0)
                (tileBinding.tileLive.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                    (bodySize * LIVE_BOTTOM_RATIO).toInt()
                (tileBinding.tileEq.layoutParams as FrameLayout.LayoutParams).apply {
                    width = eqSize
                    height = eqSize
                    topMargin = liveInset
                    marginEnd = liveInset
                }
                tileBindings[tile.id] = tileBinding
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
        renderMedia()
        host.rebuildFocus(restoreId())
    }

    /** Focusul revine pe prima dală a paginii pe care a rămas utilizatorul. */
    private fun restoreId(): String? =
        TileCatalog.items.getOrNull(Services.prefs.lastMenuPage * TileCatalog.PAGE_SIZE)?.id

    /**
     * Arcul: dala din stânga e rotită spre interior, cea din dreapta la fel în
     * oglindă, iar cele din mijloc aproape deloc. Mărimea și poziția vin din
     * [computeArc]: mijlocul e cel mai departe, deci cel mai mic.
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
        // proprii, ca niște panouri pe un perete curbat.
        view.scaleX = arcScale[positionInPage]
        view.scaleY = arcScale[positionInPage]
        view.translationX = arcShift[positionInPage]
    }

    /**
     * Semicercul: dalele din MIJLOC sunt cele mai departe, deci cele mai mici;
     * cele de la margini vin spre tine, deci sunt cele mai mari.
     *
     * Asa trebuie sa fie pentru ca dalele sunt rotite in arc concav (muchia din
     * afara vine in fata). Inainte marimea mergea invers - mijlocul cel mai mare
     * - iar rotatia spunea una si marimea alta.
     *
     * Profilul e patratic (1 - o²), nu liniar: pe un cerc adevarat adancimea se
     * schimba lin spre centru, nu in trepte egale.
     *
     * Micsorarea singura ar fi largit golurile din mijloc - fiecare dala se
     * strange in jurul propriului centru. Asa ca pozitiile se reimpart: latimile
     * vizibile se insireaza cu ACELASI spatiu intre ele, iar randul umple in
     * continuare toata latimea paginii (de aceea dalele de la margini cresc
     * putin peste marimea de dinainte).
     */
    private fun computeArc(tileWidth: Int, gap: Int) {
        val n = TileCatalog.PAGE_SIZE
        val rel = FloatArray(n) { i ->
            val o = depthOffset(i)
            1f - (1f - o * o) * ARC_DEPTH
        }
        // O dala rotita cu θ apare pe ecran mai ingusta, cam cu cos θ. Fara
        // corectia asta golurile ieseau inegale: ~24 px langa dalele de la
        // margine (cele mai rotite) si ~17 px spre mijloc.
        val squash = FloatArray(n) { i ->
            kotlin.math.cos(Math.toRadians((abs(depthOffset(i)) * MAX_TILT_DEG).toDouble())).toFloat()
        }
        var projectedSum = 0f
        for (i in 0 until n) projectedSum += rel[i] * squash[i]

        // Loc pe margini pentru marirea de la focus: dala de la margine e deja
        // cea mai mare, iar cu inca FOCUS_SCALE iesea din ecran si era taiata.
        // Rezervam jumatatea de crestere (creste in jurul centrului) plus putin aer.
        val fullBase = n * tileWidth / projectedSum
        val edgeProjected = fullBase * rel[0] * squash[0]
        val pad = edgeProjected * (FOCUS_SCALE - 1f) / 2f + EDGE_AIR_DP * context.resources.displayMetrics.density
        val base = (n * tileWidth - 2 * pad) / projectedSum   // latimea nerotita a unei dale de margine

        var cursor = pad
        for (i in 0 until n) {
            val projected = base * rel[i] * squash[i]
            val wantedCenter = cursor + i * gap + projected / 2f
            val layoutCenter = i * (tileWidth + gap) + tileWidth / 2f
            arcScale[i] = base * rel[i] / tileWidth
            arcShift[i] = wantedCenter - layoutCenter
            cursor += projected
        }
    }

    private fun buildDots() {
        binding.tilePageDots.removeAllViews()
        indicator = PageIndicatorView(context, TileCatalog.pageCount).also {
            binding.tilePageDots.addView(it)
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
        if (focused) indicator?.setPosition(index, TileCatalog.PAGE_SIZE)
        val view = tileViews.getOrNull(index) ?: return

        // Dala focusată iese în față. Scara de bază vine din arc, deci o
        // înmulțim, nu o suprascriem — altfel dalele de la margini ar sări la
        // dimensiunea celor din centru când le focusezi.
        val base = arcScale[index % TileCatalog.PAGE_SIZE]
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

        val targetX = -(pageWidth.toFloat() * page)
        host.setHomeParallax(pageFraction(), Services.prefs.animationsEnabled)
        if (Services.prefs.animationsEnabled) {
            binding.tileStrip.animate()
                .translationX(targetX)
                .setDuration(PAGE_ANIM_MS)
                .start()
        } else {
            binding.tileStrip.translationX = targetX
        }
    }

    private fun pageFraction(): Float =
        if (TileCatalog.pageCount <= 1) 0f else currentPage / (TileCatalog.pageCount - 1f)

    // ------------------------------------------------------------ dale vii

    /**
     * Media, Bluetooth și Navigație spun ce se întâmplă fără să fie deschise:
     * piesa care cântă (cu barele animate cât rulează), telefonul conectat,
     * următoarea manevră. Fără nimic de spus, dala rămâne doar cu iconița.
     */
    private fun renderLive() {
        if (!built) return
        val media = Services.media.snapshot.value
        setLive("media", media.title.takeIf { media.hasSession })
        tileBindings["media"]?.tileEq?.let {
            it.visibility = if (media.hasSession) View.VISIBLE else View.GONE
            it.playing = media.isPlaying
        }
        setLive("bt", BluetoothPresence.deviceName.value)
        val nav = NavNotificationRelay.guidance.value
        setLive("nav", if (nav.isEmpty) null else nav.primary?.takeIf { it.isNotBlank() } ?: nav.secondary)
    }

    private fun setLive(id: String, text: String?) {
        val b = tileBindings[id] ?: return
        val show = !text.isNullOrBlank()
        b.tileLive.text = text.orEmpty()
        b.tileLive.visibility = if (show) View.VISIBLE else View.GONE
        b.tileIcon.translationY = if (show) -liveIconShift else 0f
    }

    // --------------------------------------------------------- mini-player

    private fun setupMiniPlayer() {
        binding.miniCover.clipToOutline = true
        binding.miniPlay.setOnClickListener { Services.media.playPause() }
        binding.miniPlayer.setOnClickListener { host.push(MediaScreen(host)) }
    }

    private fun renderMedia() {
        val media = Services.media.snapshot.value
        renderLive()
        val mini = binding.miniPlayer
        if (!media.hasSession || media.title.isNullOrBlank()) {
            mini.visibility = View.GONE
            binding.miniEq.playing = false
            return
        }
        mini.visibility = View.VISIBLE
        val art = media.artwork
        if (art != null) binding.miniCover.setImageBitmap(art)
        else binding.miniCover.setImageResource(R.drawable.media_cover_default)
        binding.miniTitle.text = media.title
        binding.miniArtist.text = media.artist.orEmpty()
        binding.miniArtist.visibility = if (media.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.miniEq.playing = media.isPlaying
        binding.miniPlay.setImageResource(
            if (media.isPlaying) R.drawable.ic_transport_pause else R.drawable.ic_transport_play
        )
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

        /**
         * Cat de adanc e semicercul: dala din mijloc iese cu atat mai mica decat
         * cele de la margini (inainte de reimpartirea pe latime).
         */
        const val ARC_DEPTH = 0.15f

        /** Aer intre dala de la margine (marita la focus) si marginea ecranului. */
        const val EDGE_AIR_DP = 6f

        /** Cât iese în față dala focusată. */
        const val FOCUS_SCALE = 1.07f

        /**
         * Distanța camerei, ca multiplu de lățime de dală. Sub ~6 perspectiva
         * devine de fisheye; peste ~12 rotația nu se mai vede deloc.
         */
        const val CAMERA_DISTANCE_FACTOR = 8f

        /** Proportii fata de latimea dalei, ca sa nu depinda de densitate. */
        const val LABEL_HEIGHT_RATIO = 0.20f
        const val LABEL_TEXT_RATIO = 0.11f
        const val ICON_RATIO = 0.44f
        const val SHADOW_HEIGHT_RATIO = 0.40f

        /** Textul viu din dală și barele de egalizator, tot față de latura dalei. */
        const val LIVE_TEXT_RATIO = 0.075f
        const val LIVE_INSET_RATIO = 0.09f
        const val LIVE_BOTTOM_RATIO = 0.10f
        const val LIVE_ICON_SHIFT_RATIO = 0.07f
        const val EQ_RATIO = 0.12f
    }
}

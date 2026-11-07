package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ro.e92.launcher.R
import ro.e92.launcher.databinding.ItemSettingRowBinding
import ro.e92.launcher.databinding.ItemSplitNavBinding
import ro.e92.launcher.databinding.ScreenSplitBinding
import ro.e92.launcher.focus.FocusTarget
import ro.e92.launcher.ui.Screen
import ro.e92.launcher.ui.ScreenHost

/** O categorie din coloana din stânga. */
data class NavEntry(val id: String, val title: String)

/**
 * Un rând din panoul de detaliu.
 *
 * [onOption] e acțiunea butonului OPTION al controller-ului pe rândul selectat —
 * de obicei „șterge / dezleagă". Nu există meniu contextual desenat: la iDrive
 * OPTION face direct lucrul evident pentru rândul curent.
 */
data class DetailRow(
    val id: String,
    val title: String,
    val value: String = "",
    val onActivate: (() -> Unit)? = null,
    val onOption: (() -> Unit)? = null,
    /** Mic titlu de grup în listă: nu se selectează, rotița trece peste el. */
    val header: Boolean = false
)

/**
 * Ecranul split BMW: categorii în stânga, conținutul categoriei în dreapta.
 *
 * Modelul de interacțiune e cel din mașină și e implementat integral prin
 * [ro.e92.launcher.focus.FocusEngine], fără focus nativ Android:
 *
 *  - rotița plimbă focusul prin categorii;
 *  - TILT_RIGHT intră în lista de detaliu, unde rotița derulează rândurile
 *    (target-ul de detaliu consumă rotația prin `onRotate` cât timp mai are unde
 *    merge, apoi o lasă să iasă — vezi [FocusTarget]);
 *  - TILT_LEFT se întoarce în categoria curentă.
 *
 * Schimbarea categoriei NU mută focusul în dreapta: la iDrive vezi întâi ce
 * conține categoria și abia apoi intri în ea.
 */
abstract class SplitScreen(host: ScreenHost) : Screen(host) {

    protected lateinit var binding: ScreenSplitBinding

    /** Titlul coloanei din stânga. */
    protected abstract val navHeader: String

    protected abstract fun navEntries(): List<NavEntry>

    /**
     * Categoria deschisa la intrare. Null = prima din lista.
     *
     * Exista pentru ca acelasi ecran de Setari e tinta a trei randuri diferite
     * din meniul cu rotita, iar fiecare trebuie sa ajunga direct unde promite -
     * nu in „Assigned apps" de fiecare data, urmat de o cautare.
     */
    protected open val initialNavId: String? = null

    /**
     * Doar categoria [initialNavId], pe toată lățimea, fără coloana din stânga: fiecare
     * rând din meniul cu rotița deschide meniul lui, nu toate setările laolaltă.
     */
    protected open val singleSection: Boolean = false

    protected abstract fun detailRows(navId: String): List<DetailRow>

    protected open fun detailTitle(navId: String): String = ""

    protected open fun detailHint(navId: String): String = ""

    /** Categoria selectată. Supraviețuiește unui [reloadDetail]. */
    protected var selectedNavId: String = ""
        private set

    private lateinit var adapter: DetailAdapter
    private val navRows = ArrayList<View>(4)
    private var entries: List<NavEntry> = emptyList()

    /**
     * Ecranul se construieste O SINGURA DATA.
     *
     * [onShow] se reapeleaza de fiecare data cand ecranul redevine vizibil -
     * la revenirea din altul, sau dupa ce se stinge un overlay peste el. Fara
     * garda, buildNav() ar rula din nou si ar readuce categoria la cea initiala:
     * deschideai „Launcher", trecea un salut de mod de condus peste ecran, si te
     * trezeai inapoi in „Assigned apps".
     *
     * Subclasele isi pornesc in continuare observatorii la fiecare onShow -
     * garda opreste doar reconstructia, nu si restul.
     */
    private var built = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View =
        ScreenSplitBinding.inflate(inflater, container, false).also { binding = it }.root

    override fun onShow() {
        if (built) return
        built = true

        binding.navHeader.text = navHeader

        adapter = DetailAdapter()
        binding.detailList.apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            itemAnimator = null // highlight-ul de selecție trebuie să fie instant
            adapter = this@SplitScreen.adapter
        }

        buildNav()
        if (singleSection) {
            binding.navBackground.visibility = View.GONE
            binding.navHeader.visibility = View.GONE
            binding.navList.visibility = View.GONE
            binding.splitDivider.visibility = View.GONE
            binding.gSplit.setGuidelinePercent(SINGLE_START)
        }
    }

    override fun onDestroy() {
        binding.detailList.adapter = null
    }

    // --------------------------------------------------------------- navigație

    private fun buildNav() {
        entries = navEntries()
        binding.navList.removeAllViews()
        navRows.clear()

        val inflater = LayoutInflater.from(context)
        for (entry in entries) {
            val row = ItemSplitNavBinding.inflate(inflater, binding.navList, false)
            row.navTitle.text = entry.title
            row.navTitle.setOnClickListener {
                selectNav(entry.id)
                host.rebuildFocus(navFocusId(entry.id))
            }
            binding.navList.addView(row.root)
            navRows.add(row.root)
        }

        // O categorie e selectată din start: un panou de detaliu gol la intrarea
        // în ecran ar arăta ca un bug.
        val wanted = initialNavId?.takeIf { id -> entries.any { it.id == id } }
        (wanted ?: entries.firstOrNull()?.id)?.let { selectNav(it) }
    }

    /** Schimbă categoria și reîncarcă panoul din dreapta. */
    private fun selectNav(navId: String) {
        if (navId == selectedNavId) return
        selectedNavId = navId
        renderDetail(keepId = null)
    }

    private fun renderDetail(keepId: String?) {
        val navId = selectedNavId
        binding.detailTitle.text = detailTitle(navId)

        val hint = detailHint(navId)
        binding.detailHint.text = hint
        binding.detailHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE

        adapter.submit(detailRows(navId), keepId)
    }

    /**
     * Reconstruiește panoul din dreapta după ce o acțiune i-a schimbat conținutul
     * (o aplicație atribuită, o tastă învățată). [keepId] e rândul pe care trebuie
     * să rămână selecția — altfel utilizatorul ar pierde locul după fiecare
     * apăsare.
     */
    protected fun reloadDetail(keepId: String? = null) {
        if (!this::adapter.isInitialized) return
        renderDetail(keepId)
        // Focusul rămâne unde era (ScreenStack folosește focusedId ca implicit);
        // doar lista de target-uri se împrospătează.
        host.rebuildFocus()
    }

    // ------------------------------------------------------------------ focus

    override fun focusTargets(): List<FocusTarget> {
        if (!this::adapter.isInitialized) return emptyList()

        val targets = ArrayList<FocusTarget>(entries.size + 1)

        if (!singleSection) entries.forEachIndexed { index, entry ->
            val view = navRows.getOrNull(index) ?: return@forEachIndexed
            targets.add(
                FocusTarget(
                    id = navFocusId(entry.id),
                    view = view,
                    onActivate = {
                        // Apăsarea unei categorii deja selectate intră în detaliu:
                        // e scurtătura naturală, altfel ar fi un no-op.
                        if (entry.id == selectedNavId) host.rebuildFocus(DETAIL_ID)
                        else selectAndFocus(entry.id)
                    },
                    onFocus = { focused -> if (focused) selectNav(entry.id) },
                    up = entries.getOrNull(index - 1)?.let { navFocusId(it.id) },
                    down = entries.getOrNull(index + 1)?.let { navFocusId(it.id) },
                    right = DETAIL_ID
                )
            )
        }

        targets.add(
            FocusTarget(
                id = DETAIL_ID,
                view = binding.detailList,
                onActivate = { adapter.activateSelected() },
                onOption = { adapter.optionSelected() },
                // Rândul selectat se aprinde doar cât timp LISTA e focusată —
                // altfel ar arăta două selecții simultan. FocusEngine setează
                // isActivated pe view ÎNAINTE de acest callback, deci un simplu
                // rebind e suficient în ambele sensuri.
                onFocus = { adapter.notifyDataSetChanged() },
                onRotate = { steps ->
                    val moved = adapter.moveSelection(steps)
                    if (moved) binding.detailList.scrollToPosition(adapter.selectedIndex)
                    moved
                },
                left = if (singleSection) null else navFocusId(selectedNavId)
            )
        )

        return targets
    }

    private fun selectAndFocus(navId: String) {
        selectNav(navId)
        host.rebuildFocus(navFocusId(navId))
    }

    private fun navFocusId(navId: String) = "nav_$navId"

    // ----------------------------------------------------------------- adapter

    /**
     * Selecția e ținută aici, nu în view-uri: view-urile se reciclează, indexul
     * selectat nu. Același model ca [RowAdapter] și [AppGridAdapter].
     */
    private inner class DetailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class VH(val binding: ItemSettingRowBinding) :
            RecyclerView.ViewHolder(binding.root)

        inner class HeaderVH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

        private var rows: List<DetailRow> = emptyList()

        var selectedIndex: Int = 0
            private set

        fun submit(newRows: List<DetailRow>, keepId: String?) {
            val wantedId = keepId ?: rows.getOrNull(selectedIndex)?.id
            rows = newRows
            selectedIndex = newRows
                .indexOfFirst { it.id == wantedId && !it.header }
                .takeIf { it >= 0 }
                ?: newRows.indexOfFirst { !it.header }.coerceAtLeast(0)
            notifyDataSetChanged()
        }

        fun selected(): DetailRow? = rows.getOrNull(selectedIndex)

        /**
         * Fără wrap, iar la capăt rotația e consumată: rămâi în meniul deschis până
         * apeși BACK. Înainte trecea singură în categoria următoare, la ultimul rând.
         */
        fun moveSelection(delta: Int): Boolean {
            if (rows.isEmpty()) return false
            var next = (selectedIndex + delta).coerceIn(0, rows.size - 1)
            // Peste titlurile de grup; dacă după titlu nu mai e nimic, rămâi pe loc.
            val step = if (delta > 0) 1 else -1
            while (rows[next].header) {
                next += step
                if (next !in rows.indices) return true
            }
            if (next == selectedIndex) return true
            val old = selectedIndex
            selectedIndex = next
            notifyItemChanged(old)
            notifyItemChanged(next)
            return true
        }

        fun activateSelected() {
            selected()?.onActivate?.invoke()
        }

        fun optionSelected() {
            selected()?.onOption?.invoke()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position].header) TYPE_HEADER else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val res = parent.context.resources
                // Arată ca titlul secțiunii de sus (detailTitle), doar mai jos în listă.
                val text = android.widget.TextView(parent.context, null, 0, R.style.Text_Label).apply {
                    setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                    val top = (HEADER_TOP_DP * res.displayMetrics.density).toInt()
                    val bottom = (HEADER_BOTTOM_DP * res.displayMetrics.density).toInt()
                    setPadding(res.getDimensionPixelSize(R.dimen.row_padding_h), top, 0, bottom)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                return HeaderVH(text)
            }
            return VH(ItemSettingRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (vh is HeaderVH) {
                vh.text.text = row.title.uppercase()
                return
            }
            val holder = vh as VH
            holder.binding.rowTitle.text = row.title
            holder.binding.rowValue.text = row.value
            // Rândul e evidențiat doar dacă lista e și cea focusată: altfel ar
            // arăta două selecții aprinse simultan pe ecran.
            holder.itemView.isActivated =
                position == selectedIndex && binding.detailList.isActivated
            holder.itemView.setOnClickListener {
                val old = selectedIndex
                selectedIndex = position
                notifyItemChanged(old)
                notifyItemChanged(position)
                host.rebuildFocus(DETAIL_ID)
                row.onActivate?.invoke()
            }
        }
    }

    private companion object {
        const val DETAIL_ID = "detail"
        const val SINGLE_START = 0.04f
        const val TYPE_ROW = 0
        const val TYPE_HEADER = 1
        const val HEADER_TOP_DP = 18f
        const val HEADER_BOTTOM_DP = 6f
    }
}

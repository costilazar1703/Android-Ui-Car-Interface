package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ro.e92.launcher.databinding.ItemMediaRowBinding

data class Row(val id: String, val title: String, val subtitle: String = "")

/**
 * Listă stil iDrive: rânduri înalte, separator subțire, rândul selectat evidențiat
 * pe toată lățimea. Selecția e ținută în adapter, ca la [AppGridAdapter].
 */
class RowAdapter(
    private val onActivate: (Row) -> Unit
) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(val binding: ItemMediaRowBinding) : RecyclerView.ViewHolder(binding.root)

    private var rows: List<Row> = emptyList()

    var selectedIndex: Int = 0
        private set

    fun submit(newRows: List<Row>) {
        val previousId = rows.getOrNull(selectedIndex)?.id
        rows = newRows
        // Păstrăm selecția pe același element dacă a supraviețuit reîmprospătării.
        selectedIndex = newRows.indexOfFirst { it.id == previousId }.coerceAtLeast(0)
        notifyDataSetChanged()
    }

    fun selected(): Row? = rows.getOrNull(selectedIndex)

    /**
     * Fără wrap: la capătul listei rotația NU e consumată, iar [FocusEngine] mută
     * focusul mai departe pe ecran. Așa se iese dintr-o listă fără BACK.
     */
    fun moveSelection(delta: Int): Boolean {
        if (rows.isEmpty()) return false
        val next = selectedIndex + delta
        if (next < 0 || next >= rows.size) return false
        val old = selectedIndex
        selectedIndex = next
        notifyItemChanged(old)
        notifyItemChanged(next)
        return true
    }

    fun activateSelected() {
        selected()?.let(onActivate)
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemMediaRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.binding.rowTitle.text = row.title
        holder.binding.rowSubtitle.text = row.subtitle
        holder.itemView.isActivated = position == selectedIndex
        holder.itemView.setOnClickListener { onActivate(row) }
    }
}

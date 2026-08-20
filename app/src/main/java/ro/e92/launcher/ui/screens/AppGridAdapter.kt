package ro.e92.launcher.ui.screens

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ro.e92.launcher.core.AppEntry
import ro.e92.launcher.core.AppRepository
import ro.e92.launcher.databinding.ItemAppBinding

/**
 * Selecția e o proprietate a adapterului, nu a View-urilor: view-urile se
 * reciclează, indexul selectat nu. Actualizarea se face cu notifyItemChanged
 * pe cele două poziții implicate — niciodată notifyDataSetChanged.
 */
class AppGridAdapter(
    private val repo: AppRepository,
    private val onActivate: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppGridAdapter.VH>() {

    class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<AppEntry> = emptyList()

    var selectedIndex: Int = 0
        private set

    var onSelectionChanged: ((AppEntry?) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    fun submit(newItems: List<AppEntry>) {
        items = newItems
        if (selectedIndex >= items.size) selectedIndex = 0
        notifyDataSetChanged() // singurul loc: lista s-a schimbat integral
        onSelectionChanged?.invoke(selectedItem())
    }

    fun selectedItem(): AppEntry? = items.getOrNull(selectedIndex)

    /**
     * @return true dacă selecția s-a mutat. Cu wrap: în app drawer rotița nu
     *         trebuie să "scape" din listă, e singurul lucru de pe ecran.
     */
    fun moveSelection(delta: Int): Boolean {
        if (items.isEmpty()) return false
        val old = selectedIndex
        var next = (old + delta) % items.size
        if (next < 0) next += items.size
        if (next == old) return true

        selectedIndex = next
        notifyItemChanged(old)
        notifyItemChanged(next)
        onSelectionChanged?.invoke(selectedItem())
        return true
    }

    fun setSelection(index: Int) {
        if (index !in items.indices || index == selectedIndex) return
        val old = selectedIndex
        selectedIndex = index
        notifyItemChanged(old)
        notifyItemChanged(index)
        onSelectionChanged?.invoke(selectedItem())
    }

    fun activateSelected() {
        selectedItem()?.let(onActivate)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.label.text = entry.label
        holder.binding.icon.setImageDrawable(repo.icon(entry))
        holder.itemView.isActivated = position == selectedIndex
        // Touch-ul funcționează pe aceleași elemente, dar nu e presupus.
        holder.itemView.setOnClickListener {
            setSelection(holder.bindingAdapterPosition)
            onActivate(entry)
        }
    }
}

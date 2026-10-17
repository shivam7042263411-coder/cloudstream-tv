package com.lagradost.cloudstream3.ui.kumo.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.databinding.KumoCatalogRowBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState

/**
 * Vertical browse rows. Each row owns a horizontal poster list.
 * Pagination and removal are reported back to the fragment via callbacks
 * so all fetching stays in one place (KumoCatalogFragment).
 */
class KumoCatalogRowAdapter(
    private val onCardClick: (StremioMeta) -> Unit,
    private val onLoadMore: (StremioRow) -> Unit,
    private val onTitleLongClick: (StremioRow) -> Unit,
) : NoStateAdapter<StremioRow>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.key == b.key
}, contentSame = { a, b ->
    a == b
})) {
    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoCatalogRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: StremioRow, position: Int) {
        val binding = holder.view as? KumoCatalogRowBinding ?: return
        binding.apply {
            kumoCatalogRowTitle.text = item.def.name
            kumoCatalogRowCount.text = if (item.items.isEmpty()) "" else "${item.items.size}"

            val cards = KumoCatalogCardAdapter(onCardClick)
            kumoCatalogRowList.layoutManager =
                LinearLayoutManager(root.context, LinearLayoutManager.HORIZONTAL, false)
            kumoCatalogRowList.adapter = cards
            cards.submitList(item.items)

            kumoCatalogRowList.clearOnScrollListeners()
            kumoCatalogRowList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (!recyclerView.canScrollHorizontally(1)) {
                        onLoadMore(item)
                    }
                }
            })

            kumoCatalogRowTitle.setOnLongClickListener {
                onTitleLongClick(item)
                true
            }
        }
    }
}

package com.lagradost.cloudstream3.ui.kumo.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.KumoCatalogRowBinding
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.home.KumoHomePosterAdapter
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD

/** Nuvio-style library rows over the existing LibraryViewModel pages. */
class KumoLibraryPageAdapter(
    private val onCardClick: (SearchResponse) -> Unit,
) : NoStateAdapter<SyncAPI.Page>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.title == b.title
}, contentSame = { a, b ->
    a.items.size == b.items.size
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

    override fun onBindContent(holder: ViewHolderState<Any>, item: SyncAPI.Page, position: Int) {
        val binding = holder.view as? KumoCatalogRowBinding ?: return
        binding.apply {
            kumoCatalogRowTitle.text = item.title.asString(root.context)
            kumoCatalogRowCount.text = if (item.items.isEmpty()) "" else "${item.items.size}"
            val cards = KumoHomePosterAdapter { callback ->
                if (callback.action == SEARCH_ACTION_LOAD) {
                    onCardClick(callback.card)
                }
            }
            kumoCatalogRowList.layoutManager =
                LinearLayoutManager(root.context, LinearLayoutManager.HORIZONTAL, false)
            kumoCatalogRowList.adapter = cards
            cards.submitList(item.items)
            kumoCatalogRowTitle.setOnLongClickListener { false }
        }
    }
}

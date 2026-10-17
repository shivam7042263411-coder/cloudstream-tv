package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.KumoCardBinding
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

/**
 * KUMO premium card adapter — larger, cleaner poster cards.
 * Uses kumo_card.xml (148x212, 14dp radius, #14141C background) vs CloudStream's 114x180.
 * Delegates binding to existing SearchResultBuilder (keeps click → loadSearchResult → ResultFragment).
 */
open class KumoCardAdapter(
    id: Int,
    var nextFocusUp: Int? = null,
    var nextFocusDown: Int? = null,
    var clickCallback: (SearchClickCallback) -> Unit,
) : BaseAdapter<SearchResponse, Boolean>(
    id,
    diffCallback = BaseDiffCallback(
        itemSame = { a, b -> a.url == b.url && a.name == b.name },
        contentSame = { a, b -> a == b }
    )
) {
    companion object {
        val sharedPool = newSharedPool { setMaxRecycledViews(CONTENT, 24) }
    }

    var hasNext: Boolean = false

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Boolean> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = KumoCardBinding.inflate(inflater, parent, false)
        return ViewHolderState(binding)
    }

    override fun onBindContent(holder: ViewHolderState<Boolean>, item: SearchResponse, position: Int) {
        // Reuse CloudStream's proven binding (poster, title, dub/sub, rating, progress)
        // but on premium KUMO card container (larger, rounded, dark).
        SearchResultBuilder.bind(
            clickCallback = clickCallback,
            card = item,
            position = position,
            itemView = holder.itemView,
            nextFocusUp = nextFocusUp,
            nextFocusDown = nextFocusDown
        )

        // TV focus polish — ensure premium card is focusable
        if (isLayout(TV)) {
            holder.itemView.isFocusable = true
            holder.itemView.isFocusableInTouchMode = true
        }

        holder.itemView.tag = position
    }

    override fun onClearView(holder: ViewHolderState<Boolean>) {
        clearImage(holder.view.root.findViewById(R.id.imageView))
    }
}

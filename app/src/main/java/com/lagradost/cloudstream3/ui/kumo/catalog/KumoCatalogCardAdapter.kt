package com.lagradost.cloudstream3.ui.kumo.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.databinding.KumoCardBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Poster cards for Stremio catalog items, reusing the KUMO kumo_card visuals.
 * Items are StremioMeta (browse-only) — taps resolve through the provider
 * via KumoCatalogResolve, never through fabricated provider URLs.
 */
class KumoCatalogCardAdapter(
    private val onClick: (StremioMeta) -> Unit,
) : NoStateAdapter<StremioMeta>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.stableKey() == b.stableKey()
}, contentSame = { a, b ->
    a == b
})) {
    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: StremioMeta, position: Int) {
        val binding = holder.view as? KumoCardBinding ?: return
        binding.apply {
            imageView.loadImage(item.poster ?: item.background)
            imageText.text = item.name
            val year = item.year?.toString().orEmpty()
            if (year.isBlank()) {
                episodeText.isGone = true
            } else {
                episodeText.isVisible = true
                episodeText.text = year
            }
            textQuality.isGone = true
            textIsDub.isGone = true
            textIsSub.isGone = true
            textRating.isGone = true
            textFlag.isGone = true
            root.setOnClickListener {
                onClick(item)
            }
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        (holder.view as? KumoCardBinding)?.let {
            clearImage(it.imageView)
        }
        super.onClearView(holder)
    }
}

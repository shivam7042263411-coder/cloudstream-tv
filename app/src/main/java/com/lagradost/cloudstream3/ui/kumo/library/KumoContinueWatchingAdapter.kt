package com.lagradost.cloudstream3.ui.kumo.library

import android.view.LayoutInflater
import android.view.ViewGroup
import com.lagradost.cloudstream3.databinding.KumoCwCardBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.kumo.library.KumoLibraryFragment.ContinueItem
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/** Continue Watching cards: poster + S/E label + progress. Tap opens parent. */
class KumoContinueWatchingAdapter(
    private val onClick: (ContinueItem) -> Unit,
) : NoStateAdapter<ContinueItem>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.parent.url == b.parent.url && a.episode == b.episode && a.season == b.season
}, contentSame = { a, b ->
    a == b
})) {
    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoCwCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ContinueItem, position: Int) {
        val binding = holder.view as? KumoCwCardBinding ?: return
        binding.apply {
            kumoCwPoster.loadImage(item.parent.posterUrl, item.parent.posterHeaders)
            kumoCwTitle.text = item.parent.name
            val label = if (item.season != null) {
                "S${item.season} E${item.episode ?: 0}"
            } else {
                "E${item.episode ?: 0}"
            }
            kumoCwEpisode.text = label
            kumoCwProgress.max = 100
            kumoCwProgress.progress = (item.progress01 * 100).toInt().coerceIn(0, 100)
            root.setOnClickListener {
                onClick(item)
            }
        }
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        (holder.view as? KumoCwCardBinding)?.let {
            clearImage(it.kumoCwPoster)
        }
        super.onClearView(holder)
    }
}

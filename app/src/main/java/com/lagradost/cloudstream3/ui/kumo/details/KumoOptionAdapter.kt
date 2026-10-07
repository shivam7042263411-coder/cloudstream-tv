package com.lagradost.cloudstream3.ui.kumo.details

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.lagradost.cloudstream3.databinding.KumoOptionChipBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState

/** Single chip row item for seasons / ranges / dubs / genres on the KUMO Details page. */
data class KumoOption(
    val label: String,
    val selected: Boolean,
    val id: Int,
)

class KumoOptionAdapter(
    private val onClick: (Int) -> Unit,
) : NoStateAdapter<KumoOption>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.id == b.id
}, contentSame = { a, b ->
    a == b
})) {
    init {
        // Stable IDs: RecyclerView tracks the focused chip across resubmits
        // instead of recycling it away (the season focus jump-back bug).
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id.toLong()
    override fun onCreateCustomContent(parent: ViewGroup, viewType: Int): ViewHolderState<Any> {
        return ViewHolderState(
            KumoOptionChipBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: KumoOption, position: Int) {
        val binding = holder.view as? KumoOptionChipBinding ?: return
        // TV D-pad: chips must be focusable (no effect on touch). Focused chips
        // render like selected ones so focus is always visible.
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = false
        // No chip-level up/down exits: on TV, CommonActivity.dispatchKeyEvent
        // resolves vertical moves from the focused chip's own nextFocus IDs,
        // so any static ID here permanently bypasses the conditional row
        // chain (Seasons → Dubs → Ranges → Episodes) owned by the row
        // LayoutManagers via repairFocusChain. Left unset, the key falls
        // through to the LayoutManager, which lands the correct next row.
        binding.root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            renderOption(binding, item, hasFocus)
        }
        renderOption(binding, item, binding.root.hasFocus())
        binding.root.setOnClickListener {
            onClick(item.id)
        }
    }

    private fun renderOption(binding: KumoOptionChipBinding, item: KumoOption, focused: Boolean) {
        binding.kumoChipText.text = item.label
        if (item.selected || focused) {
            // Ditto reference: whole pill white, text black. Done via tint
            // + text color (invalidate-only, no relayout): swapping the
            // background drawable here triggers a layout pass mid-focus-search,
            // which recycles the incoming chip and drops rapid-tap focus.
            binding.kumoChipText.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            binding.kumoChipText.setTextColor(0xFF14141C.toInt())
        } else {
            binding.kumoChipText.backgroundTintList = null
            binding.kumoChipText.setTextColor(0xFFC9CDD3.toInt())
        }
    }
}

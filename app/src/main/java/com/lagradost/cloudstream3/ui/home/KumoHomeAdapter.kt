package com.lagradost.cloudstream3.ui.home

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.KumoHomeRowBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable

/**
 * KUMO Home row adapter — replaces ParentItemAdapter for KUMO.
 * Renders each HomePageList (catalog section) as a horizontal row.
 * Uses real HomeViewModel.ExpandableHomepageList produced by HomeViewModel + APIRepository + MainAPI.
 * Click handling → SearchHelper → loadSearchResult → ResultFragment (preserved).
 */
class KumoHomeAdapter(
    id: Int = "KumoHomeAdapter".hashCode(),
    private var clickCallback: (SearchClickCallback) -> Unit,
    private val moreInfoCallback: (HomeViewModel.ExpandableHomepageList) -> Unit,
    private val expandCallback: ((String) -> Unit)? = null,
) : BaseAdapter<HomeViewModel.ExpandableHomepageList, Bundle>(
    id,
    diffCallback = BaseDiffCallback(
        itemSame = { a, b -> a.list.name == b.list.name },
        contentSame = { a, b -> a.list.list == b.list.list }
    )
) {
    companion object {
        val sharedPool = newSharedPool { setMaxRecycledViews(CONTENT, 4) }
    }

    data class KumoRowHolder(val binding: ViewBinding) : ViewHolderState<Bundle>(binding) {
        override fun save(): Bundle = Bundle().apply {
            val recyclerView = (binding as? KumoHomeRowBinding)?.kumoRowRecycler
            putParcelable("value", recyclerView?.layoutManager?.onSaveInstanceState())
            (recyclerView?.adapter as? BaseAdapter<*, *>)?.save(recyclerView)
        }

        override fun restore(state: Bundle) {
            (binding as? KumoHomeRowBinding)?.kumoRowRecycler?.layoutManager?.onRestoreInstanceState(
                state.getParcelableCompat("value")
            )
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelable(key, T::class.java)
            } else {
                @Suppress("DEPRECATION") getParcelable(key)
            }
        }
    }

    override fun submitList(list: Collection<HomeViewModel.ExpandableHomepageList>?, commitCallback: Runnable?) {
        // Keep provider order but push empty sections last (same as CloudStream)
        super.submitList(list?.sortedBy { it.list.list.isEmpty() }, commitCallback)
    }

    override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val binding = holder.view
        if (binding !is KumoHomeRowBinding) return
        (binding.kumoRowRecycler.adapter as? KumoHomePosterAdapter)?.submitList(item.list.list)
    }

    override fun onBindContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val binding = holder.view
        if (binding !is KumoHomeRowBinding) return
        val info = item.list

        binding.apply {
            // Title from real HomePageList.name — never hard-coded
            kumoRowTitle.text = info.name

            // "View all" only on phone (TV uses focus + bottom sheet is triggered via title click)
            if (isLayout(PHONE)) {
                kumoRowMore.setOnClickListener { moreInfoCallback.invoke(item) }
                kumoRowMore.isClickable = true
            } else {
                kumoRowMore.setOnClickListener(null)
                kumoRowMore.isClickable = false
                // On TV, title itself is focusable to open expanded sheet
                kumoRowTitle.setOnClickListener { moreInfoCallback.invoke(item) }
                kumoRowTitle.isFocusable = true
                kumoRowTitle.isFocusableInTouchMode = true
            }

            val currentAdapter = kumoRowRecycler.adapter as? KumoHomePosterAdapter
            if (currentAdapter == null) {
                kumoRowRecycler.adapter = KumoHomePosterAdapter(
                    clickCallback = clickCallback,
                ).apply {
                    submitList(item.list.list)
                }
            } else {
                currentAdapter.apply {
                    submitIncomparableList(item.list.list)
                }
            }

            kumoRowRecycler.setLinearListLayout(
                isHorizontal = true,
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF,
            )

            // Pagination: when row is fully scrolled and provider indicates hasNext, expand
            kumoRowRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = info.name
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val count = recyclerView.adapter?.itemCount ?: 0
                    if (!recyclerView.isRecyclerScrollable() && item.hasNext && expandCount != count) {
                        expandCount = count
                        expandCallback?.invoke(name)
                    }
                }
            })
        }
    }

    override fun onCreateContent(parent: ViewGroup): KumoRowHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = try {
            KumoHomeRowBinding.inflate(inflater, parent, false)
        } catch (t: Throwable) {
            logError(t)
            KumoHomeRowBinding.inflate(inflater, parent, false)
        }
        return KumoRowHolder(binding)
    }
}

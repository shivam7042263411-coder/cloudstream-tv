package com.lagradost.cloudstream3.ui.kumo.catalog

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared "add catalog addon URL" dialog (Browse screen + Settings).
 * URL is validated and stored locally via StremioCatalogStore.
 */
object KumoCatalogAddonDialog {
    fun show(context: Context, onAdded: () -> Unit) {
        val activity = context as? FragmentActivity ?: return
        val density = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            hint = "https://…/manifest.json"
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val container = FrameLayout(context).apply {
            val pad = (16 * density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.add_catalog_addon)
            .setView(container)
            .setPositiveButton(R.string.add_catalog_add) { _, _ ->
                val url = input.text?.toString().orEmpty()
                ioSafe {
                    val ok = StremioCatalogStore.addAddon(activity, url)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            onAdded()
                        } else {
                            com.lagradost.cloudstream3.CommonActivity.showToast(
                                R.string.invalid_url,
                                android.widget.Toast.LENGTH_SHORT
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

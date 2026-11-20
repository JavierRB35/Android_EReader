package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import kotlinx.coroutines.launch

/**
 * Shared persistent navigation for library and knowledge.
 * Import is an action (SAF picker), never a fabricated fourth destination.
 */
internal object PremiumBottomBar {
    fun bind(activity: AppCompatActivity, root: View, selected: Int, onImport: () -> Unit) {
        root.findViewById<View>(R.id.navLibrary).setOnClickListener {
            if (activity !is MainActivity) {
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        root.findViewById<View>(R.id.navRead).setOnClickListener {
            activity.lifecycleScope.launch {
                val recent = AppDatabase.get(activity).bookDao().lastRead()
                if (recent == null) {
                    Toast.makeText(activity, R.string.no_recent_book, Toast.LENGTH_LONG).show()
                } else {
                    ReaderRouter.open(activity, recent.id)
                }
            }
        }
        root.findViewById<View>(R.id.btnImport).setOnClickListener { onImport() }
        root.findViewById<View>(R.id.navKnowledge).setOnClickListener {
            if (activity !is KnowledgeActivity) KnowledgeActivity.start(activity)
        }
        for (id in intArrayOf(R.id.navLibrary, R.id.navRead, R.id.navKnowledge)) {
            val item = root.findViewById<LinearLayout>(id)
            val label = item.getChildAt(1) as TextView
            label.setTextColor(ContextCompat.getColor(activity,
                if (id == selected) R.color.bia_accent else R.color.bia_text_secondary))
            item.isSelected = id == selected
            if (item.isSelected) item.setBackgroundResource(R.drawable.bg_premium_nav_selected)
        }
    }

    fun launchImportFromKnowledge(activity: AppCompatActivity) {
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .putExtra("bibliotecaia.premium_open_import", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

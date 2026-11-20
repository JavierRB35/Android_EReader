package com.javierrb35.bibliotecaia.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.javierrb35.bibliotecaia.R
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.CollectionEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Organizador de la biblioteca (§7.3–7.4): categorías con subcategorías, etiquetas y
 * colecciones. Eliminar un elemento nunca elimina libros; solo sus relaciones.
 */
class OrganizeActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.get(this) }

    private lateinit var categoriesList: LinearLayout
    private lateinit var tagsList: LinearLayout
    private lateinit var collectionsList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organize)

        categoriesList = findViewById(R.id.listCategories)
        tagsList = findViewById(R.id.listTags)
        collectionsList = findViewById(R.id.listCollections)

        findViewById<View>(R.id.btnAddCategory).setOnClickListener { promptNewCategory() }
        findViewById<View>(R.id.btnAddTag).setOnClickListener { promptNewTag() }
        findViewById<View>(R.id.btnAddCollection).setOnClickListener { promptNewCollection() }

        observeAll()
    }

    private fun observeAll() {
        lifecycleScope.launch {
            db.categoryDao().observeAll().collect { renderCategories(it) }
        }
        lifecycleScope.launch {
            db.tagDao().observeAll().collect { renderTags(it) }
        }
        lifecycleScope.launch {
            db.collectionDao().observeAll().collect { renderCollections(it) }
        }
    }

    // ---------- Categorías ----------

    private fun renderCategories(categories: List<CategoryEntity>) {
        categoriesList.removeAllViews()
        if (categories.isEmpty()) {
            categoriesList.addView(sectionEmptyView())
            return
        }
        categories.forEach { category ->
            val depth = depthOf(category, categories)
            val label = if (depth > 0) "   ".repeat(depth) + "└ " + category.name else category.name
            categoriesList.addView(rowView(label) { showCategoryOptions(category, categories) })
        }
    }

    private fun depthOf(category: CategoryEntity, all: List<CategoryEntity>): Int {
        var depth = 0
        var parentId = category.parentId
        val byId = all.associateBy { it.id }
        while (parentId != null && depth < 3) {
            depth++
            parentId = byId[parentId]?.parentId
        }
        return depth
    }

    private fun showCategoryOptions(category: CategoryEntity, all: List<CategoryEntity>) {
        val options = arrayOf(
            getString(R.string.organize_rename),
            getString(R.string.organize_move_up),
            getString(R.string.organize_move_down),
            getString(R.string.organize_convert_to_subcategory),
            getString(R.string.organize_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRename(category.name) { newName ->
                        lifecycleScope.launch {
                            db.categoryDao().update(category.copy(name = newName))
                        }
                    }
                    1 -> moveCategory(category, all, -1)
                    2 -> moveCategory(category, all, +1)
                    3 -> promptCategoryParent(category, all)
                    4 -> confirmDelete(category.name) {
                        lifecycleScope.launch {
                            db.categoryDao().deleteRefsForCategory(category.id)
                            db.categoryDao().deleteById(category.id)
                            // Las subcategorías pasan a raíz para no perderlas
                            all.filter { it.parentId == category.id }.forEach { child ->
                                db.categoryDao().update(child.copy(parentId = null))
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun moveCategory(category: CategoryEntity, all: List<CategoryEntity>, delta: Int) {
        lifecycleScope.launch {
            val ordered = all
            val index = ordered.indexOfFirst { it.id == category.id }
            val target = index + delta
            if (index < 0 || target < 0 || target >= ordered.size) return@launch
            val other = ordered[target]
            db.categoryDao().update(category.copy(sortOrder = other.sortOrder))
            db.categoryDao().update(other.copy(sortOrder = category.sortOrder))
        }
    }

    private fun promptNewCategory() {
        val input = EditText(this).apply { hint = getString(R.string.organize_category_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.organize_new_category)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val count = db.categoryDao().allOnce().size
                        db.categoryDao().insert(
                            CategoryEntity(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                parentId = null,
                                sortOrder = count
                            )
                        )
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun promptCategoryParent(category: CategoryEntity, all: List<CategoryEntity>) {
        val others = all.filter { it.id != category.id && it.parentId == null }
        val names = mutableListOf(getString(R.string.organize_no_parent))
        names.addAll(others.map { it.name })
        val current = others.indexOfFirst { it.id == category.parentId } + 1
        AlertDialog.Builder(this)
            .setTitle(R.string.organize_convert_to_subcategory)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                lifecycleScope.launch {
                    val parent = if (which == 0) null else others[which - 1].id
                    db.categoryDao().update(category.copy(parentId = parent))
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------- Etiquetas ----------

    private fun renderTags(tags: List<TagEntity>) {
        tagsList.removeAllViews()
        if (tags.isEmpty()) {
            tagsList.addView(sectionEmptyView())
            return
        }
        tags.forEach { tag ->
            tagsList.addView(rowView(tag.name) { showTagOptions(tag) })
        }
    }

    private fun showTagOptions(tag: TagEntity) {
        val options = arrayOf(getString(R.string.organize_rename), getString(R.string.organize_delete))
        AlertDialog.Builder(this)
            .setTitle(tag.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptRename(tag.name) { newName ->
                        lifecycleScope.launch { db.tagDao().update(tag.copy(name = newName)) }
                    }
                    1 -> confirmDelete(tag.name) {
                        lifecycleScope.launch {
                            db.tagDao().deleteRefsForTag(tag.id)
                            db.tagDao().deleteById(tag.id)
                        }
                    }
                }
            }
            .show()
    }

    private fun promptNewTag() {
        val input = EditText(this).apply { hint = getString(R.string.organize_tag_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.organize_new_tag)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.tagDao().insert(TagEntity(id = UUID.randomUUID().toString(), name = name))
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------- Colecciones ----------

    private fun renderCollections(collections: List<CollectionEntity>) {
        collectionsList.removeAllViews()
        if (collections.isEmpty()) {
            collectionsList.addView(sectionEmptyView())
            return
        }
        collections.forEach { collection ->
            collectionsList.addView(rowView(collection.name) { showCollectionOptions(collection) })
        }
    }

    private fun showCollectionOptions(collection: CollectionEntity) {
        val options = arrayOf(
            getString(R.string.organize_open),
            getString(R.string.organize_rename),
            getString(R.string.organize_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(collection.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra("collection_id", collection.id)
                            .putExtra("collection_name", collection.name)
                    )
                    1 -> promptRename(collection.name) { newName ->
                        lifecycleScope.launch { db.collectionDao().update(collection.copy(name = newName)) }
                    }
                    2 -> confirmDelete(collection.name) {
                        lifecycleScope.launch {
                            db.collectionDao().deleteRefsForCollection(collection.id)
                            db.collectionDao().deleteById(collection.id)
                        }
                    }
                }
            }
            .show()
    }

    private fun promptNewCollection() {
        val input = EditText(this).apply { hint = getString(R.string.organize_collection_name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.organize_new_collection)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        db.collectionDao().insert(
                            CollectionEntity(
                                id = UUID.randomUUID().toString(),
                                name = name,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------- Utilidades de diálogos y filas ----------

    private fun promptRename(current: String, onRename: (String) -> Unit) {
        val input = EditText(this).apply { setText(current) }
        AlertDialog.Builder(this)
            .setTitle(R.string.organize_rename)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != current) onRename(name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDelete(name: String, onDelete: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.organize_delete_confirm, name))
            .setPositiveButton(R.string.organize_delete) { _, _ -> onDelete() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun rowView(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            // Color explícito: el tema día heredaría texto negro invisible sobre el fondo OLED
            setTextColor(android.graphics.Color.parseColor("#E0D6CC"))
            setPadding(0, 28, 0, 28)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    private fun sectionEmptyView(): TextView =
        TextView(this).apply {
            text = getString(R.string.organize_empty)
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#A89C90"))
            setPadding(0, 16, 0, 16)
        }
}

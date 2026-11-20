package com.javierrb35.bibliotecaia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Base local Room (Prompt Maestro §15.4). Esquema versionado; migraciones explícitas
 * a partir de la v2 — nunca destructivas.
 */
@Database(
    entities = [
        BookEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        CollectionEntity::class,
        BookCategoryCrossRef::class,
        BookTagCrossRef::class,
        CollectionBookCrossRef::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao

    companion object {

        private const val DB_NAME = "biblioteca_ia.db"

        /** Categorías iniciales obligatorias (Prompt Maestro §7.3). */
        val DEFAULT_CATEGORIES = listOf(
            "Novela", "Aventura", "Ciencia ficción", "Fantasía", "Historia",
            "Filosofía", "Ciencia", "Tecnología", "Inteligencia artificial",
            "Economía", "Finanzas", "Empresa", "Psicología", "Biografías",
            "Formación", "Referencia", "Otros"
        )

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): AppDatabase =
            Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        seedDefaultCategories(db)
                    }
                })
                .build()

        /** Siembra las categorías iniciales en la primera creación (idempotente por diseño). */
        private fun seedDefaultCategories(db: SupportSQLiteDatabase) {
            DEFAULT_CATEGORIES.forEachIndexed { index, name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (id, name, parentId, sortOrder) VALUES (?, ?, NULL, ?)",
                    arrayOf(UUID.randomUUID().toString(), name, index)
                )
            }
        }
    }
}

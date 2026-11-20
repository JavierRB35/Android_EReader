package com.javierrb35.bibliotecaia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Base local Room (Prompt Maestro §15.4). Esquema versionado con migraciones explícitas
 * y no destructivas (§15.7).
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
        ReadingPositionEntity::class,
        AnnotationEntity::class,
        AnnotationVersionEntity::class,
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun readingDao(): ReadingDao

    abstract fun bookDao(): BookDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun refsDao(): RefsDao

    companion object {

        private const val DB_NAME = "biblioteca_ia.db"

        /** Categorías iniciales obligatorias (Prompt Maestro §7.3). */
        val DEFAULT_CATEGORIES = listOf(
            "Novela", "Aventura", "Ciencia ficción", "Fantasía", "Historia",
            "Filosofía", "Ciencia", "Tecnología", "Inteligencia artificial",
            "Economía", "Finanzas", "Empresa", "Psicología", "Biografías",
            "Formación", "Referencia", "Otros"
        )

        /** v1→v2: columna ortogonal `favorite` para el estante Favoritos (§7.1). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_positions (bookId TEXT NOT NULL PRIMARY KEY, locator TEXT NOT NULL, progress REAL NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS annotations (id TEXT NOT NULL PRIMARY KEY, bookId TEXT, locator TEXT, quote TEXT NOT NULL, body TEXT NOT NULL, kind TEXT NOT NULL, color INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations (bookId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS annotation_versions (id TEXT NOT NULL PRIMARY KEY, annotationId TEXT NOT NULL, body TEXT NOT NULL, color INTEGER NOT NULL, savedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_annotation_versions_annotationId ON annotation_versions (annotationId)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(appContext: Context): AppDatabase =
            Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

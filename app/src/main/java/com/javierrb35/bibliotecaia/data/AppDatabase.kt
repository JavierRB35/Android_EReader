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
        BookSynopsisEntity::class,
        SyncQueueEntry::class,
        DeletedBookEntity::class,
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun synopsisDao(): SynopsisDao

    abstract fun readingDao(): ReadingDao

    abstract fun bookDao(): BookDao
    abstract fun categoryDao(): CategoryDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun refsDao(): RefsDao
    abstract fun backupDao(): BackupDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {

        private const val DB_NAME = "biblioteca_ia.db"

        /** Categorías iniciales obligatorias (Prompt Maestro §7.3). */
        val DEFAULT_CATEGORIES = listOf(
            "Novela", "Aventura", "Ciencia ficción", "Fantasía", "Historia",
            "Filosofía", "Ciencia", "Tecnología", "Inteligencia artificial",
            "Economía", "Finanzas", "Empresa", "Psicología", "Biografías",
            "Formación", "Referencia", "Otros"
        )

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_books (contentHash TEXT NOT NULL PRIMARY KEY, deletedAt INTEGER NOT NULL)")
            }
        }

        /** v4→v5: saved synopsis, preserving every prior annotation and book. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_synopses (" +
                        "bookId TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, " +
                        "provider TEXT NOT NULL, generatedAt INTEGER NOT NULL, " +
                        "spoilerSafe INTEGER NOT NULL)"
                )
            }
        }

        /** v3→v4: cola local de cambios pendientes de sincronizar (Incremento 5-6). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_queue (" +
                        "entityType TEXT NOT NULL, entityId TEXT NOT NULL, " +
                        "operation TEXT NOT NULL, queuedAt INTEGER NOT NULL, " +
                        "attempts INTEGER NOT NULL DEFAULT 0, lastError TEXT, " +
                        "PRIMARY KEY(entityType, entityId))"
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

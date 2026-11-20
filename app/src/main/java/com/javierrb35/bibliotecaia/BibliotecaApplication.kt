package com.javierrb35.bibliotecaia

import android.app.Application
import com.javierrb35.bibliotecaia.updates.UpdateRuntime

/** Shared update monitor for library, EPUB, MOBI, PDF and settings screens. */
class BibliotecaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateRuntime.initialize(this)
    }
}

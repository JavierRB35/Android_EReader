package com.javierrb35.bibliotecaia

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla base de la biblioteca (Incremento 0).
 * La importación real de libros llega con el Incremento 1;
 * el botón de importación permanece deshabilitado hasta entonces.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.widget.Button>(R.id.btnImport).isEnabled = false
    }
}

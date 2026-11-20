package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.sync.SyncPolicy
import com.javierrb35.bibliotecaia.sync.SyncPolicy.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regresión de la revisión del propietario: un fallo HTTP jamás descarta la cola. */
class SyncFailureClassificationTest {

    @Test
    fun `errores de validacion son permanentes`() {
        assertEquals(FailureKind.PERMANENT, SyncPolicy.classifyFailure(400))
        assertEquals(FailureKind.PERMANENT, SyncPolicy.classifyFailure(401))
        assertEquals(FailureKind.PERMANENT, SyncPolicy.classifyFailure(403))
        assertEquals(FailureKind.PERMANENT, SyncPolicy.classifyFailure(404))
        assertEquals(FailureKind.PERMANENT, SyncPolicy.classifyFailure(422))
    }

    @Test
    fun `errores transitorios son reintentables`() {
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(408))
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(429))
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(500))
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(503))
    }

    @Test
    fun `sin respuesta util se asume reintentable`() {
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(-1))
        assertEquals(FailureKind.RETRYABLE, SyncPolicy.classifyFailure(0))
    }

    @Test
    fun `las etiquetas son aptas para diagnostico de usuario`() {
        for (kind in FailureKind.entries) {
            assertTrue(kind.label.isNotBlank())
            // Permanente implica revisión manual; reintentable implica esperar
            assertEquals(kind == FailureKind.PERMANENT, !kind.retryable)
        }
    }
}

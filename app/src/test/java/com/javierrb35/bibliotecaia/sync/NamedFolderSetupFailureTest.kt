package com.javierrb35.bibliotecaia.sync

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NamedFolderSetupFailureTest {
    @Test fun legacyProgressFailureDoesNotMasqueradeAsFolderAccessFailure() {
        val failure = NamedFolderSetupFailure(NamedFolderSetupFailure.Stage.LEGACY_PROGRESS,
            IllegalStateException("No se pudieron comprobar los avances anteriores"))
        assertTrue(failure.userMessage().contains("La carpeta nueva ha superado la prueba de acceso"))
        assertTrue(failure.userMessage().contains("Recuperación de avances anteriores"))
        assertTrue(failure.userMessage().contains("No se pudieron comprobar los avances anteriores"))
    }

    @Test fun failedFolderProbeNeverClaimsSuccessfulAccess() {
        val failure = NamedFolderSetupFailure(NamedFolderSetupFailure.Stage.FOLDER_ACCESS,
            java.io.IOException("El proveedor no permite listar esta carpeta"))
        assertFalse(failure.userMessage().contains("ha superado"))
        assertTrue(failure.userMessage().contains("No se ha verificado el acceso completo"))
        assertTrue(failure.userMessage().contains("El proveedor no permite listar esta carpeta"))
    }
}

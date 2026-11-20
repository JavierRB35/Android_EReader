package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.updates.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {

    @Test
    fun `detecta versiones mayores y menores`() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("0.1.9", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.1", "0.1.1"))
    }

    @Test
    fun `la misma version o inferior no actualiza`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `tolera prefijo v y segmentos ausentes`() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "v0.2.0"))
        assertTrue(UpdateChecker.isNewer("v0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1"))
    }

    @Test
    fun `segmentos no numericos cuentan como cero`() {
        assertTrue(UpdateChecker.isNewer("0.1.x", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.x", "0.1"))
    }
}

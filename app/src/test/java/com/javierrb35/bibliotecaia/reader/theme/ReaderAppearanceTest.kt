package com.javierrb35.bibliotecaia.reader.theme

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAppearanceTest {
    @Test fun legacyPreferencesRemainUnchanged() {
        for (id in 0..3) {
            assertEquals(id, ReaderAppearance.resolve(id, Configuration.UI_MODE_NIGHT_YES))
            assertEquals(id, ReaderAppearance.resolve(id, Configuration.UI_MODE_NIGHT_NO))
        }
    }
    @Test fun systemModeFollowsAndroidWithoutRewritingSettings() {
        assertEquals(1, ReaderAppearance.resolve(ReaderAppearance.SYSTEM,
            Configuration.UI_MODE_NIGHT_YES))
        assertEquals(3, ReaderAppearance.resolve(ReaderAppearance.SYSTEM,
            Configuration.UI_MODE_NIGHT_NO))
    }
    @Test fun invalidModesSafelyUseOriginalOled() {
        assertEquals(0, ReaderAppearance.resolve(-2, Configuration.UI_MODE_NIGHT_YES))
        assertEquals(0, ReaderAppearance.resolve(99, Configuration.UI_MODE_NIGHT_NO))
    }
}

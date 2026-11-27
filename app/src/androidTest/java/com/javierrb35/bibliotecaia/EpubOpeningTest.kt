@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.reader.epub.EpubOpener
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import java.io.File

/** Uses the real Android parser and ZIP resources; no external service or private book. */
@RunWith(AndroidJUnit4::class)
class EpubOpeningTest {
    @Test fun opensChaptersAndFindsStableSearchLocators() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("epub-test-", ".epub", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("reader-sample.epub").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val publication = EpubOpener.open(instrumentation.targetContext, file)
            try {
                assertEquals(3, publication.readingOrder.size)
                assertEquals(3, publication.tableOfContents.size)
                assertEquals("El jardín de las ideas", publication.metadata.title)
                for (link in publication.readingOrder) {
                    val resource = requireNotNull(publication.get(link.url()))
                    val bytes = resource.read().getOrElse { error(it.message) }
                    val chapter = String(bytes, Charsets.UTF_8)
                    assertTrue(chapter.contains("Content-Security-Policy"))
                    assertTrue(chapter.contains("curiosidad"))
                    resource.close()
                }
                val iterator = requireNotNull(publication.search("curiosidad"))
                try {
                    val result = iterator.next().getOrElse { error(it.message) }
                    assertFalse(result?.locators.isNullOrEmpty())
                    val locator = result!!.locators.first()
                    assertTrue(locator.toJSON().has("href"))
                    assertTrue(locator.text.highlight.orEmpty().contains("curiosidad"))
                } finally { iterator.close() }
            } finally { publication.close() }
        } finally { file.delete() }
    }
}

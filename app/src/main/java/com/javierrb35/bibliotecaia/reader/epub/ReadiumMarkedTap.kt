package com.javierrb35.bibliotecaia.reader.epub

import org.json.JSONTokener

/**
 * Readium's own decoration clicks are primary. Fallback for a short tap that
 * narrowly misses a painted underline: hit-test the SDK's existing decoration
 * boxes with a small tolerance, never guess by matching repeated quote text.
 *
 * Readium's TapEvent coordinates are physical pixels; DOM rectangles use CSS
 * pixels. This script only reads in-memory decorations in the current EPUB
 * resource and never changes the book or its DOM.
 */
internal object ReadiumMarkedTap {
    private const val TOUCH_PADDING_CSS_PX = 9

    fun hitTestScript(x: Float, y: Float): String {
        if (!x.isFinite() || !y.isFinite()) return "null"
        return """
            (function () {
              var density = window.devicePixelRatio || 1;
              var x = ${x.toDouble()} / density;
              var y = ${y.toDouble()} / density;
              var group = window.readium && window.readium.getDecorations &&
                          window.readium.getDecorations('annotations');
              if (!group || !group.items) return null;
              for (var i = group.items.length - 1; i >= 0; i--) {
                var mark = group.items[i];
                if (!mark || !mark.decoration || !mark.clickableElements) continue;
                for (var j = 0; j < mark.clickableElements.length; j++) {
                  var box = mark.clickableElements[j].getBoundingClientRect();
                  var pad = $TOUCH_PADDING_CSS_PX;
                  if (x >= box.left - pad && x <= box.right + pad &&
                      y >= box.top - pad && y <= box.bottom + pad) {
                    return String(mark.decoration.id);
                  }
                }
              }
              return null;
            })();
        """.trimIndent()
    }

    /** Never accept an arbitrary ID returned by a publication's JavaScript. */
    fun verifiedId(value: String?, existingIds: Set<String>): String? {
        if (value.isNullOrBlank()) return null
        val decoded = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
        return decoded?.takeIf { it in existingIds }
    }

}

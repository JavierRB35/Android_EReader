package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only bridge for existing native TextView anchors when a reader chooses
 * Readium "Maquetación". Native UTF-16 offsets are NOT CFIs and therefore
 * cannot be passed into Readium's decoration API.
 *
 * Instead, only a quote with ONE exact normalized occurrence in the CURRENT
 * XHTML document is eligible. The DOM Range can then be rendered through the
 * CSS Highlight API, without inserting any elements into the book's DOM.
 * Taps use the very same Range rectangles. Ambiguous or missing passages
 * remain accessible via the regular "Notas > Leer anotación" modal.
 */
internal object ReadiumNativeNoteBridge {
    private const val MAX_NOTES = 128

    fun notesForHref(annotations: List<AnnotationEntity>, href: String?): List<AnnotationEntity> {
        if (href.isNullOrBlank()) return emptyList()
        return annotations.asSequence().filter { it.deletedAt == null &&
            it.kind in setOf("NOTE", "HIGHLIGHT") &&
            it.quote.isNotBlank() && it.quote.length <= 3000 &&
            NativeEpubAnchors.point(it.locator)?.href == href
        }.take(MAX_NOTES).toList()
    }

    private fun records(notes: List<AnnotationEntity>): JSONArray = JSONArray().also { array ->
        notes.take(MAX_NOTES).forEach { note ->
            array.put(JSONObject().put("id", note.id).put("quote", note.quote)
                .put("kind", note.kind))
        }
    }

    fun decorateScript(notes: List<AnnotationEntity>): String = javascript(notes, "paint", 0f, 0f)

    fun hitTestScript(x: Float, y: Float, notes: List<AnnotationEntity>): String =
        if (!x.isFinite() || !y.isFinite()) "null"
        else javascript(notes, "hit", x, y)

    private fun javascript(notes: List<AnnotationEntity>, operation: String,
                           tapX: Float, tapY: Float): String {
        val supplied = records(notes).toString()
        return """
            (function(){
              'use strict';
              var entries = $supplied;
              var operation = '$operation';
              var touchX = ${tapX.toDouble()};
              var touchY = ${tapY.toDouble()};
              if (!document.body || !document.createRange) return null;
              var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
              var points = [], normalized = '', node, count = 0;
              var maxChars = 250000;
              // Keep offsets as UTF-16 code units, like Android TextView.
              while ((node = walker.nextNode()) && ++count <= 8000 &&
                      normalized.length < maxChars) {
                if (!node.parentElement) continue;
                var tag = node.parentElement.tagName;
                if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') continue;
                var raw = node.textContent || '';
                for (var i = 0; i < raw.length && normalized.length < maxChars; i++) {
                  var ch = raw[i];
                  if (/\s/.test(ch)) {
                    if (!normalized.length || normalized[normalized.length-1] === ' ') continue;
                    ch = ' ';
                  }
                  normalized += ch;
                  points.push([node, i]);
                }
              }
              function normalizedQuote(text) {
                return (text || '').replace(/\s+/g,' ').trim();
              }
              var noteRanges = [], underlineRanges = [];
              var hitId = null, px = touchX / (window.devicePixelRatio || 1);
              var py = touchY / (window.devicePixelRatio || 1);
              for (var n = 0; n < entries.length; n++) {
                var note = entries[n], quote = normalizedQuote(note.quote);
                if (!quote) continue;
                var pos = normalized.indexOf(quote);
                // Duplicate excerpts may refer to different positions:
                // do not invent a locator or activate the wrong note.
                if (pos < 0 || normalized.indexOf(quote, pos + 1) >= 0) continue;
                var first = points[pos], last = points[pos + quote.length - 1];
                if (!first || !last) continue;
                var range = document.createRange();
                try {
                  range.setStart(first[0], first[1]);
                  range.setEnd(last[0], last[1] + 1);
                } catch (_) { continue; }
                if (operation === 'hit') {
                  var rects = range.getClientRects();
                  for (var j = 0; j < rects.length; j++) {
                    var rect = rects[j], pad = 8;
                    if (px >= rect.left-pad && px <= rect.right+pad &&
                        py >= rect.top-pad && py <= rect.bottom+pad) {
                      hitId = note.id;
                      // Notes containing user text win over plain underlines.
                      if (note.kind === 'NOTE') return hitId;
                    }
                  }
                } else if (note.kind === 'NOTE') noteRanges.push(range);
                else underlineRanges.push(range);
              }
              if (operation === 'hit') return hitId;
              if (!window.CSS || !CSS.highlights || typeof Highlight === 'undefined')
                  return 0;
              var styleId = 'bia-native-saved-note-style';
              if (!document.getElementById(styleId)) {
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent =
                  '::highlight(bia-native-notes){background-color:rgba(181,155,98,.33);' +
                  'text-decoration:underline;text-decoration-color:#d4b483}' +
                  '::highlight(bia-native-underlines){text-decoration:underline;' +
                  'text-decoration-color:#d4b483}';
                (document.head || document.documentElement).appendChild(style);
              }
              try {
                CSS.highlights.set('bia-native-notes', new Highlight(...noteRanges));
                CSS.highlights.set('bia-native-underlines', new Highlight(...underlineRanges));
              } catch (_) { return 0; }
              return noteRanges.length + underlineRanges.length;
            })();
        """.trimIndent()
    }
}

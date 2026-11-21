package com.javierrb35.bibliotecaia.reader.speech;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReaderSpeechTest {
    @Test public void splitsLongSectionsWithoutLosingWords() {
        String text = "Lectura ".repeat(600);
        List<String> parts = ReaderSpeech.chunks(text, 500);
        assertTrue(parts.size() > 2);
        assertEquals(text.trim(), String.join(" ", parts));
        for (String part : parts) assertTrue(part.length() <= 500);
    }
    @Test public void shortPassageUsesOneUtterance() {
        assertEquals(List.of("Texto de ejemplo"), ReaderSpeech.chunks(" Texto de ejemplo ", 700));
    }
    @Test public void noTextDoesNotQueueUtterances() {
        assertTrue(ReaderSpeech.chunks("", 500).isEmpty());
    }
}
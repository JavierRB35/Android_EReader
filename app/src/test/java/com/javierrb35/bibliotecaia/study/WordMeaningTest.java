package com.javierrb35.bibliotecaia.study;

import org.junit.Test;
import static org.junit.Assert.*;

public class WordMeaningTest {
    @Test public void handlesAccentsAndCapitalLetters(){
        assertNotNull(WordMeaning.local("MISERICORDIA"));
        assertNotNull(WordMeaning.local("Salvación"));
        assertNotNull(WordMeaning.local("apóstoles"));
        assertEquals(WordMeaning.local("PROJIMO"),WordMeaning.local("prójimo"));
    }
    @Test public void permitsOnlyOneWord(){
        assertTrue(WordMeaning.oneWord("espíritu"));
        assertTrue(WordMeaning.oneWord("Señor"));
        assertFalse(WordMeaning.oneWord("la gracia"));
        assertFalse(WordMeaning.oneWord("gracia,"));
        assertFalse(WordMeaning.oneWord(""));
    }
    @Test public void preservesSelectionBoundaries(){
        String s="La gracia y la misericordia.";
        assertEquals("gracia",WordMeaning.selection(s,3,9));
        assertNull(WordMeaning.selection(s,0,9));
        assertNull(WordMeaning.selection(s,-1,8));
        assertNull(WordMeaning.selection(s,5,999));
    }
    @Test public void verifiedCatholicPagesUseDirectArticles(){
        assertEquals("https://ec.aciprensa.com/wiki/Fariseos",
            WordMeaning.catholicArticleUrl("fariseos"));
        assertEquals("https://ec.aciprensa.com/wiki/Saduceos",
            WordMeaning.catholicArticleUrl("SADUCEO"));
        assertEquals("https://ec.aciprensa.com/wiki/Ap%C3%B3stoles",
            WordMeaning.catholicArticleUrl("apóstoles"));
        assertEquals("https://ec.aciprensa.com/wiki/Pecado",
            WordMeaning.catholicArticleUrl("pecado"));
        assertNull(WordMeaning.catholicArticleUrl("cananeos"));
        assertNull(WordMeaning.catholicArticleUrl("pastores"));
    }
    @Test public void officialSearchRoutesPreserveAccents(){
        assertEquals("https://es.wikipedia.org/wiki/Especial:Buscar?search=Salvaci%C3%B3n",
            WordMeaning.wikipediaSearchUrl("Salvación"));
        assertEquals("https://es.wiktionary.org/wiki/Especial:Buscar?search=ap%C3%B3stoles",
            WordMeaning.wiktionarySearchUrl("apóstoles"));
        assertEquals("https://es.wikipedia.org/wiki/Especial:Buscar?search=Jes%C3%BAs",
            WordMeaning.wikipediaSearchUrl("Jesús"));
    }
    @Test public void searchesMultipleWordsWithAccentsAndSpaces(){
        assertEquals("https://es.wikipedia.org/wiki/Especial:Buscar?search=Santiago%20Casares",
            WordMeaning.wikipediaSearchUrl("Santiago Casares"));
        assertEquals("https://es.wikipedia.org/wiki/Especial:Buscar?search=guerra%20civil%20espa%C3%B1ola",
            WordMeaning.wikipediaSearchUrl("guerra   civil española"));
        assertEquals("https://es.wiktionary.org/wiki/Especial:Buscar?search=La%20Mancha",
            WordMeaning.wiktionarySearchUrl("La Mancha"));
        assertEquals("Santiago Casares",
            WordMeaning.searchSelection("Santiago Casares informó",0,16));
        assertNull(WordMeaning.searchSelection("Santiago Casares",-1,9));
    }
    @Test public void preservesOneWordOnlyForVerifiedCatholicArticles(){
        assertFalse(WordMeaning.oneWord("Santiago Casares"));
        try {
            WordMeaning.catholicArticleUrl("Santiago Casares");
            fail("Never synthesize unknown Catholic encyclopedia paths");
        } catch (IllegalArgumentException expected) { /* deliberate */ }
    }
    @Test public void rejectsUnsafeOrOverlongPhrases(){
        assertNull(WordMeaning.searchPhrase("fariseo?redirect=https://evil.test"));
        assertNull(WordMeaning.searchPhrase("abc\\n\\u0000def"));
        assertNull(WordMeaning.searchPhrase(new String(new char[170]).replace('\\0','a')));
        assertEquals("Martín Luther King Jr.",WordMeaning.searchPhrase("Martín Luther King Jr."));
    }
    @Test(expected=IllegalArgumentException.class)
    public void blocksUnsafePunctuation(){
        WordMeaning.wiktionarySearchUrl("fariseo?redirect=https://evil.test");
    }
}

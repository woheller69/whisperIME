package com.whispertflite.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CustomVocabularyTest {
    private static final String VOCABULARY = CustomVocabulary.DEFAULT_VOCABULARY + "Elena\nUgo\n";

    private static String fix(String text) {
        return CustomVocabulary.apply(VOCABULARY, text);
    }

    @Test
    public void soundAlikeNames() {
        assertEquals(" Ciao Elena, come stai?", fix(" Ciao Helena, come stai?"));
        assertEquals("Parlo con Elena e Elena.", fix("Parlo con Elena e Elenaa."));
    }

    @Test
    public void acronymsAndExplicitRules() {
        assertEquals("Uso Claude Code e GPT ogni giorno.", fix("Uso cloud code e GPD ogni giorno."));
        assertEquals("Ho chiesto a ChatGPT e a Claude.", fix("Ho chiesto a chat GPT e a Claude."));
    }

    @Test
    public void italianElisions() {
        assertEquals("È il libro dell'Elena.", fix("È il libro dell'Helena."));
        assertEquals("l’Elena", fix("l’Helena"));
    }

    @Test
    public void shortEntriesOnlyFixCase() {
        assertEquals("Ciao Ugo", fix("Ciao ugo"));
        assertEquals("Ciao Uco", fix("Ciao Uco"));  // not sound-alike matched: less than 5 letters
    }

    @Test
    public void unrelatedTextIsUnchanged() {
        String text = " La casa è bella, dove sei? Domani alle dieci.";
        assertEquals(text, fix(text));
    }

    @Test
    public void emptyAndCommentLines() {
        assertEquals("Helena", CustomVocabulary.apply("# comment\n\n  \n", "Helena"));
        assertEquals("", CustomVocabulary.apply(VOCABULARY, ""));
    }
}

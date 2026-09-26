package com.whispertflite.utils;

import android.content.Context;

import androidx.preference.PreferenceManager;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing of transcriptions with a user editable vocabulary (one entry per line):
 * - "Word"            words/phrases that sound alike are replaced by it, e.g. Elena fixes "Helena"
 * - "wrong = Right"   explicit replacement, e.g. "cloud code = Claude Code"
 * Sound-alike matching is only used for entries with at least {@link #MIN_FUZZY_LETTERS} letters
 * or all-caps acronyms (GPT), otherwise a short entry like "Tove" would also replace "dove".
 * Shorter entries only fix case and accents.
 * The Whisper tflite models cannot take a prompt, so this is how custom words are supported.
 */
public class CustomVocabulary {
    public static final String PREF_KEY = "customVocabulary";
    public static final String DEFAULT_VOCABULARY =
            "Claude\n" +
            "Claude Code\n" +
            "ChatGPT\n" +
            "GPT\n" +
            "cloud code = Claude Code\n" +
            "chat gpt = ChatGPT\n";

    static final int MIN_FUZZY_LETTERS = 5;

    // Apostrophes are separators, so Italian elisions like "dell'Helena" are matched word by word
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private static class Entry {
        final String[] keys;   // normalized words to match (phonetic or exact, see fuzzy)
        final boolean fuzzy;
        final String replacement;

        Entry(String[] keys, boolean fuzzy, String replacement) {
            this.keys = keys;
            this.fuzzy = fuzzy;
            this.replacement = replacement;
        }
    }

    // Parsed vocabulary of the last call, re-parsed only when the text changes
    private static String cachedVocabulary;
    private static List<Entry> cachedEntries;

    public static String getVocabulary(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, DEFAULT_VOCABULARY);
    }

    public static void setVocabulary(Context context, String vocabulary) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_KEY, vocabulary).apply();
    }

    public static String apply(Context context, String text) {
        return apply(getVocabulary(context), text);
    }

    public static String apply(String vocabulary, String text) {
        if (text == null || text.trim().isEmpty() || vocabulary == null) return text;
        List<Entry> entries = entries(vocabulary);
        if (entries.isEmpty()) return text;

        // Tokenize, keeping the separators so punctuation and spacing are preserved
        List<int[]> spans = new ArrayList<>();
        List<String> exact = new ArrayList<>();
        List<String> phonetic = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            exact.add(exactKey(m.group()));
            phonetic.add(phoneticKey(m.group()));
        }

        StringBuilder out = new StringBuilder();
        int pos = 0;       // position in text already copied
        int i = 0;
        while (i < spans.size()) {
            Entry match = null;
            for (Entry e : entries) {  // entries are sorted longest first
                if (matches(e.fuzzy ? phonetic : exact, i, e.keys)) {
                    match = e;
                    break;
                }
            }
            if (match == null) {
                i++;
                continue;
            }
            int start = spans.get(i)[0];
            int end = spans.get(i + match.keys.length - 1)[1];
            out.append(text, pos, start).append(match.replacement);
            pos = end;
            i += match.keys.length;
        }
        out.append(text.substring(pos));
        return out.toString();
    }

    private static boolean matches(List<String> words, int from, String[] keys) {
        if (from + keys.length > words.size()) return false;
        for (int k = 0; k < keys.length; k++) {
            if (!words.get(from + k).equals(keys[k])) return false;
        }
        return true;
    }

    private static synchronized List<Entry> entries(String vocabulary) {
        if (!vocabulary.equals(cachedVocabulary)) {
            cachedEntries = parse(vocabulary);
            cachedVocabulary = vocabulary;
        }
        return cachedEntries;
    }

    private static List<Entry> parse(String vocabulary) {
        List<Entry> entries = new ArrayList<>();
        for (String line : vocabulary.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String source = line;
            String target = line;
            int eq = line.indexOf('=');
            if (eq >= 0) {
                source = line.substring(0, eq).trim();
                target = line.substring(eq + 1).trim();
                if (source.isEmpty() || target.isEmpty()) continue;
            }
            List<String> words = new ArrayList<>();
            Matcher m = WORD.matcher(source);
            while (m.find()) words.add(m.group());
            if (words.isEmpty()) continue;

            boolean fuzzy = isFuzzy(words);
            String[] keys = new String[words.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = fuzzy ? phoneticKey(words.get(i)) : exactKey(words.get(i));
            entries.add(new Entry(keys, fuzzy, target));
        }
        // Longest phrases first, so "Claude Code" wins over "Claude"
        entries.sort((a, b) -> b.keys.length - a.keys.length);
        return entries;
    }

    private static boolean isFuzzy(List<String> words) {
        int letters = 0;
        for (String w : words) letters += w.length();
        if (letters >= MIN_FUZZY_LETTERS) return true;
        String joined = String.join("", words);
        return joined.length() >= 2 && joined.equals(joined.toUpperCase()) && !joined.equals(joined.toLowerCase());
    }

    // Case and accent insensitive key
    static String exactKey(String word) {
        return Normalizer.normalize(word.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /**
     * Rough phonetic key, tuned for the errors Whisper makes on names/brands:
     * case, accents, silent h, ph/f, y/i, k/q/c, w/v, voiced/unvoiced consonants (d/t, b/p) and double letters.
     */
    static String phoneticKey(String word) {
        String s = exactKey(word);
        if (s.length() <= 2) return s;  // keep short words exact
        s = s.replace("ph", "f").replace("h", "")
                .replace('y', 'i').replace('k', 'c').replace('q', 'c').replace('w', 'v')
                .replace('d', 't').replace('b', 'p');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i == 0 || s.charAt(i) != s.charAt(i - 1)) sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}

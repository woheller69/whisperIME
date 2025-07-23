package com.whispertflite.utils;

import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.whispertflite.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LanguagePairAdapter extends ArrayAdapter<Pair<String, String>> {
    private List<Pair<String, String>> languagePairs;

    public LanguagePairAdapter(Context context, int resource, List<Pair<String, String>> languagePairs) {
        super(context, resource, languagePairs);
        this.languagePairs = languagePairs;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        if (position < languagePairs.size()) {
            view.setText(languagePairs.get(position).second); // Display name
        }
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        if (position < languagePairs.size()) {
            view.setText(languagePairs.get(position).second); // Display name
        }
        return view;
    }

    public static List<Pair<String, String>> getLanguagePairs(Context context){
        // Create pairs of code and display name, then sort by display name
        List<Pair<String, String>> languagePairs = new ArrayList<>();
        String[] sortedLanguages = context.getResources().getStringArray(R.array.top40_languages);
        for (String code : sortedLanguages) {
            Locale locale = new Locale(code);
            languagePairs.add(new Pair<>(code, locale.getDisplayLanguage()));
        }

        // Sort by display name
        languagePairs.sort((pair1, pair2) -> pair1.second.compareToIgnoreCase(pair2.second));

        // Add auto at first position
        languagePairs.add(0, new Pair<>("auto", context.getString(R.string.auto_lang)));
        return languagePairs;
    }

    // Helper method to find index by language code
    public int getIndexByCode(String langCode) {
        for (int i = 0; i < languagePairs.size(); i++) {
            if (languagePairs.get(i).first.equals(langCode)) {
                return i;
            }
        }
        return 0; // Default to first item
    }
}

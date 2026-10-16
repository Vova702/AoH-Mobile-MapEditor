package com.mygame.mapeditor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREF_NAME = "LocalePrefs";
    private static final String KEY_LANG = "language_code";
    private static final String DEFAULT_LANG = "en";

    public static Context attachBaseContext(Context context) {
        var lang = getPersistedLanguage(context);
        return setLocale(context, lang);
    }

    public static Context setLocale(Context context, String languageCode) {
        persist(context, languageCode);
        return applyLocale(context, languageCode);
    }

    private static Context applyLocale(Context context, String languageCode) {
        var locale = new Locale(languageCode);
        Locale.setDefault(locale);

        var config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static String getPersistedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANG, DEFAULT_LANG);
    }

    private static void persist(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANG, languageCode).apply();
    }
}

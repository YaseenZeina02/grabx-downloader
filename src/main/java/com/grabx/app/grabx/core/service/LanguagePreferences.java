package com.grabx.app.grabx.core.service;

import java.util.List;
import java.util.prefs.Preferences;

public final class LanguagePreferences {
    public record Language(String code, String name) { @Override public String toString() { return name; } }
    public static final List<Language> LANGUAGES = List.of(new Language("en", "English"), new Language("ar", "العربية"),
            new Language("es", "Español"), new Language("fr", "Français"), new Language("de", "Deutsch"), new Language("tr", "Türkçe"));
    private final Preferences store;
    public LanguagePreferences(Preferences store) { this.store = store; }
    public String code() { return valid(store.get("grabx.language", "en")) ? store.get("grabx.language", "en") : "en"; }
    public static boolean valid(String code) { return LANGUAGES.stream().anyMatch(item -> item.code().equals(code)); }
    public void save(String code) {
        if (!valid(code)) throw new IllegalArgumentException("Choose a supported language.");
        store.put("grabx.language", code);
    }
}

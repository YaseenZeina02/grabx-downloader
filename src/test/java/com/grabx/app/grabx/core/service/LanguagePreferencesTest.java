package com.grabx.app.grabx.core.service;
import com.grabx.app.grabx.util.I18n;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LanguagePreferencesTest {
    @Test void selectionPersistsAndInvalidSavedCodesFallBackToEnglish() {
        var store = new SoundFeedbackServiceTest.MemoryPreferences();
        var language = new LanguagePreferences(store);
        assertEquals("en", language.code());
        for (var choice : LanguagePreferences.LANGUAGES) {
            language.save(choice.code());
            assertEquals(choice.code(), new LanguagePreferences(store).code());
        }
        assertThrows(IllegalArgumentException.class, () -> language.save("invalid"));
        store.put("grabx.language", "invalid"); assertEquals("en", language.code());
    }
    @Test void messagesTranslateWithoutRewritingUrlsPathsOrUnknownServerErrors() {
        assertEquals("الإعدادات", I18n.text("Settings", "ar"));
        assertEquals("جارٍ التحميل • دعم الاستكمال: نعم • تلقائي • حتى 64 اتصال",
                I18n.text("Downloading • Resume support: Yes • Auto • Up to 64 connections", "ar"));
        for (var language : LanguagePreferences.LANGUAGES) {
            assertEquals("https://example.com/Downloads?mode=Video", I18n.text("https://example.com/Downloads?mode=Video", language.code()));
            assertEquals("/tmp/Video.mp4", I18n.text("/tmp/Video.mp4", language.code()));
            assertEquals("HTTP 498: server response", I18n.text("HTTP 498: server response", language.code()));
            if (!language.code().equals("en")) assertNotEquals("Save changes", I18n.text("Save changes", language.code()));
        }
    }
}

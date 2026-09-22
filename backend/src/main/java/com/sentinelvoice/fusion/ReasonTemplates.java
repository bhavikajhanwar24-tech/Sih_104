package com.sentinelvoice.fusion;

import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * F12 — loads reason title/detail templates from {@code reasons*.properties}.
 * No hard-coded analyst sentences in service code.
 */
@Component
public class ReasonTemplates {

    private static final String BUNDLE = "reasons";

    public String title(ReasonCode code, Locale locale) {
        ReasonCode c = code == null ? null : code.canonical();
        if (c == null) {
            return "Unknown";
        }
        return message(c.name() + ".title", locale, c.name());
    }

    public String detail(ReasonCode code, Locale locale, Object... args) {
        ReasonCode c = code == null ? null : code.canonical();
        if (c == null) {
            return "";
        }
        String pattern = message(c.name() + ".detail", locale, "{0}");
        try {
            return MessageFormat.format(pattern, args == null ? new Object[0] : args);
        } catch (IllegalArgumentException e) {
            return pattern;
        }
    }

    private static String message(String key, Locale locale, String fallback) {
        Locale loc = locale == null ? Locale.ENGLISH : locale;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, loc);
            String full = "reasons." + key;
            if (bundle.containsKey(full)) {
                return bundle.getString(full);
            }
            if (bundle.containsKey(key)) {
                return bundle.getString(key);
            }
        } catch (MissingResourceException ignored) {
            // fall through
        }
        try {
            ResourceBundle en = ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH);
            String full = "reasons." + key;
            if (en.containsKey(full)) {
                return en.getString(full);
            }
        } catch (MissingResourceException ignored) {
            // fall through
        }
        return fallback;
    }
}

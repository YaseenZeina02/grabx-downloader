package com.grabx.app.grabx.util;

import java.net.HttpURLConnection;
import java.net.URI;

/** Carries a captured page reference without credentials or a fragment. */
public final class RequestReferer {
    private RequestReferer() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            String result = uri.toASCIIString();
            int fragment = result.indexOf('#');
            return fragment < 0 ? result : result.substring(0, fragment);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void apply(HttpURLConnection connection, String value) {
        String referer = normalize(value);
        if (referer != null) connection.setRequestProperty("Referer", referer);
    }
}

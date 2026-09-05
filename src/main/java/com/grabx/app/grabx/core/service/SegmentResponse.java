package com.grabx.app.grabx.core.service;

import java.io.IOException;
import java.util.regex.Pattern;

final class SegmentResponse {
    private static final Pattern RANGE = Pattern.compile("(?i)bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    static long validate(String value, long start, long end, long total) throws IOException {
        var match = RANGE.matcher(value == null ? "" : value.trim());
        try {
            if (match.matches() && Long.parseLong(match.group(1)) == start
                    && Long.parseLong(match.group(2)) >= start
                    && Long.parseLong(match.group(2)) <= end
                    && Long.parseLong(match.group(3)) == total) return Long.parseLong(match.group(2));
        } catch (NumberFormatException ignored) {}
        throw new IOException("Server returned a different file range; downloaded parts kept");
    }
}

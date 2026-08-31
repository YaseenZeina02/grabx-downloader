package com.grabx.app.grabx.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared parsing and formatting rules for video quality labels. */
public final class VideoQualityUtils {
    private static final Pattern HEIGHT_LABEL = Pattern.compile("\\b(\\d{3,4})p(?:\\d{1,3})?\\b");
    private static final int[] HEIGHT_LADDER = {144, 240, 360, 480, 540, 720, 1080, 1440, 2160, 4320};
    private static final double NORMALIZE_TOLERANCE_RATIO = 0.03;

    private VideoQualityUtils() {}

    public static int parseHeight(String label) {
        if (label == null) return -1;
        Matcher matcher = HEIGHT_LABEL.matcher(label);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static String formatHeightLabel(int height) {
        if (height == 4320) return "4320p (8K)";
        if (height == 2160) return "2160p (4K)";
        if (height == 1440) return "1440p (2K)";
        return height + "p";
    }

    public static int normalizeHeight(int height) {
        if (height < 120) return -1;

        int closest = -1;
        int closestDifference = Integer.MAX_VALUE;
        for (int candidate : HEIGHT_LADDER) {
            int difference = Math.abs(height - candidate);
            if (difference < closestDifference) {
                closestDifference = difference;
                closest = candidate;
            }
        }
        int tolerance = Math.max(4, (int) Math.round(closest * NORMALIZE_TOLERANCE_RATIO));
        return closestDifference <= tolerance ? closest : -1;
    }

    public static Set<Integer> normalizeHeights(Set<Integer> heights) {
        Set<Integer> normalized = new TreeSet<>();
        if (heights == null) return normalized;
        for (Integer height : heights) {
            if (height == null) continue;
            int value = normalizeHeight(height);
            if (value > 0) normalized.add(value);
        }
        return normalized;
    }

    public static String formatSelectorForHeight(int height) {
        int safeHeight = Math.max(1, height);
        return "bv*[height<=" + safeHeight + "]+ba/b[height<=" + safeHeight + "]/bv*+ba/b";
    }

    public static String closestSupportedLabel(
            String desired,
            List<String> availableLabels,
            String bestLabel,
            String separatorLabel
    ) {
        if (desired == null || desired.isBlank() || bestLabel.equals(desired)) return bestLabel;
        int desiredHeight = parseHeight(desired);
        if (desiredHeight <= 0 || availableLabels == null || availableLabels.isEmpty()) return bestLabel;

        List<Integer> heights = new ArrayList<>();
        Map<Integer, String> labelsByHeight = new HashMap<>();
        for (String label : availableLabels) {
            if (label == null || bestLabel.equals(label) || separatorLabel.equals(label)) continue;
            int height = parseHeight(label);
            if (height > 0) {
                heights.add(height);
                labelsByHeight.put(height, label);
            }
        }
        if (heights.isEmpty()) return bestLabel;

        heights.sort(Comparator.naturalOrder());
        Integer closest = null;
        for (Integer height : heights) {
            if (height <= desiredHeight) closest = height;
        }
        // Nothing is at or below the requested height: choose the smallest
        // available option instead of unexpectedly jumping to the largest.
        if (closest == null) closest = heights.get(0);
        return labelsByHeight.get(closest);
    }
}

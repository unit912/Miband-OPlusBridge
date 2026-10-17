// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.data;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Display names for Xiaomi Wear band models the official app speaks.
 * Not an allow-list: live sessions still depend on the captured SPP profile.
 */
public final class BandCatalog {
    private static final Pattern CHINESE = Pattern.compile(
            "小米手环\\s*(8|9|10|11)\\s*(Pro|NFC|活力版)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVERTISED = Pattern.compile(
            "(?i)(?:xiaomi\\s+)?(?:smart\\s+)?band\\s+(8|9|10|11)(?:\\s+(pro|active|nfc))?");
    private static final Pattern REDMI = Pattern.compile(
            "(?i)redmi\\s+(?:smart\\s+)?band\\s*(pro|2|3)?");

    private BandCatalog() {}

    public static boolean looksLikeBand(String advertisedName) {
        return fromAdvertisedName(advertisedName) != null;
    }

    public static String displayName(String model, String rawName) {
        String fromModel = fromModelId(model);
        if (fromModel != null) return fromModel;
        String fromAdvertised = fromAdvertisedName(rawName);
        if (fromAdvertised != null) return fromAdvertised;
        String clean = sanitize(rawName);
        if (clean != null) return clean;
        String leftover = sanitize(model);
        return leftover != null ? leftover : "小米手环";
    }

    /** Longest prefix wins. The last column is the device-space card: band or watch. */
    private static final String[][] MODELS = {
            {"lchz.watch.m67ys", "小米手环8 Pro 原神定制版", "band"},
            {"lchz.watch.m67", "小米手环8 Pro", "band"},
            {"miwear.watch.m66nfc", "小米手环8 NFC", "band"},
            {"lchz.watch.m66", "小米手环8", "band"},
            {"miwear.watch.m66", "小米手环8", "band"},
            {"mijia.watch.m69b", "小米手环8 活力版", "band"},
            {"miwear.watch.n66tc", "小米手环9 陶瓷特别版", "band"},
            {"miwear.watch.n66nfc", "小米手环9 NFC", "band"},
            {"miwear.watch.n66", "小米手环9", "band"},
            {"lchz.watch.n67", "小米手环9 Pro", "band"},
            {"miwear.watch.n67", "小米手环9 Pro", "band"},
            {"mijia.watch.n69", "Redmi手环3", "band"},
            {"miwear.watch.n69", "Redmi手环3", "band"},
            {"miwear.watch.o66nfc", "小米手环10 NFC", "band"},
            {"miwear.watch.o66gln", "小米手环10 NFC", "band"},
            {"miwear.watch.o66tc", "小米手环10 陶瓷版", "band"},
            {"miwear.watch.o66lj", "小米手环10 耀影金特别版", "band"},
            {"miwear.watch.o66", "小米手环10", "band"},
            {"miwear.watch.p67tc", "小米手环10 Pro 陶瓷版", "band"},
            {"miwear.watch.p67gln", "小米手环10 Pro NFC", "band"},
            {"miwear.watch.p67", "小米手环10 Pro", "band"},
            {"miwear.watch.q66nfc", "小米手环11 NFC", "band"},
            {"miwear.watch.q66tc", "小米手环11 陶瓷版", "band"},
            {"miwear.watch.q66", "小米手环11", "band"},
            {"mijia.watch.band01", "Redmi手环", "band"},
            {"mijia.watch.m69", "Redmi手环2", "band"},
            {"hqbd3.watch.l67", "小米手环7 Pro", "watch"},
            {"mijia.watch.n62lte", "小米手表 S3 eSIM", "watch"},
            {"mijia.watch.n62car", "小米手表 S3 SU7 限定版", "watch"},
            {"mijia.watch.n62cg", "小米手表 S3 SU7 限定版", "watch"},
            {"mijia.watch.n62s", "小米手表 S4 Sport", "watch"},
            {"mijia.watch.n62", "小米手表 S3", "watch"},
            {"mijia.watch.o62lte", "小米手表 S4 eSIM", "watch"},
            {"mijia.watch.o62m", "小米手表 S4 15周年版", "watch"},
            {"mijia.watch.o62", "小米手表 S4", "watch"},
            {"miwear.watch.o63", "小米手表 S4", "watch"},
            {"miwear.watch.p62lte", "小米手表 S5 eSIM", "watch"},
            {"miwear.watch.p62", "小米手表 S5", "watch"},
            {"mijia.watch.l61", "小米手表 S1 Pro", "watch"},
            {"lchz.watch.n65", "REDMI Watch 4", "watch"},
            {"miwear.watch.o65m", "REDMI Watch 5 eSIM", "watch"},
            {"miwear.watch.o65", "REDMI Watch 5", "watch"},
            {"miwear.watch.p65", "REDMI Watch 6", "watch"},
            {"miwear.watch.q65", "REDMI Watch 6 活力版", "watch"},
            // Huawei BR/EDR watches. The space card and the health category both follow the watch form.
            {"huawei.watch.gt3", "HUAWEI WATCH GT3", "watch"},
            {"huawei.watch.gt2", "HUAWEI WATCH GT2", "watch"},
            {"huawei.watch.gt", "HUAWEI WATCH GT", "watch"},
    };

    /**
     * OPPO health row category ({@code DeviceCategory.BAND} or {@code WATCH}).
     * An unknown model stays {@code Band}, the same fallback as the wristband card.
     */
    public static String healthCategory(String model) {
        return watchForm(model) ? "Watch" : "Band";
    }

    /** Device-space card type. Bands use the system wristband glyph, watches use the watch glyph. */
    public static String spaceType(String model) {
        return watchForm(model) ? "WATCH" : "WRISTBAND";
    }

    /** Drawable name in {@code com.heytap.mydevices}. Empty iconUrl falls back to a phone. */
    public static String spaceIcon(String model) {
        return watchForm(model) ? "default_watch" : "default_wristband";
    }

    public static boolean watchForm(String model) {
        String[] row = match(model);
        return row != null && "watch".equals(row[2]);
    }

    static String fromModelId(String model) {
        String[] row = match(model);
        return row == null ? null : row[1];
    }

    private static String[] match(String model) {
        if (model == null || model.isBlank()) return null;
        String id = model.trim().toLowerCase(Locale.ROOT);
        String[] best = null;
        for (String[] row : MODELS) {
            if (!id.startsWith(row[0])) continue;
            if (best == null || row[0].length() > best[0].length()) best = row;
        }
        return best;
    }

    static String fromAdvertisedName(String raw) {
        String clean = sanitize(raw);
        if (clean == null) return null;
        Matcher chinese = CHINESE.matcher(clean);
        if (chinese.find()) return labeled(chinese.group(1), chinese.group(2));
        Matcher advertised = ADVERTISED.matcher(clean);
        if (advertised.find()) return labeled(advertised.group(1), advertised.group(2));
        Matcher redmi = REDMI.matcher(clean);
        if (redmi.find()) {
            String variant = redmi.group(1);
            if (variant == null || variant.isBlank()) return "红米手环";
            if (variant.equalsIgnoreCase("pro")) return "红米手环 Pro";
            return "红米手环" + variant;
        }
        return null;
    }

    private static String labeled(String generation, String variant) {
        String base = "小米手环" + generation;
        if (variant == null || variant.isBlank()) return base;
        String kind = variant.trim().toLowerCase(Locale.ROOT);
        if (kind.equals("pro")) return base + " Pro";
        if (kind.equals("nfc")) return base + " NFC";
        if (kind.equals("active") || kind.contains("活力")) return base + " 活力版";
        return base;
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        if (clean.isEmpty() || clean.length() > 80) return null;
        return clean;
    }
}

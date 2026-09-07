package com.codex.mnote;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Supplied web links only; never infer URLs from screenshots or package ids. */
final class CaptureSourceUrl {
    private static final Pattern WEB_LINK = Pattern.compile(
            "https?://[^\\s<>\\\"“”‘’\\u3000，。；！？（）【】《》]+", Pattern.CASE_INSENSITIVE);
    private CaptureSourceUrl() {}

    static String clean(String supplied) {
        if (supplied == null || supplied.trim().isEmpty()) return "";
        String value = supplied.trim();
        if (value.length() > 8192) return "";
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))
                    || Character.isWhitespace(value.charAt(index))) return "";
        }
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null || uri.getPort() > 65535) return "";
            return value;
        } catch (URISyntaxException error) {
            return "";
        }
    }

    static String fromSharedText(CharSequence supplied) {
        if (supplied == null || supplied.length() > 100_000) return "";
        String exact = clean(supplied.toString());
        if (!exact.isEmpty() && WEB_LINK.matcher(exact).matches()) return exact;
        Set<String> candidates = new LinkedHashSet<>();
        Matcher matcher = WEB_LINK.matcher(supplied);
        while (matcher.find()) {
            String value = matcher.group();
            while (!value.isEmpty() && "。，、；！？）】》.,;!?".indexOf(
                    value.charAt(value.length() - 1)) >= 0) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.endsWith(")") && !value.contains("(")) value = value.substring(0, value.length() - 1);
            value = clean(value);
            if (!value.isEmpty()) candidates.add(value);
        }
        // Multiple URLs may refer to ads or other posts. Do not guess.
        return candidates.size() == 1 ? candidates.iterator().next() : "";
    }
}

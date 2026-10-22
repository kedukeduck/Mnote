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
    // Video share text is also pasted from Markdown. Brackets and a quoted
    // title delimit copies of the same link, not parts of its path.
    private static final Pattern TRANSCRIPT_LINK = Pattern.compile(
            "https?://[^\\s<>\\\"'`\\[\\]“”‘’\\u3000，。；！？（）【】《》]+", Pattern.CASE_INSENSITIVE);
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

    static String fromTranscriptShareText(CharSequence supplied) {
        if (supplied == null || supplied.length() > 8192) return "";
        String text = supplied.toString().trim();
        String exact = clean(text);
        boolean hasWhitespace = false;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isWhitespace(text.charAt(index))) { hasWhitespace = true;break; }
        }
        // A malformed naked URL must not become a valid but different prefix
        // merely because an invalid path character resembles Markdown syntax.
        if (!hasWhitespace && text.regionMatches(true, 0, "http", 0, 4)) {
            return !exact.isEmpty() && TRANSCRIPT_LINK.matcher(exact).matches()
                    && exact.regionMatches(true, 0, "https://", 0, 8) ? exact : "";
        }
        if (!exact.isEmpty() && TRANSCRIPT_LINK.matcher(exact).matches()) {
            return exact.regionMatches(true, 0, "https://", 0, 8) ? exact : "";
        }
        Set<String> candidates = new LinkedHashSet<>();
        Matcher matcher = TRANSCRIPT_LINK.matcher(supplied);
        while (matcher.find()) {
            String value = matcher.group();
            if (matcher.start() > 0 && supplied.charAt(matcher.start() - 1) == '(') {
                int balance = 0;
                for (int index = 0; index < value.length(); index++) {
                    if (value.charAt(index) == '(') balance++;
                    else if (value.charAt(index) == ')') balance--;
                }
                // Remove an outer Markdown/prose wrapper, never a balanced
                // parenthesis that belongs to a signed path or query value.
                while (balance < 0 && value.endsWith(")")) {
                    value = value.substring(0, value.length() - 1);balance++;
                }
            }
            value = clean(value);
            // Do not guess a valid URL when another supplied URL is malformed.
            if (value.isEmpty() || !value.regionMatches(true, 0, "https://", 0, 8)) return "";
            candidates.add(value);
            if (candidates.size() > 1) return "";
        }
        return candidates.size() == 1 ? candidates.iterator().next() : "";
    }
}

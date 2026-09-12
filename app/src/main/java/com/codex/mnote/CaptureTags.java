package com.codex.mnote;

import android.view.View;
import android.widget.EditText;
import org.json.JSONArray;
import java.util.*;

/** Tags are record metadata, not part of the captured source text. */
final class CaptureTags {
    static JSONArray parse(String input) {
        if(input.length()>4096) throw new IllegalArgumentException("标签输入过长；每条记录最多 20 个标签，每个最多 32 字。");
        JSONArray values = new JSONArray();
        for (String part : input.split("[,，;；\\n\\r]")) values.put(part);
        return normalize(values);
    }
    static JSONArray normalize(JSONArray input) {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (input == null) return result;
        for (int i = 0; i < input.length(); i++) {
            Object value = input.opt(i);
            if (!(value instanceof String)) throw new IllegalArgumentException("标签必须是文字。");
            String tag = ((String)value).trim();
            if (tag.startsWith("#")) tag = tag.substring(1).trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > 32 || java.util.regex.Pattern.compile("[,，;；\\p{Cntrl}]").matcher(tag).find())
                throw new IllegalArgumentException("每个标签最多 32 字，不能包含逗号、分号或控制字符。");
            if (seen.add(tag.toLowerCase(Locale.ROOT))) result.put(tag);
            if (result.length() > 20) throw new IllegalArgumentException("每条记录最多添加 20 个标签。");
        }
        return result;
    }
    static String input(JSONArray tags) {
        List<String> values = new ArrayList<>();
        if (tags != null) for (int i = 0; i < tags.length(); i++) values.add(tags.optString(i));
        return String.join("，", values);
    }
    static String display(JSONArray tags) { return tags.length() == 0 ? "" : "#" + input(tags).replace("，", "   #"); }
    static boolean matches(JSONArray tags, String filter) {
        if (filter == null) return true;
        if (filter.isEmpty()) return tags.length() == 0;
        for (int i = 0; i < tags.length(); i++) if (filter.equalsIgnoreCase(tags.optString(i))) return true;
        return false;
    }
    static final class Field {
        final EditText input;
        Field(View root) { input = root.findViewById(R.id.capture_tags_input); }
        JSONArray validated() {
            try { return parse(input.getText().toString()); }
            catch (IllegalArgumentException error) {
                input.setError(error.getMessage()); input.requestFocus();
                if(!input.isShown()) android.widget.Toast.makeText(input.getContext(),error.getMessage(),android.widget.Toast.LENGTH_LONG).show();
                return null;
            }
        }
    }
}

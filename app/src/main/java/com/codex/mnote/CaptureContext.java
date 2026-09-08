package com.codex.mnote;

import org.json.JSONObject;

/** Explicit provenance: retained context is not the quote, and neither is the user's comment. */
final class CaptureContext {
    static final int MAX_TEXT = 40_000;
    static JSONObject text(String original, String origin, String selected) throws org.json.JSONException {
        if (original.length() > MAX_TEXT) throw new IllegalArgumentException("context_too_large");
        int start = selected.isEmpty() ? -1 : original.indexOf(selected);
        boolean unique = start >= 0 && original.indexOf(selected,start+1) < 0;
        return new JSONObject().put("full_text",original).put("origin",origin)
                .put("extent","provided_text")
                .put("offset_unit","utf16_code_units")
                .put("match",unique ? "unique" : start < 0 ? "not_found" : "ambiguous")
                .put("start",unique ? start : JSONObject.NULL)
                .put("end",unique ? start+selected.length() : JSONObject.NULL);
    }
    static JSONObject image(JSONObject layer, int width, int height, boolean retained) throws org.json.JSONException {
        JSONObject selection = layer.getJSONObject("selection");
        double sx = (double)width/layer.getInt("sourceWidth"), sy = (double)height/layer.getInt("sourceHeight");
        return new JSONObject().put("retained",retained).put("asset_role",retained ? "context" : JSONObject.NULL)
                .put("annotation_coordinate_space","editor_bitmap_pixels")
                .put("editor_width",layer.getInt("sourceWidth")).put("editor_height",layer.getInt("sourceHeight"))
                .put("width",width).put("height",height).put("coordinate_space","context_image_pixels")
                .put("selected_asset_role","original")
                .put("selection",new JSONObject().put("left",selection.getDouble("left")*sx)
                        .put("top",selection.getDouble("top")*sy).put("right",selection.getDouble("right")*sx)
                        .put("bottom",selection.getDouble("bottom")*sy));
    }
}

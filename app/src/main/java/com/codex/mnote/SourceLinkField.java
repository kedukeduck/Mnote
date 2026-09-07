package com.codex.mnote;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Clipboard access only after an explicit Paste tap. */
final class SourceLinkField {
    final EditText input;
    private final View panel;
    private final TextView toggle;
    private String sharedUrl = "";
    private String automaticOrigin = "shared_text";

    SourceLinkField(View root) {
        input = root.findViewById(R.id.capture_source_url);
        panel = root.findViewById(R.id.capture_url_panel);
        toggle = root.findViewById(R.id.capture_url_toggle);
        toggle.setOnClickListener(view -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            updateLabel();
        });
        root.findViewById(R.id.capture_url_paste).setOnClickListener(view -> paste());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateLabel(); }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    void acceptSharedText(CharSequence text) {
        automaticOrigin = "shared_text";
        sharedUrl = CaptureSourceUrl.fromSharedText(text);
        input.setText(sharedUrl);
        if (!sharedUrl.isEmpty()) panel.setVisibility(View.VISIBLE);
        updateLabel();
    }

    String validated() {
        String raw = input.getText().toString().trim();
        String url = CaptureSourceUrl.clean(raw);
        if (!raw.isEmpty() && url.isEmpty()) {
            panel.setVisibility(View.VISIBLE);
            input.setError(input.getContext().getString(R.string.capture_url_invalid));
            input.requestFocus();
            return null;
        }
        return url;
    }

    String origin(String url) {
        return url.isEmpty() ? "" : url.equals(sharedUrl) ? automaticOrigin : "user_entered";
    }

    void acceptDetected(String url, String origin) {
        sharedUrl = CaptureSourceUrl.clean(url);
        automaticOrigin = origin;
        input.setText(sharedUrl);
        updateLabel();
    }

    boolean hasInput() { return !input.getText().toString().trim().isEmpty(); }

    private void updateLabel() {
        toggle.setText(hasInput() ? R.string.capture_url_attached
                : panel.getVisibility() == View.VISIBLE ? R.string.capture_url_hide : R.string.capture_url_add);
    }

    private void paste() {
        Context context = input.getContext();
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            ClipData data = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = data == null || data.getItemCount() == 0 ? null : data.getItemAt(0).getText();
            String url = CaptureSourceUrl.fromSharedText(text);
            if (url.isEmpty()) {
                Toast.makeText(context, R.string.capture_url_clipboard_empty, Toast.LENGTH_LONG).show();
                return;
            }
            input.setText(url);
            input.setSelection(url.length());
        } catch (RuntimeException error) {
            Toast.makeText(context, R.string.capture_url_clipboard_empty, Toast.LENGTH_LONG).show();
        }
    }
}

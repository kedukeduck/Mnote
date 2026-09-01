package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.GeneralSecurityException;

/** Local-only settings UI for the optional Capture Server writer credential. */
public final class CaptureSyncSettingsActivity extends Activity {
    private EditText baseUrlInput;
    private EditText tokenInput;
    private RadioGroup aiAccessGroup;
    private TextView status;
    private Button saveButton;
    private Button disableButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTitle(R.string.capture_sync_settings_title);
        setContentView(buildContent());
        populate();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.cream));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        back.setText(R.string.capture_sync_settings_back);
        back.setAllCaps(false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView title = text(
                getString(R.string.capture_sync_settings_title),
                24,
                R.color.ink
        );
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        titleParams.setMarginStart(dp(8));
        header.addView(title, titleParams);
        root.addView(header);

        TextView intro = text(
                getString(R.string.capture_sync_settings_intro),
                13,
                R.color.ink_muted
        );
        intro.setLineSpacing(0f, 1.22f);
        addWithTopMargin(root, intro, 14);

        addLabel(root, R.string.capture_sync_url_label, 22);
        baseUrlInput = new EditText(this);
        baseUrlInput.setSingleLine(true);
        baseUrlInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
        );
        baseUrlInput.setHint(R.string.capture_sync_url_hint);
        root.addView(baseUrlInput, matchWrap());

        TextView urlRule = text(
                getString(R.string.capture_sync_url_rule),
                12,
                R.color.ink_muted
        );
        addWithTopMargin(root, urlRule, 4);

        addLabel(root, R.string.capture_sync_token_label, 20);
        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        tokenInput.setImportantForAutofill(
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        );
        root.addView(tokenInput, matchWrap());

        TextView tokenRule = text(
                getString(R.string.capture_sync_token_rule),
                12,
                R.color.ink_muted
        );
        addWithTopMargin(root, tokenRule, 4);

        addLabel(root, R.string.capture_sync_ai_label, 22);
        aiAccessGroup = new RadioGroup(this);
        aiAccessGroup.setOrientation(RadioGroup.VERTICAL);
        addAiChoice(
                CaptureSyncPreferences.AI_DENY,
                R.string.capture_sync_ai_deny
        );
        addAiChoice(
                CaptureSyncPreferences.AI_LOCAL_ONLY,
                R.string.capture_sync_ai_local_only
        );
        addAiChoice(
                CaptureSyncPreferences.AI_REMOTE_NO_MEMORY,
                R.string.capture_sync_ai_remote_no_memory
        );
        addAiChoice(
                CaptureSyncPreferences.AI_REMOTE_MEMORY,
                R.string.capture_sync_ai_remote_memory
        );
        root.addView(aiAccessGroup, matchWrap());

        TextView aiRule = text(
                getString(R.string.capture_sync_ai_rule),
                12,
                R.color.ink_muted
        );
        addWithTopMargin(root, aiRule, 5);

        status = text("", 13, R.color.ink_muted);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addWithTopMargin(root, status, 18);

        saveButton = new Button(this);
        saveButton.setText(R.string.capture_sync_settings_save);
        saveButton.setAllCaps(false);
        saveButton.setTextColor(getColor(R.color.white));
        saveButton.setTextSize(15);
        saveButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        saveButton.setBackgroundResource(R.drawable.bg_button_primary);
        saveButton.setOnClickListener(view -> save());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        saveParams.topMargin = dp(12);
        root.addView(saveButton, saveParams);

        disableButton = new Button(this);
        disableButton.setText(R.string.capture_sync_settings_disable);
        disableButton.setAllCaps(false);
        disableButton.setTextColor(getColor(R.color.danger));
        disableButton.setBackgroundResource(R.drawable.bg_button_secondary);
        disableButton.setOnClickListener(view -> confirmDisable());
        LinearLayout.LayoutParams disableParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
        );
        disableParams.topMargin = dp(8);
        root.addView(disableButton, disableParams);
        return scroll;
    }

    private void populate() {
        baseUrlInput.setText(CaptureSyncPreferences.configuredBaseUrl(this));
        tokenInput.setHint(
                CaptureSyncPreferences.hasStoredToken(this)
                        ? R.string.capture_sync_token_saved_hint
                        : R.string.capture_sync_token_hint
        );
        String selected = CaptureSyncPreferences.defaultAiAccess(this);
        for (int index = 0; index < aiAccessGroup.getChildCount(); index++) {
            View child = aiAccessGroup.getChildAt(index);
            if (selected.equals(child.getTag())) {
                aiAccessGroup.check(child.getId());
                break;
            }
        }
        boolean configured = CaptureSyncPreferences.isConfigured(this);
        disableButton.setVisibility(configured ? View.VISIBLE : View.GONE);
        status.setText(
                configured
                        ? R.string.capture_sync_settings_configured
                        : R.string.capture_sync_settings_not_configured
        );
    }

    private void save() {
        saveButton.setEnabled(false);
        baseUrlInput.setError(null);
        tokenInput.setError(null);
        try {
            CaptureSyncPreferences.save(
                    this,
                    baseUrlInput.getText().toString(),
                    tokenInput.getText().toString(),
                    selectedAiAccess()
            );
            CaptureStore.markAllForSync(this);
            CaptureSyncWorker.enqueue(this);
            Toast.makeText(
                    this,
                    R.string.capture_sync_settings_saved,
                    Toast.LENGTH_LONG
            ).show();
            finish();
        } catch (IllegalArgumentException error) {
            status.setTextColor(getColor(R.color.danger));
            status.setText(R.string.capture_sync_settings_invalid);
            baseUrlInput.setError(error.getMessage());
            saveButton.setEnabled(true);
        } catch (GeneralSecurityException error) {
            status.setTextColor(getColor(R.color.danger));
            status.setText(R.string.capture_sync_settings_security_error);
            tokenInput.setError(getString(R.string.capture_sync_settings_security_error));
            saveButton.setEnabled(true);
        }
    }

    private void confirmDisable() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.capture_sync_disable_title)
                .setMessage(R.string.capture_sync_disable_detail)
                .setPositiveButton(
                        R.string.capture_sync_disable_confirm,
                        (dialog, which) -> disableSync()
                )
                .setNegativeButton(R.string.capture_cancel, null)
                .show();
    }

    private void disableSync() {
        saveButton.setEnabled(false);
        disableButton.setEnabled(false);
        try {
            CaptureSyncWorker.cancel(this);
            CaptureSyncPreferences.clearCredentials(this);
            CaptureStore.markAllLocalOnly(this);
            Toast.makeText(
                    this,
                    R.string.capture_sync_disabled,
                    Toast.LENGTH_LONG
            ).show();
            finish();
        } catch (GeneralSecurityException error) {
            status.setTextColor(getColor(R.color.danger));
            status.setText(R.string.capture_sync_disable_failed);
            saveButton.setEnabled(true);
            disableButton.setEnabled(true);
        }
    }

    private String selectedAiAccess() {
        View selected = findViewById(aiAccessGroup.getCheckedRadioButtonId());
        Object tag = selected == null ? null : selected.getTag();
        return tag instanceof String
                ? (String) tag
                : CaptureSyncPreferences.AI_LOCAL_ONLY;
    }

    private void addAiChoice(String value, int label) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setTag(value);
        option.setText(label);
        option.setTextColor(getColor(R.color.ink));
        option.setTextSize(14);
        option.setPadding(0, dp(5), 0, dp(5));
        aiAccessGroup.addView(option, matchWrap());
    }

    private void addLabel(LinearLayout root, int label, int topMarginDp) {
        TextView view = text(getString(label), 15, R.color.ink);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        addWithTopMargin(root, view, topMarginDp);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(color));
        return view;
    }

    private void addWithTopMargin(LinearLayout root, View view, int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topMarginDp);
        root.addView(view, params);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

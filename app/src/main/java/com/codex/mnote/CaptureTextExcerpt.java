package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Text handoffs contain a quote, not necessarily an article. Context is explicitly supplied. */
final class CaptureTextExcerpt {
    private final Activity activity;
    private final TextView text;
    private final CompoundButton retain;
    private final View controls;
    private final Consumer<String> selected;
    private String original = "", origin = "user_supplied";
    private int selectedStart = -1;
    private RadioGroup previewModes;
    private String quote = "";
    CaptureTextExcerpt(Activity activity, Consumer<String> selected) {
        this.activity=activity; this.selected=selected;
        text=activity.findViewById(R.id.capture_source_text);
        retain=activity.findViewById(R.id.capture_retain_text_context);
        controls=activity.findViewById(R.id.capture_text_context_controls);
        text.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            public boolean onCreateActionMode(ActionMode mode,Menu menu) {
                menu.add(0,R.id.capture_excerpt_action,0,R.string.capture_excerpt_action).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }
            public boolean onPrepareActionMode(ActionMode mode,Menu menu) { return false; }
            public boolean onActionItemClicked(ActionMode mode,MenuItem item) {
                if(item.getItemId()!=R.id.capture_excerpt_action) return false;
                int start=Math.min(text.getSelectionStart(),text.getSelectionEnd()), end=Math.max(text.getSelectionStart(),text.getSelectionEnd());
                if(start>=0 && end>start) choose(start,end);
                mode.finish(); return true;
            }
            public void onDestroyActionMode(ActionMode mode) { }
        });
        activity.findViewById(R.id.capture_text_context_edit).setOnClickListener(view->editOriginal());
        retain.setOnCheckedChangeListener((button,checked)-> {
            if(checked && original.isEmpty()) { retain.setChecked(false); editOriginal(); }
        });
    }
    void show(String supplied,boolean selectedByOtherApp) {
        quote=supplied;
        controls.setVisibility(View.VISIBLE);
        original=selectedByOtherApp || supplied.length()>CaptureContext.MAX_TEXT ? "" : supplied;
        origin=selectedByOtherApp ? "user_supplied" : "shared_text";
        activity.<TextView>findViewById(R.id.capture_editor_status).setText(selectedByOtherApp
                ? R.string.capture_text_selected_help : R.string.capture_text_shared_help);
    }
    void choose(int start,int end) {
        String displayed=text.getText().toString();
        int base=displayed.equals(original) ? 0 : selectedStart;
        selectedStart=base<0 ? -1 : base+start;
        String excerpt=text.getText().subSequence(start,end).toString();
        quote=excerpt; selected.accept(excerpt); text.setText(excerpt);
        if(previewModes!=null) previewModes.check(R.id.capture_text_preview_quote);
        Toast.makeText(activity,R.string.capture_excerpt_chosen,Toast.LENGTH_SHORT).show();
    }
    void detectedOriginal(String value,int start) {
        original=value; origin="accessibility_node"; selectedStart=start;
    }
    RadioGroup previewModes() {
        previewModes=CapturePreviewModes.create(activity,true,full->text.setText(full ? original : quote));
        updatePreviewAvailability();
        return previewModes;
    }
    private void updatePreviewAvailability() {
        if(previewModes!=null) {
            previewModes.findViewById(R.id.capture_text_preview_original).setEnabled(!original.isEmpty());
            if(original.isEmpty()) previewModes.check(R.id.capture_text_preview_quote);
        }
    }
    private void editOriginal() {
        EditText input=new EditText(activity); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(5); input.setMaxLines(10); input.setGravity(Gravity.TOP);
        CaptureLongText.enableScrolling(input);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setHint(R.string.capture_context_paste_hint); input.setText(original);
        FrameLayout inset = new FrameLayout(activity);
        int margin = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        inset.setPadding(margin, margin / 2, margin, 0);
        inset.addView(input, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.capture_text_context_edit)
                .setMessage(R.string.capture_context_provenance_help).setView(inset)
                .setPositiveButton(R.string.capture_context_use,null)
                .setNegativeButton(R.string.capture_cancel,null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=input.getText().toString();
            if(value.length()>CaptureContext.MAX_TEXT) {input.setError("原文最多 4 万字；未截断，请缩短后再使用。");return;}
            if(!value.equals(original)) { origin="user_supplied"; selectedStart=-1; }
            original=value; retain.setChecked(!original.isEmpty()); updatePreviewAvailability();
            if(previewModes!=null && previewModes.getCheckedRadioButtonId()==R.id.capture_text_preview_original) text.setText(original);
            dialog.dismiss();
        });
    }
    JSONObject context(String quote) throws org.json.JSONException {
        if(!retain.isChecked() || original.isEmpty()) return null;
        JSONObject value=CaptureContext.text(original,origin,quote);
        if("accessibility_node".equals(origin)) value.put("extent","source_text_node");
        if(selectedStart>=0 && original.startsWith(quote,selectedStart)) value.put("match","user_selected")
                .put("start",selectedStart).put("end",selectedStart+quote.length());
        return value;
    }
    void save(Bundle state) { state.putString("excerpt_original",original); state.putString("excerpt_origin",origin); state.putBoolean("excerpt_retain",retain.isChecked()); state.putInt("excerpt_start",selectedStart); }
    void restore(Bundle state) {
        original=state.getString("excerpt_original",original); origin=state.getString("excerpt_origin",origin);
        selectedStart=state.getInt("excerpt_start",-1);
        retain.setChecked(state.getBoolean("excerpt_retain",false));
        quote=state.getString("excerpt_quote",quote);
    }
}

package com.codex.mnote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.net.Uri;
import android.text.format.DateFormat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Local Inbox and setup page for universal captures. */
public final class CaptureInboxActivity extends Activity {
    private static final int RECORD_LIMIT = 50;

    private final ExecutorService thumbnailExecutor =
            Executors.newSingleThreadExecutor(new ThumbnailThreadFactory());
    private final List<Bitmap> thumbnails = new ArrayList<>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
    private Button refreshButton;
    private TextView refreshStatus;
    private boolean refreshing;
    private final BroadcastReceiver syncChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CaptureSyncWorker.ACTION_SYNC_CHANGED.equals(intent.getAction())
                    || CaptureStore.ACTION_RECORDS_CHANGED.equals(intent.getAction())
                    || AiChatStore.ACTION_CHANGED.equals(intent.getAction())) {
                renderRecords();
            }
        }
    };

    private TextView recordCount;
    private TextView syncStatus;
    private Button syncAllButton;
    private LinearLayout recordsContainer;
    private View emptyState;
    private volatile int renderGeneration;
    private volatile boolean destroyed;
    private boolean syncReceiverRegistered;
    private List<CaptureStore.CaptureRecord> libraryRecords = new ArrayList<>();
    private EditText searchInput;
    private RadioGroup filterGroup;
    private String tagFilter, filterScope;
    private int chatFilter, chatCountGeneration;
    private java.util.Map<String,Integer> chatCounts=new java.util.HashMap<>();
    private int visibleLimit = RECORD_LIMIT;
    private final java.util.Set<String> selectedRecords = new java.util.LinkedHashSet<>();
    private boolean selecting, deletingSelection;
    private List<CaptureStore.CaptureRecord> visibleRecords = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_inbox);
        CaptureStore.cleanupStaleDrafts(this);
        bindViews();
        bindActions();
        createSelectionDock();
        filterScope = CaptureAccountSession.scope(this);
        if (savedInstanceState != null && filterScope.equals(savedInstanceState.getString("tag_scope"))) {
            tagFilter = savedInstanceState.getString("tag_filter");
            chatFilter=savedInstanceState.getInt("chat_filter",0);
            selecting = savedInstanceState.getBoolean("selecting");
            ArrayList<String> restored = savedInstanceState.getStringArrayList("selected_records");
            if (restored != null) selectedRecords.addAll(restored.subList(0, Math.min(100, restored.size())));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRecords();
        if (CaptureAccountSession.hasAccount(this)) CaptureAccountSync.enqueue(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!syncReceiverRegistered) {
            IntentFilter changes = new IntentFilter(CaptureSyncWorker.ACTION_SYNC_CHANGED);
            changes.addAction(CaptureStore.ACTION_RECORDS_CHANGED);
            changes.addAction(AiChatStore.ACTION_CHANGED);
            ContextCompat.registerReceiver(
                    this,
                    syncChangedReceiver,
                    changes,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            syncReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (syncReceiverRegistered) {
            unregisterReceiver(syncChangedReceiver);
            syncReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        renderGeneration++;
        thumbnailExecutor.shutdownNow();
        refreshExecutor.shutdownNow();
        clearThumbnails();
        super.onDestroy();
    }

    private void bindViews() {
        recordCount = findViewById(R.id.capture_record_count);
        syncStatus = findViewById(R.id.capture_sync_status);
        syncAllButton = findViewById(R.id.capture_sync_all_button);
        refreshButton = findViewById(R.id.capture_refresh_button);
        refreshStatus = findViewById(R.id.capture_refresh_status);
        recordsContainer = findViewById(R.id.capture_records);
        emptyState = findViewById(R.id.capture_empty);
        searchInput = findViewById(R.id.capture_search);
        filterGroup = findViewById(R.id.capture_filter_group);
    }

    private void bindActions() {
        findViewById(R.id.capture_export_markdown).setOnClickListener(view -> {
            if(selecting) {
                findViewById(R.id.capture_selection_export).performClick();
                return;
            }
            selecting = true;
            selectedRecords.clear();
            updateSelection();
        });
        findViewById(R.id.capture_settings_button).setOnClickListener(view -> startActivity(new Intent(this,SettingsActivity.class)));
        filterGroup.setOnCheckedChangeListener((group, id) -> { visibleLimit=RECORD_LIMIT; renderFilteredRecords(); });
        findViewById(R.id.capture_tag_filter).setOnClickListener(view -> chooseTag());
        findViewById(R.id.capture_load_more).setOnClickListener(view -> { visibleLimit+=RECORD_LIMIT; renderFilteredRecords(); });
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
            return true;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                visibleLimit=RECORD_LIMIT;
                renderFilteredRecords();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        syncAllButton.setOnClickListener(view -> syncAll());
        refreshButton.setOnClickListener(view -> refreshRecords());
        ((JournalRefreshScroll)findViewById(R.id.journal_library_scroll)).setRefreshAction(this::refreshRecords);
        findViewById(R.id.journal_filter_summary).setOnClickListener(view -> {
            tagFilter = null; chatFilter=0; searchInput.setText(""); filterGroup.check(R.id.capture_filter_all); renderFilteredRecords();
        });
    }

    private void refreshRecords() {
        refreshStatus.setVisibility(View.VISIBLE);
        // Local refresh must work even offline, without a token, or during a remote pull.
        renderRecords();
        if (refreshing) return;
        if (!CaptureAccountSession.hasAccount(this)) {
            refreshStatus.setText("本机记录已刷新；登录后自动同步云端记录。");
            return;
        }
        final CaptureSyncPreferences.Config config;
        try { config = CaptureSyncPreferences.load(this); }
        catch (Exception error) {
            refreshStatus.setText(R.string.capture_refresh_setup);
            openSyncSettings();
            return;
        }
        refreshing = true;
        refreshButton.setEnabled(false);
        refreshButton.setText(R.string.capture_refresh_busy);
        refreshStatus.setText(R.string.capture_refresh_downloading);
        refreshExecutor.execute(() -> {
            String message;
            try {
                int count = CaptureAccountSync.run(getApplicationContext());
                message = count == 0 ? getString(R.string.capture_refresh_current)
                        : getString(R.string.capture_refresh_success, count);
            } catch (Exception error) {
                String reason = error.getMessage();
                message = getString("http_401".equals(reason) || "http_403".equals(reason)
                        ? R.string.capture_refresh_auth_error : "more_records_pending".equals(reason)
                        ? R.string.capture_refresh_more : "configuration_changed".equals(reason)
                        ? R.string.capture_refresh_changed : R.string.capture_refresh_failed);
                if("revision_conflict".equals(reason)) message="云端有新版本，本机修改已保留，未自动覆盖。其他记录已继续同步。";
            }
            String result = message;
            runOnUiThread(() -> {
                if (destroyed) return;
                refreshing = false;
                refreshButton.setEnabled(true);
                refreshButton.setText(R.string.capture_refresh);
                refreshStatus.setText(result);
                renderRecords();
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void chooseTag() {
        final String scope = CaptureAccountSession.scope(this);
        final String[] pendingTag = {tagFilter};
        final int[] pendingType = {filterGroup.getCheckedRadioButtonId()};
        final int[] pendingChat={chatFilter};
        LinearLayout body = JournalUi.column(this);
        body.setPadding(dp(22), dp(16), dp(22), dp(20));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = JournalUi.text(this, "筛选记录", 24, R.color.ink);
        heading.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        Button close = new Button(this); close.setText("取消"); JournalUi.quiet(close);
        heading.addView(close); body.addView(heading);
        JournalUi.section(body, "记录类型");
        RadioGroup types = new RadioGroup(this);
        types.setId(R.id.journal_filter_types);
        types.setOrientation(RadioGroup.HORIZONTAL);
        int[] ids = {R.id.capture_filter_all, R.id.capture_filter_excerpt, R.id.capture_filter_thought, R.id.capture_filter_todo};
        String[] names = {"全部", "摘录", "想法", "待办"};
        for (int i=0;i<ids.length;i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(this, null, 0, R.style.CaptureSegment);
            button.setId(ids[i]); button.setText(names[i]);
            types.addView(button, new RadioGroup.LayoutParams(0,-2,1));
        }
        types.check(pendingType[0]); body.addView(types);
        JournalUi.section(body,"AI 对话");
        RadioGroup chats=new RadioGroup(this);chats.setId(R.id.ai_filter_group);chats.setOrientation(RadioGroup.HORIZONTAL);
        int[] chatIds={R.id.ai_filter_all,R.id.ai_filter_chatted,R.id.ai_filter_unchatted};
        String[] chatNames={"全部","已聊过","未聊过"};
        for(int i=0;i<chatIds.length;i++){
            android.widget.RadioButton button=new android.widget.RadioButton(this,null,0,R.style.CaptureSegment);
            button.setId(chatIds[i]);button.setText(chatNames[i]);chats.addView(button,new RadioGroup.LayoutParams(0,-2,1));
        }
        chats.check(chatIds[Math.max(0,Math.min(2,pendingChat[0]))]);body.addView(chats);
        JournalUi.section(body, "标签");
        EditText tagSearch = new EditText(this);
        tagSearch.setId(R.id.journal_filter_search);
        tagSearch.setHint("搜索已有标签"); tagSearch.setSingleLine(true);
        tagSearch.setTextSize(16); tagSearch.setMinHeight(dp(48));
        tagSearch.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(tagSearch, new LinearLayout.LayoutParams(-1,-2));
        RadioGroup tags = new RadioGroup(this);
        tags.setId(R.id.journal_filter_tags);
        java.util.Map<String,String> values = new java.util.TreeMap<>();
        for (CaptureStore.CaptureRecord r : libraryRecords)
            for(int i=0;i<r.tags.length();i++) {
                String name=r.tags.optString(i);
                values.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT),name);
            }
        List<String> tagValues = new ArrayList<>(); tagValues.add(null); tagValues.add(""); tagValues.addAll(values.values());
        int selectedTagId = View.NO_ID;
        for (String value : tagValues) {
            android.widget.RadioButton radio = new android.widget.RadioButton(this);
            radio.setId(View.generateViewId()); radio.setTag(value);
            radio.setText(value==null ? "全部标签" : value.isEmpty() ? "未分类" : "#"+value);
            radio.setTextSize(16); radio.setMinHeight(dp(48));
            tags.addView(radio, new RadioGroup.LayoutParams(-1,-2));
            if (java.util.Objects.equals(value, pendingTag[0])) selectedTagId = radio.getId();
        }
        tags.check(selectedTagId);
        tagSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                String query=s.toString().trim().toLowerCase(java.util.Locale.ROOT);
                for(int i=0;i<tags.getChildCount();i++) {
                    View tag=tags.getChildAt(i); String value=(String)tag.getTag();
                    tag.setVisibility(value==null || value.isEmpty() || value.toLowerCase(java.util.Locale.ROOT).contains(query)
                            ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        ScrollView tagScroll=new ScrollView(this); tagScroll.addView(tags);
        body.addView(tagScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this); actions.setPadding(0,dp(16),0,0);
        Button reset=new Button(this); reset.setText("重置"); reset.setId(R.id.journal_filter_reset); JournalUi.quiet(reset);
        actions.addView(reset,new LinearLayout.LayoutParams(-2,-2));
        Button apply=new Button(this); apply.setId(R.id.journal_filter_apply); JournalUi.primary(apply);
        actions.addView(apply,new LinearLayout.LayoutParams(0,-2,1)); body.addView(actions);
        Runnable count=()->apply.setText("查看 "+matchingRecords(pendingType[0],pendingTag[0],pendingChat[0]).size()+" 条记录");
        chats.setOnCheckedChangeListener((group,id)->{pendingChat[0]=id==R.id.ai_filter_chatted?1:id==R.id.ai_filter_unchatted?2:0;count.run();});
        types.setOnCheckedChangeListener((group,id)->{pendingType[0]=id;count.run();});
        tags.setOnCheckedChangeListener((group,id)->{View chosen=tags.findViewById(id);pendingTag[0]=chosen==null?null:(String)chosen.getTag();count.run();});
        final int allTagId=tags.getChildAt(0).getId();
        reset.setOnClickListener(v->{tagSearch.setText("");types.check(R.id.capture_filter_all);tags.check(allTagId);chats.check(R.id.ai_filter_all);count.run();});
        AlertDialog dialog=new AlertDialog.Builder(this).create();
        dialog.setView(body,0,0,0,0); dialog.show();
        close.setOnClickListener(v->dialog.dismiss());
        android.view.Window window=dialog.getWindow();
        if(window!=null) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.setGravity(android.view.Gravity.BOTTOM);
            window.setBackgroundDrawableResource(R.drawable.bg_dialog);
            int available=getResources().getDisplayMetrics().heightPixels;
            window.setLayout(-1,Math.min(dp(580),Math.max(dp(240),available-dp(48))));
        }
        count.run();
        apply.setOnClickListener(v->{
            dialog.dismiss(); if(!scope.equals(CaptureAccountSession.scope(this)))return;
            tagFilter=pendingTag[0]; chatFilter=pendingChat[0]; visibleLimit=RECORD_LIMIT;
            filterGroup.check(pendingType[0]);renderFilteredRecords();
        });
    }

    private List<CaptureStore.CaptureRecord> matchingRecords(int filter,String tag) {
        return matchingRecords(filter,tag,chatFilter);
    }
    private List<CaptureStore.CaptureRecord> matchingRecords(int filter,String tag,int chats) {
        List<CaptureStore.CaptureRecord> result=new ArrayList<>();
        String query=searchInput.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        for(CaptureStore.CaptureRecord r:libraryRecords) {
            boolean match=filter==R.id.capture_filter_excerpt
                ? hasMaterial(r)
                : filter==R.id.capture_filter_thought ? hasThought(r)
                : filter==R.id.capture_filter_todo ? "todo".equals(r.kind) : true;
            String searchable=r.comment+"\n"+r.sourceText+"\n"+CaptureRecordEdits.original(r)+"\n"+r.sourceUrl+"\n"+CaptureTags.input(r.tags);
            boolean chatted=chatCounts.getOrDefault(r.id,0)>0;
            if(match && (chats==0 || (chats==1?chatted:!chatted)) && CaptureTags.matches(r.tags,tag) && searchable.toLowerCase(java.util.Locale.ROOT).contains(query))result.add(r);
        }
        return result;
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tag_filter",tagFilter);state.putString("tag_scope",filterScope);
        state.putInt("chat_filter",chatFilter);
        state.putBoolean("selecting", selecting);
        state.putStringArrayList("selected_records", new ArrayList<>(selectedRecords));
        super.onSaveInstanceState(state);
    }

    private void renderRecords() {
        String currentScope=CaptureAccountSession.scope(this);
        if (!currentScope.equals(filterScope)) { tagFilter=null; chatFilter=0;chatCounts.clear();filterScope=currentScope; visibleLimit=RECORD_LIMIT; selectedRecords.clear(); selecting=false; }
        List<CaptureStore.CaptureRecord> allRecords = CaptureStore.list(
                this,
                Integer.MAX_VALUE
        );
        renderSyncStatus(allRecords);
        libraryRecords = CaptureRemoteCache.merged(this, allRecords);
        renderFilteredRecords();
        int chatGeneration=++chatCountGeneration;
        refreshExecutor.execute(()->{try{
            CaptureAccountSession.requireScope(this,currentScope);
            java.util.Map<String,Integer> counts=AiChatStore.completedCounts(this);
            runOnUiThread(()->{if(!destroyed&&chatGeneration==chatCountGeneration&&currentScope.equals(CaptureAccountSession.scope(this))&&!counts.equals(chatCounts)){
                chatCounts=counts;renderFilteredRecords();
            }});
        }catch(Exception ignored){}});
    }

    private void renderFilteredRecords() {
        ((Button)findViewById(R.id.capture_tag_filter)).setText("筛选");
        Button summary=findViewById(R.id.journal_filter_summary);
        int activeType=filterGroup.getCheckedRadioButtonId();
        boolean hasFilter=tagFilter!=null || activeType!=R.id.capture_filter_all || chatFilter!=0;
        summary.setVisibility(hasFilter?View.VISIBLE:View.GONE);
        String type=activeType==R.id.capture_filter_excerpt?"摘录":activeType==R.id.capture_filter_thought?"想法":activeType==R.id.capture_filter_todo?"待办":"全部类型";
        summary.setText(type+(tagFilter==null?"":tagFilter.isEmpty()?" · 未分类":" · #"+tagFilter)+(chatFilter==1?" · 已聊过":chatFilter==2?" · 未聊过":"")+" · 清除筛选");
        int generation = ++renderGeneration;
        clearThumbnails();
        recordsContainer.removeAllViews();
        String query = searchInput.getText().toString().trim();
        int filter = filterGroup.getCheckedRadioButtonId();
        List<CaptureStore.CaptureRecord> allRecords=matchingRecords(filter,tagFilter);
        List<CaptureStore.CaptureRecord> records = allRecords.size() <= visibleLimit
                ? allRecords
                : new ArrayList<>(allRecords.subList(0, visibleLimit));
        visibleRecords = records;
        int selectedBefore=selectedRecords.size();
        selectedRecords.removeIf(id -> allRecords.stream().noneMatch(r -> r.id.equals(id)));
        if(selecting && selectedBefore>selectedRecords.size()) Toast.makeText(this,"已移除 "+(selectedBefore-selectedRecords.size())+" 条不符合筛选的选择",Toast.LENGTH_SHORT).show();
        findViewById(R.id.capture_load_more).setVisibility(allRecords.size()>visibleLimit ? View.VISIBLE : View.GONE);
        recordCount.setText(filteredCount(allRecords.size(), libraryRecords.size()));
        TextView emptyTitle = (TextView) ((LinearLayout) emptyState).getChildAt(1);
        TextView emptyDetail = (TextView) ((LinearLayout) emptyState).getChildAt(2);
        boolean filtered = !query.isEmpty() || filter != R.id.capture_filter_all || tagFilter!=null || chatFilter!=0;
        emptyTitle.setText(filtered ? R.string.capture_search_empty_title : R.string.capture_empty_title);
        emptyDetail.setText(filtered ? R.string.capture_search_empty_detail : R.string.capture_empty_detail);
        emptyState.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        recordsContainer.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        String lastDay="";
        for (CaptureStore.CaptureRecord record : records) {
            View card = inflater.inflate(
                    R.layout.item_capture_record,
                    recordsContainer,
                    false
            );
            bindRecord(card, record, generation);
            TextView chatBadge=card.findViewById(R.id.ai_record_badge);
            int chats=chatCounts.getOrDefault(record.id,0);chatBadge.setVisibility(chats>0?View.VISIBLE:View.GONE);
            chatBadge.setText("AI · "+chats);chatBadge.setContentDescription("已与 AI 聊过，共 "+chats+" 段对话");
            String day=DateFormat.format("yyyy-MM-dd",record.createdAt).toString();
            if(!day.equals(lastDay)) {
                TextView heading=card.findViewById(R.id.journal_item_day);
                heading.setVisibility(View.VISIBLE);
                java.time.LocalDate date=java.time.Instant.ofEpochMilli(record.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                java.time.LocalDate today=java.time.LocalDate.now();
                String prefix=date.equals(today)?"今天  ":date.equals(today.minusDays(1))?"昨天  ":"";
                heading.setText(prefix+DateFormat.format("M月d日  EEEE",record.createdAt));
                lastDay=day;
            }
            recordsContainer.addView(card);
        }
        updateSelection();
    }

    private void bindRecord(
            View card,
            CaptureStore.CaptureRecord record,
            int generation
    ) {
        String ownerScope = CaptureAccountSession.scope(this);
        RecordTagsView kind = card.findViewById(R.id.capture_item_kind);
        TextView time = card.findViewById(R.id.capture_item_time);
        TextView comment = card.findViewById(R.id.capture_item_comment);
        TextView source = card.findViewById(R.id.capture_item_source);
        TextView exactText = card.findViewById(R.id.capture_item_exact_text);
        TextView sync = card.findViewById(R.id.capture_item_sync_status);
        ImageView image = card.findViewById(R.id.capture_item_image);
        image.setClipToOutline(true);

        kind.setCategories(recordCategories(record));
        ((RecordTagsView)card.findViewById(R.id.capture_item_tags)).setTags(record.tags);
        time.setText(DateFormat.format(
                "HH:mm",
                new Date(record.createdAt)
        ));
        boolean hasComment = !record.comment.trim().isEmpty();
        boolean hasMaterial = hasMaterial(record);
        TextView commentLabel = card.findViewById(R.id.capture_item_comment_label);
        commentLabel.setText("todo".equals(record.kind) ? R.string.capture_preview_todo : R.string.capture_preview_thought);
        commentLabel.setVisibility(hasComment ? View.VISIBLE : View.GONE);
        setOptionalText(comment, hasComment ? record.comment : "");
        comment.setMaxLines(hasMaterial ? 3 : 4);
        source.setText(sourceTypeLabel(record.sourceType));
        if (!record.sourceUrl.isEmpty()) {
            source.append(" · " + getString(R.string.capture_url_saved_badge));
        }
        sync.setText(syncStateLabel(record.syncState));
        if("http_409".equals(record.syncLastError)) sync.setText("云端有新版本 · 本机修改已保留");
        if (CaptureStore.SYNC_SYNCED.equals(record.syncState)) {
            sync.setTextColor(getColor(R.color.success));
        } else if (CaptureStore.SYNC_FAILED.equals(record.syncState)) {
            sync.setTextColor(getColor(R.color.danger));
        } else {
            sync.setTextColor(getColor(R.color.ink_muted));
        }
        sync.setVisibility(CaptureStore.SYNC_FAILED.equals(record.syncState) ? View.VISIBLE : View.GONE);
        String original = CaptureRecordEdits.original(record).trim();
        String quote = record.sourceText.trim();
        String excerpt = quote.isEmpty() ? original : quote;
        TextView excerptLabel = card.findViewById(R.id.capture_item_excerpt_label);
        excerptLabel.setText(!quote.isEmpty() ? R.string.capture_preview_excerpt
                : !original.isEmpty() ? R.string.capture_preview_original : R.string.capture_preview_image);
        card.findViewById(R.id.capture_item_excerpt_block).setVisibility(hasMaterial ? View.VISIBLE : View.GONE);
        setOptionalText(exactText, ellipsize(excerpt, 420));
        java.io.File thumbnailFile = record.hasImage ? record.annotatedFile : record.contextFile;
        boolean hasPreview = thumbnailFile != null;
        if (!hasComment && !hasMaterial) setOptionalText(comment, "未填写文字");
        image.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        if (hasPreview) {
            thumbnailExecutor.execute(() -> {
                // A fast sequence of search/filter changes must not queue obsolete image decodes.
                if (destroyed || generation != renderGeneration) return;
                Bitmap thumbnail = CaptureStore.decodeThumbnail(
                        thumbnailFile
                );
                runOnUiThread(() -> {
                    if (thumbnail == null) {
                        image.setVisibility(View.GONE);
                        return;
                    }
                    if (destroyed || generation != renderGeneration) {
                        thumbnail.recycle();
                        return;
                    }
                    thumbnails.add(thumbnail);
                    image.setImageBitmap(thumbnail);
                });
            });
        }
        card.setContentDescription(
                (hasComment ? commentLabel.getText() + "：" + ellipsize(record.comment, 420) + "，" : "")
                        + (hasMaterial ? excerptLabel.getText() + "：" + exactText.getText() + (hasPreview ? "，含截图" : "") + "，" : "")
                        + kind.getContentDescription() + "，" + DateFormat.format("yyyy-MM-dd HH:mm",record.createdAt) + "，" + source.getText()
                        + "，自定义标签：" + CaptureTags.display(record.tags) + "，" + sync.getText() + "，"
                        + getString(R.string.capture_detail_open_hint)
        );
        card.setClickable(true);
        card.setFocusable(true);
        card.setTag(record.id);
        android.widget.CheckBox selected = new android.widget.CheckBox(this);
        selected.setId(R.id.capture_item_selected);
        selected.setContentDescription("选择这条记录");
        selected.setClickable(false); selected.setFocusable(false);
        selected.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        selected.setMinWidth(0); selected.setMinimumWidth(0);
        ((LinearLayout) card.findViewById(R.id.journal_item_body)).addView(selected, 0,
                new LinearLayout.LayoutParams(dp(44),dp(48)));
        card.setOnClickListener(view -> {
            if (deletingSelection || !ownerScope.equals(CaptureAccountSession.scope(this))) return;
            if (selecting) toggleSelection(record.id); else showRecordDetail(record, ownerScope);
        });
        card.setOnLongClickListener(view -> {
            if (!deletingSelection && ownerScope.equals(CaptureAccountSession.scope(this))) {
                selecting=true; toggleSelection(record.id);
            }
            return true;
        });
    }

    private void createSelectionDock() {
        LinearLayout root = findViewById(R.id.journal_library_root);
        LinearLayout dock = new LinearLayout(this); dock.setId(R.id.capture_selection_dock);
        dock.setOrientation(LinearLayout.VERTICAL); dock.setBackgroundColor(getColor(R.color.cream));
        int padding=Math.round(16*getResources().getDisplayMetrics().density);
        dock.setPadding(padding, padding/2, padding, padding/2);
        root.addView(dock, new LinearLayout.LayoutParams(-1,-2));
        LinearLayout top = new LinearLayout(this); top.setId(R.id.journal_selection_header);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setPadding(dp(22),dp(8),dp(22),dp(8));root.addView(top,0,new LinearLayout.LayoutParams(-1,-2));
        TextView count = new TextView(this); count.setId(R.id.capture_selection_count); count.setTextColor(getColor(R.color.ink)); count.setTextSize(15);
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        top.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        selectionButton(top,R.id.capture_selection_all,"全选当前",()->{
            for (CaptureStore.CaptureRecord r : visibleRecords) { if(selectedRecords.size()>=100) break; selectedRecords.add(r.id); }
            updateSelection();
        });
        selectionButton(top,R.id.capture_selection_cancel,"取消",this::exitSelection);
        LinearLayout actions = new LinearLayout(this); dock.addView(actions);
        Button delete = selectionButton(actions,R.id.capture_selection_delete,"删除",this::deleteSelection);
        delete.setTextColor(getColor(R.color.danger)); delete.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button export = selectionButton(actions,R.id.capture_selection_export,"导出 Markdown",()->{
            if (selectedRecords.isEmpty() || !filterScope.equals(CaptureAccountSession.scope(this))) return;
            startActivity(new Intent(this,MarkdownExportActivity.class)
                .putExtra("inline_export", true)
                .putExtra("selection_scope",filterScope).putStringArrayListExtra("record_ids",new ArrayList<>(selectedRecords)));
        });
        export.setBackgroundResource(R.drawable.bg_button_primary); export.setTextColor(getColor(R.color.white));
        export.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        updateSelection();
    }
    private Button selectionButton(LinearLayout root,int id,String text,Runnable action) {
        Button button = new Button(this);button.setId(id);button.setText(text);button.setTextSize(13);
        button.setBackgroundResource(android.R.color.transparent);root.addView(button);
        button.setOnClickListener(v->{if(!deletingSelection) action.run();});return button;
    }
    private void toggleSelection(String id) {
        if (!selectedRecords.remove(id)) {
            if (selectedRecords.size()>=100) { Toast.makeText(this,"每次最多选择 100 条",Toast.LENGTH_SHORT).show();return; }
            selectedRecords.add(id);
        }
        updateSelection();
    }
    private void exitSelection() { if(deletingSelection)return;selecting=false;selectedRecords.clear();updateSelection(); }
    @Override public void onBackPressed() { if(selecting) exitSelection();else super.onBackPressed(); }
    private void updateSelection() {
        View dock=findViewById(R.id.capture_selection_dock);if(dock==null)return;
        dock.setVisibility(selecting?View.VISIBLE:View.GONE);
        findViewById(R.id.journal_selection_header).setVisibility(selecting?View.VISIBLE:View.GONE);
        findViewById(R.id.journal_library_header).setVisibility(selecting?View.GONE:View.VISIBLE);
        if(selecting && searchInput.hasFocus()) {
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(searchInput.getWindowToken(),0);
            searchInput.clearFocus();
        }
        ((TextView)findViewById(R.id.capture_selection_count)).setText("已选 "+selectedRecords.size()+" 条");
        for(int id:new int[]{R.id.capture_selection_export,R.id.capture_selection_delete})
            findViewById(id).setEnabled(!deletingSelection&&!selectedRecords.isEmpty());
        for(int i=0;i<recordsContainer.getChildCount();i++) {
            View card=recordsContainer.getChildAt(i);android.widget.CheckBox check=card.findViewById(R.id.capture_item_selected);
            if(check==null)continue;
            boolean chosen=selectedRecords.contains(card.getTag());
            check.setVisibility(selecting?View.VISIBLE:View.GONE);check.setChecked(chosen);check.jumpDrawablesToCurrentState();
            card.findViewById(R.id.journal_item_body).setSelected(chosen);
            card.setSelected(chosen);
            card.setPadding(0,0,0,0);
        }
    }
    private void deleteSelection() {
        String scope=filterScope;
        List<CaptureStore.CaptureRecord> snapshot=new ArrayList<>();
        for(CaptureStore.CaptureRecord record:libraryRecords) if(selectedRecords.contains(record.id))snapshot.add(record);
        if(snapshot.isEmpty()||!scope.equals(CaptureAccountSession.scope(this)))return;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("删除选中的 "+snapshot.size()+" 条记录？")
            .setMessage((CaptureAccountSession.hasAccount(this)?"只删除已勾选的记录；联网后同步到账号回收站，其他设备也会移除。已公开的分享不会自动撤销。":"只从本机列表移除已勾选的记录，不删除服务器副本。")+"\n这些记录的关联 AI 对话及资料副本也会删除，恢复记录不会恢复对话。")
            .setNegativeButton("取消",null).setPositiveButton("删除 "+snapshot.size()+" 条",(d,w)->{
                deletingSelection=true;updateSelection();
                refreshExecutor.execute(()->{
                    boolean success=false;
                    try{CaptureDeletionStore.deleteMany(this,scope,snapshot);success=true;}catch(Exception ignored){}
                    final boolean ok=success;
                    runOnUiThread(()->{if(destroyed)return;deletingSelection=false;
                        if(ok)exitSelection();renderRecords();Toast.makeText(this,ok?"已删除 "+snapshot.size()+" 条记录":"记录或账号已变化，或保存失败；未执行批量删除，请刷新后重试",Toast.LENGTH_LONG).show();});
                });
            }).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
    }

    private void showRecordDetail(CaptureStore.CaptureRecord record, String ownerScope) {
        if (!ownerScope.equals(CaptureAccountSession.scope(this))) return;
        if (!record.hasImage) {
            presentRecordDetail(record, null, null, ownerScope);
            return;
        }
        Toast.makeText(
                this,
                R.string.capture_detail_loading,
                Toast.LENGTH_SHORT
        ).show();
        thumbnailExecutor.execute(() -> {
            Bitmap image = CaptureStore.decodeReviewBitmap(record.annotatedFile);
            Bitmap full = record.contextFile == null ? null : CaptureStore.decodeReviewBitmap(record.contextFile);
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || !ownerScope.equals(CaptureAccountSession.scope(this))) {
                    if (full != null) full.recycle();
                    if (image != null) {
                        image.recycle();
                    }
                    return;
                }
                presentRecordDetail(record, image, full, ownerScope);
            });
        });
    }

    private void presentRecordDetail(CaptureStore.CaptureRecord record, Bitmap image,
                                     Bitmap full, String ownerScope) {
        CaptureRecordPage.show(this,record,image,full,
                () -> confirmDelete(record,ownerScope),this::openSourceUrl,
                () -> startActivityForResult(new Intent(this,CaptureRecordEditActivity.class)
                        .putExtra(CaptureRecordEditActivity.ID,record.id)
                        .putExtra(CaptureRecordEditActivity.SCOPE,ownerScope),701));
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=701 || result!=RESULT_OK || data==null) return;
        String scope=data.getStringExtra(CaptureRecordEditActivity.SCOPE), id=data.getStringExtra(CaptureRecordEditActivity.ID);
        refreshExecutor.execute(()->{
            try {
                CaptureStore.CaptureRecord record;
                synchronized(CaptureAccountSession.LOCK) {record=CaptureRecordEdits.latest(this,scope,id);}
                runOnUiThread(()->{if(!destroyed && !isFinishing()) showRecordDetail(record,scope);});
            } catch(Exception ignored) { }
        });
    }

    private void confirmDelete(CaptureStore.CaptureRecord record, String scope) {
        if (!scope.equals(CaptureAccountSession.scope(this))) return;
        AlertDialog confirmation = new AlertDialog.Builder(this).setTitle("删除这条记录？")
                .setMessage((CaptureAccountSession.hasAccount(this)
                        ? "将从当前列表移除，联网后同步移到账号回收站。其他设备同步后也会移除。"
                        : "将从本机列表移除。未登录时不会删除服务器上的副本。")+"\n本条记录的关联 AI 对话及资料副本也会删除，恢复记录不会恢复对话。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    refreshExecutor.execute(() -> {
                        boolean ok = false;
                        try { synchronized (CaptureAccountSession.LOCK) {
                            CaptureAccountSession.requireScope(this, scope);
                            CaptureDeletionStore.delete(this, record.id); ok = true;
                        }} catch (Exception ignored) { }
                        boolean success = ok;
                        runOnUiThread(() -> { if (!destroyed) {
                            renderRecords(); Toast.makeText(this, success ? ("guest".equals(scope) ? "本机记录已删除" : "已删除，联网后同步") : "删除失败，记录已保留", Toast.LENGTH_SHORT).show();
                        }});
                    });
                }).show();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger));
    }

    private void openSourceUrl(String supplied) {
        String url = CaptureSourceUrl.clean(supplied);
        if (url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.capture_url_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openSyncSettings() {
        startActivity(new Intent(this, CaptureAccountActivity.class));
    }

    private void syncAll() {
        refreshRecords();
    }

    private void renderSyncStatus(List<CaptureStore.CaptureRecord> records) {
        if (CaptureAccountSession.hasAccount(this)) {
            syncAllButton.setEnabled(true);
            String error = CaptureAccountSession.preferences(this).getString("sync_error", "");
            syncStatus.setText("账号：" + CaptureAccountSession.username(this) + (error.isEmpty() ? " · 自动同步已开启"
                    : "login_required".equals(error) ? " · 登录已过期，请重新登录"
                    : "revision_conflict".equals(error) ? " · 云端有新版本，本机修改已保留" : " · 同步待重试，可点击刷新"));
            return;
        }
        syncAllButton.setEnabled(true);
        syncStatus.setText("未登录 · 新记录仅保存在本机");
        syncStatus.setTextColor(getColor(R.color.ink_muted));
    }

    private int syncStateLabel(String state) {
        if (CaptureStore.SYNC_PENDING.equals(state)) {
            return R.string.capture_sync_item_pending;
        }
        if (CaptureStore.SYNC_FAILED.equals(state)) {
            return R.string.capture_sync_item_failed;
        }
        if (CaptureStore.SYNC_SYNCED.equals(state)) {
            return R.string.capture_sync_item_synced;
        }
        return R.string.capture_sync_item_local;
    }

    private void clearThumbnails() {
        // Dropping references is safe; recycling while old cards still draw is not.
        thumbnails.clear();
    }

    private static void setOptionalText(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.setText(value);
        }
    }

    private List<String> recordCategories(CaptureStore.CaptureRecord record) {
        List<String> categories = new ArrayList<>();
        if ("todo".equals(record.kind)) categories.add("待办");
        else if (hasThought(record)) categories.add("想法");
        if (hasMaterial(record)) categories.add("摘录");
        if (categories.isEmpty()) categories.add("记录");
        return categories;
    }

    private boolean hasThought(CaptureStore.CaptureRecord record) {
        return !"todo".equals(record.kind) && (!record.comment.trim().isEmpty()
                || ("thought".equals(record.kind) && !hasMaterial(record)));
    }

    private boolean hasMaterial(CaptureStore.CaptureRecord record) {
        return record.hasImage || record.contextFile != null || !record.sourceText.trim().isEmpty()
                || !CaptureRecordEdits.original(record).trim().isEmpty();
    }

    private String filteredCount(int count, int total) {
        return count == total ? total + " 条记录" : count + " / " + total + " 条";
    }

    private int sourceTypeLabel(String type) {
        if ("clipboard".equals(type)) return R.string.capture_source_clipboard;
        if ("process_text".equals(type) || "accessibility_selection".equals(type)) {
            return R.string.capture_source_process_text;
        }
        if ("share_text".equals(type)) {
            return R.string.capture_source_share_text;
        }
        if ("share_image".equals(type)) {
            return R.string.capture_source_share_image;
        }
        if ("quick_note".equals(type)) {
            return R.string.capture_source_quick_note;
        }
        return R.string.capture_source_screen;
    }

    private static String ellipsize(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum) + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ThumbnailThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "capture-thumbnails");
            thread.setDaemon(true);
            return thread;
        }
    }
}

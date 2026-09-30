package com.codex.mnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30,shadows={AiChatCoreTest.Crypto.class,CaptureAccountTest.Crypto.class,CaptureAccountTest.Scheduler.class})
public class AiChatCoreTest {
    Context context; String profile;
    @Before public void setup() throws Exception {
        context=RuntimeEnvironment.getApplication();
        profile=AiModelPreferences.save(context,null,"Test model","https://model.example/v1","vision-model","test-key-not-real",true,true);
    }
    CaptureStore.CaptureRecord note() throws Exception {
        return CaptureStore.save(context,null,null,null,null,"thought","我的想法","quick_note","引用片段","");
    }
    String conversation(CaptureStore.CaptureRecord record) throws Exception {
        String id=AiChatStore.create(context,AiChatStore.snapshot(context,record.id,new LinkedHashSet<>(Arrays.asList("thought","excerpt","original","images","metadata"))),profile);
        AiChatStore.authorize(context,id); return id;
    }
    void login(String id) throws Exception {
        CaptureAccountSession.save(context,"https://account.example",new JSONObject().put("account_id",id.repeat(32))
                .put("username","test").put("access_token","mns_test_only_token_123").put("expires_at",System.currentTimeMillis()/1000+3600));
        profile=AiModelPreferences.save(context,null,"Test model","https://model.example/v1","vision-model","test-key-not-real",true,true);
    }
    @Test public void profilesNeverExposeKeyAndEndpointChangeRequiresReentry() throws Exception {
        JSONObject listed=AiModelPreferences.list(context).getJSONObject(0);
        assertFalse(listed.toString().contains("test-key-not-real")); assertFalse(listed.has("ciphertext"));
        AiModelPreferences.save(context,profile,"Rename","https://model.example/v1","vision-model","",true,true);
        assertEquals("test-key-not-real",AiModelPreferences.load(context,profile).apiKey);
        assertThrows(IllegalArgumentException.class,()->AiModelPreferences.save(context,profile,"Rename","https://other.example/v1","vision-model","",true,true));
    }
    @Test public void profilesAndConversationsAreAccountScoped() throws Exception {
        String guest=conversation(note()); AiChatStore.beginTurn(context,guest,"guest question");
        login("a"); assertTrue(AiChatStore.list(context,null).isEmpty()); assertEquals(1,AiModelPreferences.list(context).length());
        String account=conversation(note()); AiChatStore.beginTurn(context,account,"A question");
        login("b"); assertTrue(AiChatStore.list(context,null).isEmpty());
        assertThrows(IOException.class,()->AiChatStore.get(context,account));
        CaptureAccountSession.clear(context); assertEquals(guest,AiChatStore.list(context,null).get(0).getString("id"));
    }
    @Test public void emptyConversationHiddenAndOnlySuccessfulAssistantCounts() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=conversation(record);
        assertTrue(AiChatStore.list(context,null).isEmpty());
        String request=AiChatStore.beginTurn(context,id,"Question");
        AiChatStore.finish(context,id,request,"partial","failed","response_incomplete");
        assertTrue(AiChatStore.completedCounts(context).isEmpty());
        String retry=AiChatStore.beginRetry(context,id); AiChatStore.finish(context,id,retry,"Full answer","complete","");
        assertEquals(Integer.valueOf(1),AiChatStore.completedCounts(context).get(record.id));
        assertEquals(3,AiChatStore.get(context,id).getJSONArray("messages").length());
    }
    @Test public void snapshotsRemainFrozenAndExcludedModulesStayExcluded() throws Exception {
        CaptureStore.CaptureRecord record=note(); JSONObject snapshot=AiChatStore.snapshot(context,record.id,Collections.singleton("excerpt"));
        assertEquals("",snapshot.getString("thought")); assertEquals("引用片段",snapshot.getString("excerpt")); assertEquals(0,snapshot.getJSONArray("tags").length());
        String id=AiChatStore.create(context,snapshot,profile); AiChatStore.authorize(context,id); AiChatStore.beginTurn(context,id,"Question");
        CaptureRecordEdits.save(context,"guest",record.id,CaptureRecordEdits.fingerprint(record),"new thought","new excerpt","new original");
        assertEquals("引用片段",AiChatStore.get(context,id).getJSONObject("snapshot").getString("excerpt"));
        assertEquals(CaptureRecordEdits.fingerprint(record),snapshot.getString("fingerprint"));
    }
    @Test public void actualImageBytesAreBoundedAndSentAsDataNotPublicLinks() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xffee8822);
        CaptureStore.CaptureRecord record=CaptureStore.save(context,null,bitmap,bitmap,null,"comment","image","screen","",""); bitmap.recycle();
        String id=conversation(record); AiChatStore.beginTurn(context,id,"What do you see?");
        JSONObject snapshot=AiChatStore.get(context,id).getJSONObject("snapshot");
        assertEquals(1,snapshot.getJSONArray("images").length());
        String encoded=snapshot.getJSONArray("images").getJSONObject(0).getString("data_base64");
        byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);
        assertEquals(0xff,bytes[0]&255); assertEquals(0xd8,bytes[1]&255);
        JSONObject payload=AiChatClient.request(AiChatStore.get(context,id),AiModelPreferences.load(context,profile),true);
        assertTrue(payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(2)
                .getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"));
        assertFalse(payload.toString().contains("test-key-not-real")); assertFalse(payload.toString().contains("updates/"));
    }
    @Test public void textOnlyProfileRefusesImagesWithoutSilentOmission() throws Exception {
        String id=conversation(note()); JSONObject c=AiChatStore.get(context,id);
        c.getJSONObject("snapshot").getJSONArray("images").put(new JSONObject().put("label","image").put("content_type","image/png").put("data_base64","AA=="));
        AiModelPreferences.Config text=new AiModelPreferences.Config("test","text","https://model.example/v1","text","key",false);
        assertEquals("vision_required",assertThrows(IOException.class,()->AiChatClient.request(c,text,true)).getMessage());
    }
    @Test public void consentDoesNotRewriteRecordPolicyAndMustBeRenewedWhenChanged() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=AiChatStore.create(context,AiChatStore.snapshot(context,record.id,Collections.singleton("thought")),profile);
        assertTrue(AiChatStore.needsConsent(context,id)); assertThrows(IOException.class,()->AiChatStore.beginTurn(context,id,"Question"));
        AiChatStore.authorize(context,id); assertFalse(AiChatStore.needsConsent(context,id));
        assertEquals(CaptureSyncPreferences.AI_LOCAL_ONLY,CaptureStore.find(context,record.id).aiAccess);
        JSONObject metadata=new JSONObject(new String(Files.readAllBytes(record.metadataFile.toPath()),StandardCharsets.UTF_8)).put("aiAccess","deny");
        try(FileOutputStream output=new FileOutputStream(record.metadataFile)) { output.write(metadata.toString().getBytes(StandardCharsets.UTF_8)); }
        assertTrue(AiChatStore.needsConsent(context,id));
    }
    @Test public void payloadHasStoreFalseAndOnlyCurrentSessionNoHiddenTruncation() throws Exception {
        String id=conversation(note()); AiChatStore.beginTurn(context,id,"Question");
        JSONObject payload=AiChatClient.request(AiChatStore.get(context,id),AiModelPreferences.load(context,profile),true);
        assertFalse(payload.getBoolean("store")); assertTrue(payload.getBoolean("stream")); assertFalse(payload.has("tools"));
        JSONObject oversized=AiChatStore.get(context,id); oversized.getJSONObject("snapshot").put("original","x".repeat(200_001));
        assertEquals("context_too_large",assertThrows(IOException.class,()->AiChatClient.request(oversized,AiModelPreferences.load(context,profile),true)).getMessage());
        assertThrows(IOException.class,()->AiChatStore.saveDraft(context,id,"x".repeat(100_001)));
    }
    @Test public void draftsLocalAndDeleteErasesSnapshotBody() throws Exception {
        String id=conversation(note()); AiChatStore.beginTurn(context,id,"Question"); AiChatStore.saveDraft(context,id,"private draft");
        assertFalse(AiChatStore.get(context,id).toString().contains("private draft")); assertEquals("private draft",AiChatStore.draft(context,id));
        AiChatStore.delete(context,id); assertTrue(AiChatStore.list(context,null).isEmpty());
        String persisted=new String(Files.readAllBytes(new File(AiChatStore.directory(context,"guest"),id+".json").toPath()),StandardCharsets.UTF_8);
        assertFalse(persisted.contains("我的想法")); assertFalse(persisted.contains("private draft"));
    }
    @Test public void sseParsesUtf8MultipleChunksAndRequiresFinishAndDone() throws Exception {
        String input="data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                +"data: {\"choices\":[{\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        StringBuilder output=new StringBuilder(); AiChatClient.parseSse(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),new AiChatClient.Call(),output::append);
        assertEquals("你好世界",output.toString());
        assertEquals("response_incomplete",assertThrows(IOException.class,()->AiChatClient.parseSse(new ByteArrayInputStream(input.replace("data: [DONE]\n\n","").getBytes(StandardCharsets.UTF_8)),new AiChatClient.Call(),t->{})).getMessage());
    }
    @Test public void jsonLengthLimitPreservesPartialAndProviderErrorsAreNotLeaked() throws Exception {
        StringBuilder output=new StringBuilder();
        assertEquals("response_limit",assertThrows(IOException.class,()->AiChatClient.parseJson("{\"choices\":[{\"message\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}",output::append)).getMessage());
        assertEquals("partial",output.toString());
        assertEquals("provider_error",assertThrows(IOException.class,()->AiChatClient.parseJson("{\"error\":{\"message\":\"secret-response-body\"}}",output::append)).getMessage());
        assertEquals("chat_failed",AiChatClient.safeCode(new IOException("provider included key secret")));
    }
    @Test public void invalidEndpointsAndPathTraversalRejected() {
        for(String url:Arrays.asList("http://model.example/v1","https://user:password@model.example/v1","https://model.example/v1?key=secret","https://model.example/v1#fragment"))
            assertThrows(IllegalArgumentException.class,()->AiModelPreferences.validateBaseUrl(url));
        assertThrows(IOException.class,()->AiChatStore.requireId("../../outside"));
        assertEquals("https://model.example/v1",AiModelPreferences.validateBaseUrl("https://model.example/v1/chat/completions"));
    }
    static final class Listener implements AiChatClient.Listener {
        JSONObject done; String error;
        public void onUpdate(JSONObject c) { }
        public void onDone(JSONObject c) { done=c; }
        public void onError(String code) { error=code; }
    }
    @Test public void injectedProviderStreamsPersistsAndResolvesActiveCall() throws Exception {
        String id=conversation(note()); Listener listener=new Listener();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,request,running,chunk)-> {
            assertFalse(Thread.holdsLock(CaptureAccountSession.LOCK)); assertFalse(request.getBoolean("store")); chunk.accept("Answer "); chunk.accept("complete");
        }); call.thread.join(5000); assertFalse(call.thread.isAlive()); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(listener.error); assertNotNull(listener.done); assertNull(AiChatClient.active(context,id));
        assertEquals("Answer complete",AiChatStore.get(context,id).getJSONArray("messages").getJSONObject(1).getString("content"));
    }
    @Test @Config(shadows={Crypto.class,CaptureAccountTest.Crypto.class,CaptureAccountTest.Scheduler.class,ImmediatePosts.class})
    public void terminalCallbacksSeeInactiveCallBeforeWorkerFinallyRuns() throws Exception {
        for(boolean fail:new boolean[]{false,true}) {
            String id=conversation(note()); java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean delivered=new java.util.concurrent.atomic.AtomicBoolean();
            AiChatClient.Listener listener=new AiChatClient.Listener() {
                public void onUpdate(JSONObject c) { }
                private void terminal() {
                    // ImmediatePosts dispatches inline, so the worker is deterministically still before finally.
                    if(AiChatClient.active(context,id)!=null) failure.set("terminal_callback_still_active");
                    delivered.set(true);
                }
                public void onDone(JSONObject c) { terminal(); }
                public void onError(String code) { terminal(); }
            };
            AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,payload,running,chunk)-> {
                if(fail) throw new IOException("response_incomplete"); chunk.accept("Answer");
            });
            call.thread.join(5000); assertFalse(call.thread.isAlive()); assertTrue(delivered.get()); assertNull(failure.get());
        }
    }
    @Implements(AiChatClient.class) public static class ImmediatePosts {
        @Implementation protected static void post(Context context,String scope,Runnable callback) {
            if(scope.equals(CaptureAccountSession.scope(context))) callback.run();
        }
    }
    @Test public void interruptedTransportPersistsPartialAsFailureAndRetryDoesNotRepeatUser() throws Exception {
        String id=conversation(note()); Listener listener=new Listener();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,request,running,chunk)-> { chunk.accept("partial"); throw new IOException("response_incomplete"); });
        call.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals("response_incomplete",listener.error);
        JSONObject failed=AiChatStore.get(context,id).getJSONArray("messages").getJSONObject(1); assertEquals("partial",failed.getString("content")); assertEquals("failed",failed.getString("status"));
        Listener retry=new Listener(); AiChatClient.Call next=AiChatClient.start(context,id,null,retry,(config,request,running,chunk)-> { chunk.accept("complete"); });
        next.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertNotNull(retry.done);
        assertEquals(3,AiChatStore.get(context,id).getJSONArray("messages").length());
    }
    @Test public void cancelRetainsPartialAndAccountSwitchNeverWritesNewScope() throws Exception {
        String id=conversation(note()); Listener listener=new Listener();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,request,running,chunk)-> {
            chunk.accept("partial"); running.cancel(); chunk.accept("not retained");
        }); call.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals("cancelled",listener.error);
        assertEquals("stopped",AiChatStore.get(context,id).getJSONArray("messages").getJSONObject(1).getString("status"));
        String second=conversation(note()); AtomicInteger invocations=new AtomicInteger();
        AiChatClient.Call switched=AiChatClient.start(context,second,"Question",new Listener(),(config,request,running,chunk)-> {
            invocations.incrementAndGet(); login("a"); chunk.accept("wrong account");
        }); switched.thread.join(5000); assertEquals(1,invocations.get()); assertTrue(AiChatStore.list(context,null).isEmpty());
        CaptureAccountSession.clear(context); assertFalse(AiChatStore.get(context,second).toString().contains("wrong account"));
    }
    @Test public void casSyncPushPullAndConflictRecoveryPreserveBothSides() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=conversation(record); FakeRemote remote=new FakeRemote();
        String turn=AiChatStore.beginTurn(context,id,"Question"); AiChatStore.finish(context,id,turn,"First","complete","");
        assertTrue(AiChatSync.push(context,"guest",id,remote)); assertEquals(1,AiChatStore.read(context,"guest",id).getInt("revision"));
        AiChatStore.rename(context,id,"local title"); AiChatStore.saveDraft(context,id,"draft survives");
        JSONObject changed=AiChatStore.copy(remote.records.get(id)); changed.put("title","remote title"); remote.put(changed);
        assertEquals("revision_conflict",assertThrows(IOException.class,()->AiChatSync.push(context,"guest",id,remote)).getMessage());
        assertEquals("remote title",AiChatStore.get(context,id).getString("title")); assertEquals("draft survives",AiChatStore.draft(context,id));
        assertEquals(2,AiChatStore.list(context,record.id).size());
        assertTrue(AiChatStore.list(context,record.id).stream().anyMatch(c->c.optString("title").startsWith("本机冲突副本")));
        AiChatStore.beginTurn(context,id,"Can continue"); assertTrue(AiChatSync.push(context,"guest",id,remote));
    }
    @Test public void syncNeverTransfersLocalSecretsDraftsOrConfig() throws Exception {
        String id=conversation(note()); AiChatStore.beginTurn(context,id,"Question"); AiChatStore.saveDraft(context,id,"draft-secret");
        FakeRemote remote=new FakeRemote(); AiChatSync.push(context,"guest",id,remote); String wire=remote.records.get(id).toString();
        for(String forbidden:Arrays.asList("test-key-not-real","draft-secret","ciphertext","profile_id","consent_policy","dirty")) assertFalse(wire.contains(forbidden));
    }
    @Test public void localParentDeletionPrunesBodyEvenWithoutExplicitChatHook() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=conversation(record); AiChatStore.beginTurn(context,id,"Question");
        CaptureDeletionStore.delete(context,record.id); AiChatSync.pruneDeletedRecords(context,"guest");
        JSONObject wrapper=AiChatStore.read(context,"guest",id); assertTrue(wrapper.getBoolean("deleted")); assertFalse(wrapper.has("conversation"));
    }
    @Test public void dirtyLocalAcknowledgementCannotEraseConcurrentPartial() throws Exception {
        String id=conversation(note()); String request=AiChatStore.beginTurn(context,id,"Question"); FakeRemote remote=new FakeRemote();
        AiChatSync.push(context,"guest",id,(method,path,body,revision)-> {
            JSONObject response=remote.request(method,path,body,revision);
            AiChatStore.finish(context,id,request,"new partial","generating",""); return response;
        });
        JSONObject wrapper=AiChatStore.read(context,"guest",id); assertTrue(wrapper.getBoolean("dirty")); assertEquals(1,wrapper.getInt("revision"));
        assertEquals("new partial",AiChatStore.get(context,id).getJSONArray("messages").getJSONObject(1).getString("content"));
    }
    @Test public void httpTransportUsesOnlyProviderKeyDisablesRedirectsAndParsesRealSseBytes() throws Exception {
        String id=conversation(note()); AiChatStore.beginTurn(context,id,"Question");
        AiModelPreferences.Config config=AiModelPreferences.load(context,profile);
        FakeConnection connection=new FakeConnection(200,"text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        StringBuilder text=new StringBuilder();
        AiChatClient.http(config,AiChatClient.request(AiChatStore.get(context,id),config,true),new AiChatClient.Call(),text::append,uri->{
            assertEquals("https://model.example/v1/chat/completions",uri.toString()); return connection;
        });
        assertEquals("回答",text.toString()); assertEquals("POST",connection.getRequestMethod());
        assertFalse(connection.getInstanceFollowRedirects()); assertEquals("Bearer test-key-not-real",connection.getRequestProperty("Authorization"));
        JSONObject posted=new JSONObject(connection.posted.toString("UTF-8")); assertFalse(posted.getBoolean("store")); assertTrue(posted.getBoolean("stream"));
        assertTrue(connection.disconnected); assertFalse(connection.posted.toString("UTF-8").contains("mns_"));
    }
    @Test public void redirectAndAuthFailuresNeverConsumeErrorBodyOrFollowLocation() throws Exception {
        for(int status:new int[]{302,401,429,500}) {
            FakeConnection connection=new FakeConnection(status,"text/plain","secret error body");
            assertThrows(IOException.class,()->AiChatClient.http(AiModelPreferences.load(context,profile),new JSONObject(),new AiChatClient.Call(),s->{},uri->connection));
            assertFalse(connection.read); assertFalse(connection.getInstanceFollowRedirects()); assertTrue(connection.disconnected);
        }
    }
    @Test public void noTokensAccountSwitchActivelyDisconnectsRunningProvider() throws Exception {
        String id=conversation(note()); java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",new Listener(),(config,payload,running,chunk)-> {
            entered.countDown(); long until=System.currentTimeMillis()+5000;
            while(!running.cancelled && System.currentTimeMillis()<until) Thread.sleep(10);
            running.check(context);
        });
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)); login("a"); call.thread.join(5000);
        assertFalse(call.thread.isAlive()); assertTrue(call.cancelled); assertTrue(AiChatStore.list(context,null).isEmpty());
    }
    @Test public void modelChangesDoNotSilentlyRerouteExistingConversation() throws Exception {
        String id=conversation(note()); AiModelPreferences.save(context,profile,"different","https://other.example/v1","different","new-test-key",true,true);
        Listener listener=new Listener(); AtomicInteger requests=new AtomicInteger();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,payload,running,chunk)->requests.incrementAndGet());
        call.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals("profile_changed",listener.error); assertEquals(0,requests.get());
        assertEquals(0,AiChatStore.get(context,id).getJSONArray("messages").length());
    }
    @Test public void pullConflictKeepsDraftAndLocalCopyThenNextSyncCanProgress() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=conversation(record); FakeRemote remote=new FakeRemote();
        String request=AiChatStore.beginTurn(context,id,"Question"); AiChatStore.finish(context,id,request,"answer","complete",""); AiChatSync.push(context,"guest",id,remote);
        AiChatStore.rename(context,id,"Local changed"); AiChatStore.saveDraft(context,id,"typed draft");
        JSONObject changed=AiChatStore.copy(remote.records.get(id)).put("title","Remote changed"); remote.put(changed);
        assertEquals("revision_conflict",assertThrows(IOException.class,()->AiChatSync.run(context,"guest",remote)).getMessage());
        assertEquals("typed draft",AiChatStore.draft(context,id)); assertEquals("Remote changed",AiChatStore.get(context,id).getString("title"));
        AiChatSync.run(context,"guest",remote); assertEquals(2,remote.records.size());
    }
    @Test public void lostUploadAcknowledgementIsIdempotentNotAConflictFork() throws Exception {
        CaptureStore.CaptureRecord record=note(); String id=conversation(record); AiChatStore.beginTurn(context,id,"Question");
        FakeRemote remote=new FakeRemote(); remote.put(AiChatStore.get(context,id)); // The server committed, but client never received its acknowledgement.
        AiChatSync.run(context,"guest",remote);
        assertEquals(1,AiChatStore.list(context,record.id).size()); assertEquals(1,remote.records.size());
        assertFalse(AiChatStore.read(context,"guest",id).getBoolean("dirty")); assertEquals(1,AiChatStore.read(context,"guest",id).getInt("revision"));
        assertTrue(AiChatSync.equivalent(new JSONObject("{\"a\":1,\"b\":[2,3]}"),new JSONObject("{\"b\":[2,3],\"a\":1}")));
    }
    @Test public void pullTombstonePurgesUnsentPrivateBodiesAndDoesNotResurrect() throws Exception {
        String id=conversation(note()); String request=AiChatStore.beginTurn(context,id,"Question"); AiChatStore.finish(context,id,request,"answer","complete","");
        FakeRemote remote=new FakeRemote(); AiChatSync.push(context,"guest",id,remote); AiChatStore.rename(context,id,"unsent edit");
        remote.changes.put(new JSONObject().put("sequence",remote.changes.length()+1).put("id",id)
                .put("record_id",AiChatStore.get(context,id).getString("record_id")).put("revision",2).put("deleted",true)); remote.records.remove(id);
        AiChatSync.run(context,"guest",remote); JSONObject w=AiChatStore.read(context,"guest",id);
        assertTrue(w.getBoolean("deleted")); assertFalse(w.has("conversation")); assertFalse(w.getBoolean("dirty")); assertTrue(remote.records.isEmpty());
    }
    @Test public void pageContextImagesAreNotDuplicatedAndClipboardRelationIsExplicit() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff88ccff);
        CaptureStore.CaptureRecord record=CaptureStore.save(context,null,bitmap,bitmap,null,"thought","thought","clipboard","quote",""); bitmap.recycle();
        JSONObject metadata=new JSONObject(new String(Files.readAllBytes(record.metadataFile.toPath()),StandardCharsets.UTF_8));
        metadata.put("captureContext",new JSONObject().put("image",new JSONObject().put("purpose","page_context")));
        try(FileOutputStream output=new FileOutputStream(record.metadataFile)) { output.write(metadata.toString().getBytes(StandardCharsets.UTF_8)); }
        JSONObject snapshot=AiChatStore.snapshot(context,record.id,new HashSet<>(Arrays.asList("images","excerpt")));
        assertEquals(1,snapshot.getJSONArray("images").length()); assertTrue(snapshot.getJSONArray("images").getJSONObject(0).getString("label").contains("未指定摘录位置"));
        assertTrue(snapshot.getString("context_note").contains("剪贴板")); assertEquals("",snapshot.getString("record_created_at"));
    }
    @Test @Config(shadows={Crypto.class,CaptureAccountTest.Crypto.class,ReservationRecords.class,ReservationSync.class})
    public void syncedTurnReservesBeforeProviderAndDoesNotHoldGlobalMutex() throws Exception {
        login("a"); String id=conversation(note()); ReservationSync.pushes=0; ReservationSync.reject=false; Listener listener=new Listener();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,payload,running,chunk)-> {
            assertEquals(1,ReservationSync.pushes); assertFalse(Thread.holdsLock(CaptureAccountSession.LOCK));
            assertEquals("generating",AiChatStore.get(context,id).getJSONArray("messages").getJSONObject(1).getString("status")); chunk.accept("Answer");
        }); call.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(listener.done); assertEquals(2,ReservationSync.pushes);
    }
    @Test @Config(shadows={Crypto.class,CaptureAccountTest.Crypto.class,ReservationRecords.class,ReservationSync.class})
    public void failedReservationNeverInvokesProviderAndPreservesInputDraft() throws Exception {
        login("a"); String id=conversation(note()); ReservationSync.pushes=0; ReservationSync.reject=true;
        Listener listener=new Listener(); AtomicInteger provider=new AtomicInteger();
        AiChatClient.Call call=AiChatClient.start(context,id,"Question",listener,(config,payload,running,chunk)->provider.incrementAndGet());
        call.thread.join(5000); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,provider.get()); assertEquals("revision_conflict",listener.error); assertEquals("Question",AiChatStore.draft(context,id));
        ReservationSync.reject=false;
    }
    @Implements(CaptureAccountSync.class) public static class ReservationRecords {
        @Implementation protected static void enqueue(Context context) { }
        @Implementation protected static int run(Context context) { return 0; }
    }
    @Implements(AiChatSync.class) public static class ReservationSync {
        static int pushes; static boolean reject;
        @Implementation protected static void push(Context context,String id) throws IOException {
            pushes++; if(reject) throw new IOException("revision_conflict");
        }
    }
    static final class FakeConnection extends java.net.HttpURLConnection {
        final int status; final String type,body; final ByteArrayOutputStream posted=new ByteArrayOutputStream();
        boolean disconnected,read;
        FakeConnection(int status,String type,String body) throws Exception { super(new java.net.URL("https://model.example/v1/chat/completions")); this.status=status;this.type=type;this.body=body; }
        public void disconnect() { disconnected=true; }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public OutputStream getOutputStream() { return posted; }
        public int getResponseCode() { return status; }
        public String getContentType() { return type; }
        public InputStream getInputStream() { read=true; return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
    }
    static final class FakeRemote implements AiChatSync.Transport {
        final Map<String,JSONObject> records=new HashMap<>(); final JSONArray changes=new JSONArray();
        JSONObject put(JSONObject body) throws Exception {
            String id=body.getString("id"); int revision=records.containsKey(id)?records.get(id).getInt("revision")+1:1;
            JSONObject result=AiChatStore.copy(body).put("revision",revision); records.put(id,result);
            changes.put(new JSONObject().put("sequence",changes.length()+1).put("id",id).put("record_id",body.getString("record_id")).put("revision",revision).put("deleted",false));
            return AiChatStore.copy(result);
        }
        public JSONObject request(String method,String path,JSONObject body,Integer revision) throws Exception {
            assertFalse(Thread.holdsLock(CaptureAccountSession.LOCK));
            if(path.startsWith("/v1/chat/changes")) {
                long after=Long.parseLong(path.split("after=")[1].split("&")[0]); JSONArray page=new JSONArray();
                for(int i=0;i<changes.length();i++) if(changes.getJSONObject(i).getLong("sequence")>after) page.put(changes.getJSONObject(i));
                return new JSONObject().put("changes",page).put("next_cursor",changes.length()).put("has_more",false);
            }
            String id=path.substring(path.lastIndexOf('/')+1); JSONObject current=records.get(id);
            if("GET".equals(method)) { if(current==null) throw new IOException("http_404"); return AiChatStore.copy(current); }
            if(revision!=(current==null?0:current.getInt("revision"))) throw new IOException("http_409");
            if("PUT".equals(method)) return put(body);
            if(current==null) throw new IOException("http_404"); records.remove(id); return new JSONObject().put("revision",revision+1);
        }
    }
    @Implements(AiModelPreferences.class) public static class Crypto {
        @Implementation protected static CaptureSyncPreferences.EncryptedValue encrypt(String value) {
            return new CaptureSyncPreferences.EncryptedValue(java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)),"synthetic-iv");
        }
        @Implementation protected static String decrypt(String value,String iv) { return new String(java.util.Base64.getDecoder().decode(value),StandardCharsets.UTF_8); }
    }
}

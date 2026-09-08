package com.codex.mnote;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35}, instrumentedPackages="com.codex.mnote", shadows={CaptureAccountTest.Crypto.class,
        CaptureAccountTest.Scheduler.class,CaptureAccountTest.Reader.class,CaptureAccountTest.Http.class,
        CaptureAccountTest.Uploader.class})
public class CaptureAccountTest {
    Context context;
    static List<JSONObject> feed;
    static Map<String,JSONObject> cloud;
    static boolean offline;
    static int uploads, deletes;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); feed=new ArrayList<>(); cloud=new HashMap<>();
        offline=false; uploads=0; deletes=0;
    }
    void login(String id) throws Exception {
        CaptureAccountSession.save(context,"https://test.example",new JSONObject()
                .put("account_id",id.repeat(32)).put("username","user-"+id)
                .put("access_token","mns_test_only_session_"+UUID.randomUUID())
                .put("expires_at",System.currentTimeMillis()/1000+3600));
    }
    CaptureStore.CaptureRecord note(String text) throws Exception {
        return CaptureStore.save(context,null,null,null,null,"thought",text,"quick_note","","");
    }
    List<CaptureStore.CaptureRecord> visible() { return CaptureRemoteCache.merged(context,CaptureStore.list(context,100)); }

    @Test public void accountFilesAndStableCacheAreIsolatedAcrossRelogin() throws Exception {
        note("guest"); login("a"); String scope=CaptureAccountSession.scope(context);
        assertTrue(visible().isEmpty()); note("A private");
        login("b"); assertTrue(visible().isEmpty()); note("B private");
        login("a"); assertEquals(scope,CaptureAccountSession.scope(context));
        assertEquals("A private",visible().get(0).comment);
        CaptureAccountSession.clear(context); assertEquals("guest",visible().get(0).comment);
    }
    @Test public void explicitImportIsIdempotentAndSkipsDeletedGuestNotes() throws Exception {
        note("keep"); CaptureStore.CaptureRecord deleted=note("deleted");
        CaptureDeletionStore.delete(context,deleted.id); assertTrue(CaptureDeletionStore.pending(context).isEmpty());
        login("a"); assertEquals(1,CaptureAccountImport.run(context));
        assertEquals(0,CaptureAccountImport.run(context)); assertEquals("keep",visible().get(0).comment);
        assertEquals(CaptureStore.SYNC_PENDING,visible().get(0).syncState);
        CaptureAccountSession.clear(context); assertEquals(2,CaptureStore.list(context,10).size());
        assertEquals(1,visible().size());
    }
    @Test public void loginPushesPendingAndPullsOtherDeviceNotes() throws Exception {
        login("a"); CaptureStore.CaptureRecord local=note("phone");
        JSONObject remote=CaptureRemoteCacheTest.record("desktop-note",1); cloud.put("desktop-note",remote);
        feed.add(CaptureRemoteCacheTest.change(1,"upsert","desktop-note",remote));
        CaptureAccountSync.run(context);
        assertEquals(1,uploads); assertEquals(2,visible().size());
        assertEquals(CaptureStore.SYNC_SYNCED,CaptureStore.list(context,10).get(0).syncState);
        CaptureAccountSync.run(context); assertEquals(1,uploads);
        assertEquals("phone",cloud.get(local.id).getString("comment"));
    }
    @Test public void offlineDeletePersistsAndRetryNeverResurrects() throws Exception {
        login("a"); CaptureStore.CaptureRecord local=note("delete me"); CaptureAccountSync.run(context);
        offline=true; CaptureDeletionStore.delete(context,local.id);
        assertTrue(visible().isEmpty()); assertThrows(IOException.class,()->CaptureAccountSync.run(context));
        assertEquals(Collections.singletonList(local.id),CaptureDeletionStore.pending(context));
        offline=false; CaptureAccountSync.run(context); CaptureAccountSync.run(context);
        assertTrue(CaptureDeletionStore.pending(context).isEmpty()); assertTrue(visible().isEmpty());
        assertEquals(1,uploads); assertEquals(1,deletes);
        login("b"); assertTrue(CaptureDeletionStore.hidden(context).isEmpty());
        login("a"); assertTrue(CaptureDeletionStore.hidden(context).contains(local.id));
    }
    @Test public void remoteDeleteSuppressesEvenAnUnsentLocalCopy() throws Exception {
        login("a"); CaptureStore.CaptureRecord local=note("stale");
        feed.add(CaptureRemoteCacheTest.change(1,"delete",local.id,null));
        CaptureAccountSync.run(context); assertEquals(0,uploads); assertTrue(visible().isEmpty());
    }
    @Test public void explicitServerRestoreAndNewerRevisionBecomeVisible() throws Exception {
        login("a"); CaptureStore.CaptureRecord local=note("old"); CaptureAccountSync.run(context);
        CaptureDeletionStore.delete(context,local.id); CaptureAccountSync.run(context);
        JSONObject restored=CaptureRemoteCacheTest.record(local.id,3).put("comment","restored on desktop");
        cloud.put(local.id,restored); feed.add(CaptureRemoteCacheTest.change(feed.size()+1,"restore",local.id,restored));
        CaptureAccountSync.run(context);
        assertEquals("restored on desktop",visible().get(0).comment);
        assertFalse(CaptureDeletionStore.hidden(context).contains(local.id)); assertEquals(1,uploads);
    }
    @Test public void expiredSessionKeepsOfflineNotesAndReportsLoginRequired() throws Exception {
        login("a"); note("offline");
        CaptureAccountSession.preferences(context).edit().putLong("expires_at",0).commit();
        assertThrows(java.security.GeneralSecurityException.class,()->CaptureAccountSync.run(context));
        assertEquals("login_required",CaptureAccountSession.preferences(context).getString("sync_error",""));
        assertEquals("offline",visible().get(0).comment); assertEquals(0,uploads);
    }
    @Test public void draftOwnershipRejectsChangedAccountAndInvalidScopeCannotEscapeStorage() throws Exception {
        login("a"); String old=CaptureAccountSession.scope(context); login("b");
        assertThrows(IOException.class,()->CaptureAccountSession.requireScope(context,old));
        CaptureAccountSession.preferences(context).edit().putString("scope","../outside").commit();
        assertThrows(IllegalStateException.class,()->CaptureAccountSession.scope(context));
    }
    @Test public void deleteSuccessSurvivesBroadcastFailure() throws Exception {
        CaptureStore.CaptureRecord local=note("remove");
        Context broken=new android.content.ContextWrapper(context) {
            @Override public void sendBroadcast(android.content.Intent intent) { throw new IllegalStateException("test"); }
        };
        CaptureDeletionStore.delete(broken,local.id); assertTrue(visible().isEmpty());
    }

    // Only cryptography is substituted in Robolectric; production uses Android Keystore AES-GCM.
    @Implements(CaptureSyncPreferences.class) public static class Crypto {
        @Implementation protected static CaptureSyncPreferences.EncryptedValue encryptToken(String token) {
            return new CaptureSyncPreferences.EncryptedValue(token,"test-iv");
        }
        @Implementation protected static String decryptToken(String ciphertext,String iv) { return ciphertext; }
    }
    @Implements(CaptureAccountSync.class) public static class Scheduler {
        @Implementation protected static void enqueue(Context context) { }
    }
    @Implements(CaptureSyncReader.class) public static class Reader {
        @Implementation protected static CaptureSyncReader.Transport forConfig(CaptureSyncPreferences.Config config) {
            return (path,limit)-> {
                if (offline) throw new IOException("network_unavailable");
                try {
                    long after=Long.parseLong(path.split("after=")[1].split("&")[0]);
                    List<JSONObject> changes=new ArrayList<>();
                    for (JSONObject change:feed) if(change.getLong("sequence")>after) changes.add(change);
                    return CaptureRemoteCacheTest.page(feed.size(),false,changes.toArray(new JSONObject[0]));
                } catch(Exception error) { throw new IOException(error); }
            };
        }
    }
    @Implements(CaptureAccountHttp.class) public static class Http {
        @Implementation protected static JSONObject request(String base,String method,String path,String token,JSONObject body,Integer revision) throws Exception {
            if(offline) throw new IOException("network_unavailable");
            String id=path.substring(path.lastIndexOf('/')+1); JSONObject record=cloud.get(id);
            if(record==null) throw new IOException("http_404");
            if(method.equals("DELETE")) {
                assertEquals(record.getInt("revision"),revision.intValue()); cloud.remove(id); deletes++;
                feed.add(CaptureRemoteCacheTest.change(feed.size()+1,"delete",id,null));
            }
            return record;
        }
    }
    @Implements(CaptureSyncUploader.class) public static class Uploader {
        @Implementation protected static CaptureSyncUploader.UploadResult upload(Context context,CaptureSyncPreferences.Config config,CaptureStore.CaptureRecord local) throws Exception {
            assertEquals(CaptureAccountSession.scope(context),config.accountKey); uploads++;
            JSONObject record=CaptureRemoteCacheTest.record(local.id,1).put("comment",local.comment);
            cloud.put(local.id,record); feed.add(CaptureRemoteCacheTest.change(feed.size()+1,"upsert",local.id,record));
            return new CaptureSyncUploader.UploadResult(1);
        }
    }
}

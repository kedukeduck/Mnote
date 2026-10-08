package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class,
        PrivateJournalAuxiliaryUiTest.Http.class})
public class PrivateJournalAuxiliaryUiTest {
    @Implements(value = CaptureAccountHttp.class, isInAndroidSdk = false)
    public static class Http {
        static JSONObject credentials;
        @Implementation protected static JSONObject request(String base, String method, String path,
                String token, JSONObject body, Integer revision) throws Exception {
            assertEquals("/v1/auth/login", path);
            credentials = body;
            return account();
        }
    }
    private static JSONObject account() throws Exception {
        return new JSONObject().put("account_id", "a".repeat(32)).put("username", "journal")
            .put("access_token", "mns_test_only").put("expires_at", 4102444800L);
    }
    @Before public void reset() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureAccountSession.clear(context);
        CaptureAccountSession.preferences(context).edit().remove("sync_error").remove("last_sync").commit();
        Http.credentials = null;
    }
    @Test public void loginAndActivationHaveDifferentPasswordLabelsAndExistingShortPasswordCanLogin() throws Exception {
        try (var controller = Robolectric.buildActivity(CaptureAccountActivity.class).setup()) {
            var activity = controller.get();
            EditText password = ReflectionHelpers.getField(activity, "password");
            assertEquals("密码", ((TextView) password.getTag()).getText().toString());
            Button activate = ReflectionHelpers.getField(activity, "activate");
            SettingsActivityTest.render(SettingsActivityTest.layout(activity, 390, 844), "journal-account-login.png");
            activate.performClick();
            assertTrue(((TextView) password.getTag()).getText().toString().contains("12"));
            SettingsActivityTest.render(SettingsActivityTest.layout(activity, 390, 844), "journal-account-activation.png");
            activate.performClick();
            assertEquals("密码", ((TextView) password.getTag()).getText().toString());
            ((EditText) ReflectionHelpers.getField(activity, "server")).setText("https://example.test");
            ((EditText) ReflectionHelpers.getField(activity, "username")).setText("journal");
            password.setText("oldpass");
            ((Button) ReflectionHelpers.getField(activity, "login")).performClick();
            ((ExecutorService) ReflectionHelpers.getField(activity, "executor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("oldpass", Http.credentials.getString("password"));
            assertTrue(activity.isFinishing());
        }
    }
    @Test public void accountStatusComesFromSyncResultAndFormIsInitiallyHidden() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureAccountSession.save(context, "https://example.test", account());
        CaptureAccountSession.preferences(context).edit().putString("sync_error", "revision_conflict").commit();
        try (var controller = Robolectric.buildActivity(CaptureAccountActivity.class).setup()) {
            var activity = controller.get();
            TextView result = ReflectionHelpers.getField(activity, "syncSummary");
            assertTrue(result.getText().toString().contains("本机修改已保留"));
            assertFalse(((EditText) ReflectionHelpers.getField(activity, "password")).isShown());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity, 390, 844), "journal-account-sync.png");
            CaptureAccountSession.preferences(context).edit().putString("sync_error", "login_required").commit();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(result.getText().toString().contains("登录已过期"));
        }
    }
    @Test public void settingsOwnsPermissionsAndDiagnosticsWithoutRequestingAccessAutomatically() throws Exception {
        try (var controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            var activity = controller.get();
            View root = SettingsActivityTest.layout(activity, 390, 844);
            View permissionLabel = findText(root, "快捷方式与权限");
            assertNotNull(permissionLabel);
            ((View) permissionLabel.getParent()).performClick();
            assertEquals(CapturePermissionsActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            ((View) findText(root, "帮助与诊断").getParent()).performClick();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
        try (var controller = Robolectric.buildActivity(CapturePermissionsActivity.class).setup()) {
            var activity = controller.get();
            View root = SettingsActivityTest.layout(activity, 390, 844);
            assertNotNull(findText(root, "添加「记录」快捷按钮"));
            assertNull(findText(root, "添加单次摘录"));
            assertNull(findText(root, "添加随手记"));
            assertNull(shadowOf(activity).getNextStartedActivity());
            SettingsActivityTest.render(root, "journal-permissions.png");
            findText(root, "打开系统无障碍设置").performClick();
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            assertNull(shadowOf(activity).getNextStartedActivity());
        }
    }
    @Test
    @Config(qualifiers="zh-rCN-w390dp-h844dp-mdpi", shadows=AppUpdateActivityTest.ClientShadow.class)
    public void updatePageShowsVerifiedDownloadActionAndCancellationDoesNotInstall() throws Exception {
        AppUpdateActivityTest.ClientShadow.fail=false;
        AppUpdateActivityTest.ClientShadow.result=new AppRelease("1.17.0-test",
            "https://chenyu.online/heartnote-capture/updates/files/mnote-android-v1.17.0-test/Mnote-Android-1.17.0-test.apk",
            "a".repeat(64),"优化记录与分享体验\n修复稳定性问题",100);
        try(var controller=Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            var activity=controller.get();
            ((ExecutorService)ReflectionHelpers.getField(activity,"executor")).submit(()->{}).get(5,TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
            Button download=ReflectionHelpers.getField(activity,"download");
            Button install=ReflectionHelpers.getField(activity,"install");
            assertTrue(download.isEnabled()); assertEquals(View.VISIBLE,download.getVisibility());
            assertEquals(View.GONE,install.getVisibility());
            SettingsActivityTest.render(SettingsActivityTest.layout(activity,390,844),"journal-update.png");
            download.performClick();
            var dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            assertFalse(install.isEnabled()); assertTrue(download.isEnabled());
            assertNull(shadowOf(activity).getNextStartedActivity());
        } finally {
            AppUpdateActivityTest.ClientShadow.result=null;
        }
    }

    static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }
}

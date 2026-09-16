package com.codex.mnote;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, instrumentedPackages = "com.codex.mnote",
    shadows = {CaptureAccountTest.Crypto.class, CaptureAccountTest.Scheduler.class})
public class SettingsActivityTest {
    @Test
    public void settingsGroupsAccountAndUpdateNavigationAndRenders() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        CaptureAccountSession.save(context, "https://example.test",
            new JSONObject()
                .put("account_id", "a".repeat(32))
                .put("username", "我的空间")
                .put("access_token", "mns_test_only")
                .put("expires_at", 4102444800L));
        try (var controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            var activity = controller.get();
            View root = layout(activity, 390, 844);
            render(root, "settings-preview.png");
            activity.findViewById(R.id.settings_account).performClick();
            assertEquals(CaptureAccountActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            activity.findViewById(R.id.settings_updates).performClick();
            assertEquals(AppUpdateActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            CaptureAccountSession.clear(context);
            controller.pause().resume();
            var summary = (android.widget.TextView) org.robolectric.util.ReflectionHelpers.getField(
                activity, "accountSummary");
            assertTrue(summary.getText().toString().contains("登录后"));
            layout(activity, 320, 568);
            assertTrue(activity.findViewById(R.id.settings_updates).isShown());
        }
    }
    static View layout(Activity activity, int width, int height) {
        float density = activity.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        return root;
    }
    static void render(View root, String name) throws Exception {
        Bitmap bitmap =
            Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File directory = new File("build/ui-previews");
        directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}

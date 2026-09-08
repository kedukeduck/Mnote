package com.codex.mnote;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={30,35}, shadows=CaptureSourceContextTest.ServiceInfoShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CaptureFeedbackTest {
    @Test public void feedbackDoesNotStealTouchOrFocusAndExpires() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        CaptureFeedback feedback = new CaptureFeedback(controller.get());
        try {
            feedback.show(R.string.capture_saved);
            TextView chip = ReflectionHelpers.getField(feedback,"chip");
            assertNotNull(chip);
            assertEquals(controller.get().getString(R.string.capture_saved),chip.getText());
            WindowManager.LayoutParams params = (WindowManager.LayoutParams)chip.getLayoutParams();
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type);
            assertNotEquals(0,params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            assertNotEquals(0,params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2601));
            assertNull(ReflectionHelpers.getField(feedback,"chip"));
        } finally { feedback.hide(); controller.destroy(); }
    }

    @Test public void newMessageReplacesOldMessageAndLockScreenSuppressesChip() {
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        CaptureFeedback feedback = new CaptureFeedback(controller.get());
        try {
            feedback.show(R.string.capture_screenshot_ready);
            TextView old = ReflectionHelpers.getField(feedback,"chip");
            feedback.show(R.string.capture_saved);
            assertNotSame(old,ReflectionHelpers.getField(feedback,"chip"));
            assertFalse(old.isAttachedToWindow());
            shadowOf(controller.get().getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
            feedback.show(R.string.capture_saved);
            assertNull(ReflectionHelpers.getField(feedback,"chip"));
        } finally { feedback.hide(); controller.destroy(); }
    }

    @Test public void serviceTeardownRemovesFeedbackAndPendingExpiry() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        var service = controller.get();
        service.onServiceConnected();
        CaptureAccessibilityService.showFeedback(service,R.string.capture_saved);
        CaptureFeedback feedback = ReflectionHelpers.getField(service,"feedback");
        assertNotNull(ReflectionHelpers.getField(feedback,"chip"));
        controller.destroy();
        assertNull(ReflectionHelpers.getField(feedback,"chip"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertNull(ReflectionHelpers.getField(feedback,"chip"));
    }

    @Test public void accessibilityShortcutTargetsMnoteServiceNotAnImaginaryPermissionSwitch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            CaptureAccessibilitySettings.open(activity);
            Intent intent = shadowOf(activity).getNextStartedActivity();
            assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS",intent.getAction());
            assertEquals(new ComponentName(activity,CaptureAccessibilityService.class),
                    intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME));
        }
    }

    @Test public void missingOemDetailScreenFallsBackToAccessibilityList() {
        try (var controller = Robolectric.buildActivity(NoDetailsActivity.class).setup()) {
            Activity activity = controller.get();
            CaptureAccessibilitySettings.open(activity);
            assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    shadowOf(activity).getNextStartedActivity().getAction());
        }
    }

    @Test public void diagnosticSeparatesMissingCapabilityFromReadyWindowAccess() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.codex.mnote.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        var controller = Robolectric.buildService(CaptureAccessibilityService.class).create();
        try {
            var service = controller.get();
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            service.setServiceInfo(info); service.onServiceConnected();
            String missing = CaptureAccessibilitySettings.diagnostic(service);
            assertTrue(missing,missing.contains("系统未授予"));
            ReflectionHelpers.callInstanceMethod(info,"setCapabilities",ReflectionHelpers.ClassParameter.from(int.class,
                    AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT));
            service.setServiceInfo(info);
            String ready = CaptureAccessibilitySettings.diagnostic(service);
            assertTrue(ready,ready.contains("窗口读取能力已启用"));
        } finally { controller.destroy(); }
    }

    public static class NoDetailsActivity extends Activity {
        @Override public void startActivity(Intent intent) {
            if ("android.settings.ACCESSIBILITY_DETAILS_SETTINGS".equals(intent.getAction()))
                throw new android.content.ActivityNotFoundException();
            super.startActivity(intent);
        }
    }
}

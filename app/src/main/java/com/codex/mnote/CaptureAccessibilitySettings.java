package com.codex.mnote;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

final class CaptureAccessibilitySettings {
    static void open(Activity activity) {
        ComponentName component = new ComponentName(activity, CaptureAccessibilityService.class);
        // Android Settings supports this action; OEMs may not, so retain a list fallback.
        try {
            activity.startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component));
        } catch (RuntimeException error) {
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", component.flattenToString());
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:show_fragment_args", args));
        }
    }

    static String diagnostic(Context context) {
        AccessibilityServiceInfo info = CaptureAccessibilityService.connectedInfo();
        String state;
        if (!CaptureAccessibilityService.isReady()) state = context.getString(R.string.capture_source_service_disconnected);
        else if (info == null) state = context.getString(R.string.capture_source_service_pending);
        else if ((info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) == 0)
            state = context.getString(R.string.capture_source_capability_missing);
        else if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0 || info.eventTypes == 0)
            state = context.getString(R.string.capture_source_service_pending);
        else state = context.getString(R.string.capture_source_capability_ready);
        return state + "\n\n" + context.getString(R.string.capture_source_settings_help);
    }
}

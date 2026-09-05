package com.codex.mnote;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings entry; each click can request exactly one screenshot. */
public final class CaptureQuickSettingsTileService extends TileService {
    static void requestRefresh(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                    context.getApplicationContext(),
                    new ComponentName(
                            context,
                            CaptureQuickSettingsTileService.class
                    )
            );
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(
                CaptureAccessibilityService.isReady()
                        ? Tile.STATE_ACTIVE
                        : Tile.STATE_INACTIVE
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setSubtitle(getString(
                    CaptureAccessibilityService.isReady()
                            ? R.string.capture_tile_ready
                            : R.string.capture_tile_setup_needed
            ));
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        Runnable launch = this::launchTrigger;
        if (isLocked()) {
            unlockAndRun(launch);
        } else {
            launch.run();
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchTrigger() {
        Intent intent = captureIntent(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(capturePendingIntent(this));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    static Intent captureIntent(Context context) {
        // A translucent activity in Mnote's existing task exposes its Inbox,
        // not the source app. Each capture needs a separate, unaffiliated task.
        return new Intent(context, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static PendingIntent capturePendingIntent(Context context) {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 35) {
            // Only SystemUI receives this immutable, explicit, user-clicked
            // capability; no background worker or external app is given it.
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            );
        }
        return PendingIntent.getActivity(
                context,
                // Use a new identity so a PendingIntent cached by SystemUI
                // from 1.0.0 cannot retain that version's task-launch flags.
                4302,
                captureIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
        );
    }
}

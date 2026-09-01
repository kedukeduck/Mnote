package com.codex.mnote;

import android.annotation.SuppressLint;
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
        Intent intent = new Intent(this, CaptureTriggerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    4301,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            startActivityAndCollapse(intent);
        }
    }
}

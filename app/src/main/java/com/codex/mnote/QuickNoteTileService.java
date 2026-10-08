package com.codex.mnote;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Legacy pinned tile alias. New installations offer only the unified recording tile. */
public final class QuickNoteTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setLabel(getString(R.string.capture_tile_label));
            tile.setState(Tile.STATE_ACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(getString(R.string.capture_tile_ready));
            }
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(this::launchEditor);
        } else {
            launchEditor();
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchEditor() {
        Intent intent = CaptureQuickSettingsTileService.prepareCaptureIntent(this);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(CaptureQuickSettingsTileService.capturePendingIntent(this, intent));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    static Intent noteIntent(Context context) {
        return CaptureQuickSettingsTileService.captureIntent(context);
    }

    static PendingIntent notePendingIntent(Context context) {
        return CaptureQuickSettingsTileService.capturePendingIntent(context);
    }
}

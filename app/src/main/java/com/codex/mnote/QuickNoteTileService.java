package com.codex.mnote;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Permission-free text entry. This path never requests a screenshot. */
public final class QuickNoteTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(getString(R.string.quick_note_tile_subtitle));
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
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(notePendingIntent(this));
        } else {
            startActivityAndCollapse(noteIntent(this));
        }
    }

    static Intent noteIntent(Context context) {
        // A fresh task preserves an existing editor and returns to the source app.
        return new Intent(context, CaptureEditorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    static PendingIntent notePendingIntent(Context context) {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 35) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return PendingIntent.getActivity(context, 4401, noteIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                options.toBundle());
    }
}

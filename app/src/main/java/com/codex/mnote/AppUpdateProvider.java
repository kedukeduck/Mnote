package com.codex.mnote;

/** Only the private update cache can be shared with the system installer. */
public final class AppUpdateProvider extends androidx.core.content.FileProvider {
    public AppUpdateProvider() {
        super(R.xml.update_file_paths);
    }
}

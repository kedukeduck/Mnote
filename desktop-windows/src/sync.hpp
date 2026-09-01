#pragma once

#include <string>

namespace PersonalCaptureSync {

struct Settings {
    bool enabled = false;
    std::wstring serverUrl;
    std::wstring writeToken;
    std::string aiAccess = "local_only";
    std::wstring error;
};

struct Result {
    bool attempted = false;
    bool succeeded = false;
    std::wstring error;
};

// Reads [sync] from %LOCALAPPDATA%\PersonalCapture\settings.ini.  An absent
// file is a valid, disabled configuration.  Credentials are kept in memory
// only and must never be copied into capture metadata or diagnostic text.
Settings LoadSettings(const std::wstring& appDirectory);

// Encodes one image for the server payload.  The server's per-asset 16 MiB
// limit is enforced before allocating the base64 output.
bool Base64File(
    const std::wstring& path,
    std::string& encoded,
    std::wstring& error);

// Sends one bounded PUT request. Redirects are disabled so the Authorization
// header cannot be forwarded to another origin. HTTP is accepted only for
// localhost or a literal private/link-local IP; all other destinations require
// HTTPS.
Result PutCapture(
    const Settings& settings,
    const std::wstring& captureId,
    const std::string& jsonPayload);

} // namespace PersonalCaptureSync

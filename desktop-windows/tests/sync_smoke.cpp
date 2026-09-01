#include "../src/sync.hpp"

#include <windows.h>

#include <string>

namespace {

void BestEffortCleanup(const std::wstring& directory) {
    DeleteFileW((directory + L"\\settings.ini").c_str());
    DeleteFileW((directory + L"\\bytes.bin").c_str());
    RemoveDirectoryW(directory.c_str());
}

} // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) {
        return 10;
    }

    wchar_t temporaryRoot[MAX_PATH]{};
    if (GetTempPathW(MAX_PATH, temporaryRoot) == 0U) {
        return 11;
    }
    const std::wstring directory =
        std::wstring(temporaryRoot) + L"MnoteSyncSmoke-" + std::to_wstring(GetCurrentProcessId());
    BestEffortCleanup(directory);
    if (CreateDirectoryW(directory.c_str(), nullptr) == FALSE) {
        return 12;
    }
    const std::wstring settingsPath = directory + L"\\settings.ini";

    // A public HTTP origin must be rejected before any network operation.
    if (WritePrivateProfileStringW(
            L"sync", L"server_url", L"http://example.com", settingsPath.c_str()) == FALSE ||
        WritePrivateProfileStringW(
            L"sync", L"write_token", L"smoke-write-token", settingsPath.c_str()) == FALSE) {
        BestEffortCleanup(directory);
        return 13;
    }
    const PersonalCaptureSync::Settings rejected = PersonalCaptureSync::LoadSettings(directory);
    if (rejected.enabled || rejected.error.empty()) {
        BestEffortCleanup(directory);
        return 14;
    }

    const std::wstring localUrl = L"http://127.0.0.1:" + std::wstring(argv[1]);
    if (WritePrivateProfileStringW(
            L"sync", L"server_url", localUrl.c_str(), settingsPath.c_str()) == FALSE ||
        // Ensure the privacy default is exercised instead of explicitly set.
        WritePrivateProfileStringW(L"sync", L"ai_access", nullptr, settingsPath.c_str()) == FALSE) {
        BestEffortCleanup(directory);
        return 15;
    }
    const PersonalCaptureSync::Settings settings = PersonalCaptureSync::LoadSettings(directory);
    if (!settings.enabled || settings.aiAccess != "local_only" || !settings.error.empty()) {
        BestEffortCleanup(directory);
        return 16;
    }

    const std::wstring bytesPath = directory + L"\\bytes.bin";
    HANDLE bytesFile = CreateFileW(
        bytesPath.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (bytesFile == INVALID_HANDLE_VALUE) {
        BestEffortCleanup(directory);
        return 17;
    }
    const unsigned char bytes[] = {1U, 2U, 3U, 4U};
    DWORD written = 0;
    const BOOL wrote = WriteFile(bytesFile, bytes, sizeof(bytes), &written, nullptr);
    CloseHandle(bytesFile);
    if (wrote == FALSE || written != sizeof(bytes)) {
        BestEffortCleanup(directory);
        return 18;
    }
    std::string base64;
    std::wstring encodeError;
    if (!PersonalCaptureSync::Base64File(bytesPath, base64, encodeError) || base64 != "AQIDBA==") {
        BestEffortCleanup(directory);
        return 19;
    }

    const PersonalCaptureSync::Result upload = PersonalCaptureSync::PutCapture(
        settings,
        L"smoke-capture-0001",
        "{\"schema_version\":1,\"id\":\"smoke-capture-0001\"}");
    BestEffortCleanup(directory);
    return upload.succeeded ? 0 : 20;
}

#include <windows.h>

namespace {

constexpr wchar_t kMainWindowClass[] = L"PersonalCapture.MessageWindow";
constexpr UINT kCommandNewCapture = 40001;
constexpr UINT kCommandSyncPending = 40004;

} // namespace

int wmain(int argc, wchar_t** argv) {
    const HWND mainWindow = FindWindowW(kMainWindowClass, nullptr);
    if (mainWindow == nullptr) {
        return 10;
    }
    UINT command = kCommandNewCapture;
    if (argc == 2 && lstrcmpiW(argv[1], L"sync") == 0) {
        command = kCommandSyncPending;
    } else if (argc > 2 || (argc == 2 && lstrcmpiW(argv[1], L"capture") != 0)) {
        return 12;
    }
    return PostMessageW(mainWindow, WM_COMMAND, command, 0) != FALSE ? 0 : 11;
}

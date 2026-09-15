#include <windows.h>
#include <string>
#include <iostream>
constexpr wchar_t ClassName[] = L"PersonalCapture.MessageWindow";
LRESULT CALLBACK Proc(HWND hwnd, UINT message, WPARAM w, LPARAM l) {
    if (message == WM_TIMER) {
        DestroyWindow(hwnd);
        return 0;
    }
    if (message == WM_DESTROY) {
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, message, w, l);
}
int wmain(int argc, wchar_t **argv) {
    if (argc != 2)
        return 2;
    std::wstring action = argv[1];
    if (action == L"host") {
        WNDCLASSW cls{};
        cls.lpszClassName = ClassName;
        cls.hInstance = GetModuleHandleW(nullptr);
        cls.lpfnWndProc = Proc;
        if (!RegisterClassW(&cls))
            return 3;
        auto hwnd = CreateWindowW(ClassName, L"Mnote updater handoff fixture", 0, 0, 0, 0, 0,
                                  nullptr, nullptr, cls.hInstance, nullptr);
        if (!hwnd)
            return 4;
        SetTimer(hwnd, 1, 30000, nullptr);
        MSG message;
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        // Emulate worker shutdown after the last application window disappeared.
        Sleep(2500);
        return 0;
    }
    auto hwnd = FindWindowW(ClassName, nullptr);
    if (!hwnd)
        return 5;
    if (action == L"pid") {
        DWORD process = 0;
        GetWindowThreadProcessId(hwnd, &process);
        std::cout << process << "\n";
    }
    if (action == L"close")
        PostMessageW(hwnd, WM_CLOSE, 0, 0);
    return 0;
}

#include <windows.h>
#include <gdiplus.h>
#include <iostream>
#include <string>

HWND Window(const wchar_t *title) { return FindWindowW(L"Mnote.Workspace", title); }
HWND Editor() {
    HWND window = Window(L"Mnote · 记下想法");
    return window ? window : Window(L"Mnote · 查看与修改");
}
void Click(HWND window, int id) {
    PostMessageW(window, WM_COMMAND, MAKEWPARAM(id, BN_CLICKED),
                 reinterpret_cast<LPARAM>(GetDlgItem(window, id)));
}
int wmain(int argc, wchar_t **argv) {
    if (argc < 2)
        return 2;
    std::wstring action = argv[1];
    if (action == L"screenshot" && argc == 3) {
        ULONG_PTR token = 0;
        Gdiplus::GdiplusStartupInput input;
        Gdiplus::GdiplusStartup(&token, &input, nullptr);
        HDC screen = GetDC(nullptr), memory = CreateCompatibleDC(screen);
        int width = GetSystemMetrics(SM_CXSCREEN), height = GetSystemMetrics(SM_CYSCREEN);
        HBITMAP bitmap = CreateCompatibleBitmap(screen, width, height);
        auto previous = SelectObject(memory, bitmap);
        BitBlt(memory, 0, 0, width, height, screen, 0, 0, SRCCOPY);
        const CLSID encoder = {
            0x557cf406, 0x1a04, 0x11d3, {0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e}};
        int result = 0;
        {
            Gdiplus::Bitmap image(bitmap, nullptr);
            result = image.Save(argv[2], &encoder, nullptr) == Gdiplus::Ok ? 0 : 18;
        }
        SelectObject(memory, previous);
        DeleteObject(bitmap);
        DeleteDC(memory);
        ReleaseDC(nullptr, screen);
        Gdiplus::GdiplusShutdown(token);
        return result;
    }
    HWND main = FindWindowW(L"PersonalCapture.MessageWindow", nullptr),
         home = Window(L"Mnote · 我的知识库");
    if (action == L"source") {
        HWND source =
            CreateWindowW(L"STATIC", L"Mnote GUI source fixture", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                          60, 60, 1120, 680, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
        CreateWindowW(L"STATIC", L"External page fixture · select a region and retain its context",
                      WS_CHILD | WS_VISIBLE, 80, 120, 900, 60, source, nullptr,
                      GetModuleHandleW(nullptr), nullptr);
        SetForegroundWindow(source);
        MSG message;
        while (GetMessageW(&message, nullptr, 0, 0) > 0) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        return 0;
    }
    if (action == L"focus-source") {
        auto source = FindWindowW(L"STATIC", L"Mnote GUI source fixture");
        if (!source)
            return 16;
        SetForegroundWindow(source);
        return 0;
    }
    if (action == L"ready")
        return main && home ? 0 : 10;
    if (action == L"capture" || action == L"quick" || action == L"sync" || action == L"show" ||
        action == L"exit") {
        if (!main)
            return 10;
        UINT command = action == L"capture" ? 40001
                       : action == L"quick" ? 40005
                       : action == L"sync"  ? 40004
                       : action == L"show"  ? 40002
                                            : 40003;
        PostMessageW(main, WM_COMMAND, command, 0);
        return 0;
    }
    if (action == L"next" || action == L"pen" || action == L"cancel-capture") {
        HWND overlay = FindWindowW(L"PersonalCapture.CaptureOverlay", nullptr);
        if (!overlay)
            return 11;
        Click(overlay, action == L"next" ? 1007 : action == L"pen" ? 1002 : 1008);
        return 0;
    }
    if (action == L"editor")
        return Editor() ? 0 : 12;
    if (action == L"fill") {
        auto window = Editor();
        if (!window)
            return 12;
        SetDlgItemTextW(window, 2100, L"wine-smoke-note");
        SetDlgItemTextW(window, 2103, L"工作，灵感");
        return 0;
    }
    if (action == L"full") {
        auto w = Editor();
        if (!w)
            return 12;
        SendDlgItemMessageW(w, 2110, BM_SETCHECK, BST_CHECKED, 0);
        Click(w, 2110);
        return 0;
    }
    if (action == L"save") {
        auto w = Editor();
        if (!w)
            return 12;
        Click(w, 2106);
        return 0;
    }
    if (action == L"count") {
        if (!home)
            return 10;
        auto n = SendDlgItemMessageW(home, 2005, LB_GETCOUNT, 0, 0);
        std::cout << n << "\n";
        return argc == 3 && n != std::stoi(argv[2]) ? 20 : 0;
    }
    if (action == L"open") {
        if (!home)
            return 10;
        SendDlgItemMessageW(home, 2005, LB_SETCURSEL, 0, 0);
        Click(home, 2011);
        return 0;
    }
    if (action == L"edit") {
        auto w = Editor();
        if (!w)
            return 12;
        SetDlgItemTextW(w, 2100, L"edited-thought");
        SetDlgItemTextW(w, 2101, L"clipboard excerpt");
        std::wstring original = L"original start\r\n";
        for (int i = 0; i < 600; i++)
            original += L"scrollable line " + std::to_wstring(i) + L"\r\n";
        original += L"original END";
        SetDlgItemTextW(w, 2102, original.c_str());
        SetDlgItemTextW(w, 2103, L"已编辑");
        Click(w, 2106);
        return 0;
    }
    if (action == L"delete") {
        auto w = Editor();
        if (!w)
            return 12;
        Click(w, 2108);
        return 0;
    }
    if (action == L"confirm") {
        auto dialog = FindWindowW(L"#32770", nullptr);
        if (!dialog)
            return 13;
        PostMessageW(dialog, WM_COMMAND, IDYES, 0);
        return 0;
    }
    if (action == L"trash") {
        Click(home, 2010);
        return 0;
    }
    if (action == L"tag" && argc == 3) {
        auto c = GetDlgItem(home, 2003);
        auto index = SendMessageW(c, CB_FINDSTRINGEXACT, static_cast<WPARAM>(-1),
                                  reinterpret_cast<LPARAM>(argv[2]));
        if (index < 0)
            return 17;
        SendMessageW(c, CB_SETCURSEL, static_cast<WPARAM>(index), 0);
        PostMessageW(home, WM_COMMAND, MAKEWPARAM(2003, CBN_SELCHANGE),
                     reinterpret_cast<LPARAM>(c));
        return 0;
    }
    if (action == L"account") {
        Click(home, 2009);
        return 0;
    }
    if (action == L"login" && argc == 4) {
        auto w = Window(L"Mnote · 账号与同步");
        if (!w)
            return 14;
        SetDlgItemTextW(w, 2200, argv[2]);
        SetDlgItemTextW(w, 2201, L"gui-account");
        SetDlgItemTextW(w, 2202, L"test-password-only-123");
        SetDlgItemTextW(w, 2203, argv[3]);
        Click(w, 2204);
        return 0;
    }
    if (action == L"import") {
        auto w = Window(L"Mnote · 账号与同步");
        if (!w)
            return 14;
        Click(w, 2206);
        return 0;
    }
    if (action == L"close-account") {
        auto w = Window(L"Mnote · 账号与同步");
        if (!w)
            return 14;
        PostMessageW(w, WM_CLOSE, 0, 0);
        return 0;
    }
    if (action == L"clipboard") {
        const wchar_t *value = L"explicit clipboard excerpt";
        if (!OpenClipboard(nullptr))
            return 15;
        EmptyClipboard();
        auto memory = GlobalAlloc(GMEM_MOVEABLE, (wcslen(value) + 1) * 2);
        auto target = GlobalLock(memory);
        memcpy(target, value, (wcslen(value) + 1) * 2);
        GlobalUnlock(memory);
        SetClipboardData(CF_UNICODETEXT, memory);
        CloseClipboard();
        auto w = Editor();
        if (!w)
            return 12;
        SendDlgItemMessageW(w, 2111, BM_SETCHECK, BST_CHECKED, 0);
        Click(w, 2111);
        return 0;
    }
    if (action == L"context-screen" || action == L"context-text") {
        auto w = Editor();
        if (!w)
            return 12;
        Click(w, action == L"context-screen" ? 2113 : 2112);
        return 0;
    }
    if (action == L"idle") {
        auto w = Editor();
        return w && IsWindowEnabled(GetDlgItem(w, 2106)) ? 0 : 19;
    }
    return 2;
}

#include <windows.h>
#include <gdiplus.h>
#include <iostream>
#include <string>
#include <vector>

HWND Window(const wchar_t *title) { return FindWindowW(L"Mnote.Workspace", title); }
HWND Editor() {
    HWND window = Window(L"Mnote · 记下想法");
    return window ? window : Window(L"Mnote · 查看与修改");
}
void Click(HWND window, int id) {
    PostMessageW(window, WM_COMMAND, MAKEWPARAM(id, BN_CLICKED),
                 reinterpret_cast<LPARAM>(GetDlgItem(window, id)));
}
std::wstring ControlText(HWND control) {
    const auto length = SendMessageW(control, WM_GETTEXTLENGTH, 0, 0);
    if (length < 0 || length > 200000)
        return L"";
    std::wstring text(static_cast<std::size_t>(length) + 1, L'\0');
    const auto copied = SendMessageW(control, WM_GETTEXT, text.size(),
                                     reinterpret_cast<LPARAM>(text.data()));
    text.resize(static_cast<std::size_t>(copied));
    return text;
}
BOOL CALLBACK CollectRichEdits(HWND child, LPARAM value) {
    wchar_t className[64]{};
    GetClassNameW(child, className, 64);
    if (_wcsicmp(className, L"RICHEDIT50W") == 0)
        reinterpret_cast<std::vector<HWND> *>(value)->push_back(child);
    return TRUE;
}
int wmain(int argc, wchar_t **argv) {
    if (argc < 2)
        return 2;
    std::wstring action = argv[1];
    if ((action == L"screenshot" && argc == 3) || (action == L"window-screenshot" && argc == 4)) {
        HWND target = action == L"window-screenshot" ? Window(argv[3]) : nullptr;
        if (action == L"window-screenshot" && !target) return 19;
        ULONG_PTR token = 0;
        Gdiplus::GdiplusStartupInput input;
        Gdiplus::GdiplusStartup(&token, &input, nullptr);
        HDC screen = GetDC(nullptr), memory = CreateCompatibleDC(screen);
        int width = GetSystemMetrics(SM_CXSCREEN), height = GetSystemMetrics(SM_CYSCREEN);
        if (target) {
            ShowWindow(target, SW_SHOWNORMAL);
            SetWindowPos(target, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            RECT bounds{}; GetWindowRect(target, &bounds);
            width = bounds.right - bounds.left; height = bounds.bottom - bounds.top;
            RedrawWindow(target, nullptr, nullptr, RDW_INVALIDATE | RDW_ALLCHILDREN | RDW_UPDATENOW);
        }
        HBITMAP bitmap = CreateCompatibleBitmap(screen, width, height);
        auto previous = SelectObject(memory, bitmap);
        if (target) {
            Sleep(350);
            RECT bounds{}; GetWindowRect(target, &bounds);
            BitBlt(memory, 0, 0, width, height, screen, bounds.left, bounds.top, SRCCOPY);
            SetWindowPos(target, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        } else BitBlt(memory, 0, 0, width, height, screen, 0, 0, SRCCOPY);
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
    if (action == L"ai-save-chat") {
        auto window = Editor(); if (!window) return 80;
        Click(window, 29001); return 0;
    }
    if (action == L"ai-closed")
        return Window(L"Mnote · 与 AI 聊聊") ? 100 : 0;
    if (action == L"ai-ready" || action == L"ai-draft" || action == L"ai-draft-ready" ||
        action == L"ai-close" || action == L"ai-send" || action == L"ai-small" || action == L"ai-large") {
        auto window = Window(L"Mnote · 与 AI 聊聊"); if (!window) return 81;
        if(action==L"ai-small"||action==L"ai-large") {
            SetWindowPos(window,nullptr,0,0,action==L"ai-small"?680:880,action==L"ai-small"?620:900,SWP_NOMOVE|SWP_NOZORDER);
        }
        auto input = GetDlgItem(window, 29008), transcript = GetDlgItem(window, 29009);
        if (!input || !transcript || !IsWindowEnabled(GetDlgItem(window, 29005))) return 82;
        RECT a{}, b{}; GetWindowRect(transcript, &a); GetWindowRect(input, &b);
        if (a.bottom >= b.top) return 83;
        if (action == L"ai-draft") SetWindowTextW(input,L"Synthetic draft, never sent to a model.");
        if (action == L"ai-draft-ready") { wchar_t text[100]{};SendMessageW(input,WM_GETTEXT,100,reinterpret_cast<LPARAM>(text));if(std::wstring(text)!=L"Synthetic draft, never sent to a model.")return 84; }
        if (action == L"ai-close") PostMessageW(window,WM_CLOSE,0,0);
        if (action == L"ai-send") Click(window,29005);
        return 0;
    }
    if (action == L"ai-models" || action == L"ai-all-history") {
        auto settings=Window(L"Mnote · 设置");if(!settings)return 85;
        Click(settings,action==L"ai-models"?29003:29004);return 0;
    }
    if (action == L"ai-models-ready" || action == L"ai-models-fill" || action == L"ai-models-close") {
        auto window=Window(L"Mnote · AI 模型配置");if(!window)return 86;
        if(!SendDlgItemMessageW(window,29023,EM_GETPASSWORDCHAR,0,0))return 87;
        if(action==L"ai-models-fill"){
            SetDlgItemTextW(window,29020,L"Synthetic model · not invoked");SetDlgItemTextW(window,29021,L"https://models.invalid/v1");
            SetDlgItemTextW(window,29022,L"mock-vision");SetDlgItemTextW(window,29023,L"sk_SYNTHETIC_GUI_ONLY");
            SendDlgItemMessageW(window,29024,BM_SETCHECK,BST_CHECKED,0);Click(window,2106);
        }
        if(action==L"ai-models-close")PostMessageW(window,WM_CLOSE,0,0);
        return 0;
    }
    if (action == L"ai-consent-cancel") {
        auto dialog=FindWindowW(L"#32770",L"Mnote · 确认资料与模型");if(!dialog)return 88;
        PostMessageW(dialog,WM_COMMAND,IDNO,0);return 0;
    }
    if (action == L"ai-history-empty") {
        auto window=Window(L"Mnote · 全部 AI 对话");if(!window)return 89;
        if(SendDlgItemMessageW(window,2005,LB_GETCOUNT,0,0)!=0)return 90;
        PostMessageW(window,WM_CLOSE,0,0);return 0;
    }
    if (action == L"ai-history-continue" || action == L"ai-history-close") {
        auto window = Window(L"Mnote · 全部 AI 对话");
        if (!window)
            return 91;
        if (action == L"ai-history-close") {
            PostMessageW(window, WM_CLOSE, 0, 0);
            return 0;
        }
        if (SendDlgItemMessageW(window, 2005, LB_GETCOUNT, 0, 0) != 1)
            return 92;
        SendDlgItemMessageW(window, 2005, LB_SETCURSEL, 0, 0);
        Click(window, 29000);
        return 0;
    }
    if (action == L"ai-bubbles-ready" || action == L"ai-bubbles-select") {
        auto window = Window(L"Mnote · 与 AI 聊聊");
        auto transcript = GetDlgItem(window, 29009), input = GetDlgItem(window, 29008);
        if (!window || !transcript || !input)
            return 93;
        RECT transcriptBounds{}, inputBounds{};
        GetWindowRect(transcript, &transcriptBounds);
        GetWindowRect(input, &inputBounds);
        if (transcriptBounds.bottom >= inputBounds.top || IsWindowVisible(GetDlgItem(window, 29033)))
            return 94;
        std::vector<HWND> bodies;
        EnumChildWindows(transcript, CollectRichEdits, reinterpret_cast<LPARAM>(&bodies));
        if (bodies.size() != 4)
            return 95;
        HWND own = nullptr, assistant = nullptr;
        int ownCount = 0, assistantCount = 0;
        for (auto body : bodies) {
            const auto text = ControlText(body);
            if (text.find(L"我想把收藏变成行动") != std::wstring::npos ||
                text.find(L"先从这条记录开始") != std::wstring::npos) {
                ++ownCount;
                own = body;
            }
            if (text.find(L"给想法一个小小的出口") != std::wstring::npos ||
                text.find(L"今天只做一件事") != std::wstring::npos) {
                ++assistantCount;
                assistant = body;
            }
            if (!(GetWindowLongPtrW(body, GWL_STYLE) & ES_READONLY))
                return 96;
        }
        if (ownCount != 2 || assistantCount != 2 || !own || !assistant)
            return 97;
        RECT ownBounds{}, assistantBounds{};
        GetWindowRect(own, &ownBounds);
        GetWindowRect(assistant, &assistantBounds);
        if (ownBounds.left <= assistantBounds.left || ownBounds.right <= assistantBounds.right)
            return 98;
        if (action == L"ai-bubbles-select") {
            SendMessageW(assistant, EM_SETSEL, 0, 7);
            const auto selection = SendMessageW(assistant, EM_GETSEL, 0, 0);
            if (LOWORD(selection) != 0 || HIWORD(selection) != 7)
                return 99;
            SendMessageW(assistant, EM_SETSEL, 0, 0);
        }
        return 0;
    }
    if (action == L"library-uncovered")
        return home && IsWindowVisible(home) && IsWindowEnabled(home) &&
               !Window(L"Mnote · 设置") && !Window(L"Mnote · 账号与同步") ? 0 : 68;
    if (action == L"home-size" && argc == 4) {
        if (!home) return 60;
        return SetWindowPos(home, nullptr, 0, 0, std::stoi(argv[2]), std::stoi(argv[3]),
                            SWP_NOMOVE | SWP_NOZORDER) ? 0 : 61;
    }
    if (action == L"cards-ready") {
        auto list = GetDlgItem(home, 2005);
        if (!home || !list || SendMessageW(list, LB_GETCOUNT, 0, 0) != 3) return 62;
        const int dpi = static_cast<int>(GetDpiForWindow(home));
        auto scale = [dpi](int n) { return MulDiv(n, dpi, 96); };
        bool thought = false, excerpt = false, mixed = false;
        for (int i = 0; i < 3; ++i) {
            const auto length = SendMessageW(list, LB_GETTEXTLEN, i, 0);
            if (length < 1 || length > 200000) return 64;
            std::wstring label(static_cast<std::size_t>(length) + 1, L'\0');
            SendMessageW(list, LB_GETTEXT, i, reinterpret_cast<LPARAM>(label.data()));
            thought = thought || label.rfind(L"想法 · ", 0) == 0;
            excerpt = excerpt || label.rfind(L"摘录 · ", 0) == 0;
            const bool rowMixed = label.rfind(L"想法 · 摘录 · ", 0) == 0;
            mixed = mixed || rowMixed;
            if (SendMessageW(list, LB_GETITEMHEIGHT, i, 0) != scale(rowMixed ? 276 : 212)) return 63;
            if (rowMixed && (label.find(L"我的想法：edited-thought") == std::wstring::npos ||
                             label.find(L"摘录：clipboard excerpt") == std::wstring::npos)) return 69;
        }
        if (!thought || !excerpt || !mixed) return 65;
        RECT row{}, bounds{}, capture{}, note{}, client{};
        SendMessageW(list, LB_GETITEMRECT, 1, reinterpret_cast<LPARAM>(&row));
        HDC dc = GetDC(list);
        const auto gap = GetPixel(dc, scale(20), row.top + scale(2));
        const auto paper = GetPixel(dc, scale(4), row.top + scale(30));
        ReleaseDC(list, dc);
        if (gap != RGB(247,244,236) || paper != RGB(255,254,250)) return 66;
        GetWindowRect(list, &bounds); GetWindowRect(GetDlgItem(home, 2008), &capture);
        GetWindowRect(GetDlgItem(home, 2007), &note); GetClientRect(home, &client);
        if (client.right < scale(1080) && (capture.right > bounds.left || note.right > bounds.left))
            return 67;
        return 0;
    }
    if (action == L"mixed-preview-ready") {
        auto list = GetDlgItem(home, 2005);
        if (!list || SendMessageW(list, LB_GETCOUNT, 0, 0) != 1) return 70;
        const int dpi = static_cast<int>(GetDpiForWindow(home));
        auto scale = [dpi](int n) { return MulDiv(n, dpi, 96); };
        RECT row{};
        SendMessageW(list, LB_GETITEMRECT, 0, reinterpret_cast<LPARAM>(&row));
        HDC dc = GetDC(list);
        // Source surface begins after the complete thought block, not on top of it.
        const auto quoteLine = GetPixel(dc, scale(14), row.top + scale(126));
        const auto sourceSurface = GetPixel(dc, scale(20), row.top + scale(126));
        ReleaseDC(list, dc);
        return quoteLine == RGB(149,85,48) && sourceSurface == RGB(239,235,227) ? 0 : 71;
    }
    if (action == L"mixed-dpi-ready") {
        auto list = GetDlgItem(home, 2005);
        if (!list || SendMessageW(list, LB_GETCOUNT, 0, 0) != 1) return 72;
        const int originalDpi = static_cast<int>(GetDpiForWindow(home));
        // Do not pass a RECT pointer from the driver's address space to the app.
        // A null suggested rect keeps the current bounds for this synthetic DPI check.
        SendMessageW(home, WM_DPICHANGED, MAKEWPARAM(144, 144), 0);
        const bool enlarged = SendMessageW(list, LB_GETITEMHEIGHT, 0, 0) == MulDiv(276, 144, 96);
        SendMessageW(home, WM_DPICHANGED, MAKEWPARAM(originalDpi, originalDpi), 0);
        const bool restored = SendMessageW(list, LB_GETITEMHEIGHT, 0, 0) == MulDiv(276, originalDpi, 96);
        return enlarged && restored ? 0 : 73;
    }
    if (action == L"read-record" && argc == 3) {
        auto list = GetDlgItem(home, 2005);
        int index = std::stoi(argv[2]);
        if (!list || index < 0 || index >= SendMessageW(list, LB_GETCOUNT, 0, 0)) return 20;
        SendMessageW(list, LB_SETCURSEL, index, 0);
        SendMessageW(home, WM_COMMAND, MAKEWPARAM(2005, LBN_SELCHANGE), reinterpret_cast<LPARAM>(list));
        return 0;
    }
    if (action == L"markdown") {
        Click(home, 25000);
        return 0;
    }
    if (action == L"inline-ready") {
        return home && IsWindowEnabled(home) && IsWindowVisible(GetDlgItem(home, 27003)) &&
                       !Window(L"Mnote · 导出给 AI") && !FindWindowW(L"#32770", L"Mnote · 确认导出")
                   ? 0
                   : 55;
    }
    if (action == L"settings-shares" || action == L"settings-close") {
        auto settings = Window(L"Mnote · 设置");
        if (!settings)
            return 23;
        if (action == L"settings-close")
            PostMessageW(settings, WM_CLOSE, 0, 0);
        else
            Click(settings, 25003);
        return 0;
    }
    if (action == L"share-text" || action == L"share-text-ready" || action == L"share-text-close") {
        if (action == L"share-text") {
            auto w = Window(L"Mnote · 导出链接管理");
            if (!w)
                return 34;
            Click(w, 27006);
            return 0;
        }
        auto w = Window(L"Mnote · 分享时的文字");
        if (!w)
            return 56;
        wchar_t text[30000]{};
        GetDlgItemTextW(w, 2102, text, 30000);
        if (std::wstring(text).find(L"original END") == std::wstring::npos)
            return 57;
        if (action == L"share-text-close")
            PostMessageW(w, WM_CLOSE, 0, 0);
        return 0;
    }
    if (action == L"multi-begin") {
        SendDlgItemMessageW(home, 2005, LB_SETCURSEL, 0, 0);
        SendMessageW(GetDlgItem(home, 2005), WM_CONTEXTMENU, 0, -1);
        return 0;
    }
    if (action == L"multi-ready")
        return home && IsWindowVisible(GetDlgItem(home, 27003)) &&
                       IsWindowEnabled(GetDlgItem(home, 27003))
                   ? 0
                   : 40;
    if (action == L"multi-all") {
        Click(home, 27002);
        return 0;
    }
    if (action == L"multi-cancel") {
        Click(home, 27001);
        return 0;
    }
    if (action == L"multi-delete") {
        Click(home, 27003);
        return 0;
    }
    if (action == L"multi-delete-cancel" || action == L"multi-delete-confirm") {
        auto dialog = FindWindowW(L"#32770", L"Mnote · 批量删除");
        if (!dialog)
            return 41;
        PostMessageW(dialog, WM_COMMAND, action == L"multi-delete-cancel" ? IDNO : IDYES, 0);
        return 0;
    }
    if (action == L"markdown-preselected") {
        auto w = Window(L"Mnote · 导出给 AI");
        if (!w)
            return 42;
        return SendDlgItemMessageW(w, 2005, LB_GETSELCOUNT, 0, 0) == 3 ? 0 : 43;
    }
    if (action == L"markdown-preview-image") {
        auto w = Window(L"Mnote · 导出给 AI");
        if (!w)
            return 42;
        SendDlgItemMessageW(w, 2005, LB_SETCARETINDEX, 0, 0);
        Click(w, 27004);
        return 0;
    }
    if (action == L"image-close") {
        auto w = Window(L"Mnote · 图片与页面上下文");
        if (!w)
            return 44;
        PostMessageW(w, WM_CLOSE, 0, 0);
        return 0;
    }
    if (action == L"markdown-select" || action == L"markdown-save" || action == L"markdown-close" ||
        action == L"markdown-shares") {
        auto w = Window(L"Mnote · 导出给 AI");
        if (!w)
            return 30;
        if (action == L"markdown-select") {
            if (SendDlgItemMessageW(w, 2005, LB_GETCOUNT, 0, 0) != 3)
                return 31;
            Click(w, 25001);
        }
        if (action == L"markdown-save")
            Click(w, 2106);
        if (action == L"markdown-close")
            PostMessageW(w, WM_CLOSE, 0, 0);
        if (action == L"markdown-shares")
            Click(w, 25003);
        return 0;
    }
    if (action == L"markdown-file") {
        auto dialog = FindWindowW(L"#32770", L"Mnote · 保存 Markdown");
        if (!dialog || !IsWindowVisible(dialog))
            return 32;
        // Use the offered filename in the isolated test working directory. Native dialog
        // edit-control IDs vary between Wine and Windows; no app automation bypass is used.
        PostMessageW(dialog, WM_COMMAND, IDOK, 0);
        return 0;
    }
    if (action == L"markdown-confirm" || action == L"markdown-confirm-cancel") {
        auto dialog = FindWindowW(L"#32770", L"Mnote · 确认导出");
        if (!dialog)
            return 37;
        PostMessageW(dialog, WM_COMMAND, action == L"markdown-confirm" ? IDYES : IDNO, 0);
        return 0;
    }
    if (action == L"history-ready" || action == L"history-next" || action == L"history-close") {
        auto w = Window(L"Mnote · 历史分享图片（当时的快照）");
        if (!w)
            return 49;
        // A successful decode is required before the title changes to this value.
        if (action == L"history-close")
            PostMessageW(w, WM_CLOSE, 0, 0);
        if (action == L"history-next") {
            auto combo = FindWindowExW(w, nullptr, L"COMBOBOX", nullptr);
            if (!combo || SendMessageW(combo, CB_GETCOUNT, 0, 0) != 4)
                return 50;
            SendMessageW(combo, CB_SETCURSEL, 1, 0);
            SendMessageW(w, WM_COMMAND, MAKEWPARAM(GetDlgCtrlID(combo), CBN_SELCHANGE),
                         reinterpret_cast<LPARAM>(combo));
        }
        return 0;
    }
    if (action == L"shares-covers-ready" || action == L"shares-preview" ||
        action == L"shares-revoke" || action == L"shares-empty" || action == L"shares-close") {
        auto w = Window(L"Mnote · 导出链接管理");
        if (!w)
            return 34;
        auto list = GetDlgItem(w, 2005);
        if (action == L"shares-covers-ready") {
            wchar_t status[256]{};
            GetDlgItemTextW(w, 2300, status, 256);
            return std::wstring(status).find(L"已显示 1 次分享的图片预览") != std::wstring::npos &&
                           !Window(L"Mnote · 历史分享图片（当时的快照）")
                       ? 0
                       : 51;
        }
        if (action == L"shares-preview") {
            if (SendMessageW(list, LB_GETCOUNT, 0, 0) != 1)
                return 35;
            SendMessageW(list, LB_SETCURSEL, 0, 0);
            Click(w, 27004);
        }
        if (action == L"shares-revoke") {
            if (SendMessageW(list, LB_GETCOUNT, 0, 0) != 1)
                return 35;
            SendMessageW(list, LB_SETCURSEL, 0, 0);
            Click(w, 2106);
        }
        if (action == L"shares-empty" && SendMessageW(list, LB_GETCOUNT, 0, 0) != 0)
            return 36;
        if (action == L"shares-close")
            PostMessageW(w, WM_CLOSE, 0, 0);
        return 0;
    }
    if (action == L"settings") {
        Click(home, 26000);
        return 0;
    }
    if (action == L"settings-ready") {
        auto w = Window(L"Mnote · 设置");
        return w && IsWindowVisible(w) && GetDlgItem(w, 2009) && GetDlgItem(w, 24000) ? 0 : 23;
    }
    if (action == L"updates") {
        if (!home || !GetDlgItem(home, 26000))
            return 23;
        SendMessageW(home, WM_COMMAND, MAKEWPARAM(26000, BN_CLICKED),
                     reinterpret_cast<LPARAM>(GetDlgItem(home, 26000)));
        auto settings = Window(L"Mnote · 设置");
        if (!settings)
            return 23;
        Click(settings, 24000);
        return 0;
    }
    if (action == L"updates-ready" || action == L"close-updates") {
        auto w = Window(L"Mnote · 版本与更新");
        if (!w || !IsWindowEnabled(GetDlgItem(w, 24001)))
            return 23;
        wchar_t status[512]{};
        GetDlgItemTextW(w, 2300, status, 512);
        if (!status[0] || std::wstring(status).find(L"正在") != std::wstring::npos)
            return 23;
        if (!GetDlgItem(w, 24002) || !GetDlgItem(w, 24003) || !GetDlgItem(w, 24005))
            return 23;
        if (action == L"close-updates")
            PostMessageW(w, WM_CLOSE, 0, 0);
        return 0;
    }
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
    if (action == L"tags-existing") {
        auto w = Editor();
        if (!w)
            return 12;
        auto picker = GetDlgItem(w, 27005);
        if (!picker || SendMessageW(picker, CB_GETCOUNT, 0, 0) != 3)
            return 52;
        SetDlgItemTextW(w, 2103, L"已编辑");
        int index =
            int(SendMessageW(picker, CB_FINDSTRINGEXACT, -1, reinterpret_cast<LPARAM>(L"灵感")));
        if (index == CB_ERR)
            return 53;
        for (int i = 0; i < 2; ++i) {
            SendMessageW(picker, CB_SETCURSEL, index, 0);
            SendMessageW(w, WM_COMMAND, MAKEWPARAM(27005, CBN_SELCHANGE),
                         reinterpret_cast<LPARAM>(picker));
        }
        wchar_t tags[256]{};
        GetDlgItemTextW(w, 2103, tags, 256);
        return std::wstring(tags) == L"已编辑，灵感" ? 0 : 54;
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
    if ((action == L"tag" || action == L"kind") && argc == 3) {
        const int id = action == L"kind" ? 2004 : 2003;
        auto c = GetDlgItem(home, id);
        auto index = SendMessageW(c, CB_FINDSTRINGEXACT, static_cast<WPARAM>(-1),
                                  reinterpret_cast<LPARAM>(argv[2]));
        if (index < 0)
            return 17;
        SendMessageW(c, CB_SETCURSEL, static_cast<WPARAM>(index), 0);
        PostMessageW(home, WM_COMMAND, MAKEWPARAM(id, CBN_SELCHANGE),
                     reinterpret_cast<LPARAM>(c));
        return 0;
    }
    if (action == L"account") {
        SendMessageW(home, WM_COMMAND, MAKEWPARAM(26000, BN_CLICKED),
                     reinterpret_cast<LPARAM>(GetDlgItem(home, 26000)));
        auto settings = Window(L"Mnote · 设置");
        if (!settings)
            return 14;
        Click(settings, 2009);
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

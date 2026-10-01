#include "../src/chat_transcript.hpp"
#include <commctrl.h>
#include <richedit.h>
#include <windowsx.h>
#include <algorithm>
#include <iostream>
#include <stdexcept>

using namespace Mnote::ChatTranscript;
namespace {
int checks = 0, retryCommands = 0, menuCount = 0, menuItems = 0;
bool menuRetry = false, selectionEnabled = false;
HWND host = nullptr;
int menuChoice = 0;
void Expect(bool condition, const char *description) {
    ++checks;
    if (!condition)
        throw std::runtime_error(std::string("bubble assertion: ") + description);
}
void Pump(int ms = 30) {
    auto until = GetTickCount64() + static_cast<ULONGLONG>(ms);
    MSG message{};
    do {
        while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        Sleep(5);
    } while (GetTickCount64() < until);
}
std::vector<HWND> Children(HWND window) {
    std::vector<HWND> children;
    for (auto child = GetWindow(window, GW_CHILD); child; child = GetWindow(child, GW_HWNDNEXT))
        children.push_back(child);
    return children;
}
std::wstring Text(HWND window) {
    auto length = GetWindowTextLengthW(window);
    std::wstring text(static_cast<std::size_t>(length) + 1, 0);
    GetWindowTextW(window, text.data(), length + 1);
    text.resize(static_cast<std::size_t>(length));
    return text;
}
HWND FindText(const std::wstring &part) {
    for (auto child : Children(host))
        if (Text(child).find(part) != std::wstring::npos)
            return child;
    return nullptr;
}
RECT Bounds(HWND window) {
    RECT rect{};
    GetWindowRect(window, &rect);
    return rect;
}
SCROLLINFO Scroll() {
    SCROLLINFO info{sizeof(info), SIF_ALL, 0, 0, 0, 0, 0};
    GetScrollInfo(host, SB_VERT, &info);
    return info;
}
VOID CALLBACK MenuTimer(HWND, UINT, UINT_PTR timer, DWORD) {
    auto popup = FindWindowW(L"#32768", nullptr);
    if (!popup)
        return;
    KillTimer(nullptr, timer);
    auto menu = reinterpret_cast<HMENU>(SendMessageW(popup, 0x01e1 /* MN_GETHMENU */, 0, 0));
    ++menuCount;
    menuItems = GetMenuItemCount(menu);
    menuRetry = GetMenuState(menu, 4, MF_BYCOMMAND) != static_cast<UINT>(-1);
    selectionEnabled = (GetMenuState(menu, 2, MF_BYCOMMAND) & (MF_DISABLED | MF_GRAYED)) == 0;
    if (menuChoice == -1) {
        DestroyWindow(GetParent(host));
        EndMenu();
        return;
    }
    if (!menuChoice) {
        EndMenu();
        return;
    }
    for (int i = 0; i < menuItems; ++i) {
        auto id = GetMenuItemID(menu, i);
        if (id == static_cast<UINT>(menuChoice)) {
            RECT bounds{};
            GetMenuItemRect(nullptr, menu, static_cast<UINT>(i), &bounds);
            SetCursorPos((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2);
            INPUT clicks[2]{};
            clicks[0].type = clicks[1].type = INPUT_MOUSE;
            clicks[0].mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
            clicks[1].mi.dwFlags = MOUSEEVENTF_LEFTUP;
            SendInput(2, clicks, sizeof(INPUT));
            break;
        }
    }
}
void Menu(HWND child, int choice = 0, bool keyboard = false) {
    menuChoice = choice;
    auto timer = SetTimer(nullptr, 0, 40, MenuTimer);
    if (keyboard)
        SendMessageW(child, WM_KEYDOWN, VK_APPS, 0);
    else
        SendMessageW(child, WM_CONTEXTMENU, reinterpret_cast<WPARAM>(child), MAKELPARAM(-1, -1));
    KillTimer(nullptr, timer);
    Pump();
}
std::wstring Clipboard() {
    if (!OpenClipboard(nullptr))
        return L"";
    auto data = GetClipboardData(CF_UNICODETEXT);
    auto value = data ? static_cast<const wchar_t *>(GlobalLock(data)) : nullptr;
    std::wstring result = value ? value : L"";
    if (value)
        GlobalUnlock(data);
    CloseClipboard();
    return result;
}
LRESULT CALLBACK Procedure(HWND window, UINT message, WPARAM wp, LPARAM lp) {
    if (message == WM_COMMAND && LOWORD(wp) == 42) {
        ++retryCommands;
        return 0;
    }
    return DefWindowProcW(window, message, wp, lp);
}
void Cases() {
    WNDCLASSW klass{};
    klass.lpszClassName = L"Mnote.BubbleTest";
    klass.hInstance = GetModuleHandleW(nullptr);
    klass.lpfnWndProc = Procedure;
    RegisterClassW(&klass);
    auto parent = CreateWindowW(klass.lpszClassName, L"Synthetic IM transcript",
                                WS_OVERLAPPEDWINDOW | WS_VISIBLE, 50, 50, 900, 900, nullptr,
                                nullptr, klass.hInstance, nullptr);
    host = Create(parent, 77, 42, 96);
    MoveWindow(host, 20, 20, 800, 700, TRUE);
    SetForegroundWindow(parent);
    std::vector<Message> messages = {
        {"user1", true, L"我想把每周回顾变成一个习惯。", L"", "completed", L""},
        {"ai1", false,
         L"## 从一个很小的动作开始\n试着在周日留出 **十分钟**。\n- 选出一条让你有触动的记录\n- "
         L"写下为什么在意它\n> 不必每次都有结论。\n```\nnext_step = "
         L"one_small_action\n```\n[example](https://example.invalid) <script>inert</script>",
         L"Synthetic", "completed", L""},
        {"user2", true, L"好。", L"", "completed", L""},
        {"ai2", false, L"先挑一条最近的记录，我们一起看看。", L"Synthetic", "stopped", L""}};
    Update(host, messages, false);
    Pump();
    auto children = Children(host);
    Expect(children.size() == 4, "one selectable text control per real message");
    auto own = FindText(L"我想"), ai = FindText(L"从一个很小"), shortReply = FindText(L"好。"),
         last = FindText(L"先挑");
    Expect(own && ai && shortReply && last, "all messages present");
    Expect(Bounds(own).left > Bounds(ai).left, "own right, AI left");
    Expect(Bounds(shortReply).right - Bounds(shortReply).left <
               Bounds(own).right - Bounds(own).left,
           "short replies natural width");
    Expect(Bounds(ai).right - Bounds(ai).left < Bounds(host).right - Bounds(host).left,
           "bounded bubble width");
    Expect(Text(ai).find(L"**") == std::wstring::npos && Text(ai).find(L"##") == std::wstring::npos,
           "safe Markdown headings and emphasis");
    Expect(Text(ai).find(L"<script>inert</script>") != std::wstring::npos, "HTML stays inert text");
    Expect(SendMessageW(ai, EM_GETAUTOURLDETECT, 0, 0) == 0, "no automatic URL actions");
    Expect((GetWindowLongPtrW(ai, GWL_STYLE) & ES_READONLY) != 0, "read only selectable body");
    Expect(!CanRetry(messages, 0, false) && !CanRetry(messages, 1, false),
           "no retry own/old replies");
    Expect(CanRetry(messages, 3, false), "latest stopped AI is retryable");
    Expect(!CanRetry(messages, 3, true), "retry blocked while sending");
    messages.back().status = "completed";
    Expect(!CanRetry(messages, 3, false), "completed AI not retryable");
    messages.back().status = "failed";
    Expect(CanRetry(messages, 3, false), "latest failed AI retryable");
    auto retried = messages;
    retried.push_back({"retry", false, L"", L"Synthetic", "failed", L""});
    Expect(CanRetry(retried, 4, false), "synced retry can find earlier user across failed replies");
    Update(host, messages, false);
    Expect(FindText(L"从一个很小") == ai, "stable message control across update");
    SendMessageW(ai, EM_SETSEL, 0, 4);
    Menu(ai, 2);
    Expect(selectionEnabled && Clipboard() == L"从一个很", "copy selected rendered text");
    Menu(ai, 1, true);
    Expect(Clipboard() == messages[1].text, "keyboard menu copies complete raw Markdown");
    Expect(!menuRetry && menuItems == 3, "old completed message no retry menu");
    SendMessageW(last, EM_SETSEL, 0, 0);
    Menu(last);
    Expect(menuRetry && menuItems == 5 && !selectionEnabled,
           "failed AI menu and disabled empty selection");
    Menu(last, 3);
    CHARRANGE selected{};
    SendMessageW(last, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&selected));
    Expect(selected.cpMin == 0 && selected.cpMax >= GetWindowTextLengthW(last),
           "select-text action selects body");
    Menu(last, 4);
    Expect(retryCommands == 1, "retry delegates to composer, not model provider");
    Update(host, messages, true);
    Menu(last);
    Expect(!menuRetry, "menu respects active request");
    Update(host, messages, false);
    // A real press-and-hold gesture, not a context-menu message.
    auto bounds = Bounds(last);
    SetCursorPos(bounds.left + 10, bounds.top + 10);
    auto previousMenus = menuCount;
    menuChoice = 0;
    auto timer = SetTimer(nullptr, 0, 50, MenuTimer);
    INPUT click{};
    click.type = INPUT_MOUSE;
    click.mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
    SendInput(1, &click, sizeof(click));
    Pump(850);
    click.mi.dwFlags = MOUSEEVENTF_LEFTUP;
    SendInput(1, &click, sizeof(click));
    KillTimer(nullptr, timer);
    Pump();
    Expect(menuCount == previousMenus + 1, "stationary long press opens menu");
    previousMenus = menuCount;
    SetCursorPos(bounds.left - 8, bounds.top + 8);
    timer = SetTimer(nullptr, 0, 50, MenuTimer);
    click.mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
    SendInput(1, &click, sizeof(click));
    Pump(850);
    click.mi.dwFlags = MOUSEEVENTF_LEFTUP;
    SendInput(1, &click, sizeof(click));
    KillTimer(nullptr, timer);
    Pump();
    Expect(menuCount == previousMenus + 1, "bubble padding also supports long press");
    // Moving to select text must not open the hold menu.
    previousMenus = menuCount;
    SetCursorPos(bounds.left + 10, bounds.top + 10);
    click.mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
    SendInput(1, &click, sizeof(click));
    Pump(50);
    SetCursorPos(bounds.left + 70, bounds.top + 10);
    Pump(750);
    click.mi.dwFlags = MOUSEEVENTF_LEFTUP;
    SendInput(1, &click, sizeof(click));
    Pump();
    Expect(menuCount == previousMenus, "drag selection cancels long press");
    std::wstring longReply;
    for (int i = 0; i < 100; ++i)
        longReply += L"可滚动的合成回复，保留 Markdown 与选择。\n";
    messages.back().text = longReply;
    messages.back().status = "generating";
    Update(host, messages, true);
    Pump();
    Expect(Scroll().nMax > static_cast<int>(Scroll().nPage) * 2,
           "RichEdit height includes every paragraph");
    Expect(Scroll().nPos + static_cast<int>(Scroll().nPage) >= Scroll().nMax - 2,
           "stream follows bottom when already there");
    SendMessageW(host, WM_VSCROLL, SB_TOP, 0);
    messages.back().text += L"继续增长的回复。";
    Update(host, messages, true);
    Expect(Scroll().nPos == 0, "reading older messages does not jump on token arrival");
    Expect(FindText(L"从一个很小") == ai, "unchanged Markdown view survives streaming");
    SendMessageW(ai, EM_SETSEL, 1, 8);
    messages.back().text += L"又一个 token。";
    Update(host, messages, true);
    SendMessageW(ai, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&selected));
    Expect(selected.cpMin == 1 && selected.cpMax == 8, "older text selection survives streaming");
    MoveWindow(host, 20, 20, 580, 360, TRUE);
    Pump();
    Expect(Scroll().nPos == 0, "narrow resize preserves non-bottom reading position");
    Expect(Bounds(shortReply).right <= Bounds(host).right && Bounds(ai).left >= Bounds(host).left,
           "narrow bubbles remain inside viewport");
    SendMessageW(host, WM_VSCROLL, SB_BOTTOM, 0);
    Update(host, messages, true);
    Expect(Scroll().nPos + static_cast<int>(Scroll().nPage) >= Scroll().nMax - 2,
           "bottom follow can resume");
    messages.back().text = std::wstring(100000, L'x');
    for (std::size_t i = 39; i < messages.back().text.size(); i += 40)
        messages.back().text[i] = L'\n';
    messages.back().text.replace(99980, 20, L"END_OF_LONG_MESSAGE!");
    Update(host, messages, true);
    last = FindText(L"END_OF_LONG_MESSAGE!");
    Expect(last != nullptr, "maximum message retained through end");
    POINTL tail{};
    SendMessageW(last, EM_POSFROMCHAR, reinterpret_cast<WPARAM>(&tail),
                 GetWindowTextLengthW(last) - 1);
    Expect(tail.y >= 0 && tail.y < Bounds(last).bottom - Bounds(last).top,
           "maximum message final line visible in native overflow viewport");
    Expect((GetWindowLongPtrW(last, GWL_STYLE) & WS_VSCROLL) != 0,
           "exceptionally long message has explicit inner scrollbar");
    Expect(Scroll().nPos + static_cast<int>(Scroll().nPage) >= Scroll().nMax - 2,
           "maximum message bottom reachable");
    SendMessageW(last, WM_VSCROLL, SB_TOP, 0);
    messages.back().text.back() = L'?';
    Update(host, messages, true);
    Expect(SendMessageW(last, EM_GETFIRSTVISIBLELINE, 0, 0) == 0,
           "overflow reading position survives new stream content");
    messages.back().text = std::wstring(99980, L'\n') + L"END_MANY_PARAGRAPHS!";
    Update(host, messages, true);
    last = FindText(L"END_MANY_PARAGRAPHS!");
    SendMessageW(last, EM_SETSEL, -1, -1);
    SendMessageW(last, EM_SCROLLCARET, 0, 0);
    SendMessageW(last, EM_POSFROMCHAR, reinterpret_cast<WPARAM>(&tail),
                 GetWindowTextLengthW(last) - 1);
    Expect(tail.y >= 0 && tail.y < Bounds(last).bottom - Bounds(last).top,
           "extreme paragraph count remains navigable to final character");
    Menu(last, 1);
    Expect(Clipboard() == messages.back().text, "overflow copy preserves every original character");
    auto other = std::vector<Message>{{"other", false, L"另一个会话\nSecond line", L"Mock", "completed", L""}};
    Update(host, other, false);
    Expect(Children(host).size() == 1 && FindText(L"另一个会话"),
           "scope/session replacement removes old controls");
    auto remaining = FindText(L"另一个会话");
    auto secondLine = SendMessageW(remaining, EM_LINEINDEX, 1, 0);
    POINTL before{}, after{};
    SendMessageW(remaining, EM_POSFROMCHAR, reinterpret_cast<WPARAM>(&before), secondLine);
    SetDpi(host, 192);
    SendMessageW(remaining, EM_POSFROMCHAR, reinterpret_cast<WPARAM>(&after), secondLine);
    Expect(before.y > 0 && after.y >= before.y*2-2,
           "monitor DPI transition doubles actual rendered paragraph advance");
    UINT numerator = 0, denominator = 0;
    SendMessageW(remaining, EM_GETZOOM, reinterpret_cast<WPARAM>(&numerator),
                 reinterpret_cast<LPARAM>(&denominator));
    Expect(denominator > 0 && numerator == denominator*2, "DPI transition updates rich text zoom");
    SetDpi(host, 96);
    Expect(FindText(L"另一个会话") == remaining, "DPI changes preserve stable message view");
    Update(host, {}, false);
    Expect(Children(host).empty(), "empty state has no fake message controls");
    Update(host, other, false);
    Menu(FindText(L"另一个会话"), -1);
    Expect(!IsWindow(parent), "window destruction inside message menu is safe");
}
} // namespace
int wmain() {
    INITCOMMONCONTROLSEX controls{sizeof(controls), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&controls);
    try {
        Cases();
        std::cout << "IM transcript: " << checks
                  << " checks passed (synthetic only, no model calls)\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << e.what() << "\n";
        return 1;
    }
}

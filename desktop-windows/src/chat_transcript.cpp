#include "chat_transcript.hpp"

#include <commctrl.h>
#include <richedit.h>
#include <windowsx.h>
#include <algorithm>
#include <memory>
#include <numeric>
#include <stdexcept>

namespace Mnote::ChatTranscript {
namespace {
constexpr COLORREF Background = RGB(247, 244, 236), Ink = RGB(36, 43, 43),
                   Muted = RGB(102, 108, 102), Accent = RGB(36, 73, 78),
                   Border = RGB(221, 216, 205), Paper = RGB(255, 254, 250),
                   Copper = RGB(149, 85, 48);
constexpr int CopyMessage = 1, CopySelection = 2, SelectText = 3, Retry = 4;
constexpr UINT_PTR HoldTimer = 1;
struct Span {
    LONG first, last;
    bool bold = false, code = false;
    COLORREF color = Ink;
    int size = 225;
};
struct Bubble {
    HWND text = nullptr;
    Message message;
    std::wstring plain;
    int top = 0, left = 0, width = 0, height = 0, bodyHeight = 0, measuredWidth = 0, fullHeight = 0,
        innerLine = 0;
    bool dirty = true;
    bool overflow = false, restoreInner = false, innerBottom = true;
    POINT press{};
    bool holding = false, held = false;
};
struct View {
    HWND hwnd = nullptr;
    int dpi = 96, retryCommand = 0, scroll = 0, extent = 0;
    bool receiving = false, layout = false;
    HFONT font = nullptr, label = nullptr;
    HWND holdTarget = nullptr;
    POINT press{};
    std::vector<std::unique_ptr<Bubble>> bubbles;
};
int Scale(const View &v, int value) { return MulDiv(value, v.dpi, 96); }
View *State(HWND hwnd) { return reinterpret_cast<View *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA)); }
void Zoom(HWND text, int dpi) {
    int divisor = std::gcd(dpi, 96);
    int numerator = dpi / divisor, denominator = 96 / divisor;
    // RichEdit only accepts numerator/denominator values up to 64, not raw DPI.
    if (numerator > 64 || denominator > 64) {
        numerator = std::clamp(MulDiv(dpi, 8, 96), 1, 64);
        denominator = 8;
    }
    SendMessageW(text, EM_SETZOOM, numerator, denominator);
}
void Fonts(View &v) {
    auto oldFont = v.font, oldLabel = v.label;
    v.font = CreateFontW(-Scale(v, 15), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH,
                         L"Segoe UI");
    v.label = CreateFontW(-Scale(v, 12), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                          OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH,
                          L"Segoe UI");
    for (auto &b : v.bubbles) {
        SendMessageW(b->text, WM_SETFONT, reinterpret_cast<WPARAM>(v.font), FALSE);
        Zoom(b->text, v.dpi);
        b->measuredWidth = 0;
        b->dirty = true;
    }
    if (oldFont)
        DeleteObject(oldFont);
    if (oldLabel)
        DeleteObject(oldLabel);
}
std::wstring Markdown(const std::wstring &input, bool own, std::vector<Span> &spans) {
    // The same deliberately small, inert Markdown subset used by the original transcript.
    // Links, HTML and images are text only: never launched, downloaded or evaluated.
    std::wstring result;
    std::size_t at = 0;
    bool code = false;
    while (at < input.size()) {
        auto end = input.find(L'\n', at);
        if (end == std::wstring::npos)
            end = input.size();
        auto line = input.substr(at, end - at);
        if (!line.empty() && line.back() == L'\r')
            line.pop_back();
        if (line.rfind(L"```", 0) == 0) {
            code = !code;
            at = end + 1;
            continue;
        }
        bool bold = false;
        COLORREF color = own ? RGB(255, 254, 250) : Ink;
        int size = 225;
        if (!code) {
            std::size_t heading = 0;
            while (heading < line.size() && line[heading] == L'#')
                ++heading;
            if (heading > 0 && heading <= 6 && heading < line.size() && line[heading] == L' ') {
                line.erase(0, heading + 1);
                bold = true;
                size = heading <= 2 ? 280 : 250;
            }
            if (line.rfind(L"> ", 0) == 0) {
                line = L"│ " + line.substr(2);
                color = own ? RGB(219, 232, 226) : Copper;
            }
            if (line.rfind(L"- ", 0) == 0 || line.rfind(L"* ", 0) == 0)
                line = L"• " + line.substr(2);
        }
        auto start = static_cast<LONG>(result.size());
        auto base = spans.size();
        spans.push_back({start, start, bold, code, color, size});
        if (!code) {
            bool emphasis = false;
            LONG emphasisStart = 0;
            for (std::size_t i = 0; i < line.size(); ++i) {
                if (i + 1 < line.size() && line[i] == L'*' && line[i + 1] == L'*') {
                    if (emphasis)
                        spans.push_back({emphasisStart, static_cast<LONG>(result.size()), true,
                                         false, color, size});
                    else
                        emphasisStart = static_cast<LONG>(result.size());
                    emphasis = !emphasis;
                    ++i;
                } else
                    result += line[i];
            }
        } else
            result += line;
        result += L'\r';
        spans[base].last = static_cast<LONG>(result.size());
        at = end + 1;
    }
    if (!result.empty())
        result.pop_back();
    return result;
}
std::wstring Footer(const Bubble &b) {
    auto suffix = b.overflow ? std::wstring(L" · 长回复可在消息内滚动阅读") : std::wstring();
    if (b.message.status == "generating")
        return (b.message.text.empty() ? L"正在思考…" : L"正在回复…") + suffix;
    if (b.message.status == "stopped")
        return L"已停止 · 已收到的内容已保留" + suffix;
    if (b.message.status == "failed")
        return L"未完成 · " + b.message.error + suffix;
    return b.overflow ? L"长回复 · 在消息内滚动阅读完整内容（Ctrl + End 到末尾）" : L"";
}
std::vector<Message> Messages(const View &v) {
    std::vector<Message> result;
    for (const auto &b : v.bubbles)
        result.push_back(b->message);
    return result;
}
void Copy(HWND owner, const std::wstring &text) {
    auto bytes = (text.size() + 1) * sizeof(wchar_t);
    auto memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (!memory)
        return;
    auto buffer = GlobalLock(memory);
    if (!buffer) {
        GlobalFree(memory);
        return;
    }
    memcpy(buffer, text.c_str(), bytes);
    GlobalUnlock(memory);
    if (!OpenClipboard(owner)) {
        GlobalFree(memory);
        return;
    }
    EmptyClipboard();
    if (!SetClipboardData(CF_UNICODETEXT, memory))
        GlobalFree(memory);
    CloseClipboard();
}
void Menu(HWND host, HWND child, POINT screen) {
    auto v = State(host);
    if (!v)
        return;
    auto it = std::find_if(v->bubbles.begin(), v->bubbles.end(),
                           [child](const auto &b) { return b->text == child; });
    if (it == v->bubbles.end())
        return;
    auto id = (*it)->message.id;
    const auto raw = (*it)->message.text;
    CHARRANGE selected{};
    SendMessageW(child, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&selected));
    bool selection = selected.cpMin != selected.cpMax;
    bool retry =
        CanRetry(Messages(*v), static_cast<std::size_t>(it - v->bubbles.begin()), v->receiving);
    auto popup = CreatePopupMenu();
    AppendMenuW(popup, MF_STRING, CopyMessage, L"复制整条消息");
    AppendMenuW(popup, MF_STRING | (selection ? MF_ENABLED : MF_GRAYED), CopySelection,
                L"复制选中文字");
    AppendMenuW(popup, MF_STRING, SelectText, L"选择文本");
    if (retry) {
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(popup, MF_STRING, Retry, L"用上一问继续…");
    }
    if (screen.x == -1 && screen.y == -1) {
        RECT bounds{};
        GetWindowRect(child, &bounds);
        RECT viewport{};
        GetWindowRect(host, &viewport);
        screen = {bounds.left + Scale(*v, 16),
                  std::clamp(bounds.top + Scale(*v, 20), viewport.top, viewport.bottom - 1)};
    }
    auto command = TrackPopupMenu(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON, screen.x, screen.y, 0,
                                  host, nullptr);
    DestroyMenu(popup);
    // Streaming, closing a window, and account changes can happen inside the menu loop.
    v = State(host);
    if (!v)
        return;
    it = std::find_if(v->bubbles.begin(), v->bubbles.end(),
                      [&id](const auto &b) { return b->message.id == id; });
    if (it == v->bubbles.end())
        return;
    child = (*it)->text;
    if (command == CopyMessage)
        Copy(host, raw);
    else if (command == CopySelection)
        SendMessageW(child, WM_COPY, 0, 0);
    else if (command == SelectText) {
        SetFocus(child);
        SendMessageW(child, EM_SETSEL, 0, -1);
    } else if (command == Retry &&
               CanRetry(Messages(*v), static_cast<std::size_t>(it - v->bubbles.begin()),
                        v->receiving))
        PostMessageW(GetParent(host), WM_COMMAND, MAKEWPARAM(v->retryCommand, 0), 0);
}
void Position(View &v) {
    RECT client{};
    GetClientRect(v.hwnd, &client);
    v.scroll = std::clamp(v.scroll, 0, std::max(0, v.extent - static_cast<int>(client.bottom)));
    SCROLLINFO info{sizeof(info),
                    SIF_RANGE | SIF_PAGE | SIF_POS,
                    0,
                    std::max(0, v.extent - 1),
                    static_cast<UINT>(client.bottom),
                    v.scroll,
                    0};
    SetScrollInfo(v.hwnd, SB_VERT, &info, TRUE);
    auto batch = BeginDeferWindowPos(static_cast<int>(v.bubbles.size()));
    for (auto &b : v.bubbles) {
        auto x = b->left + Scale(v, 15), y = b->top + Scale(v, 35) - v.scroll;
        if (batch)
            batch = DeferWindowPos(batch, b->text, nullptr, x, y, b->width - Scale(v, 30),
                                   b->bodyHeight, SWP_NOZORDER | SWP_NOACTIVATE);
        else
            SetWindowPos(b->text, nullptr, x, y, b->width - Scale(v, 30), b->bodyHeight,
                         SWP_NOZORDER | SWP_NOACTIVATE);
    }
    if (batch)
        EndDeferWindowPos(batch);
    for (auto &b : v.bubbles)
        SendMessageW(b->text, EM_SHOWSCROLLBAR, SB_VERT, b->overflow);
    for (auto &b : v.bubbles)
        if (b->overflow && b->restoreInner) {
            if (b->innerBottom)
                SendMessageW(b->text, WM_VSCROLL, SB_BOTTOM, 0);
            else
                SendMessageW(b->text, EM_LINESCROLL, 0,
                             b->innerLine - SendMessageW(b->text, EM_GETFIRSTVISIBLELINE, 0, 0));
            b->restoreInner = false;
        }
    InvalidateRect(v.hwnd, nullptr, TRUE);
}
void Text(Bubble &b) {
    CHARRANGE selection{};
    SendMessageW(b.text, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&selection));
    std::vector<Span> spans;
    b.plain = Markdown(b.message.text, b.message.own, spans);
    SetWindowTextW(b.text, b.plain.c_str());
    SendMessageW(b.text, EM_SETBKGNDCOLOR, 0, b.message.own ? Accent : Paper);
    auto formatSpan = [&](const Span &span) {
        CHARRANGE range{span.first, span.last};
        SendMessageW(b.text, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&range));
        CHARFORMAT2W format{};
        format.cbSize = sizeof(format);
        format.dwMask = CFM_FACE | CFM_SIZE | CFM_COLOR | CFM_BOLD;
        format.dwEffects = span.bold ? CFE_BOLD : 0;
        format.crTextColor = span.color;
        format.yHeight = span.size;
        lstrcpynW(format.szFaceName, span.code ? L"Consolas" : L"Segoe UI", LF_FACESIZE);
        SendMessageW(b.text, EM_SETCHARFORMAT, SCF_SELECTION, reinterpret_cast<LPARAM>(&format));
    };
    auto color = b.message.own ? Paper : Ink;
    formatSpan({0, -1, false, false, color, 225});
    for (const auto &span : spans)
        if (span.bold || span.code || span.color != color || span.size != 225)
            formatSpan(span);
    SendMessageW(b.text, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&selection));
}
void Layout(View &v, bool bottom) {
    if (v.layout)
        return;
    v.layout = true;
    RECT client{};
    GetClientRect(v.hwnd, &client);
    auto dc = GetDC(v.hwnd);
    auto oldFont = SelectObject(dc, v.font);
    int top = Scale(v, 12),
        available = std::max(Scale(v, 90), static_cast<int>(client.right) - Scale(v, 16));
    int maximum = available * 86 / 100;
    for (auto &entry : v.bubbles) {
        auto &b = *entry;
        if (b.overflow) {
            SCROLLINFO info{sizeof(info), SIF_ALL, 0, 0, 0, 0, 0};
            GetScrollInfo(b.text, SB_VERT, &info);
            b.innerBottom = info.nPos + static_cast<int>(info.nPage) >= info.nMax - Scale(v, 3);
            b.innerLine = static_cast<int>(SendMessageW(b.text, EM_GETFIRSTVISIBLELINE, 0, 0));
        }
        if (b.dirty)
            Text(b);
        // Natural width for short replies; longer paragraphs wrap in a bounded column.
        int natural = Scale(v, 48);
        std::size_t at = 0;
        while (at < b.plain.size()) {
            auto end = b.plain.find(L'\r', at);
            if (end == std::wstring::npos)
                end = b.plain.size();
            SIZE size{};
            GetTextExtentPoint32W(dc, b.plain.data() + at, static_cast<int>(end - at), &size);
            natural = std::max(natural, static_cast<int>(size.cx) * 5 / 4 + Scale(v, 30));
            if (natural >= maximum)
                break;
            at = end + 1;
        }
        b.width = std::min(maximum, natural);
        int textWidth = std::max(1, b.width - Scale(v, 30));
        if (b.measuredWidth != textWidth || b.dirty) {
            // Ask RichEdit for its actual laid-out height, including heading/code font metrics.
            SetWindowPos(b.text, nullptr, 0, 0, textWidth, Scale(v, 24),
                         SWP_NOZORDER | SWP_NOACTIVATE);
            RECT rect{};
            GetClientRect(b.text, &rect);
            SendMessageW(b.text, EM_SETRECTNP, 0, reinterpret_cast<LPARAM>(&rect));
            SendMessageW(b.text, EM_REQUESTRESIZE, 0, 0);
            b.measuredWidth = textWidth;
            b.restoreInner = true;
        }
        // Win32 limits child-window dimensions to 32,767 pixels. Only exceptionally long
        // bodies get their own native scroll viewport; their text is never truncated.
        b.overflow = b.fullHeight > 28000;
        b.bodyHeight = b.overflow ? std::clamp(static_cast<int>(client.bottom) * 3 / 4,
                                               Scale(v, 160), Scale(v, 420))
                                  : std::max(Scale(v, 22), b.fullHeight);
        b.dirty = false;
        b.left = b.message.own ? available - b.width + Scale(v, 8) : Scale(v, 8);
        b.top = top;
        b.height = Scale(v, 51) + b.bodyHeight + (Footer(b).empty() ? 0 : Scale(v, 25));
        top += b.height + Scale(v, 20);
    }
    SelectObject(dc, oldFont);
    ReleaseDC(v.hwnd, dc);
    v.extent = top;
    if (bottom)
        v.scroll = std::max(0, v.extent - static_cast<int>(client.bottom));
    Position(v);
    v.layout = false;
}
bool AtBottom(const View &v) {
    RECT client{};
    GetClientRect(v.hwnd, &client);
    return v.scroll + client.bottom >= v.extent - Scale(v, 28);
}
HWND Hit(const View &v, POINT local) {
    for (const auto &b : v.bubbles)
        if (local.y + v.scroll >= b->top + Scale(v, 22) &&
            local.y + v.scroll < b->top + Scale(v, 51) + b->bodyHeight && local.x >= b->left &&
            local.x < b->left + b->width)
            return b->text;
    return nullptr;
}
LRESULT CALLBACK TextProcedure(HWND hwnd, UINT message, WPARAM wp, LPARAM lp, UINT_PTR,
                               DWORD_PTR reference) {
    auto b = reinterpret_cast<Bubble *>(reference);
    auto host = GetParent(hwnd);
    switch (message) {
    case WM_MOUSEWHEEL:
        if (b->overflow) {
            SCROLLINFO info{sizeof(info), SIF_ALL, 0, 0, 0, 0, 0};
            GetScrollInfo(hwnd, SB_VERT, &info);
            bool up = GET_WHEEL_DELTA_WPARAM(wp) > 0;
            if ((up && info.nPos > info.nMin) ||
                (!up && info.nPos + static_cast<int>(info.nPage) < info.nMax))
                break;
        }
        return SendMessageW(host, message, wp, lp);
    case WM_LBUTTONDOWN:
        b->press = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
        b->holding = true;
        b->held = false;
        SetTimer(hwnd, HoldTimer, 650, nullptr);
        break;
    case WM_MOUSEMOVE:
        if (b->holding &&
            (abs(GET_X_LPARAM(lp) - b->press.x) > 5 || abs(GET_Y_LPARAM(lp) - b->press.y) > 5)) {
            b->holding = false;
            KillTimer(hwnd, HoldTimer);
        }
        break;
    case WM_TIMER:
        if (wp == HoldTimer) {
            KillTimer(hwnd, HoldTimer);
            if (b->holding && (GetKeyState(VK_LBUTTON) & 0x8000)) {
                b->holding = false;
                b->held = true;
                ReleaseCapture();
                POINT point = b->press;
                ClientToScreen(hwnd, &point);
                Menu(host, hwnd, point);
            }
            return 0;
        }
        break;
    case WM_LBUTTONUP:
        KillTimer(hwnd, HoldTimer);
        b->holding = false;
        if (b->held) {
            b->held = false;
            return 0;
        }
        break;
    case WM_CANCELMODE:
    case WM_CAPTURECHANGED:
        KillTimer(hwnd, HoldTimer);
        b->holding = false;
        break;
    case WM_CONTEXTMENU:
        Menu(host, hwnd, {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)});
        return 0;
    case WM_KEYDOWN:
        if (wp == VK_APPS || (wp == VK_F10 && (GetKeyState(VK_SHIFT) & 0x8000))) {
            Menu(host, hwnd, {-1, -1});
            return 0;
        }
        if (!b->overflow && (wp == VK_PRIOR || wp == VK_NEXT) &&
            !(GetKeyState(VK_SHIFT) & 0x8000)) {
            SendMessageW(host, WM_VSCROLL, wp == VK_PRIOR ? SB_PAGEUP : SB_PAGEDOWN, 0);
            return 0;
        }
        break;
    case WM_NCDESTROY:
        KillTimer(hwnd, HoldTimer);
        RemoveWindowSubclass(hwnd, TextProcedure, 1);
        break;
    }
    return DefSubclassProc(hwnd, message, wp, lp);
}
LRESULT CALLBACK Procedure(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    auto v = State(hwnd);
    if (message == WM_NCCREATE) {
        auto create = reinterpret_cast<CREATESTRUCTW *>(lp);
        v = static_cast<View *>(create->lpCreateParams);
        v->hwnd = hwnd;
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(v));
    }
    if (!v)
        return DefWindowProcW(hwnd, message, wp, lp);
    switch (message) {
    case WM_LBUTTONDOWN:
        v->press = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
        v->holdTarget = Hit(*v, v->press);
        if (v->holdTarget) {
            SetCapture(hwnd);
            SetTimer(hwnd, HoldTimer, 650, nullptr);
        }
        return 0;
    case WM_MOUSEMOVE:
        if (v->holdTarget && (abs(GET_X_LPARAM(lp) - v->press.x) > Scale(*v, 5) ||
                              abs(GET_Y_LPARAM(lp) - v->press.y) > Scale(*v, 5))) {
            v->holdTarget = nullptr;
            KillTimer(hwnd, HoldTimer);
        }
        return 0;
    case WM_LBUTTONUP:
    case WM_CANCELMODE:
    case WM_CAPTURECHANGED:
        v->holdTarget = nullptr;
        KillTimer(hwnd, HoldTimer);
        if (message == WM_LBUTTONUP && GetCapture() == hwnd)
            ReleaseCapture();
        return 0;
    case WM_TIMER:
        if (wp == HoldTimer) {
            KillTimer(hwnd, HoldTimer);
            auto target = v->holdTarget;
            auto point = v->press;
            v->holdTarget = nullptr;
            if (target && IsWindow(target) && (GetKeyState(VK_LBUTTON) & 0x8000)) {
                ReleaseCapture();
                ClientToScreen(hwnd, &point);
                Menu(hwnd, target, point);
            }
            return 0;
        }
        break;
    case WM_SIZE: {
        // Bottom-follow is based on the old viewport before WM_SIZE changes its height.
        SCROLLINFO info{sizeof(info), SIF_ALL, 0, 0, 0, 0, 0};
        GetScrollInfo(hwnd, SB_VERT, &info);
        bool bottom = v->scroll + static_cast<int>(info.nPage) >= v->extent - Scale(*v, 28);
        Layout(*v, bottom);
        return 0;
    }
    case WM_VSCROLL: {
        RECT client{};
        GetClientRect(hwnd, &client);
        switch (LOWORD(wp)) {
        case SB_LINEUP:
            v->scroll -= Scale(*v, 36);
            break;
        case SB_LINEDOWN:
            v->scroll += Scale(*v, 36);
            break;
        case SB_PAGEUP:
            v->scroll -= static_cast<int>(client.bottom) * 4 / 5;
            break;
        case SB_PAGEDOWN:
            v->scroll += static_cast<int>(client.bottom) * 4 / 5;
            break;
        case SB_TOP:
            v->scroll = 0;
            break;
        case SB_BOTTOM:
            v->scroll = v->extent;
            break;
        case SB_THUMBPOSITION:
        case SB_THUMBTRACK: {
            SCROLLINFO info{sizeof(info), SIF_TRACKPOS, 0, 0, 0, 0, 0};
            GetScrollInfo(hwnd, SB_VERT, &info);
            v->scroll = info.nTrackPos;
            break;
        }
        }
        Position(*v);
        return 0;
    }
    case WM_MOUSEWHEEL:
        v->scroll -= MulDiv(GET_WHEEL_DELTA_WPARAM(wp), Scale(*v, 108), WHEEL_DELTA);
        Position(*v);
        return 0;
    case WM_NOTIFY: {
        auto header = reinterpret_cast<NMHDR *>(lp);
        if (header->code == EN_REQUESTRESIZE) {
            auto resize = reinterpret_cast<REQRESIZE *>(lp);
            for (auto &b : v->bubbles)
                if (b->text == header->hwndFrom) {
                    b->fullHeight =
                        static_cast<int>(resize->rc.bottom - resize->rc.top) + Scale(*v, 2);
                    break;
                }
        }
        return 0;
    }
    case WM_CONTEXTMENU: {
        POINT point{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)}, local = point;
        ScreenToClient(hwnd, &local);
        for (auto &b : v->bubbles)
            if (local.y + v->scroll >= b->top && local.y + v->scroll < b->top + b->height &&
                local.x >= b->left && local.x < b->left + b->width) {
                Menu(hwnd, b->text, point);
                return 0;
            }
        return 0;
    }
    case WM_SETFOCUS:
        if (!v->bubbles.empty())
            SetFocus(v->bubbles.back()->text);
        return 0;
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT: {
        PAINTSTRUCT paint{};
        auto dc = BeginPaint(hwnd, &paint);
        RECT client{};
        GetClientRect(hwnd, &client);
        auto background = CreateSolidBrush(Background);
        FillRect(dc, &client, background);
        DeleteObject(background);
        SetBkMode(dc, TRANSPARENT);
        auto oldFont = SelectObject(dc, v->label);
        if (v->bubbles.empty()) {
            SetTextColor(dc, Muted);
            RECT text{Scale(*v, 26), Scale(*v, 32), client.right - Scale(*v, 26), client.bottom};
            DrawTextW(dc,
                      L"从这一条记录出发。\n\n让 AI "
                      L"帮你澄清想法、探索不同视角，\n或把一个启发变成下一步行动。\n\n选择资料，写"
                      L"下问题。确认后才会发送给模型。",
                      -1, &text, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX);
        }
        for (const auto &b : v->bubbles) {
            int top = b->top - v->scroll;
            if (top + b->height < 0 || top > client.bottom)
                continue;
            SetTextColor(dc, Muted);
            RECT label{b->left, top, b->left + b->width, top + Scale(*v, 22)};
            auto name = b->message.own             ? std::wstring(L"你")
                        : b->message.model.empty() ? std::wstring(L"AI")
                                                   : L"AI · " + b->message.model;
            DrawTextW(dc, name.c_str(), -1, &label,
                      DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX |
                          (b->message.own ? DT_RIGHT : DT_LEFT));
            auto brush = CreateSolidBrush(b->message.own ? Accent : Paper);
            auto pen = CreatePen(PS_SOLID, 1, b->message.own ? Accent : Border);
            auto oldBrush = SelectObject(dc, brush), oldPen = SelectObject(dc, pen);
            RoundRect(dc, b->left, top + Scale(*v, 22), b->left + b->width,
                      top + Scale(*v, 51) + b->bodyHeight, Scale(*v, 24), Scale(*v, 24));
            SelectObject(dc, oldBrush);
            SelectObject(dc, oldPen);
            DeleteObject(brush);
            DeleteObject(pen);
            auto footer = Footer(*b);
            if (!footer.empty()) {
                RECT status{b->left, top + Scale(*v, 56) + b->bodyHeight,
                            client.right - Scale(*v, 8), top + b->height};
                DrawTextW(dc, footer.c_str(), -1, &status,
                          DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX);
            }
        }
        SelectObject(dc, oldFont);
        EndPaint(hwnd, &paint);
        return 0;
    }
    case WM_NCDESTROY:
        KillTimer(hwnd, HoldTimer);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        DeleteObject(v->font);
        DeleteObject(v->label);
        delete v;
        return DefWindowProcW(hwnd, message, wp, lp);
    }
    return DefWindowProcW(hwnd, message, wp, lp);
}
} // namespace
bool CanRetry(const std::vector<Message> &messages, std::size_t index, bool receiving) {
    return !receiving && index < messages.size() && index + 1 == messages.size() && index > 0 &&
           !messages[index].own &&
           std::any_of(messages.begin(), messages.begin() + static_cast<std::ptrdiff_t>(index),
                       [](const Message &message) { return message.own; }) &&
           (messages[index].status == "failed" || messages[index].status == "stopped");
}
HWND Create(HWND parent, int id, int retryCommand, int dpi) {
    WNDCLASSW klass{};
    klass.lpfnWndProc = Procedure;
    klass.hInstance = GetModuleHandleW(nullptr);
    klass.lpszClassName = L"Mnote.ChatTranscript";
    klass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    RegisterClassW(&klass);
    auto v = std::make_unique<View>();
    v->dpi = dpi;
    v->retryCommand = retryCommand;
    // A native RichEdit call may still be on the stack if its popup menu closes the
    // last chat window. Keep the module loaded until process exit, not per view.
    static HMODULE richEdit = LoadLibraryW(L"Msftedit.dll");
    if (!richEdit)
        throw std::runtime_error("chat_text_control");
    Fonts(*v);
    auto window = CreateWindowExW(
        WS_EX_CONTROLPARENT, klass.lpszClassName, L"聊天消息",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | WS_VSCROLL | WS_CLIPCHILDREN, 0, 0, 100, 100, parent,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), klass.hInstance, v.get());
    if (!window)
        throw std::runtime_error("chat_text_control");
    v.release();
    return window;
}
void SetDpi(HWND window, int dpi) {
    auto v = State(window);
    if (!v || dpi <= 0 || dpi == v->dpi)
        return;
    bool bottom = AtBottom(*v);
    v->scroll = MulDiv(v->scroll, dpi, v->dpi);
    v->dpi = dpi;
    Fonts(*v);
    Layout(*v, bottom);
}
void Update(HWND window, const std::vector<Message> &messages, bool receiving) {
    auto v = State(window);
    if (!v)
        return;
    bool bottom = AtBottom(*v);
    v->receiving = receiving;
    std::vector<std::unique_ptr<Bubble>> next;
    for (const auto &m : messages) {
        auto it = std::find_if(v->bubbles.begin(), v->bubbles.end(),
                               [&m](const auto &b) { return b && b->message.id == m.id; });
        std::unique_ptr<Bubble> b;
        if (it != v->bubbles.end())
            b = std::move(*it);
        else {
            b = std::make_unique<Bubble>();
            b->text =
                CreateWindowExW(0, L"RICHEDIT50W", L"",
                                WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_MULTILINE | ES_READONLY |
                                    ES_AUTOVSCROLL | WS_VSCROLL,
                                0, 0, 100, 24, window, nullptr, GetModuleHandleW(nullptr), nullptr);
            SendMessageW(b->text, WM_SETFONT, reinterpret_cast<WPARAM>(v->font), FALSE);
            Zoom(b->text, v->dpi);
            SendMessageW(b->text, EM_EXLIMITTEXT, 0, 100000);
            SendMessageW(b->text, EM_AUTOURLDETECT, 0, 0);
            SendMessageW(b->text, EM_SETTARGETDEVICE, 0, 0);
            SendMessageW(b->text, EM_SETEVENTMASK, 0, ENM_REQUESTRESIZE);
            SetWindowSubclass(b->text, TextProcedure, 1, reinterpret_cast<DWORD_PTR>(b.get()));
        }
        b->dirty = b->dirty || b->message.text != m.text || b->message.own != m.own;
        b->message = m;
        next.push_back(std::move(b));
    }
    for (const auto &b : v->bubbles)
        if (b)
            DestroyWindow(b->text);
    v->bubbles = std::move(next);
    Layout(*v, bottom);
}
} // namespace Mnote::ChatTranscript

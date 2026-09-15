#include "workspace.hpp"
#include "updater.hpp"
#include <cmath>
#include <commctrl.h>
#include <condition_variable>
#include <deque>
#include <set>
#include <shellapi.h>
#include <thread>
#include <windowsx.h>

namespace Mnote::Workspace {
namespace {
constexpr wchar_t ClassName[] = L"Mnote.Workspace";
constexpr UINT Complete = WM_APP + 40;
constexpr COLORREF Background = RGB(247, 248, 252), Ink = RGB(31, 36, 49),
                   Muted = RGB(111, 119, 139), Accent = RGB(86, 92, 244),
                   Border = RGB(225, 229, 239);
enum Control {
    Title = 2000,
    Subtitle,
    Search,
    TagFilter,
    KindFilter,
    List,
    Refresh,
    NewNote,
    Capture,
    AccountButton,
    Trash,
    OpenRecord,
    Note = 2100,
    Quote,
    Original,
    TagsInput,
    Url,
    Kind,
    Save,
    Cancel,
    Delete,
    Preview,
    Full,
    Clipboard,
    ReadContext,
    ScreenContext,
    RemoveContext,
    OpenUrl,
    Export,
    Server = 2200,
    Username,
    Password,
    Invitation,
    Login,
    Logout,
    Import,
    Status = 2300,
    ImageRole,
    ZoomReset,
    AiAccess = 2320,
    Updates = 24000,
    UpdateCheck,
    UpdateDownload,
    UpdateInstall,
    UpdatePage,
    UpdateNotes
};
enum class Mode { Library, Editor, Account, Image, Toast, Update };
struct Placement {
    HWND control;
    int x, y, w, h;
    bool stretch = false;
};
struct Window {
    std::optional<Updater::Release> release;
    fs::path updateFile;
    HWND hwnd = nullptr;
    std::uint64_t serial = 0;
    Mode mode = Mode::Library;
    int dpi = 96, scroll = 0, extent = 0;
    HFONT font = nullptr, heading = nullptr;
    std::vector<Placement> placements;
    bool busy = false, dirty = false, loading = false, showTrash = false, editing = false;
    std::string scope, baseline;
    Draft draft;
    Record record;
    std::vector<Record> records, filtered;
    std::shared_ptr<Gdiplus::Bitmap> preview;
    std::wstring previewRole, status;
    double zoom = 1;
    POINT pan{}, anchor{};
    bool dragging = false;
};
HINSTANCE instance = nullptr;
HWND home = nullptr, editor = nullptr, accountWindow = nullptr;
std::unique_ptr<Library> library;
std::map<HWND, std::unique_ptr<Window>> windows;
std::uint64_t nextSerial = 0;
std::function<void()> captureAction;
std::function<void()> exitForUpdateAction;
std::function<void(const std::wstring &, bool)> notify;
HBRUSH backgroundBrush = nullptr, whiteBrush = nullptr;
HFONT defaultFont = nullptr;
std::thread worker, syncWorker;
std::mutex queueMutex;
std::condition_variable queueWake;
std::deque<std::function<void()>> jobs, completions;
bool stopping = false, syncing = false;
LRESULT CALLBACK Procedure(HWND, UINT, WPARAM, LPARAM);
void Load();
void Layout(Window &);
void OpenEditor(Draft, const Record *record = nullptr);
void OpenAccount();
void DrawImage(Window &, HDC, RECT);
int Scale(const Window &w, int n) { return MulDiv(n, w.dpi, 96); }
HWND ControlOf(Window &w, int id) { return GetDlgItem(w.hwnd, id); }
std::wstring Text(HWND control) {
    int n = GetWindowTextLengthW(control);
    std::wstring text(static_cast<std::size_t>(n) + 1, L'\0');
    text.resize(static_cast<std::size_t>(GetWindowTextW(control, text.data(), n + 1)));
    return text;
}
void Set(Window &w, int id, const std::wstring &text) {
    SetWindowTextW(ControlOf(w, id), text.c_str());
}
bool Checked(Window &w, int id) {
    return SendMessageW(ControlOf(w, id), BM_GETCHECK, 0, 0) == BST_CHECKED;
}
Window *Find(HWND hwnd, std::uint64_t serial) {
    auto i = windows.find(hwnd);
    return i != windows.end() && i->second->serial == serial ? i->second.get() : nullptr;
}
void StatusText(Window &w, const std::wstring &text) {
    w.status = text;
    Set(w, Status, text);
}
void Post(std::function<void()> complete) {
    std::lock_guard<std::mutex> lock(queueMutex);
    if (stopping)
        return;
    completions.push_back(std::move(complete));
    PostMessageW(home, Complete, 0, 0);
}
void Enqueue(std::function<void()> job) {
    std::lock_guard<std::mutex> lock(queueMutex);
    if (stopping)
        return;
    jobs.push_back(std::move(job));
    queueWake.notify_one();
}
void Busy(Window &w, bool value) {
    w.busy = value;
    for (int id :
         {Save,        Delete,        Login,         Logout,      Import,         Clipboard,
          ReadContext, ScreenContext, RemoveContext, Full,        Note,           Quote,
          Original,    TagsInput,     Url,           Kind,        Server,         Username,
          Password,    Invitation,    AiAccess,      UpdateCheck, UpdateDownload, UpdateInstall})
        if (auto c = ControlOf(w, id))
            EnableWindow(c, !value);
}
void Failure(HWND hwnd, std::uint64_t serial, const std::wstring &error) {
    if (auto w = Find(hwnd, serial)) {
        Busy(*w, false);
        StatusText(*w, error);
    }
    notify(error, true);
}
void Run(Window &w, std::function<void()> operation, std::function<void(Window &)> success) {
    if (w.busy)
        return;
    Busy(w, true);
    StatusText(w, L"正在处理…");
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    Enqueue([hwnd, serial, operation = std::move(operation), success = std::move(success)] {
        try {
            operation();
            Post([hwnd, serial, success] {
                if (auto p = Find(hwnd, serial)) {
                    Busy(*p, false);
                    success(*p);
                }
            });
        } catch (const std::exception &error) {
            auto text = ErrorText(error);
            Post([hwnd, serial, text] { Failure(hwnd, serial, text); });
        }
    });
}
HWND Add(Window &w, int id, const wchar_t *type, const std::wstring &text, DWORD style, int x,
         int y, int width, int height, bool stretch = false) {
    HWND c =
        CreateWindowExW(0, type, text.c_str(), WS_CHILD | WS_VISIBLE | style, 0, 0, 1, 1, w.hwnd,
                        reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)), instance, nullptr);
    SendMessageW(c, WM_SETFONT, reinterpret_cast<WPARAM>(id == Title ? w.heading : w.font), TRUE);
    w.placements.push_back({c, x, y, width, height, stretch});
    return c;
}
void Label(Window &w, int id, const std::wstring &text, int y) {
    Add(w, id, L"STATIC", text, SS_LEFT, 28, y, 660, 24, true);
}
HWND Button(Window &w, int id, const std::wstring &text, int x, int y, int width = 112) {
    return Add(w, id, L"BUTTON", text, WS_TABSTOP | BS_OWNERDRAW, x, y, width, 36);
}
HWND Edit(Window &w, int id, const std::wstring &text, int y, int height, int limit,
          bool multiline = true) {
    HWND c = Add(w, id, L"EDIT", text,
                 WS_TABSTOP | WS_BORDER |
                     (multiline ? ES_MULTILINE | ES_AUTOVSCROLL | ES_WANTRETURN | WS_VSCROLL
                                : ES_AUTOHSCROLL),
                 28, y, 660, height, true);
    SendMessageW(c, EM_SETLIMITTEXT, static_cast<WPARAM>(limit), 0);
    SendMessageW(c, EM_SETMARGINS, EC_LEFTMARGIN | EC_RIGHTMARGIN, MAKELPARAM(12, 12));
    return c;
}
std::wstring Field(const Json &data, const char *key) {
    return data.contains(key) && data[key].is_string() ? Wide(data[key].get<std::string>()) : L"";
}
Json Object(const Json &data, const char *key) {
    return data.contains(key) && data[key].is_object() ? data[key] : Json::object();
}
std::wstring OriginalText(const Json &data) {
    return Field(Object(Object(Object(data, "evidence"), "context"), "text"), "full_text");
}
void LoadPreview(Window &w) {
    w.preview.reset();
    std::string role = w.previewRole.empty() ? "annotated" : Utf8(w.previewRole);
    if (role == "context" && w.draft.fullImage) {
        w.preview = w.draft.fullImage;
        return;
    }
    auto &assets = w.mode == Mode::Image ? w.record.assets : w.draft.assets;
    auto found = assets.find(role);
    if (found == assets.end() && !assets.empty())
        found = assets.begin();
    if (found != assets.end()) {
        auto image =
            std::shared_ptr<Gdiplus::Bitmap>(Gdiplus::Bitmap::FromFile(found->second.c_str()));
        if (image && image->GetLastStatus() == Gdiplus::Ok &&
            static_cast<std::uint64_t>(image->GetWidth()) * image->GetHeight() <= 32000000)
            w.preview.reset(image->Clone(0, 0, static_cast<INT>(image->GetWidth()),
                                         static_cast<INT>(image->GetHeight()),
                                         PixelFormat32bppARGB));
    }
}
void Fonts(Window &w) {
    if (w.font)
        DeleteObject(w.font);
    if (w.heading)
        DeleteObject(w.heading);
    w.font = CreateFontW(-Scale(w, 15), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, 0,
                         0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    w.heading = CreateFontW(-Scale(w, 28), 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE,
                            DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    for (auto &p : w.placements)
        SendMessageW(
            p.control, WM_SETFONT,
            reinterpret_cast<WPARAM>(GetDlgCtrlID(p.control) == Title ? w.heading : w.font), TRUE);
}
Window &Create(Mode mode, const std::wstring &title, int width, int height) {
    auto state = std::make_unique<Window>();
    state->serial = ++nextSerial;
    state->mode = mode;
    state->scope = library->account().scope;
    auto *raw = state.get();
    HWND hwnd = CreateWindowExW(WS_EX_APPWINDOW | WS_EX_CONTROLPARENT, ClassName, title.c_str(),
                                WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN, CW_USEDEFAULT, CW_USEDEFAULT,
                                width, height, nullptr, nullptr, instance, raw);
    if (!hwnd)
        throw std::runtime_error("window_failed");
    state->hwnd = hwnd;
    state->dpi = static_cast<int>(GetDpiForWindow(hwnd));
    Fonts(*state);
    windows.emplace(hwnd, std::move(state));
    return *raw;
}
void Layout(Window &w) {
    RECT r{};
    GetClientRect(w.hwnd, &r);
    int width = MulDiv(r.right, 96, w.dpi), height = MulDiv(r.bottom, 96, w.dpi);
    if (w.mode == Mode::Toast) {
        MoveWindow(ControlOf(w, Subtitle), Scale(w, 20), Scale(w, 16), r.right - Scale(w, 40),
                   Scale(w, 28), TRUE);
        MoveWindow(ControlOf(w, Status), Scale(w, 20), Scale(w, 48), r.right - Scale(w, 40),
                   r.bottom - Scale(w, 56), TRUE);
        return;
    }
    if (w.mode == Mode::Library) {
        auto move = [&](int id, int x, int y, int ww, int hh) {
            MoveWindow(ControlOf(w, id), Scale(w, x), Scale(w, y), Scale(w, ww), Scale(w, hh),
                       TRUE);
        };
        move(Title, 28, 24, width - 270, 40);
        move(Subtitle, 28, 70, width - 56, 24);
        move(AccountButton, width - 206, 28, 178, 36);
        move(Updates, width - 340, 28, 120, 36);
        move(NewNote, 28, 110, 132, 40);
        move(Capture, 170, 110, 132, 40);
        move(Refresh, width - 152, 110, 124, 40);
        move(Search, 28, 174, std::max(150, width - 466), 38);
        move(TagFilter, width - 426, 174, 190, 280);
        move(KindFilter, width - 226, 174, 198, 280);
        move(List, 28, 230, width - 56, std::max(80, height - 326));
        move(Trash, 28, height - 80, 120, 36);
        move(OpenRecord, width - 176, height - 80, 148, 36);
        move(Status, 28, height - 36, width - 56, 24);
        return;
    }
    if (w.mode == Mode::Image) {
        MoveWindow(ControlOf(w, ImageRole), Scale(w, 24), Scale(w, 14), Scale(w, 240),
                   Scale(w, 240), TRUE);
        MoveWindow(ControlOf(w, ZoomReset), Scale(w, 284), Scale(w, 14), Scale(w, 148),
                   Scale(w, 36), TRUE);
        InvalidateRect(w.hwnd, nullptr, TRUE);
        return;
    }
    int view = std::max(40, height - 112), maxScroll = std::max(0, w.extent - view);
    w.scroll = std::clamp(w.scroll, 0, maxScroll);
    SCROLLINFO info{sizeof(info),
                    SIF_RANGE | SIF_PAGE | SIF_POS,
                    0,
                    w.extent - 1,
                    static_cast<UINT>(view),
                    w.scroll,
                    0};
    SetScrollInfo(w.hwnd, SB_VERT, &info, TRUE);
    for (auto &p : w.placements) {
        int id = GetDlgCtrlID(p.control), y = p.y - w.scroll;
        if (id == Status)
            y = height - 44;
        else if (id == Save || id == Login)
            y = height - 94;
        else if (id == Cancel || id == Delete)
            y = height - 94;
        int ww = p.stretch ? std::max(120, width - 56) : p.w;
        MoveWindow(p.control, Scale(w, p.x), Scale(w, y), Scale(w, ww), Scale(w, p.h), TRUE);
        bool footer = id == Status || id == Save || id == Login || id == Cancel || id == Delete;
        // Clip the scrolling body above the fixed save/status footer.
        if (!footer)
            SetWindowRgn(p.control,
                         CreateRectRgn(0, Scale(w, std::max(0, -y)), Scale(w, ww),
                                       Scale(w, std::max(0, std::min(p.h, view - y)))),
                         TRUE);
        ShowWindow(p.control, footer || (y + p.h > 0 && y < view) ? SW_SHOW : SW_HIDE);
    }
}
void Populate(Window &w) {
    int tagIndex = static_cast<int>(SendMessageW(ControlOf(w, TagFilter), CB_GETCURSEL, 0, 0));
    auto query = Text(ControlOf(w, Search)), tag = Text(ControlOf(w, TagFilter));
    int kind = static_cast<int>(SendMessageW(ControlOf(w, KindFilter), CB_GETCURSEL, 0, 0));
    auto lower = [](std::wstring value) {
        for (auto &c : value)
            c = static_cast<wchar_t>(towlower(c));
        return value;
    };
    query = lower(query);
    std::string selected;
    int old = static_cast<int>(SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0));
    if (old >= 0 && static_cast<std::size_t>(old) < w.filtered.size())
        selected = w.filtered[static_cast<std::size_t>(old)].id;
    w.filtered.clear();
    SendMessageW(ControlOf(w, List), WM_SETREDRAW, FALSE, 0);
    SendMessageW(ControlOf(w, List), LB_RESETCONTENT, 0, 0);
    int selection = -1;
    for (const auto &record : w.records) {
        if (record.deleted != w.showTrash)
            continue;
        if (kind > 0 && Field(record.data, "kind") !=
                            std::vector<std::wstring>{L"", L"thought", L"todo",
                                                      L"later"}[static_cast<std::size_t>(kind)])
            continue;
        if (tagIndex == 1 && !record.data.value("tags", Json::array()).empty())
            continue;
        if (tagIndex >= 2 && !tag.empty()) {
            bool matches = false;
            for (const auto &item : record.data.value("tags", Json::array()))
                if (lower(Wide(item.get<std::string>())) == lower(tag))
                    matches = true;
            if (!matches)
                continue;
        }
        auto source = Object(record.data, "source");
        std::wstring haystack = Field(record.data, "comment") + L" " + Field(source, "text") +
                                L" " + OriginalText(record.data) + L" " + TagText(record.data) +
                                L" " + Field(source, "window_title");
        if (!query.empty() && lower(haystack).find(query) == std::wstring::npos)
            continue;
        if (record.id == selected)
            selection = static_cast<int>(w.filtered.size());
        w.filtered.push_back(record);
        SendMessageW(ControlOf(w, List), LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L""));
    }
    SendMessageW(ControlOf(w, List), LB_SETCURSEL,
                 static_cast<WPARAM>(selection < 0 ? 0 : selection), 0);
    SendMessageW(ControlOf(w, List), WM_SETREDRAW, TRUE, 0);
    InvalidateRect(ControlOf(w, List), nullptr, TRUE);
    Set(w, OpenRecord, w.showTrash ? L"恢复选中记录" : L"查看 / 修改");
}
void Load() {
    if (!home || !windows.count(home))
        return;
    auto hwnd = home;
    auto serial = windows[home]->serial;
    auto scope = library->account().scope;
    Enqueue([hwnd, serial, scope] {
        try {
            auto rows = library->list(scope);
            auto account = library->account();
            Post([hwnd, serial, scope, rows = std::move(rows), account]() mutable {
                auto w = Find(hwnd, serial);
                if (!w || library->account().scope != scope)
                    return;
                bool changed = w->scope != scope;
                w->scope = scope;
                w->records = std::move(rows);
                auto tag = changed ? L"全部标签" : Text(ControlOf(*w, TagFilter));
                bool untagged =
                    !changed && SendMessageW(ControlOf(*w, TagFilter), CB_GETCURSEL, 0, 0) == 1;
                SendMessageW(ControlOf(*w, TagFilter), CB_RESETCONTENT, 0, 0);
                SendMessageW(ControlOf(*w, TagFilter), CB_ADDSTRING, 0,
                             reinterpret_cast<LPARAM>(L"全部标签"));
                SendMessageW(ControlOf(*w, TagFilter), CB_ADDSTRING, 0,
                             reinterpret_cast<LPARAM>(L"未分类"));
                std::set<std::wstring> tags;
                if (!untagged && !tag.empty() && tag != L"全部标签")
                    tags.insert(tag);
                for (const auto &record : w->records)
                    for (const auto &item : record.data.value("tags", Json::array()))
                        tags.insert(Wide(item.get<std::string>()));
                int selected = untagged ? 1 : 0, index = 2;
                for (const auto &item : tags) {
                    SendMessageW(ControlOf(*w, TagFilter), CB_ADDSTRING, 0,
                                 reinterpret_cast<LPARAM>(item.c_str()));
                    if (!untagged && item == tag)
                        selected = index;
                    ++index;
                }
                SendMessageW(ControlOf(*w, TagFilter), CB_SETCURSEL, static_cast<WPARAM>(selected),
                             0);
                if (changed) {
                    Set(*w, Search, L"");
                    w->showTrash = false;
                    SendMessageW(ControlOf(*w, KindFilter), CB_SETCURSEL, 0, 0);
                }
                Set(*w, AccountButton, account.signedIn() ? Wide(account.username) : L"登录账号");
                Set(*w, Subtitle,
                    account.signedIn() ? L"你的知识与灵感 · 按账号自动同步"
                                       : L"本机笔记 · 登录后可手动导入到个人账号");
                Populate(*w);
            });
        } catch (const std::exception &error) {
            auto text = ErrorText(error);
            Post([hwnd, serial, text] {
                if (auto w = Find(hwnd, serial))
                    StatusText(*w, text);
            });
        }
    });
}
void OpenImage(Window &from) {
    auto &w = Create(Mode::Image, L"Mnote · 图片与页面上下文", 1000, 760);
    w.record = from.record;
    w.record.data = from.draft.data;
    w.record.assets = from.draft.assets;
    w.draft = from.draft;
    Add(w, ImageRole, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 24, 14, 240, 240);
    for (auto role : {L"annotated", L"original", L"context"})
        if (w.record.assets.count(Utf8(role)) ||
            (std::wstring(role) == L"context" && w.draft.fullImage)) {
            auto label = std::wstring(role) == L"annotated"  ? L"圈选区域 · 含批注"
                         : std::wstring(role) == L"original" ? L"圈选区域 · 原始图片"
                                                             : L"完整截图 · 页面上下文";
            auto index = SendMessageW(ControlOf(w, ImageRole), CB_ADDSTRING, 0,
                                      reinterpret_cast<LPARAM>(label));
            SendMessageW(ControlOf(w, ImageRole), CB_SETITEMDATA, static_cast<WPARAM>(index),
                         reinterpret_cast<LPARAM>(role));
        }
    SendMessageW(ControlOf(w, ImageRole), CB_SETCURSEL, 0, 0);
    w.previewRole = reinterpret_cast<const wchar_t *>(
        SendMessageW(ControlOf(w, ImageRole), CB_GETITEMDATA, 0, 0));
    Button(w, ZoomReset, L"适应窗口", 284, 14, 148);
    LoadPreview(w);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
}
void OpenEditor(Draft draft, const Record *record) {
    if (editor && IsWindow(editor)) {
        ShowWindow(editor, SW_SHOW);
        SetForegroundWindow(editor);
        throw std::runtime_error("editor_open");
    }
    auto &w = Create(Mode::Editor, record ? L"Mnote · 查看与修改" : L"Mnote · 记下想法", 760, 900);
    editor = w.hwnd;
    w.draft = std::move(draft);
    if (!w.draft.data["evidence"].is_object())
        w.draft.data["evidence"] = Json::object();
    if (!w.draft.data["evidence"]["context"].is_object())
        w.draft.data["evidence"]["context"] = Json::object();
    w.scope = w.draft.scope.empty() ? library->account().scope : w.draft.scope;
    w.loading = true;
    if (record) {
        w.record = *record;
        w.editing = true;
        w.baseline = Library::fingerprint(*record);
    }
    auto data = w.draft.data;
    auto source = Object(data, "source");
    Label(w, Title, w.editing ? L"回看，也继续想" : L"记下这一刻", 24);
    // Heading uses a larger line box than normal section labels.
    w.placements.back().h = 42;
    Label(w, Subtitle,
          Field(source, "app_name").empty()
              ? L"只记录你选择保留的内容"
              : L"来源：" + Field(source, "app_name") + L"  ·  " + Field(source, "window_title"),
          76);
    Add(w, Preview, L"BUTTON", L"截图预览 · 点击放大", BS_OWNERDRAW | WS_TABSTOP, 28, 112, 660, 188,
        true);
    LoadPreview(w);
    int y = 320;
    Label(w, 2400, L"我的想法", y);
    Edit(w, Note, Field(data, "comment"), y + 30, 130, 20000);
    y += 182;
    Label(w, 2401, L"标签 · 用逗号分隔", y);
    Edit(w, TagsInput, TagText(data), y + 30, 38, 4096, false);
    y += 94;
    Label(w, 2402, L"记录类型", y);
    Add(w, Kind, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 28, y + 30, 220, 180);
    for (auto value : {L"想法", L"TODO", L"稍后回顾"})
        SendMessageW(ControlOf(w, Kind), CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value));
    auto kind = Field(data, "kind");
    SendMessageW(ControlOf(w, Kind), CB_SETCURSEL,
                 kind == L"todo"    ? 1
                 : kind == L"later" ? 2
                                    : 0,
                 0);
    y += 94;
    Label(w, 2414, L"AI 访问权限 · 默认仅本地 AI", y);
    Add(w, AiAccess, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 28, y + 30, 380, 240);
    for (auto value :
         {L"仅本地 AI", L"禁止 AI 访问", L"允许远程 AI，不记忆", L"允许远程 AI 并记忆"})
        SendMessageW(ControlOf(w, AiAccess), CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value));
    auto access = Field(data, "ai_access");
    SendMessageW(ControlOf(w, AiAccess), CB_SETCURSEL,
                 access == L"deny"               ? 1
                 : access == L"remote_no_memory" ? 2
                 : access == L"remote_memory"    ? 3
                                                 : 0,
                 0);
    y += 94;
    if (!w.editing && w.draft.assets.empty()) {
        Add(w, Clipboard, L"BUTTON", L"摘录剪贴板的当前第一条文字（主动读取一次）",
            BS_AUTOCHECKBOX | WS_TABSTOP, 28, y, 660, 32, true);
        y += 44;
        Button(w, ReadContext, L"读取页面文字", 28, y, 180);
        Button(w, ScreenContext, L"保存页面截图", 222, y, 180);
        Button(w, RemoveContext, L"不保留上下文", 416, y, 180);
        y += 52;
        Label(w, 2403, L"上下文可独立保留；不代表摘录来自该页面。读取结果可能不完整。", y);
        y += 44;
    }
    if (w.draft.fullImage && !w.editing) {
        Add(w, Full, L"BUTTON", L"同时保留完整截图，让 AI 知道圈选位置",
            BS_AUTOCHECKBOX | WS_TABSTOP, 28, y, 660, 32, true);
        y += 52;
    }
    Label(w, 2404, L"摘录 · 可编辑", y);
    Edit(w, Quote, Field(source, "text"), y + 30, 130, 100000);
    y += 182;
    Label(w, 2405, L"页面原文 · 可编辑，框内可滚动", y);
    Edit(w, Original, OriginalText(data), y + 30, 180, 40000);
    y += 232;
    Label(w, 2406, L"来源链接", y);
    Edit(w, Url, Field(source, "url"), y + 30, 38, 8192, false);
    y += 84;
    Button(w, OpenUrl, L"打开网页", 28, y, 140);
    Button(w, Export, L"复制记录 JSON", 182, y, 180);
    y += 56;
    w.extent = y;
    Button(w, Save, L"保存记录", 28, 0, 148);
    Button(w, Cancel, L"取消", 190, 0, 100);
    if (w.editing)
        Button(w, Delete, L"删除记录", 310, 0, 132);
    Label(w, Status,
          w.editing ? L"修改不会丢失原始图片和圈选信息。"
                    : L"未登录时仅保存在本机；不会自动读取剪贴板。",
          0);
    w.loading = false;
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
    SetFocus(ControlOf(w, Note));
    if (!w.editing && Field(source, "url").empty() && Context::Same(w.draft.source)) {
        auto app = Field(w.draft.source.metadata, "app_name");
        for (auto &c : app)
            c = static_cast<wchar_t>(towlower(c));
        if (app == L"chrome.exe" || app == L"msedge.exe" || app == L"firefox.exe" ||
            app == L"brave.exe") {
            auto target = w.draft.source;
            auto hwnd = w.hwnd;
            auto serial = w.serial;
            Enqueue([target, hwnd, serial] {
                try {
                    auto result = Context::ReadPage(target, library->root(), true);
                    auto url = Wide(result.value("url", std::string()));
                    Post([url, hwnd, serial] {
                        if (auto form = Find(hwnd, serial); form && !form->busy &&
                                                            Text(ControlOf(*form, Url)).empty() &&
                                                            !url.empty()) {
                            Set(*form, Url, url);
                            StatusText(*form, L"已识别浏览器地址栏链接；未读取页面正文。");
                        }
                    });
                } catch (const std::exception &) { /* Best effort: never erase a
                                                      manually entered link. */
                }
            });
        }
    }
}
void OpenAccount() {
    if (accountWindow && IsWindow(accountWindow)) {
        SetForegroundWindow(accountWindow);
        return;
    }
    auto &w = Create(Mode::Account, L"Mnote · 账号与同步", 720, 730);
    accountWindow = w.hwnd;
    auto account = library->account();
    Label(w, Title, L"一个账号，随处回看", 24);
    w.placements.back().h = 44;
    Label(w, Subtitle,
          account.signedIn() ? L"当前账号：" + Wide(account.username)
                             : L"使用 Android 上相同的用户名和密码",
          82);
    Label(w, 2410, L"同步服务器", 126);
    Edit(w, Server, account.server, 156, 38, 8192, false);
    Label(w, 2411, L"用户名", 216);
    Edit(w, Username, Wide(account.username), 246, 38, 128, false);
    Label(w, 2412, L"密码（仅用于登录，不保存）", 306);
    auto password = Edit(w, Password, L"", 336, 38, 1024, false);
    SetWindowLongPtrW(password, GWL_STYLE, GetWindowLongPtrW(password, GWL_STYLE) | ES_PASSWORD);
    SendMessageW(password, EM_SETPASSWORDCHAR, 0x25cf, 0);
    Label(w, 2413, L"首次激活码（已有账号留空）", 396);
    Edit(w, Invitation, L"", 426, 38, 1024, false);
    Button(w, Logout, L"退出账号", 28, 486, 152);
    Button(w, Import, L"导入本机旧记录", 194, 486, 194);
    w.extent = 550;
    Button(w, Login, L"登录 / 激活", 28, 0, 152);
    Button(w, Cancel, L"关闭", 196, 0, 120);
    Label(w, Status, L"账号数据隔离保存；登录不会自动上传本机旧记录。", 0);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
}
void PutClipboard(HWND hwnd, const std::wstring &text) {
    if (!OpenClipboard(hwnd))
        throw std::runtime_error("clipboard_busy");
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, (text.size() + 1) * sizeof(wchar_t));
    if (!memory) {
        CloseClipboard();
        throw std::runtime_error("clipboard_busy");
    }
    void *bytes = GlobalLock(memory);
    if (!bytes) {
        GlobalFree(memory);
        CloseClipboard();
        throw std::runtime_error("clipboard_busy");
    }
    memcpy(bytes, text.c_str(), (text.size() + 1) * sizeof(wchar_t));
    GlobalUnlock(memory);
    if (!EmptyClipboard() || !SetClipboardData(CF_UNICODETEXT, memory)) {
        GlobalFree(memory);
        CloseClipboard();
        throw std::runtime_error("clipboard_busy");
    }
    CloseClipboard();
}
void ReadClipboard(Window &w) {
    if (!Checked(w, Clipboard)) {
        if (MessageBoxW(w.hwnd, L"移除当前摘录文字？你可以保留已经编辑的内容。", L"Mnote",
                        MB_YESNO | MB_ICONQUESTION) == IDYES)
            Set(w, Quote, L"");
        return;
    }
    std::wstring text;
    try {
        if (!IsClipboardFormatAvailable(CF_UNICODETEXT) || !OpenClipboard(w.hwnd))
            throw std::runtime_error("clipboard_unavailable");
        HANDLE data = GetClipboardData(CF_UNICODETEXT);
        SIZE_T bytes = data ? GlobalSize(data) : 0;
        if (!bytes || bytes > 200002) {
            CloseClipboard();
            throw std::runtime_error("text_too_long");
        }
        auto value = static_cast<const wchar_t *>(GlobalLock(data));
        if (!value) {
            CloseClipboard();
            throw std::runtime_error("clipboard_unavailable");
        }
        auto length = wcsnlen(value, bytes / sizeof(wchar_t));
        bool valid = length < bytes / sizeof(wchar_t) && length <= 100000;
        if (valid)
            text.assign(value, length);
        GlobalUnlock(data);
        CloseClipboard();
        if (!valid || text.empty())
            throw std::runtime_error("clipboard_unavailable");
        Utf8(text);
    } catch (...) {
        SendMessageW(ControlOf(w, Clipboard), BM_SETCHECK, BST_UNCHECKED, 0);
        throw;
    }
    if (!Text(ControlOf(w, Quote)).empty() &&
        MessageBoxW(w.hwnd, L"用本次剪贴板文字替换当前摘录？", L"Mnote",
                    MB_YESNO | MB_ICONQUESTION) != IDYES) {
        SendMessageW(ControlOf(w, Clipboard), BM_SETCHECK, BST_UNCHECKED, 0);
        return;
    }
    Set(w, Quote, text);
    w.draft.data["source"]["text_origin"] = "clipboard";
    w.draft.data["source"]["text"] = Utf8(text);
    w.draft.data["evidence"]["exact_text"] = {{"text", Utf8(text)}, {"delivered_by", "clipboard"}};
    StatusText(w, L"已读取本次剪贴板文字。不会继续监听剪贴板。");
}
Json Edited(Window &w) {
    Json data = w.draft.data;
    int access = static_cast<int>(SendMessageW(ControlOf(w, AiAccess), CB_GETCURSEL, 0, 0));
    data["ai_access"] = access == 1   ? "deny"
                        : access == 2 ? "remote_no_memory"
                        : access == 3 ? "remote_memory"
                                      : "local_only";
    data["comment"] = Utf8(Text(ControlOf(w, Note)));
    data["tags"] = Mnote::Tags(Text(ControlOf(w, TagsInput)));
    int kind = static_cast<int>(SendMessageW(ControlOf(w, Kind), CB_GETCURSEL, 0, 0));
    data["kind"] = kind == 1 ? "todo" : kind == 2 ? "later" : "thought";
    auto quote = Text(ControlOf(w, Quote)), original = Text(ControlOf(w, Original));
    data["source"]["text"] = Utf8(quote);
    data["source"]["url"] = Utf8(Text(ControlOf(w, Url)));
    if (original.empty()) {
        if (data.contains("evidence") && data["evidence"].contains("context"))
            data["evidence"]["context"].erase("text");
    } else if (original != OriginalText(data) ||
               quote != Field(Object(w.draft.data, "source"), "text")) {
        auto previous = Object(Object(Object(data, "evidence"), "context"), "text");
        auto text = TextContext(original,
                                original != OriginalText(data)
                                    ? "user_supplied"
                                    : previous.value("origin", std::string("user_supplied")),
                                quote);
        text["relation_to_quote"] = "unverified";
        data["evidence"]["context"]["text"] = text;
    }
    if (!w.editing && !quote.empty()) {
        auto before = Field(Object(w.draft.data, "source"), "text");
        bool changed = before != quote;
        auto delivered = changed
                             ? "user_edited"
                             : data["source"].value("text_origin", std::string("user_supplied"));
        data["evidence"]["exact_text"] = {{"text", Utf8(quote)}, {"delivered_by", delivered}};
        data["source"]["text_origin"] = delivered;
    }
    return data;
}
void SaveEditor(Window &w) {
    if (w.busy)
        return;
    auto data = Edited(w);
    if (data["ai_access"] != w.draft.data.value("ai_access", Json("local_only")) &&
        (data["ai_access"] == "remote_no_memory" || data["ai_access"] == "remote_memory") &&
        MessageBoxW(
            w.hwnd,
            L"允许已授权的远程 AI "
            L"接口读取这条记录？选择“并记忆”还允许其长期记忆。账号同步本身不需要开放远程 AI。",
            L"Mnote · AI 权限", MB_YESNO | MB_ICONQUESTION) != IDYES)
        return;
    auto assets = w.draft.assets;
    auto scope = w.scope, baseline = w.baseline;
    auto full = w.draft.fullImage;
    auto staging = w.draft.staging;
    bool retain = full && (ControlOf(w, Full) ? Checked(w, Full)
                                              : data.value("evidence", Json::object())
                                                    .value("context", Json::object())
                                                    .value("image", Json::object())
                                                    .value("retained", false));
    Run(
        w,
        [data, assets, scope, baseline, full, retain, staging]() mutable {
            if (full && !baseline.size()) {
                data["evidence"]["context"]["image"]["retained"] = retain;
                data["evidence"]["context"]["image"]["asset_role"] =
                    retain ? Json("context") : Json(nullptr);
                if (retain) {
                    auto path = staging / L"context.png";
                    Context::SavePng(*full, path);
                    assets["context"] = path;
                } else
                    assets.erase("context");
            }
            library->save(scope, data, assets, baseline);
        },
        [](Window &form) {
            form.dirty = false;
            notify(L"记录已保存到本机", false);
            DestroyWindow(form.hwnd);
            Load();
            Sync();
        });
}
void CaptureContext(Window &w, bool screenshot) {
    if (!Context::Same(w.draft.source))
        throw std::runtime_error("source_changed");
    if ((!Text(ControlOf(w, Original)).empty() || w.draft.fullImage) &&
        MessageBoxW(w.hwnd, L"替换当前保留的页面上下文？摘录和想法不变。", L"Mnote",
                    MB_YESNO | MB_ICONQUESTION) != IDYES)
        return;
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    auto source = w.draft.source;
    if (screenshot) {
        Busy(w, true);
        ShowWindow(w.hwnd, SW_HIDE);
        Hide();
        SetForegroundWindow(source.window);
        SetTimer(w.hwnd, 3, 240, nullptr);
        return;
    }
    auto result = std::make_shared<Json>();
    Run(
        w, [result, source] { *result = Context::ReadPage(source, library->root()); },
        [result, hwnd, serial](Window &form) {
            if (!Find(hwnd, serial))
                return;
            auto text = Wide(result->value("text", std::string()));
            if (text.empty()) {
                StatusText(form, L"这个应用没有提供可读文字。请手动粘贴原文，或保存页面截图。");
                return;
            }
            form.draft.fullImage.reset();
            form.draft.data["evidence"]["context"].erase("image");
            Set(form, Original, text);
            auto context = TextContext(text, "windows_accessibility", Text(ControlOf(form, Quote)));
            context["extent"] = "accessible_page_partial";
            context["relation_to_quote"] = "unverified";
            context["truncated"] = result->value("truncated", false);
            form.draft.data["evidence"]["context"]["text"] = context;
            form.draft.data["source"]["text"] = Utf8(Text(ControlOf(form, Quote)));
            if (Text(ControlOf(form, Url)).empty())
                Set(form, Url, Wide(result->value("url", std::string())));
            StatusText(form, L"已读取 " + std::to_wstring(text.size()) +
                                 L" 字。仅为应用提供的可访问内容，不保证整篇文章完整。");
            form.dirty = true;
            LoadPreview(form);
            InvalidateRect(ControlOf(form, Preview), nullptr, TRUE);
        });
}
void OpenSelected(Window &w) {
    int i = static_cast<int>(SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0));
    if (i < 0 || static_cast<std::size_t>(i) >= w.filtered.size())
        return;
    auto record = w.filtered[static_cast<std::size_t>(i)];
    if (w.showTrash) {
        auto scope = w.scope;
        Run(
            w, [scope, record] { library->restore(scope, record.id); },
            [](Window &form) {
                StatusText(form, L"记录已恢复");
                Load();
                Sync();
            });
        return;
    }
    Draft draft;
    draft.data = record.data;
    draft.assets = record.assets;
    draft.scope = w.scope;
    OpenEditor(std::move(draft), &record);
}
void UpdateButtons(Window &w) {
    EnableWindow(ControlOf(w, UpdateCheck), !w.busy);
    EnableWindow(ControlOf(w, UpdateDownload), !w.busy && w.release.has_value());
    EnableWindow(ControlOf(w, UpdateInstall), !w.busy && !w.updateFile.empty());
}
void CheckUpdate(Window &w) {
    if (w.busy)
        return;
    w.release.reset();
    w.updateFile.clear();
    Busy(w, true);
    UpdateButtons(w);
    Set(w, UpdateNotes, L"");
    StatusText(w, L"正在查询官方发布…");
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    Enqueue([hwnd, serial] {
        try {
            auto release = Updater::Check();
            Post([hwnd, serial, release] {
                if (auto form = Find(hwnd, serial)) {
                    Busy(*form, false);
                    form->release = release;
                    StatusText(*form, release ? L"发现新版本：" + Wide(release->version)
                                              : L"当前已是最新可用版本。");
                    if (release)
                        Set(*form, UpdateNotes,
                            L"安装包：" + std::to_wstring(release->size / 1024) + L" KiB\r\n\r\n" +
                                Wide(release->notes));
                    UpdateButtons(*form);
                }
            });
        } catch (const std::exception &error) {
            auto text = Updater::Error(error);
            Post([hwnd, serial, text] {
                if (auto form = Find(hwnd, serial)) {
                    Busy(*form, false);
                    StatusText(*form, text);
                    UpdateButtons(*form);
                }
            });
        }
    });
}
void OpenUpdate() {
    for (const auto &entry : windows)
        if (entry.second->mode == Mode::Update) {
            ShowWindow(entry.first, SW_SHOW);
            SetForegroundWindow(entry.first);
            return;
        }
    auto &w = Create(Mode::Update, L"Mnote · 版本与更新", 740, 790);
    Label(w, Title, L"让 Mnote 保持最新", 24);
    w.placements.back().h = 44;
    Label(w, Subtitle, L"当前版本：" + std::wstring(Updater::Current), 80);
    Label(w, 2420, L"从官方 GitHub 获取版本，不使用你的笔记账号或 Token。", 116);
    Button(w, UpdateCheck, L"检查更新", 28, 160, 160);
    Button(w, UpdateDownload, L"下载更新", 204, 160, 160);
    Button(w, UpdateInstall, L"安装更新", 380, 160, 160);
    Label(w, 2421, L"安装会先退出旧版；未保存内容会提醒。账号和笔记保留。", 216);
    Label(w, 2422, L"便携版使用安装器升级后将转为安装版，请从新快捷方式启动。", 250);
    Label(w, 2423, L"目前为未签名测试版，系统可能询问确认；不会静默安装。", 284);
    Edit(w, UpdateNotes, L"", 330, 220, 20000);
    SendMessageW(ControlOf(w, UpdateNotes), EM_SETREADONLY, TRUE, 0);
    Button(w, UpdatePage, L"打开官方发布页", 28, 570, 200);
    w.extent = 625;
    Button(w, Cancel, L"关闭", 28, 0, 120);
    Label(w, Status, L"", 0);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
    CheckUpdate(w);
}
void Command(Window &w, int id, int event) {
    if (w.mode == Mode::Library) {
        if (id == Updates) {
            OpenUpdate();
            return;
        }
        if (id == Search && event == EN_CHANGE)
            Populate(w);
        else if ((id == TagFilter || id == KindFilter) && event == CBN_SELCHANGE)
            Populate(w);
        else if (id == OpenRecord || (id == List && event == LBN_DBLCLK))
            OpenSelected(w);
        else if (id == NewNote)
            QuickNote();
        else if (id == Capture) {
            Hide();
            captureAction();
        } else if (id == Refresh) {
            Load();
            Sync();
        } else if (id == AccountButton)
            OpenAccount();
        else if (id == Trash) {
            w.showTrash = !w.showTrash;
            Set(w, Trash, w.showTrash ? L"返回记录" : L"回收站");
            Populate(w);
        }
        return;
    }
    if (w.mode == Mode::Image) {
        if (id == ImageRole && event == CBN_SELCHANGE) {
            auto index = SendMessageW(ControlOf(w, ImageRole), CB_GETCURSEL, 0, 0);
            if (index < 0)
                return;
            w.previewRole = reinterpret_cast<const wchar_t *>(SendMessageW(
                ControlOf(w, ImageRole), CB_GETITEMDATA, static_cast<WPARAM>(index), 0));
            w.zoom = 1;
            w.pan = {};
            LoadPreview(w);
            InvalidateRect(w.hwnd, nullptr, TRUE);
        }
        if (id == ZoomReset) {
            w.zoom = 1;
            w.pan = {};
            InvalidateRect(w.hwnd, nullptr, TRUE);
        }
        return;
    }
    if ((event == EN_CHANGE || (event == CBN_SELCHANGE && (id == Kind || id == AiAccess))) &&
        !w.loading)
        w.dirty = true;
    if (id == Cancel) {
        PostMessageW(w.hwnd, WM_CLOSE, 0, 0);
        return;
    }
    if (w.busy)
        return;
    if (w.mode == Mode::Update) {
        if (id == UpdateCheck)
            CheckUpdate(w);
        else if (id == UpdatePage)
            ShellExecuteW(w.hwnd, L"open", Updater::Page, nullptr, nullptr, SW_SHOWNORMAL);
        else if (id == UpdateDownload && w.release) {
            if (MessageBoxW(w.hwnd,
                            L"从 GitHub 下载更新？可能产生网络流量。校验通过后仍需你点击安装。",
                            L"Mnote · 下载更新", MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            auto release = *w.release;
            auto hwnd = w.hwnd;
            auto serial = w.serial;
            Busy(w, true);
            UpdateButtons(w);
            Enqueue([release, hwnd, serial] {
                try {
                    auto path =
                        Updater::Download(library->root(), release, [hwnd, serial](int percent) {
                            Post([hwnd, serial, percent] {
                                if (auto form = Find(hwnd, serial))
                                    StatusText(*form, L"正在下载并校验… " +
                                                          std::to_wstring(percent) + L"%");
                            });
                        });
                    Post([path, hwnd, serial] {
                        if (auto form = Find(hwnd, serial)) {
                            Busy(*form, false);
                            form->updateFile = path;
                            StatusText(*form, L"下载和校验完成，点击安装更新。");
                            UpdateButtons(*form);
                        }
                    });
                } catch (const std::exception &error) {
                    auto text = Updater::Error(error);
                    Post([hwnd, serial, text] {
                        if (auto form = Find(hwnd, serial)) {
                            Busy(*form, false);
                            StatusText(*form, text);
                            UpdateButtons(*form);
                        }
                    });
                }
            });
        } else if (id == UpdateInstall && w.release && !w.updateFile.empty()) {
            if (!CanExit())
                return;
            if (MessageBoxW(
                    w.hwnd,
                    L"现在退出 Mnote 并启动安装器？更新后请从安装器或新的桌面快捷方式打开。",
                    L"Mnote · 安装更新", MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            try {
                Updater::Launch(w.updateFile, *w.release);
                exitForUpdateAction();
            } catch (const std::exception &error) {
                StatusText(w, Updater::Error(error));
            }
        }
        return;
    }
    if (w.mode == Mode::Account) {
        if (id == Login) {
            if (editor && IsWindow(editor)) {
                StatusText(w, L"请先保存或关闭正在编辑的记录，再切换账号。");
                return;
            }
            auto server = Text(ControlOf(w, Server)), username = Text(ControlOf(w, Username)),
                 password = Text(ControlOf(w, Password)),
                 invitation = Text(ControlOf(w, Invitation));
            Run(
                w,
                [server, username, password, invitation]() mutable {
                    try {
                        library->login(server, username, password, invitation);
                    } catch (...) {
                        SecureZeroMemory(password.data(), password.size() * sizeof(wchar_t));
                        throw;
                    }
                    SecureZeroMemory(password.data(), password.size() * sizeof(wchar_t));
                },
                [](Window &form) {
                    Set(form, Password, L"");
                    Set(form, Invitation, L"");
                    form.dirty = false;
                    StatusText(form, L"登录成功，正在拉取账号记录。本机旧记录需手动导入。");
                    Load();
                    Sync();
                });
        } else if (id == Logout) {
            if (editor && IsWindow(editor)) {
                StatusText(w, L"请先保存或关闭正在编辑的记录。");
                return;
            }
            Run(
                w, [] { library->logout(); },
                [](Window &form) {
                    form.dirty = false;
                    StatusText(form, L"已退出，账号记录仍安全保留在本机。");
                    Load();
                });
        } else if (id == Import) {
            auto scope = library->account().scope;
            if (scope == "guest") {
                StatusText(w, L"请先登录。");
                return;
            }
            if (MessageBoxW(w.hwnd,
                            L"把本机未登录记录和旧 Inbox "
                            L"导入当前账号并同步？旧文件不会删除。",
                            L"Mnote · 导入记录", MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            auto count = std::make_shared<int>(0);
            Run(
                w, [scope, count] { *count = library->importGuest(scope); },
                [count](Window &form) {
                    StatusText(form, L"已导入 " + std::to_wstring(*count) + L" 条记录。");
                    Load();
                    Sync();
                });
        }
        return;
    }
    if (id == Save)
        SaveEditor(w);
    else if (id == Delete) {
        if (MessageBoxW(w.hwnd, L"将这条记录移入回收站？登录后会同步删除其他设备上的记录。",
                        L"Mnote · 删除记录", MB_YESNO | MB_ICONQUESTION) != IDYES)
            return;
        auto scope = w.scope, recordId = w.record.id, baseline = w.baseline;
        Run(
            w, [scope, recordId, baseline] { library->erase(scope, recordId, baseline); },
            [](Window &form) {
                form.dirty = false;
                notify(L"记录已移入回收站", false);
                DestroyWindow(form.hwnd);
                Load();
                Sync();
            });
    } else if (id == Preview && w.preview)
        OpenImage(w);
    else if (id == Clipboard)
        ReadClipboard(w);
    else if (id == ReadContext)
        CaptureContext(w, false);
    else if (id == ScreenContext)
        CaptureContext(w, true);
    else if (id == Full) {
        w.dirty = true;
        StatusText(w, Checked(w, Full) ? L"将同时保留完整截图和圈选坐标。"
                                       : L"仅保留圈选截图和批注。");
    } else if (id == RemoveContext) {
        w.draft.fullImage.reset();
        w.draft.data["evidence"]["context"].erase("image");
        w.draft.data["evidence"]["context"].erase("text");
        Set(w, Original, L"");
        LoadPreview(w);
        InvalidateRect(ControlOf(w, Preview), nullptr, TRUE);
        w.dirty = true;
    } else if (id == OpenUrl) {
        auto url = Text(ControlOf(w, Url));
        if (url.rfind(L"https://", 0) != 0 && url.rfind(L"http://", 0) != 0) {
            StatusText(w, L"仅直接打开 HTTP(S) 网页；其他应用链接请自行复制。");
            return;
        }
        ShellExecuteW(w.hwnd, L"open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    } else if (id == Export) {
        PutClipboard(w.hwnd, Wide(Edited(w).dump(2)));
        StatusText(w, L"已复制记录 JSON（不含账号密码或会话）。");
    }
}
void DrawTextLine(HDC dc, RECT r, const std::wstring &text, COLORREF color, HFONT font,
                  UINT flags = DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS) {
    auto old = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, color);
    DrawTextW(dc, text.c_str(), static_cast<int>(text.size()), &r, flags);
    SelectObject(dc, old);
}
void DrawImage(Window &w, HDC dc, RECT r) {
    FillRect(dc, &r, whiteBrush);
    if (!w.preview) {
        DrawTextLine(dc, r, L"纯文字也值得被记录", Muted, w.font,
                     DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        return;
    }
    Gdiplus::Graphics graphics(dc);
    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
    double width = w.preview->GetWidth(), height = w.preview->GetHeight();
    if (width <= 0 || height <= 0)
        return;
    double ratio =
        std::min((r.right - r.left - 20) / width, (r.bottom - r.top - 20) / height) * w.zoom;
    auto dw = static_cast<float>(width * ratio), dh = static_cast<float>(height * ratio);
    float x = (static_cast<float>(r.left + r.right) - dw) / 2 + static_cast<float>(w.pan.x),
          y = (static_cast<float>(r.top + r.bottom) - dh) / 2 + static_cast<float>(w.pan.y);
    graphics.SetClip(Gdiplus::Rect(r.left, r.top, r.right - r.left, r.bottom - r.top));
    graphics.DrawImage(w.preview.get(), x, y, dw, dh);
    if (w.previewRole == L"context") {
        auto image = Object(Object(Object(w.draft.data, "evidence"), "context"), "image"),
             s = Object(image, "selection");
        if (!s.empty() && image.value("purpose", std::string()) != "page_context") {
            double left = s.value("left", 0.0), top = s.value("top", 0.0),
                   right = s.value("right", 0.0), bottom = s.value("bottom", 0.0);
            Gdiplus::Pen pen(Gdiplus::Color(255, 86, 92, 244), 3.0f);
            graphics.DrawRectangle(&pen, x + static_cast<float>(left * ratio),
                                   y + static_cast<float>(top * ratio),
                                   static_cast<float>((right - left) * ratio),
                                   static_cast<float>((bottom - top) * ratio));
        }
    }
}
void DrawRecord(Window &w, const DRAWITEMSTRUCT &item) {
    if (item.itemID >= w.filtered.size())
        return;
    const auto &record = w.filtered[item.itemID];
    auto r = item.rcItem;
    HDC dc = item.hDC;
    HBRUSH brush =
        CreateSolidBrush(item.itemState & ODS_SELECTED ? RGB(234, 235, 255) : RGB(255, 255, 255));
    FillRect(dc, &r, brush);
    DeleteObject(brush);
    int pad = Scale(w, 18);
    r.left += pad;
    r.right -= pad;
    r.top += Scale(w, 10);
    r.bottom = r.top + Scale(w, 24);
    auto text = Field(record.data, "comment");
    if (text.empty())
        text = Field(Object(record.data, "source"), "text");
    if (text.empty())
        text = L"截图记录";
    DrawTextLine(dc, r, text, Ink, w.font);
    r.top += Scale(w, 31);
    r.bottom += Scale(w, 31);
    auto tags = TagText(record.data);
    DrawTextLine(dc, r, tags.empty() ? L"未分类" : L"# " + tags, Accent, w.font);
    r.top += Scale(w, 29);
    r.bottom += Scale(w, 29);
    auto state = record.state == "synced"  ? L"已同步"
                 : record.state == "local" ? L"仅本机"
                 : record.state == "error" ? L"同步待处理"
                                           : L"等待同步";
    DrawTextLine(dc, r,
                 Field(record.data, "created_at") + L"   ·   " + state + L"   ·   " +
                     Field(Object(record.data, "source"), "app_name"),
                 Muted, w.font);
    if (item.itemState & ODS_FOCUS)
        DrawFocusRect(dc, &item.rcItem);
}
LRESULT Dispatch(Window &w, UINT message, WPARAM wp, LPARAM lp) {
    switch (message) {
    case WM_ACTIVATE:
        if (w.mode == Mode::Library && w.hwnd == home && LOWORD(wp) != WA_INACTIVE) {
            Load();
            Sync();
        }
        break;
    case WM_SIZE:
        Layout(w);
        return 0;
    case WM_GETMINMAXINFO: {
        auto info = reinterpret_cast<MINMAXINFO *>(lp);
        info->ptMinTrackSize = {Scale(w, w.mode == Mode::Library ? 780 : 680), Scale(w, 480)};
        return 0;
    }
    case WM_DPICHANGED: {
        w.dpi = HIWORD(wp);
        Fonts(w);
        auto r = reinterpret_cast<RECT *>(lp);
        SetWindowPos(w.hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                     SWP_NOZORDER | SWP_NOACTIVATE);
        Layout(w);
        return 0;
    }
    case WM_COMMAND:
        Command(w, LOWORD(wp), HIWORD(wp));
        return 0;
    case WM_MEASUREITEM: {
        auto item = reinterpret_cast<MEASUREITEMSTRUCT *>(lp);
        item->itemHeight = static_cast<UINT>(Scale(w, 108));
        return TRUE;
    }
    case WM_DRAWITEM: {
        auto &item = *reinterpret_cast<DRAWITEMSTRUCT *>(lp);
        if (item.CtlID == List)
            DrawRecord(w, item);
        else if (item.CtlID == Preview)
            DrawImage(w, item.hDC, item.rcItem);
        else
            DrawButton(item);
        return TRUE;
    }
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLORBTN:
        SetBkMode(reinterpret_cast<HDC>(wp), TRANSPARENT);
        SetTextColor(reinterpret_cast<HDC>(wp),
                     GetDlgCtrlID(reinterpret_cast<HWND>(lp)) == Status ? Accent : Ink);
        return reinterpret_cast<LRESULT>(backgroundBrush);
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX:
        SetBkColor(reinterpret_cast<HDC>(wp), RGB(255, 255, 255));
        SetTextColor(reinterpret_cast<HDC>(wp), Ink);
        return reinterpret_cast<LRESULT>(whiteBrush);
    case WM_ERASEBKGND: {
        RECT r;
        GetClientRect(w.hwnd, &r);
        FillRect(reinterpret_cast<HDC>(wp), &r, backgroundBrush);
        return 1;
    }
    case WM_PAINT: {
        PAINTSTRUCT paint{};
        auto dc = BeginPaint(w.hwnd, &paint);
        if (w.mode == Mode::Image) {
            RECT r;
            GetClientRect(w.hwnd, &r);
            r.top = Scale(w, 64);
            DrawImage(w, dc, r);
        }
        EndPaint(w.hwnd, &paint);
        return 0;
    }
    case WM_MOUSEWHEEL:
        if (w.mode == Mode::Image) {
            w.zoom =
                std::clamp(w.zoom * (GET_WHEEL_DELTA_WPARAM(wp) > 0 ? 1.2 : 1.0 / 1.2), 0.5, 10.0);
            InvalidateRect(w.hwnd, nullptr, TRUE);
        } else if (w.mode != Mode::Library) {
            w.scroll -= GET_WHEEL_DELTA_WPARAM(wp) / WHEEL_DELTA * 70;
            Layout(w);
        }
        return 0;
    case WM_VSCROLL: {
        if (w.mode == Mode::Library || w.mode == Mode::Image)
            break;
        int code = LOWORD(wp);
        SCROLLINFO info{sizeof(info), SIF_ALL, 0, 0, 0, 0, 0};
        GetScrollInfo(w.hwnd, SB_VERT, &info);
        if (code == SB_LINEUP)
            w.scroll -= 32;
        else if (code == SB_LINEDOWN)
            w.scroll += 32;
        else if (code == SB_PAGEUP)
            w.scroll -= static_cast<int>(info.nPage);
        else if (code == SB_PAGEDOWN)
            w.scroll += static_cast<int>(info.nPage);
        else if (code == SB_THUMBTRACK)
            w.scroll = info.nTrackPos;
        Layout(w);
        return 0;
    }
    case WM_LBUTTONDOWN:
        if (w.mode == Mode::Image) {
            w.dragging = true;
            w.anchor = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            SetCapture(w.hwnd);
        }
        return 0;
    case WM_MOUSEMOVE:
        if (w.mode == Mode::Image && w.dragging) {
            POINT p{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            w.pan.x += p.x - w.anchor.x;
            w.pan.y += p.y - w.anchor.y;
            w.anchor = p;
            InvalidateRect(w.hwnd, nullptr, TRUE);
        }
        return 0;
    case WM_LBUTTONUP:
        w.dragging = false;
        if (GetCapture() == w.hwnd)
            ReleaseCapture();
        return 0;
    case WM_TIMER:
        if (wp == 4) {
            KillTimer(w.hwnd, 4);
            DestroyWindow(w.hwnd);
            return 0;
        }
        if (wp == 1) {
            Sync();
            return 0;
        }
        if (wp == 3) {
            KillTimer(w.hwnd, 3);
            try {
                auto image = Context::Screenshot(w.draft.source);
                w.draft.fullImage = image;
                w.draft.data["evidence"]["context"].erase("text");
                Set(w, Original, L"");
                w.draft.data["evidence"]["context"]["image"] = {
                    {"retained", true},
                    {"asset_role", "context"},
                    {"width", image->GetWidth()},
                    {"height", image->GetHeight()},
                    {"coordinate_space", "context_image_pixels"},
                    {"purpose", "page_context"},
                    {"relation_to_quote", "unverified"},
                    {"selection_meaning", "full_viewport_not_quote_location"}};
                w.previewRole = L"context";
                LoadPreview(w);
                w.dirty = true;
                StatusText(w, L"完整页面截图已准备，将在保存记录时一起保存。");
            } catch (const std::exception &error) {
                StatusText(w, ErrorText(error));
                notify(L"无法捕获原应用：请回到原页面后重新打开随手记。", true);
            }
            Busy(w, false);
            ShowWindow(w.hwnd, SW_SHOW);
            SetForegroundWindow(w.hwnd);
            InvalidateRect(ControlOf(w, Preview), nullptr, TRUE);
            return 0;
        }
        break;
    case Complete: {
        std::deque<std::function<void()>> callbacks;
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            callbacks.swap(completions);
        }
        for (auto &f : callbacks)
            try {
                f();
            } catch (const std::exception &error) {
                notify(ErrorText(error), true);
            }
        return 0;
    }
    case WM_CLOSE:
        if (w.mode == Mode::Library) {
            ShowWindow(w.hwnd, SW_HIDE);
            return 0;
        }
        if (w.busy) {
            StatusText(w, L"操作进行中，请稍候。你的输入仍保留。");
            return 0;
        }
        if (w.mode == Mode::Editor && w.dirty &&
            MessageBoxW(w.hwnd, L"有未保存的内容，确定放弃此次修改？", L"Mnote",
                        MB_YESNO | MB_ICONQUESTION) != IDYES)
            return 0;
        DestroyWindow(w.hwnd);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(w.hwnd, message, wp, lp);
}
LRESULT CALLBACK Procedure(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    auto w = reinterpret_cast<Window *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
        w = static_cast<Window *>(reinterpret_cast<CREATESTRUCTW *>(lp)->lpCreateParams);
        w->hwnd = hwnd;
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(w));
    }
    if (!w)
        return DefWindowProcW(hwnd, message, wp, lp);
    if (message == WM_NCDESTROY) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        if (editor == hwnd)
            editor = nullptr;
        if (accountWindow == hwnd)
            accountWindow = nullptr;
        if (w->font)
            DeleteObject(w->font);
        if (w->heading)
            DeleteObject(w->heading);
        // Remove only this draft's explicitly allocated staging files, never the
        // library or Inbox.
        if (w->mode == Mode::Editor && !w->draft.staging.empty()) {
            for (auto name : {L"original.png", L"annotated.png", L"context.png"}) {
                std::error_code error;
                fs::remove(w->draft.staging / name, error);
            }
            std::error_code error;
            fs::remove(w->draft.staging, error);
        }
        windows.erase(hwnd);
        return DefWindowProcW(hwnd, message, wp, lp);
    }
    try {
        return Dispatch(*w, message, wp, lp);
    } catch (const std::exception &error) {
        StatusText(*w, ErrorText(error));
        notify(ErrorText(error), true);
        return 0;
    }
}
} // namespace

bool DrawButton(const DRAWITEMSTRUCT &item) {
    auto r = item.rcItem;
    bool primary =
        item.CtlID == Save || item.CtlID == Login || item.CtlID == NewNote || item.CtlID == 1007;
    bool disabled = (item.itemState & ODS_DISABLED) != 0;
    COLORREF fill = disabled ? RGB(237, 239, 245) : primary ? Accent : RGB(255, 255, 255);
    if (item.itemState & ODS_SELECTED)
        fill = primary ? RGB(67, 73, 211) : RGB(234, 235, 250);
    auto brush = CreateSolidBrush(fill);
    auto pen = CreatePen(PS_SOLID, 1, primary ? fill : Border);
    auto oldBrush = SelectObject(item.hDC, brush), oldPen = SelectObject(item.hDC, pen);
    RoundRect(item.hDC, r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, 14, 14);
    SelectObject(item.hDC, oldBrush);
    SelectObject(item.hDC, oldPen);
    DeleteObject(brush);
    DeleteObject(pen);
    auto font = reinterpret_cast<HFONT>(SendMessageW(item.hwndItem, WM_GETFONT, 0, 0));
    DrawTextLine(item.hDC, r, Text(item.hwndItem),
                 disabled  ? Muted
                 : primary ? RGB(255, 255, 255)
                           : Ink,
                 font ? font : defaultFont, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    if (item.itemState & ODS_FOCUS) {
        InflateRect(&r, -4, -4);
        DrawFocusRect(item.hDC, &r);
    }
    return true;
}
HFONT Font() { return defaultFont; }
void Toast(const std::wstring &text, bool error) {
    auto &w = Create(Mode::Toast, L"Mnote · 通知", 460, 132);
    SetWindowLongPtrW(w.hwnd, GWL_STYLE, WS_POPUP | WS_BORDER | WS_CLIPCHILDREN);
    SetWindowLongPtrW(w.hwnd, GWL_EXSTYLE, WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST);
    Label(w, Subtitle, error ? L"Mnote · 请留意" : L"Mnote · 已保存", 16);
    Label(w, Status, text, 48);
    RECT area;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &area, 0);
    SetWindowPos(w.hwnd, HWND_TOPMOST, area.right - Scale(w, 480), area.bottom - Scale(w, 150),
                 Scale(w, 460), Scale(w, 132), SWP_NOACTIVATE | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
    Layout(w);
    SetTimer(w.hwnd, 4, error ? 6500 : 3600, nullptr);
}
void Start(HINSTANCE appInstance, const fs::path &root, std::function<void()> capture,
           std::function<void(const std::wstring &, bool)> notice,
           std::function<void()> exitForUpdate) {
    instance = appInstance;
    captureAction = std::move(capture);
    exitForUpdateAction = std::move(exitForUpdate);
    notify = [notice = std::move(notice)](const std::wstring &text, bool error) {
        notice(text, error);
        try {
            Toast(text, error);
        } catch (const std::exception &) {
        }
    };
    library = std::make_unique<Library>(root);
    backgroundBrush = CreateSolidBrush(Background);
    whiteBrush = CreateSolidBrush(RGB(255, 255, 255));
    defaultFont = CreateFontW(-15, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, 0, 0,
                              CLEARTYPE_QUALITY, 0, L"Segoe UI");
    WNDCLASSEXW cls{};
    cls.cbSize = sizeof(cls);
    cls.hInstance = instance;
    cls.lpfnWndProc = Procedure;
    cls.lpszClassName = ClassName;
    cls.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    cls.hbrBackground = backgroundBrush;
    cls.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    RegisterClassExW(&cls);
    auto &w = Create(Mode::Library, L"Mnote · 我的知识库", 1020, 800);
    home = w.hwnd;
    Label(w, Title, L"我的知识库", 24);
    Label(w, Subtitle, L"把遇见的内容，变成自己的思考。", 70);
    Button(w, AccountButton, L"登录账号", 0, 0, 178);
    Button(w, Updates, L"版本更新", 0, 0, 120);
    Button(w, NewNote, L"＋ 随手记", 28, 110, 132);
    Button(w, Capture, L"单次摘录", 170, 110, 132);
    Button(w, Refresh, L"刷新与同步", 0, 110, 124);
    Add(w, Search, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | WS_TABSTOP, 28, 174, 360, 38);
    SendMessageW(ControlOf(w, Search), EM_SETCUEBANNER, TRUE,
                 reinterpret_cast<LPARAM>(L"搜索想法、摘录、原文与标签"));
    Add(w, TagFilter, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 0, 174, 190, 280);
    Add(w, KindFilter, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 0, 174, 198, 280);
    for (auto value : {L"全部类型", L"想法", L"TODO", L"稍后回顾"})
        SendMessageW(ControlOf(w, KindFilter), CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value));
    SendMessageW(ControlOf(w, KindFilter), CB_SETCURSEL, 0, 0);
    Add(w, List, L"LISTBOX", L"",
        LBS_OWNERDRAWFIXED | LBS_HASSTRINGS | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL |
            WS_TABSTOP,
        28, 230, 900, 400);
    Button(w, Trash, L"回收站", 28, 0, 120);
    Button(w, OpenRecord, L"查看 / 修改", 0, 0, 148);
    Label(w, Status, L"Ctrl+Shift+F8 随手记  ·  Ctrl+Shift+F9 截图摘录", 0);
    Layout(w);
    worker = std::thread([] {
        CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        for (;;) {
            std::function<void()> job;
            {
                std::unique_lock<std::mutex> lock(queueMutex);
                queueWake.wait(lock, [] { return stopping || !jobs.empty(); });
                if (stopping)
                    break;
                job = std::move(jobs.front());
                jobs.pop_front();
            }
            try {
                job();
            } catch (const std::exception &error) {
                auto text = ErrorText(error);
                Post([text] { notify(text, true); });
            }
        }
        CoUninitialize();
    });
    SetTimer(home, 1, 60000, nullptr);
    Show();
    Load();
    Sync();
}
void Stop() {
    if (library)
        library->interrupt();
    if (home)
        KillTimer(home, 1);
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        stopping = true;
        jobs.clear();
    }
    queueWake.notify_all();
    if (worker.joinable())
        worker.join();
    if (syncWorker.joinable())
        syncWorker.join();
    while (!windows.empty())
        DestroyWindow(windows.begin()->first);
    home = nullptr;
    library.reset();
    if (defaultFont)
        DeleteObject(defaultFont);
    DeleteObject(backgroundBrush);
    DeleteObject(whiteBrush);
}
bool HasEditor() { return editor && IsWindow(editor); }
bool CanExit() {
    for (const auto &item : windows) {
        auto &w = *item.second;
        if (w.busy) {
            ShowWindow(w.hwnd, SW_SHOW);
            SetForegroundWindow(w.hwnd);
            StatusText(w, L"请等待当前保存或读取操作完成后再退出。");
            return false;
        }
        if (w.mode == Mode::Editor && w.dirty &&
            MessageBoxW(w.hwnd, L"记录尚未保存，确定放弃并退出 Mnote？", L"Mnote",
                        MB_YESNO | MB_ICONQUESTION) != IDYES)
            return false;
    }
    return true;
}
void Show() {
    if (home) {
        ShowWindow(home, SW_SHOWNORMAL);
        SetForegroundWindow(home);
        Load();
    }
}
void Hide() {
    for (const auto &item : windows)
        ShowWindow(item.first, SW_HIDE);
}
std::string Scope() { return library->account().scope; }
fs::path Staging() {
    auto path = library->root() / L"Drafts" / Wide(NewId());
    fs::create_directories(path);
    return path;
}
void QuickNote() {
    if (editor && IsWindow(editor)) {
        ShowWindow(editor, SW_SHOW);
        SetForegroundWindow(editor);
        return;
    }
    Draft draft;
    draft.source = Context::Foreground();
    draft.data = {{"schema_version", 1},
                  {"id", NewId()},
                  {"created_at", Timestamp()},
                  {"kind", "thought"},
                  {"comment", ""},
                  {"source", draft.source.metadata},
                  {"tags", Json::array()},
                  {"ai_access", "local_only"}};
    draft.staging = Staging();
    draft.scope = Scope();
    OpenEditor(std::move(draft));
}
void Compose(Draft draft) { OpenEditor(std::move(draft)); }
void Sync() {
    if (!library || syncing || !library->account().signedIn())
        return;
    syncing = true;
    auto scope = Scope();
    if (home && windows.count(home))
        StatusText(*windows[home], L"正在同步与拉取记录…");
    if (syncWorker.joinable())
        syncWorker.join();
    syncWorker = std::thread([scope] {
        std::wstring result;
        bool failed = false;
        try {
            int count = library->sync();
            result = L"同步完成 · 本轮处理 " + std::to_wstring(count) + L" 项变更";
        } catch (const std::exception &error) {
            result = ErrorText(error);
            failed = true;
        }
        Post([scope, result, failed] {
            syncing = false;
            if (home && windows.count(home) && Scope() == scope)
                StatusText(*windows[home], result);
            Load();
            if (failed && editor && windows.count(editor))
                StatusText(*windows[editor], L"云端同步未完成；本机内容保留。");
        });
    });
}
bool Translate(MSG &message) {
    HWND root = GetAncestor(message.hwnd, GA_ROOT);
    auto found = windows.find(root);
    if (found == windows.end())
        return false;
    auto &w = *found->second;
    if (message.message == WM_KEYDOWN) {
        if (message.wParam == VK_ESCAPE) {
            PostMessageW(root, WM_CLOSE, 0, 0);
            return true;
        }
        if (message.wParam == VK_F5 && w.mode == Mode::Library) {
            Load();
            Sync();
            return true;
        }
        if (message.wParam == 'S' && GetKeyState(VK_CONTROL) < 0 && w.mode == Mode::Editor) {
            try {
                SaveEditor(w);
            } catch (const std::exception &error) {
                StatusText(w, ErrorText(error));
            }
            return true;
        }
    }
    bool handled = IsDialogMessageW(root, &message) != FALSE;
    if (handled && message.message == WM_KEYDOWN && message.wParam == VK_TAB &&
        w.mode != Mode::Library && w.mode != Mode::Image) {
        auto focus = GetFocus();
        for (const auto &p : w.placements)
            if (p.control == focus && GetDlgCtrlID(focus) != Save &&
                GetDlgCtrlID(focus) != Cancel && GetDlgCtrlID(focus) != Status) {
                RECT r;
                GetClientRect(root, &r);
                int view = MulDiv(r.bottom, 96, w.dpi) - 112;
                if (p.y < w.scroll || p.y + p.h > w.scroll + view) {
                    w.scroll = std::max(0, p.y - 24);
                    Layout(w);
                }
                break;
            }
    }
    return handled;
}
} // namespace Mnote::Workspace

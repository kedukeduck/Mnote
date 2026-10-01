#include "workspace.hpp"
#include "updater.hpp"
#include "ai_chat.hpp"
#include "chat_transcript.hpp"
#include <cmath>
#include <commctrl.h>
#include <commdlg.h>
#include <condition_variable>
#include <deque>
#include <objidl.h>
#include <richedit.h>
#include <set>
#include <shellapi.h>
#include <thread>
#include <windowsx.h>

namespace Mnote::Workspace {
namespace {
constexpr wchar_t ClassName[] = L"Mnote.Workspace";
constexpr UINT Complete = WM_APP + 40;
constexpr COLORREF Background = RGB(247, 244, 236), Ink = RGB(36, 43, 43),
                   Muted = RGB(102, 108, 102), Accent = RGB(36, 73, 78),
                   Border = RGB(221, 216, 205), Surface = RGB(239, 235, 227),
                   Selected = RGB(227, 233, 227), Copper = RGB(149, 85, 48),
                   Paper = RGB(255, 254, 250),
                   Danger = RGB(175, 73, 66);
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
    UpdateNotes,
    BatchExport = 25000,
    SelectAll,
    ClearSelection,
    ExportShares,
    SettingsButton = 26000,
    MultiSelect = 27000,
    MultiCancel,
    MultiAll,
    MultiDelete,
    ExportPreview,
    TagsPicker,
    ShareText,
    Brand,
    AllRecords,
    FilterToggle,
    ReaderPanel,
    ReaderTitle,
    ReaderEmpty,
    MoreOptions,
    ReaderThought,
    ReaderExcerpt,
    ReaderOriginal,
    ReaderCrop,
    ReaderContext,
    ReaderSource,
    ChatButton = 29000, SaveChat, ChatFilter, ChatModels, ChatHistory, ChatSend, ChatStop,
    ChatNew, ChatInput, ChatTranscript, ChatProfile, ChatMaterials, ChatRename, ChatRefresh,
    ChatThought, ChatExcerpt, ChatOriginal, ChatImages, ChatMetadata, ChatName,
    ModelLabel, ModelEndpoint, ModelId, ModelKey, ModelVision, ModelLimit, ModelNew,
    ModelTest, ModelImageTest, ModelDelete, ChatSuggestOne, ChatSuggestTwo, ChatSuggestThree,
    ChatRetry
};
enum class Mode {
    Library,
    Editor,
    Account,
    Image,
    Toast,
    Update,
    Markdown,
    Shares,
    Settings,
    ShareText,
    Reader, Chat, ChatHistory, ChatModels
};
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
    HFONT font = nullptr, heading = nullptr, brand = nullptr, reading = nullptr,
          label = nullptr;
    std::vector<Placement> placements;
    bool busy = false, dirty = false, loading = false, showTrash = false, editing = false;
    std::string scope, baseline;
    Draft draft;
    Record record;
    std::vector<Record> records, filtered;
    bool selecting = false;
    bool filtersOpen = false, moreOptions = false;
    HWND reader = nullptr;
    std::string readerKey;
    std::map<int, std::shared_ptr<Gdiplus::Bitmap>> readerImages;
    int readerGeneration = 0;
    std::set<std::string> selectedIds;
    std::map<std::string, std::shared_ptr<Gdiplus::Bitmap>> thumbnails;
    Json shares = Json::array();
    std::set<std::string> shareLoading, shareErrors;
    std::map<std::string, std::wstring> shareCoverLabels;
    std::deque<std::string> shareCacheOrder;
    int shareGeneration = 0;
    Json shareImages = Json::array();
    std::string shareId;
    std::shared_ptr<Gdiplus::Bitmap> preview;
    std::wstring previewRole, status;
    double zoom = 1;
    POINT pan{}, anchor{};
    bool dragging = false;
    std::string chatId, chatRecordId, profileId;
    Json profiles = Json::array();
    std::vector<Ai::Conversation> conversations;
    std::map<std::string, int> chatCounts;
    std::shared_ptr<std::atomic_bool> chatStop;
    Ai::Conversation conversation;
    bool chatReceiving = false;
};
HINSTANCE instance = nullptr;
HWND home = nullptr, editor = nullptr, accountWindow = nullptr;
std::unique_ptr<Library> library;
std::unique_ptr<Ai::Store> chats;
std::vector<std::thread> chatWorkers;
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
void OpenChat(const Record &, const std::string &id = "");
void OpenChatHistory(const std::string &record = "");
void OpenChatModels();
void ChatCommand(Window &, int, int);
void SelectionControls(Window &);
void DrawImage(Window &, HDC, RECT);
void RefreshReader(Window &);
void LayoutReader(Window &);
void QueueLibraryThumbnail(Window &, const Record &);
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
    for (int id : {Save,      Delete,         Login,          Logout,        Import,
                   Clipboard, ReadContext,    ScreenContext,  RemoveContext, Full,
                   Note,      Quote,          Original,       TagsInput,     Url,
                   Kind,      Server,         Username,       Password,      Invitation,
                   AiAccess,  UpdateCheck,    UpdateDownload, UpdateInstall, BatchExport,
                   SelectAll, ClearSelection, ExportShares,   MultiSelect,   MultiCancel,
                   MultiAll,  MultiDelete,    ImageRole,      ExportPreview, TagsPicker,
                   ShareText, SaveChat, ChatSend, ChatProfile, ChatRefresh, ChatRename, ModelLabel,
                   ModelEndpoint, ModelId, ModelKey, ModelVision, ModelLimit, ModelTest,
                   ModelImageTest, ModelDelete, ModelNew})
        if (auto c = ControlOf(w, id))
            EnableWindow(c, !value);
}
void Failure(HWND hwnd, std::uint64_t serial, const std::wstring &error) {
    if (auto w = Find(hwnd, serial)) {
        Busy(*w, false);
        StatusText(*w, error);
        if (w->mode == Mode::Image && !w->shareId.empty())
            SetWindowTextW(w->hwnd, L"Mnote · 图片加载失败，可重新选择图片重试");
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
    if (w.brand)
        DeleteObject(w.brand);
    if (w.reading)
        DeleteObject(w.reading);
    if (w.label)
        DeleteObject(w.label);
    w.font = CreateFontW(-Scale(w, 15), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, 0,
                         0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    w.heading = CreateFontW(-Scale(w, 25), 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE,
                            DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    w.brand = CreateFontW(-Scale(w, 34), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                          DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Georgia");
    w.reading = CreateFontW(-Scale(w, 19), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                            DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    w.label = CreateFontW(-Scale(w, 12), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                          DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    for (auto &p : w.placements)
        SendMessageW(
            p.control, WM_SETFONT,
            reinterpret_cast<WPARAM>(GetDlgCtrlID(p.control) == Brand ? w.brand
                                     : GetDlgCtrlID(p.control) == Title ? w.heading
                                     : GetDlgCtrlID(p.control) == ReaderThought ? w.reading
                                                                                : w.font), TRUE);
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
int RecordRowHeight(Window &w, const Record &record) {
    // Reserve room for both voices; never let custom tags overlap source material.
    const auto content = PresentRecord(record);
    return Scale(w, content.hasMaterial ? (content.comment.empty() ? 212 : 276) : 196);
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
    if (w.mode == Mode::Reader) {
        LayoutReader(w);
        return;
    }
    if (w.mode == Mode::Chat) {
        Mnote::ChatTranscript::SetDpi(ControlOf(w, ChatTranscript), w.dpi);
        auto move = [&](int id, int x, int y, int ww, int hh) {
            MoveWindow(ControlOf(w, id), Scale(w, x), Scale(w, y), Scale(w, ww), Scale(w, hh),
                       TRUE);
        };
        move(Title, 28, 20, width - 310, 40);
        move(ChatHistory, width - 274, 22, 116, 36);
        move(ChatNew, width - 146, 22, 118, 36);
        move(Subtitle, 28, 67, width - 56, 42);
        move(ChatProfile, 28, 112, width - 318, 220);
        move(ChatMaterials, width - 278, 112, 146, 36);
        move(ChatRefresh, width - 120, 112, 92, 36);
        const int checks[] = {ChatThought, ChatExcerpt, ChatOriginal, ChatImages, ChatMetadata};
        for (int i = 0; i < 5; ++i) {
            move(checks[i], 28 + i * (width - 56) / 5, 158, (width - 56) / 5, 28);
            ShowWindow(ControlOf(w, checks[i]), w.chatId.empty() ? SW_SHOW : SW_HIDE);
        }
        int top = w.chatId.empty() ? 196 : 158;
        bool empty = w.conversation.data.value("messages", Json::array()).empty();
        move(ChatTranscript, 28, top, width - 56, std::max(80, height - top - (empty ? 238 : 194)));
        int suggestionWidth = (width - 80) / 3;
        move(ChatSuggestOne, 28, height - 222, suggestionWidth, 32);
        move(ChatSuggestTwo, 40 + suggestionWidth, height - 222, suggestionWidth, 32);
        move(ChatSuggestThree, 52 + suggestionWidth * 2, height - 222, suggestionWidth, 32);
        for (int id : {ChatSuggestOne, ChatSuggestTwo, ChatSuggestThree})
            ShowWindow(ControlOf(w, id), empty ? SW_SHOW : SW_HIDE);
        move(ChatInput, 28, height - 178, width - 192, 112);
        move(ChatSend, width - 150, height - 178, 122, 48);
        move(ChatStop, width - 150, height - 120, 122, 38);
        move(Status, 28, height - 52, width - 56, 38);
        InvalidateRect(w.hwnd, nullptr, TRUE);
        return;
    }
    if (w.mode == Mode::ChatHistory) {
        auto move = [&](int id, int x, int y, int ww, int hh) {
            MoveWindow(ControlOf(w, id), Scale(w, x), Scale(w, y), Scale(w, ww), Scale(w, hh),
                       TRUE);
        };
        move(Title, 28, 22, width - 56, 42);
        move(Subtitle, 28, 74, width - 56, 34);
        move(Search, 28, 120, width - 180, 38);
        move(ChatRefresh, width - 140, 120, 112, 38);
        move(List, 28, 172, width - 56, height - 340);
        move(ChatName, 28, height - 150, width - 292, 38);
        move(ChatRename, width - 252, height - 150, 104, 38);
        move(Delete, width - 136, height - 150, 108, 38);
        move(ChatButton, 28, height - 96, 134, 38);
        move(ChatNew, 176, height - 96, 132, 38);
        move(Cancel, 322, height - 96, 100, 38);
        move(Status, 28, height - 44, width - 56, 34);
        return;
    }
    if (w.mode == Mode::Library) {
        auto move = [&](int id, int x, int y, int ww, int hh) {
            MoveWindow(ControlOf(w, id), Scale(w, x), Scale(w, y), Scale(w, ww), Scale(w, hh),
                       TRUE);
        };
        bool wide = width >= 1080;
        int sidebar = wide ? 180 : 152, left = sidebar + 24;
        int listWidth = wide ? std::clamp((width - sidebar) * 38 / 100, 330, 410)
                             : width - left - 24;
        int split = left + listWidth + 24;
        move(Brand, 24, 22, sidebar - 32, 48);
        move(AllRecords, 12, 102, sidebar - 24, 42);
        move(Trash, 12, 154, sidebar - 24, 42);
        move(SettingsButton, 12, height - 126, sidebar - 24, 40);
        move(AccountButton, 12, height - 78, sidebar - 24, 38);
        move(Title, left, 24, listWidth - 100, 40);
        move(MultiSelect, left + listWidth - 80, 25, 80, 36);
        move(Search, left, 84, listWidth - 88, 38);
        move(FilterToggle, left + listWidth - 78, 84, 78, 38);
        move(TagFilter, left, 132, (listWidth - 12) / 2, 280);
        move(KindFilter, left + (listWidth + 12) / 2, 132, (listWidth - 12) / 2, 280);
        move(ChatFilter,left,172,listWidth,200);
        ShowWindow(ControlOf(w, TagFilter), w.filtersOpen ? SW_SHOW : SW_HIDE);
        ShowWindow(ControlOf(w, KindFilter), w.filtersOpen ? SW_SHOW : SW_HIDE);
        ShowWindow(ControlOf(w, ChatFilter), w.filtersOpen ? SW_SHOW : SW_HIDE);
        int listTop = w.filtersOpen ? 220 : 140;
        move(List, left, listTop, listWidth, std::max(80, height - listTop - 102));
        move(BatchExport, left + listWidth - 134, height - 82, 134, 38);
        move(MultiCancel, left, height - 82, 62, 38);
        move(MultiAll, left + 68, height - 82, 82, 38);
        move(MultiDelete, left + listWidth - 102, 25, 102, 36);
        move(OpenRecord, wide ? split + 24 : left, height - 82, 100, 38);
        move(ReaderTitle, split + 24, 28, 100, 36);
        move(Capture, wide ? width - 354 : 12, wide ? 28 : 218,
             wide ? 88 : sidebar - 24, 38);
        move(NewNote, wide ? width - 256 : 12, wide ? 28 : 264,
             wide ? 108 : sidebar - 24, 38);
        move(Refresh, wide ? width - 138 : 12, wide ? 28 : 310,
             wide ? 114 : sidebar - 24, 38);
        move(Subtitle, 24, height - 32, sidebar - 36, 22);
        move(Status, left, height - 34, width - left - 24, 24);
        move(ReaderPanel, split, 92, std::max(120, width - split - 12), height - 190);
        ShowWindow(ControlOf(w, ReaderPanel), wide ? SW_SHOW : SW_HIDE);
        ShowWindow(ControlOf(w, ReaderTitle), wide ? SW_SHOW : SW_HIDE);
        InvalidateRect(w.hwnd, nullptr, TRUE);
        return;
    }
    if (w.mode == Mode::Markdown || w.mode == Mode::Shares) {
        auto move = [&](int id, int x, int y, int ww, int hh) {
            MoveWindow(ControlOf(w, id), Scale(w, x), Scale(w, y), Scale(w, ww), Scale(w, hh),
                       TRUE);
        };
        move(Title, 28, 24, width - 56, 40);
        move(Subtitle, 28, 76, width - 56, 58);
        move(SelectAll, 28, 142, 132, 36);
        move(ClearSelection, 172, 142, 100, 36);
        move(ExportShares, width - 176, 142, 148, 36);
        move(ExportPreview, w.mode == Mode::Shares ? 28 : 288, 142, 160, 36);
        move(ShareText, 204, 142, 150, 36);
        move(List, 28, 194, width - 56, std::max(80, height - 308));
        if (w.mode == Mode::Shares)
            SendMessageW(ControlOf(w, List), LB_SETITEMHEIGHT, 0,
                         Scale(w, std::max(240, std::min(384, height - 308))));
        move(Save, width - 228, height - 96, 200, 40);
        move(Cancel, 28, height - 96, 120, 40);
        move(Status, 28, height - 44, width - 56, 36);
        return;
    }
    if (w.mode == Mode::ShareText) {
        MoveWindow(ControlOf(w, Title), Scale(w, 28), Scale(w, 20), r.right - Scale(w, 56),
                   Scale(w, 42), TRUE);
        MoveWindow(ControlOf(w, Original), Scale(w, 28), Scale(w, 82), r.right - Scale(w, 56),
                   r.bottom - Scale(w, 158), TRUE);
        MoveWindow(ControlOf(w, Cancel), Scale(w, 28), r.bottom - Scale(w, 58), Scale(w, 120),
                   Scale(w, 38), TRUE);
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
    if (w.mode == Mode::Editor && !w.moreOptions && p.y >= 10000) {
            ShowWindow(p.control, SW_HIDE);
            continue;
        }
        if (w.mode == Mode::Editor && p.y >= 10000)
            y -= 10000;
        if (id == Status)
            y = height - 44;
        else if (id == Save || id == Login || id == SaveChat)
            y = height - 94;
        else if (id == Cancel || id == Delete)
            y = height - 94;
        int ww = p.stretch ? std::max(120, width - 56) : p.w;
        MoveWindow(p.control, Scale(w, p.x), Scale(w, y), Scale(w, ww), Scale(w, p.h), TRUE);
        bool footer = id == Status || id == Save || id == Login || id == Cancel || id == Delete || id == SaveChat;
        // Clip the scrolling body above the fixed save/status footer.
        if (!footer)
            SetWindowRgn(p.control,
                         CreateRectRgn(0, Scale(w, std::max(0, -y)), Scale(w, ww),
                                       Scale(w, std::max(0, std::min(p.h, view - y)))),
                         TRUE);
        ShowWindow(p.control, footer || (y + p.h > 0 && y < view) ? SW_SHOW : SW_HIDE);
    }
}
LRESULT CALLBACK ReaderTextProc(HWND hwnd, UINT message, WPARAM wp, LPARAM lp, UINT_PTR,
                                DWORD_PTR) {
    if (message == WM_MOUSEWHEEL)
        return SendMessageW(GetParent(hwnd), message, wp, lp);
    if (message == WM_NCDESTROY)
        RemoveWindowSubclass(hwnd, ReaderTextProc, 1);
    return DefSubclassProc(hwnd, message, wp, lp);
}
void LayoutReader(Window &w) {
    RECT client{};
    GetClientRect(w.hwnd, &client);
    int width = MulDiv(client.right, 96, w.dpi), height = MulDiv(client.bottom, 96, w.dpi);
    int contentWidth = std::min(680, std::max(160, width - 56));
    int left = std::max(24, (width - contentWidth) / 2), y = 22;
    auto dc = GetDC(w.hwnd);
    for (auto &p : w.placements) {
        int id = GetDlgCtrlID(p.control);
        p.x = left;
        p.w = id == 28102 ? 70 : contentWidth;
        p.y = y;
        auto image = w.readerImages.find(id);
        if (id == ReaderCrop || id == ReaderContext) {
            p.h = image == w.readerImages.end() ? 40
                      : std::max(80, int(double(contentWidth) * image->second->GetHeight() /
                                        image->second->GetWidth()));
        } else if (id == ReaderThought || id == ReaderExcerpt || id == ReaderOriginal ||
                   id == ReaderEmpty) {
            auto previous = SelectObject(dc, id == ReaderThought ? w.reading : w.font);
            RECT measured{0, 0, Scale(w, contentWidth - 8), 0};
            auto value = Text(p.control);
            DrawTextW(dc, value.c_str(), -1, &measured,
                      DT_CALCRECT | DT_WORDBREAK | DT_EDITCONTROL | DT_NOPREFIX);
            SelectObject(dc, previous);
            p.h = std::max(30, MulDiv(measured.bottom, 96, w.dpi) + 12);
        } else
            p.h = id == ReaderSource ? 42 : 28;
        y += p.h + ((id == ReaderThought || id == ReaderExcerpt || id == ReaderOriginal ||
                     id == ReaderCrop || id == ReaderContext) ? 28 : 10);
    }
    ReleaseDC(w.hwnd, dc);
    w.extent = y;
    w.scroll = std::clamp(w.scroll, 0, std::max(0, y - height));
    SCROLLINFO info{sizeof(info), SIF_RANGE | SIF_PAGE | SIF_POS, 0, y - 1,
                    static_cast<UINT>(height), w.scroll, 0};
    SetScrollInfo(w.hwnd, SB_VERT, &info, TRUE);
    for (const auto &p : w.placements)
        MoveWindow(p.control, Scale(w, p.x), Scale(w, p.y - w.scroll), Scale(w, p.w),
                   Scale(w, p.h), TRUE);
    InvalidateRect(w.hwnd, nullptr, TRUE);
}
void RefreshReader(Window &from) {
    auto found = windows.find(from.reader);
    if (found == windows.end())
        return;
    auto &w = *found->second;
    auto index = SendMessageW(ControlOf(from, List), LB_GETCURSEL, 0, 0);
    const Record *record = index >= 0 && static_cast<std::size_t>(index) < from.filtered.size()
                               ? &from.filtered[static_cast<std::size_t>(index)] : nullptr;
    auto key = record ? from.scope + ":" + record->id + ":" + Library::fingerprint(*record) : std::string();
    if (key == from.readerKey && !w.placements.empty())
        return;
    from.readerKey = key;
    ++w.readerGeneration;
    for (const auto &p : w.placements)
        DestroyWindow(p.control);
    w.placements.clear();
    w.readerImages.clear();
    w.scroll = 0;
    w.scope = from.scope;
    if (!record) {
        Add(w, ReaderEmpty, L"STATIC",
            from.records.empty() ? L"留住一个想法\r\n\r\n从随手记或截图开始，记录会出现在这里。"
                                  : L"没有匹配的记录\r\n\r\n试试其他关键词，或清除筛选条件。",
            SS_LEFT, 24, 22, 500, 120);
        Layout(w);
        return;
    }
    w.record = *record;
    w.draft.data = record->data;
    w.draft.assets = record->assets;
    if(!record->deleted) Button(w,ChatButton,L"与 AI 聊聊 · 本条记录的对话",24,0,300);
    auto section = [&](int labelId, int id, const std::wstring &title, const std::wstring &value) {
        if (value.empty())
            return;
        Label(w, labelId, title, 0);
        if (labelId == 28102) {
            auto label = ControlOf(w, labelId);
            SetWindowLongPtrW(label, GWL_STYLE, (GetWindowLongPtrW(label, GWL_STYLE) & ~SS_TYPEMASK) | SS_CENTER);
        }
        auto body = Add(w, id, L"EDIT", value, ES_MULTILINE | ES_READONLY | WS_TABSTOP,
                        24, 0, 500, 100);
        SendMessageW(body, WM_SETFONT,
                     reinterpret_cast<WPARAM>(id == ReaderThought ? w.reading : w.font), TRUE);
        SetWindowSubclass(body, ReaderTextProc, 1, 0);
    };
    section(28101, ReaderThought, L"我的想法", Field(record->data, "comment"));
    auto source = Object(record->data, "source");
    section(28102, ReaderExcerpt, L"摘录", Field(source, "text"));
    std::map<int, fs::path> assets;
    for (auto role : {"annotated", "original"}) {
        auto asset = record->assets.find(role);
        if (asset != record->assets.end()) {
            assets[ReaderCrop] = asset->second;
            break;
        }
    }
    if (auto image = record->assets.find("context"); image != record->assets.end())
        assets[ReaderContext] = image->second;
    for (const auto &[id, path] : assets) {
        (void)path;
        Label(w, id == ReaderCrop ? 28103 : 28104,
              id == ReaderCrop ? L"圈选截图" : L"完整页面截图", 0);
        Add(w, id, L"BUTTON", L"正在读取图片…", BS_OWNERDRAW | WS_TABSTOP, 24, 0, 500, 40);
    }
    section(28105, ReaderOriginal, L"页面原文", OriginalText(record->data));
    if (!Field(source, "url").empty())
        Button(w, ReaderSource, L"打开来源网页", 24, 0, 240);
    Layout(w);
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    auto generation = w.readerGeneration;
    if (!assets.empty())
        Enqueue([hwnd, serial, generation, assets] {
            std::map<int, std::shared_ptr<Gdiplus::Bitmap>> decoded;
            for (const auto &[id, path] : assets) {
                std::unique_ptr<Gdiplus::Bitmap> image(Gdiplus::Bitmap::FromFile(path.c_str()));
                if (!image || image->GetLastStatus() != Gdiplus::Ok || !image->GetWidth() ||
                    !image->GetHeight() || std::uint64_t(image->GetWidth()) * image->GetHeight() > 32000000)
                    continue;
                decoded[id].reset(image->Clone(0, 0, static_cast<INT>(image->GetWidth()),
                                               static_cast<INT>(image->GetHeight()), PixelFormat32bppARGB));
            }
            Post([hwnd, serial, generation, decoded] {
                auto form = Find(hwnd, serial);
                if (!form || generation != form->readerGeneration)
                    return;
                form->readerImages = decoded;
                for (auto id : {ReaderCrop, ReaderContext})
                    if (ControlOf(*form, id) && !decoded.count(id))
                        Set(*form, id, L"图片暂不可用，点击打开图片窗口重试");
                Layout(*form);
            });
        });
}
void QueueLibraryThumbnail(Window &w, const Record &record) {
    if (w.thumbnails.count(record.id) || w.shareLoading.count(record.id) ||
        w.shareErrors.count(record.id) || record.assets.empty())
        return;
    fs::path path;
    for (auto role : {"annotated", "original", "context"})
        if (auto found = record.assets.find(role); found != record.assets.end()) {
            path = found->second;
            break;
        }
    if (path.empty())
        return;
    w.shareLoading.insert(record.id);
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    auto scope = w.scope, id = record.id;
    Enqueue([hwnd, serial, scope, id, path] {
        std::shared_ptr<Gdiplus::Bitmap> thumb;
        std::unique_ptr<Gdiplus::Bitmap> image(Gdiplus::Bitmap::FromFile(path.c_str()));
        if (image && image->GetLastStatus() == Gdiplus::Ok && image->GetWidth() && image->GetHeight() &&
            std::uint64_t(image->GetWidth()) * image->GetHeight() <= 32000000) {
            double ratio = std::min(180.0 / image->GetWidth(), 180.0 / image->GetHeight());
            thumb = std::make_shared<Gdiplus::Bitmap>(std::max(1, int(image->GetWidth() * ratio)),
                                                     std::max(1, int(image->GetHeight() * ratio)), PixelFormat32bppARGB);
            Gdiplus::Graphics graphics(thumb.get());
            graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
            graphics.DrawImage(image.get(), 0, 0, thumb->GetWidth(), thumb->GetHeight());
        }
        Post([hwnd, serial, scope, id, thumb] {
            auto form = Find(hwnd, serial);
            if (!form || form->scope != scope)
                return;
            form->shareLoading.erase(id);
            if (thumb) {
                if (form->thumbnails.size() >= 100)
                    form->thumbnails.erase(form->thumbnails.begin());
                form->thumbnails[id] = thumb;
            } else
                form->shareErrors.insert(id);
            InvalidateRect(ControlOf(*form, List), nullptr, FALSE);
        });
    });
}
void Populate(Window &w) {
    KillTimer(ControlOf(w, List), 82);
    RemovePropW(ControlOf(w, List), L"MnoteHoldIndex");
    int tagIndex = static_cast<int>(SendMessageW(ControlOf(w, TagFilter), CB_GETCURSEL, 0, 0));
    auto query = Text(ControlOf(w, Search)), tag = Text(ControlOf(w, TagFilter));
    int kind = static_cast<int>(SendMessageW(ControlOf(w, KindFilter), CB_GETCURSEL, 0, 0));
    int chatFilter = static_cast<int>(SendMessageW(ControlOf(w, ChatFilter), CB_GETCURSEL, 0, 0));
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
        if ((chatFilter == 1 && w.chatCounts[record.id] == 0) ||
            (chatFilter == 2 && w.chatCounts[record.id] > 0)) continue;
        const auto presentation = PresentRecord(record);
        if (kind == 1 && !presentation.hasThought)
            continue;
        if (kind > 1 && kind < 4 && Field(record.data, "kind") !=
                            std::vector<std::wstring>{L"", L"thought", L"todo",
                                                      L"later"}[static_cast<std::size_t>(kind)])
            continue;
        if (kind == 4 && !presentation.hasMaterial)
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
                                L" " + Field(source, "window_title") + L" " + Field(source, "url");
        if (!query.empty() && lower(haystack).find(query) == std::wstring::npos)
            continue;
        if (record.id == selected)
            selection = static_cast<int>(w.filtered.size());
        w.filtered.push_back(record);
        // Owner-drawn rows still expose a useful label to keyboard/screen-reader users.
        std::wstring summary;
        if (!presentation.comment.empty())
            summary = presentation.commentLabel + L"：" + presentation.comment.substr(0, 256);
        if (presentation.hasMaterial)
            summary += L" · " + presentation.materialLabel + L"：" +
                       (presentation.material.empty() ? L"已保留页面截图" : presentation.material.substr(0, 256));
        auto accessible = RecordCategory(record) + L" · " + Field(record.data, "created_at") +
                          L" · " + summary + L" · " + TagText(record.data);
        if(w.chatCounts[record.id]>0) accessible+=L" · AI 对话 "+std::to_wstring(w.chatCounts[record.id]);
        SendMessageW(ControlOf(w, List), LB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(accessible.c_str()));
    }
    SendMessageW(ControlOf(w, List), LB_SETCURSEL,
                 static_cast<WPARAM>(selection < 0 ? 0 : selection), 0);
    SendMessageW(ControlOf(w, List), WM_SETREDRAW, TRUE, 0);
    InvalidateRect(ControlOf(w, List), nullptr, TRUE);
    Set(w, OpenRecord, w.showTrash ? L"恢复记录" : L"编辑记录");
    Set(w, Title, w.showTrash ? L"回收站" : L"全部记录");
    for (auto it = w.selectedIds.begin(); it != w.selectedIds.end();) {
        if (std::none_of(w.filtered.begin(), w.filtered.end(),
                         [&](const Record &r) { return r.id == *it; }))
            it = w.selectedIds.erase(it);
        else
            ++it;
    }
    SelectionControls(w);
    RefreshReader(w);
}
void SelectionControls(Window &w) {
    for (int id : {Trash, OpenRecord, MultiSelect})
        ShowWindow(ControlOf(w, id), w.selecting ? SW_HIDE : SW_SHOW);
    for (int id : {MultiCancel, MultiAll, MultiDelete})
        ShowWindow(ControlOf(w, id), w.selecting ? SW_SHOW : SW_HIDE);
    EnableWindow(ControlOf(w, MultiSelect), !w.showTrash);
    EnableWindow(ControlOf(w, MultiDelete), !w.busy && !w.selectedIds.empty());
    EnableWindow(ControlOf(w, BatchExport), !w.busy && (!w.selecting || !w.selectedIds.empty()));
    Set(w, BatchExport, w.selecting ? L"导出已选" : L"选择导出");
    if (w.selecting)
        StatusText(w, L"已选 " + std::to_wstring(w.selectedIds.size()) +
                          L" 条 · 点击勾选 · Esc 退出多选 · 最多 100 条");
    Layout(w);
    InvalidateRect(ControlOf(w, List), nullptr, FALSE);
}
LRESULT CALLBACK LibraryListProc(HWND hwnd, UINT message, WPARAM wp, LPARAM lp, UINT_PTR,
                                 DWORD_PTR) {
    auto it = windows.find(GetParent(hwnd));
    if (it == windows.end())
        return DefSubclassProc(hwnd, message, wp, lp);
    auto &w = *it->second;
    constexpr UINT holdTimer = 82;
    if (message == WM_LBUTTONDOWN && !w.busy && !w.showTrash) {
        auto hit = SendMessageW(hwnd, LB_ITEMFROMPOINT, 0, lp);
        if (!HIWORD(hit) && LOWORD(hit) < w.filtered.size()) {
            SetPropW(hwnd, L"MnoteHoldIndex",
                     reinterpret_cast<HANDLE>(static_cast<INT_PTR>(LOWORD(hit) + 1)));
            SetPropW(hwnd, L"MnoteHoldPoint", reinterpret_cast<HANDLE>(lp));
            SetTimer(hwnd, holdTimer, 600, nullptr);
        }
    }
    if (message == WM_MOUSEMOVE || message == WM_VSCROLL || message == WM_MOUSEWHEEL ||
        message == WM_CANCELMODE || message == WM_CAPTURECHANGED) {
        auto origin = reinterpret_cast<LPARAM>(GetPropW(hwnd, L"MnoteHoldPoint"));
        if (message != WM_MOUSEMOVE || abs(GET_X_LPARAM(lp) - GET_X_LPARAM(origin)) > 8 ||
            abs(GET_Y_LPARAM(lp) - GET_Y_LPARAM(origin)) > 8) {
            KillTimer(hwnd, holdTimer);
            RemovePropW(hwnd, L"MnoteHoldIndex");
        }
    }
    if (message == WM_TIMER && wp == holdTimer) {
        KillTimer(hwnd, holdTimer);
        auto index = reinterpret_cast<INT_PTR>(RemovePropW(hwnd, L"MnoteHoldIndex")) - 1;
        if (!w.busy && !w.showTrash && index >= 0 &&
            static_cast<std::size_t>(index) < w.filtered.size() &&
            (GetAsyncKeyState(VK_LBUTTON) & 0x8000)) {
            w.selecting = true;
            if (w.selectedIds.size() < 100)
                w.selectedIds.insert(w.filtered[index].id);
            SetPropW(hwnd, L"MnoteHeld", reinterpret_cast<HANDLE>(1));
            SelectionControls(w);
        }
        return 0;
    }
    if (message == WM_CONTEXTMENU && !w.busy && !w.showTrash) {
        int index = static_cast<int>(SendMessageW(hwnd, LB_GETCURSEL, 0, 0));
        if (lp != -1) {
            POINT p{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            ScreenToClient(hwnd, &p);
            auto hit = SendMessageW(hwnd, LB_ITEMFROMPOINT, 0, MAKELPARAM(p.x, p.y));
            index = HIWORD(hit) ? -1 : LOWORD(hit);
        }
        if (index >= 0 && static_cast<std::size_t>(index) < w.filtered.size()) {
            w.selecting = true;
            if (w.selectedIds.size() < 100)
                w.selectedIds.insert(w.filtered[index].id);
            SelectionControls(w);
        }
        return 0;
    }
    if (message == WM_LBUTTONUP) {
        KillTimer(hwnd, holdTimer);
        RemovePropW(hwnd, L"MnoteHoldIndex");
        bool held = RemovePropW(hwnd, L"MnoteHeld") != nullptr;
        auto result = DefSubclassProc(hwnd, message, wp, lp);
        auto hit = SendMessageW(hwnd, LB_ITEMFROMPOINT, 0, lp);
        if (w.selecting && !w.busy && !held && !HIWORD(hit) && LOWORD(hit) < w.filtered.size()) {
            auto id = w.filtered[LOWORD(hit)].id;
            if (!w.selectedIds.erase(id) && w.selectedIds.size() < 100)
                w.selectedIds.insert(id);
            SelectionControls(w);
        }
        return result;
    }
    if (message == WM_KEYDOWN && wp == VK_SPACE && w.selecting && !w.busy) {
        auto index = SendMessageW(hwnd, LB_GETCURSEL, 0, 0);
        if (index >= 0 && static_cast<std::size_t>(index) < w.filtered.size()) {
            auto id = w.filtered[index].id;
            if (!w.selectedIds.erase(id) && w.selectedIds.size() < 100)
                w.selectedIds.insert(id);
            SelectionControls(w);
        }
        return 0;
    }
    if (message == WM_NCDESTROY) {
        KillTimer(hwnd, holdTimer);
        RemovePropW(hwnd, L"MnoteHoldIndex");
        RemovePropW(hwnd, L"MnoteHoldPoint");
        RemovePropW(hwnd, L"MnoteHeld");
    }
    return DefSubclassProc(hwnd, message, wp, lp);
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
            chats->prune(scope);
            std::map<std::string,int> counts;
            for(const auto &c:chats->list(scope)) if(Ai::Store::chatted(c)) ++counts[c.data.at("record_id").get<std::string>()];
            auto account = library->account();
            Post([hwnd, serial, scope, rows = std::move(rows), counts, account]() mutable {
                auto w = Find(hwnd, serial);
                if (!w || library->account().scope != scope)
                    return;
                bool changed = w->scope != scope;
                w->scope = scope;
                w->records = std::move(rows);
                w->chatCounts=counts;
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
                    for(const auto &entry:windows)if(entry.second->scope!=scope&&
                        (entry.second->mode==Mode::Chat||entry.second->mode==Mode::ChatHistory||entry.second->mode==Mode::ChatModels)){
                        if(entry.second->chatStop)entry.second->chatStop->store(true);
                        ShowWindow(entry.first,SW_HIDE);PostMessageW(entry.first,WM_CLOSE,0,0);
                    }
                    w->thumbnails.clear();
                    w->shareLoading.clear();
                    w->shareErrors.clear();
                    w->selecting = false;
                    w->selectedIds.clear();
                    Set(*w, Search, L"");
                    w->showTrash = false;
                    SendMessageW(ControlOf(*w, KindFilter), CB_SETCURSEL, 0, 0);
                    SendMessageW(ControlOf(*w, ChatFilter), CB_SETCURSEL, 0, 0);
                }
                Set(*w, AccountButton, account.signedIn() ? Wide(account.username) : L"登录账号");
                Set(*w, Subtitle,
                    account.signedIn() ? L"按账号自动同步" : L"本机记录");
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
std::shared_ptr<Gdiplus::Bitmap> ShareBitmap(const std::string &bytes, bool thumbnail = false) {
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes.size());
    if (!memory)
        throw std::runtime_error("image_unavailable");
    auto data = GlobalLock(memory);
    if (!data) {
        GlobalFree(memory);
        throw std::runtime_error("image_unavailable");
    }
    memcpy(data, bytes.data(), bytes.size());
    GlobalUnlock(memory);
    IStream *raw = nullptr;
    if (FAILED(CreateStreamOnHGlobal(memory, TRUE, &raw))) {
        GlobalFree(memory);
        throw std::runtime_error("image_unavailable");
    }
    std::unique_ptr<IStream, void (*)(IStream *)> stream(raw, [](IStream *p) { p->Release(); });
    std::unique_ptr<Gdiplus::Bitmap> decoded(Gdiplus::Bitmap::FromStream(stream.get()));
    if (!decoded || decoded->GetLastStatus() != Gdiplus::Ok || !decoded->GetWidth() ||
        !decoded->GetHeight() ||
        static_cast<std::uint64_t>(decoded->GetWidth()) * decoded->GetHeight() > 32000000)
        throw std::runtime_error("image_unavailable");
    double scale =
        thumbnail ? std::min(600.0 / decoded->GetWidth(), 180.0 / decoded->GetHeight()) : 1.0;
    auto result = std::make_shared<Gdiplus::Bitmap>(std::max(1, int(decoded->GetWidth() * scale)),
                                                    std::max(1, int(decoded->GetHeight() * scale)),
                                                    PixelFormat32bppARGB);
    Gdiplus::Graphics graphics(result.get());
    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
    if (result->GetLastStatus() != Gdiplus::Ok ||
        graphics.DrawImage(decoded.get(), 0, 0, result->GetWidth(), result->GetHeight()) !=
            Gdiplus::Ok)
        throw std::runtime_error("image_unavailable");
    return result;
}
void LoadShareImage(Window &w) {
    if (w.busy)
        return;
    auto index = SendMessageW(ControlOf(w, ImageRole), CB_GETCURSEL, 0, 0);
    if (index < 0 || static_cast<std::size_t>(index) >= w.shareImages.size())
        return;
    auto asset = w.shareImages.at(static_cast<std::size_t>(index));
    auto scope = w.scope, id = w.shareId;
    auto bitmap = std::make_shared<std::shared_ptr<Gdiplus::Bitmap>>();
    w.preview.reset();
    w.zoom = 1;
    w.pan = {};
    InvalidateRect(w.hwnd, nullptr, TRUE);
    SetWindowTextW(w.hwnd, L"Mnote · 正在读取历史分享图片…");
    EnableWindow(ControlOf(w, ImageRole), FALSE);
    Run(
        w,
        [scope, id, asset, bitmap] {
            auto bytes = library->markdownExportImage(scope, id, asset);
            *bitmap = ShareBitmap(bytes);
        },
        [bitmap, scope](Window &form) {
            EnableWindow(ControlOf(form, ImageRole), TRUE);
            if (scope != library->account().scope) {
                PostMessageW(form.hwnd, WM_CLOSE, 0, 0);
                return;
            }
            form.preview = *bitmap;
            SetWindowTextW(form.hwnd, L"Mnote · 历史分享图片（当时的快照）");
            InvalidateRect(form.hwnd, nullptr, TRUE);
        });
}
void OpenShareImages(Window &from, const std::string &id, const Json &images) {
    if (from.scope != library->account().scope)
        return;
    if (images.empty()) {
        StatusText(from, L"这次分享只有文字，没有保存图片。");
        return;
    }
    auto &w = Create(Mode::Image, L"Mnote · 历史分享图片", 1000, 760);
    w.shareId = id;
    w.shareImages = images;
    Add(w, ImageRole, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 24, 14, 240, 240);
    for (const auto &asset : images) {
        auto role = asset.at("role").get<std::string>();
        auto label = L"记录 " + std::to_wstring(asset.at("record_index").get<int>()) + L" · " +
                     (role == "context"    ? L"完整页面"
                      : role == "original" ? L"圈选原图"
                                           : L"批注图");
        SendMessageW(ControlOf(w, ImageRole), CB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(label.c_str()));
    }
    SendMessageW(ControlOf(w, ImageRole), CB_SETCURSEL, 0, 0);
    Button(w, ZoomReset, L"适应窗口", 284, 14, 148);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
    LoadShareImage(w);
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
    if(w.editing){w.placements.back().stretch=false;w.placements.back().w=420;Button(w,ChatButton,L"本条 AI 对话",490,24,180);}
    Label(w, Subtitle,
          Field(source, "app_name").empty()
              ? L"只记录你选择保留的内容"
              : L"来源：" + Field(source, "app_name") + L"  ·  " + Field(source, "window_title"),
          76);
    Add(w, Preview, L"BUTTON", L"截图预览 · 点击放大", BS_OWNERDRAW | WS_TABSTOP, 28, 112, 660, 188,
        true);
    LoadPreview(w);
    if (!w.preview)
        w.placements.back().h = 0;
    int y = w.preview ? 320 : 124;
    Label(w, 2400, L"我的想法", y);
    Edit(w, Note, Field(data, "comment"), y + 30, 210, 20000);
    y += 258;
    Button(w, MoreOptions, L"更多选项 · 摘录、标签与上下文", 28, y, 340);
    y += 58;
    int moreStart = y;
    w.moreOptions = w.editing;
    Label(w, 2401, L"标签 · 选择已有，也可输入新标签", y);
    Edit(w, TagsInput, TagText(data), y + 30, 38, 4096, false);
    auto picker =
        Add(w, TagsPicker, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 28, y + 76, 380, 220);
    SendMessageW(picker, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"正在读取已有标签…"));
    SendMessageW(picker, CB_SETCURSEL, 0, 0);
    EnableWindow(picker, FALSE);
    auto editorHwnd = w.hwnd;
    auto editorSerial = w.serial;
    auto tagScope = w.scope;
    Enqueue([editorHwnd, editorSerial, tagScope] {
        try {
            auto tags = library->existingTags(tagScope);
            Post([editorHwnd, editorSerial, tagScope, tags] {
                auto form = Find(editorHwnd, editorSerial);
                if (!form || tagScope != library->account().scope)
                    return;
                auto control = ControlOf(*form, TagsPicker);
                SendMessageW(control, CB_RESETCONTENT, 0, 0);
                SendMessageW(control, CB_ADDSTRING, 0,
                             reinterpret_cast<LPARAM>(tags.empty() ? L"还没有标签，可在上方新建"
                                                                   : L"选择已有标签，点击即添加"));
                for (const auto &tag : tags) {
                    auto value = Wide(tag.get<std::string>());
                    SendMessageW(control, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value.c_str()));
                }
                SendMessageW(control, CB_SETCURSEL, 0, 0);
                EnableWindow(control, !form->busy && !tags.empty());
            });
        } catch (const std::exception &) {
            Post([editorHwnd, editorSerial] {
                if (auto form = Find(editorHwnd, editorSerial)) {
                    SetWindowTextW(ControlOf(*form, TagsPicker), L"读取失败，仍可手动输入");
                }
            });
        }
    });
    y += 150;
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
    for (auto &p : w.placements)
        if (p.y >= moreStart)
            p.y += 10000;
    if (!w.moreOptions)
        w.extent = moreStart;
    Button(w, Save, L"保存记录", 28, 0, 148);
    Button(w, SaveChat, L"保存并聊天", 190, 0, 142);
    Button(w, Cancel, L"取消", 348, 0, 86);
    if (w.editing)
        Button(w, Delete, L"删除记录", 450, 0, 118);
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
void SaveEditor(Window &w, bool startChat = false) {
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
    auto saved = std::make_shared<Record>();
    bool retain = full && (ControlOf(w, Full) ? Checked(w, Full)
                                              : data.value("evidence", Json::object())
                                                    .value("context", Json::object())
                                                    .value("image", Json::object())
                                                    .value("retained", false));
    Run(
        w,
        [data, assets, scope, baseline, full, retain, staging, saved]() mutable {
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
            *saved=library->save(scope, data, assets, baseline);
        },
        [saved,startChat](Window &form) {
            form.dirty = false;
            notify(L"记录已保存到本机", false);
            DestroyWindow(form.hwnd);
            Load();
            Sync();
            if(startChat) OpenChat(*saved);
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
    Label(w, 2420, L"从 Mnote 服务器获取版本，不使用你的笔记账号或 Token。", 116);
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
void ShareList(Window &w) {
    if (w.busy)
        return;
    ++w.shareGeneration;
    w.shareLoading.clear();
    w.shareErrors.clear();
    w.thumbnails.clear();
    w.shareCacheOrder.clear();
    w.shareCoverLabels.clear();
    auto scope = w.scope;
    auto result = std::make_shared<Json>();
    Run(
        w, [scope, result] { *result = library->markdownExports(scope); },
        [result](Window &form) {
            if (form.scope != library->account().scope) {
                PostMessageW(form.hwnd, WM_CLOSE, 0, 0);
                return;
            }
            form.shares = *result;
            SendMessageW(ControlOf(form, List), LB_RESETCONTENT, 0, 0);
            for (const auto &e : form.shares) {
                auto label = Wide(e.at("created").get<std::string>()) + L" · " +
                             std::to_wstring(e.at("record_count").get<int>()) + L" 条 / " +
                             std::to_wstring(e.at("image_count").get<int>()) + L" 张图";
                SendMessageW(ControlOf(form, List), LB_ADDSTRING, 0,
                             reinterpret_cast<LPARAM>(label.c_str()));
            }
            SendMessageW(ControlOf(form, List), LB_SETCURSEL, 0, 0);
            StatusText(form, form.shares.empty() ? L"没有有效的导出分享。"
                                                 : L"图片预览自动加载 · 双击卡片查看全部图片。");
        });
}
void OpenSettings() {
    for (const auto &entry : windows)
        if (entry.second->mode == Mode::Settings) {
            ShowWindow(entry.first, SW_SHOW);
            SetForegroundWindow(entry.first);
            return;
        }
    auto &w = Create(Mode::Settings, L"Mnote · 设置", 720, 780);
    Label(w, Title, L"设置", 28);
    w.placements.back().h = 44;
    Label(w, Subtitle, L"让记录安心保存，让 Mnote 保持最新。", 86);
    Label(w, 26101, L"我的空间", 142);
    auto account = library->account();
    Button(w, AccountButton,
           L"账号与同步\n" + (account.signedIn()
                                  ? Wide(account.username) + L" · 管理登录、同步和本机导入"
                                  : L"登录后，在不同设备之间同步记录。"),
           28, 178, 260);
    w.placements.back().h = 100;
    w.placements.back().stretch = true;
    Label(w, 26106, L"分享", 302);
    Button(w, ExportShares, L"分享管理\n回看已导出的文字与图片，管理分享链接", 28, 338, 260);
    w.placements.back().h = 100;
    w.placements.back().stretch = true;
    Label(w, 26107, L"思考伙伴", 462);
    Button(w,ChatModels,L"AI 模型配置\n本机密钥、多模型与连接测试",28,498,260);
    w.placements.back().h=88; w.placements.back().stretch=true;
    Button(w,ChatHistory,L"全部 AI 对话\n继续讨论，或回到当时的记录",28,598,260);
    w.placements.back().h=88; w.placements.back().stretch=true;
    Label(w, 26103, L"应用", 710);
    Button(w, Updates,
           L"版本与更新\n当前 " + std::wstring(Updater::Current) + L" · 从 Mnote 服务器获取更新",
           28, 746, 260);
    w.placements.back().h = 100;
    w.placements.back().stretch = true;
    Label(w, 26105, L"不依赖 GitHub，也不需要笔记账号或 Token。", 862);
    w.extent = 904;
    Button(w, Cancel, L"返回", 28, 0, 100);
    Label(w, Status, L"", 0);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
}
Record ChatRecord(const std::string &scope, const std::string &id) {
    if (library->account().scope != scope)
        throw std::runtime_error("account_changed");
    for (const auto &record : library->list(scope))
        if (record.id == id && !record.deleted)
            return record;
    throw std::runtime_error("record_unavailable");
}
void ChatProfileList(Window &w) {
    w.profiles = chats->profiles(w.scope);
    SendMessageW(ControlOf(w, ChatProfile), CB_RESETCONTENT, 0, 0);
    int selected = 0, index = 0;
    auto desired =
        w.chatId.empty() ? Json::object() : w.conversation.data.value("model", Json::object());
    for (const auto &p : w.profiles) {
        auto label = Field(p, "label") + L" · " + Field(p, "model");
        SendMessageW(ControlOf(w, ChatProfile), CB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(label.c_str()));
        if ((!desired.empty() &&
             p.value("model", std::string()) == desired.value("model", std::string()) &&
             p.value("base_url", std::string()) == desired.value("base_url", std::string())) ||
            (desired.empty() && p.value("default", false)))
            selected = index;
        ++index;
    }
    if (w.profiles.empty())
        SendMessageW(ControlOf(w, ChatProfile), CB_ADDSTRING, 0,
                     reinterpret_cast<LPARAM>(L"请先在设置中添加 AI 模型"));
    SendMessageW(ControlOf(w, ChatProfile), CB_SETCURSEL, selected, 0);
}
Json ChosenProfile(Window &w) {
    auto index = SendMessageW(ControlOf(w, ChatProfile), CB_GETCURSEL, 0, 0);
    if (index < 0 || static_cast<std::size_t>(index) >= w.profiles.size())
        throw std::runtime_error("chat_model");
    return w.profiles.at(static_cast<std::size_t>(index));
}
std::vector<Mnote::ChatTranscript::Message> ChatMessages(const Ai::Conversation &c) {
    std::vector<Mnote::ChatTranscript::Message> messages;
    for (const auto &m : c.data.value("messages", Json::array())) {
        auto status = m.value("status", std::string());
        messages.push_back({m.value("id", std::string()), m.value("role", std::string()) == "user",
                            Field(m, "content"), Field(m, "model"), status,
                            status == "failed" ? Ai::Store::error(std::runtime_error(
                                                     m.value("error", std::string("chat_request"))))
                                               : L""});
    }
    return messages;
}
void ChatTranscriptText(Window &w, const Ai::Conversation &c) {
    w.conversation = c;
    Mnote::ChatTranscript::Update(ControlOf(w, ChatTranscript), ChatMessages(c), w.chatReceiving);
    Set(w, Title, Field(c.data, "title").empty() ? L"与 AI 聊聊" : Field(c.data, "title"));
    Layout(w);
}
void OpenChat(const Record &record, const std::string &id) {
    auto scope = library->account().scope;
    ChatRecord(scope, record.id);
    for (const auto &entry : windows)
        if (entry.second->mode == Mode::Chat && entry.second->scope == scope &&
            ((!id.empty() && entry.second->chatId == id) ||
             (id.empty() && entry.second->chatId.empty() &&
              entry.second->chatRecordId == record.id))) {
            ShowWindow(entry.first, SW_SHOW);
            SetForegroundWindow(entry.first);
            return;
        }
    auto &w = Create(Mode::Chat, L"Mnote · 与 AI 聊聊", 880, 900);
    w.record = record;
    w.chatRecordId = record.id;
    w.chatId = id;
    Label(w, Title, L"与 AI 聊聊", 20);
    Label(w, Subtitle, L"本条记录 · 不读取其他笔记，不自动修改记录", 66);
    Button(w, ChatHistory, L"本条会话", 0, 0, 116);
    Button(w, ChatNew, L"新建对话", 0, 0, 116);
    Add(w, ChatProfile, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 28, 112, 500, 220);
    Button(w, ChatMaterials, L"查看本次资料", 0, 0, 158);
    const std::pair<int, const wchar_t *> modules[] = {{ChatThought, L"我的想法"},
                                                       {ChatExcerpt, L"摘录"},
                                                       {ChatOriginal, L"原文"},
                                                       {ChatImages, L"截图"},
                                                       {ChatMetadata, L"来源与标签"}};
    for (const auto &m : modules) {
        Add(w, m.first, L"BUTTON", m.second, BS_AUTOCHECKBOX | WS_TABSTOP, 28, 158, 120, 28);
        SendMessageW(ControlOf(w, m.first), BM_SETCHECK, BST_CHECKED, 0);
    }
    Mnote::ChatTranscript::Create(w.hwnd, ChatTranscript, ChatRetry, w.dpi);
    Edit(w, ChatInput, L"", 610, 110, 100000);
    SendMessageW(ControlOf(w, ChatInput), EM_SETCUEBANNER, TRUE,
                 reinterpret_cast<LPARAM>(L"针对这条记录，想聊些什么？"));
    Button(w, ChatSend, L"发送", 0, 0, 122);
    Button(w, ChatStop, L"停止生成", 0, 0, 122);
    EnableWindow(ControlOf(w, ChatStop), FALSE);
    Button(w, ChatSuggestOne, L"帮我梳理这条记录", 0, 0, 160);
    Button(w, ChatSuggestTwo, L"提出一个不同视角", 0, 0, 170);
    Button(w, ChatSuggestThree, L"转化成下一步行动", 0, 0, 166);
    Button(w, ChatRefresh, L"同步会话", 0, 0, 92);
    Label(w, Status, L"Enter 换行；Ctrl + Enter 发送。回复仅作参考，不会自动执行。", 0);
    w.loading = true;
    if (!id.empty()) {
        w.conversation = chats->get(scope, id);
        Set(w, ChatInput, Wide(w.conversation.draft));
        if (!Ai::Store::snapshotMatches(record, w.conversation.data["snapshot"]))
            Set(w, Subtitle, L"这条记录已更新 · 当前会话仍使用原版本，可基于新版新建对话");
    } else
        Set(w, ChatInput, Wide(chats->scratch(scope, record.id)));
    ChatTranscriptText(w, w.conversation);
    ChatProfileList(w);
    w.loading = false;
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
    SetFocus(ControlOf(w, ChatInput));
}
void LoadChatHistory(Window &w) {
    auto query = Text(ControlOf(w, Search));
    for (auto &c : query)
        c = static_cast<wchar_t>(towlower(c));
    w.conversations.clear();
    SendMessageW(ControlOf(w, List), LB_RESETCONTENT, 0, 0);
    for (const auto &c : chats->list(w.scope, w.chatRecordId)) {
        auto record = ChatRecord(w.scope, c.data.at("record_id").get<std::string>());
        auto summary = Field(record.data, "comment");
        if (summary.empty())
            summary = Field(Object(record.data, "source"), "text");
        auto text = Field(c.data, "title") + L"  ·  " + Field(c.data, "updated_at") + L"  ·  " +
                    summary.substr(0, 40);
        std::wstring searchable = text;
        for (const auto &m : c.data["messages"])
            searchable += Field(m, "content");
        for (auto &ch : searchable)
            ch = static_cast<wchar_t>(towlower(ch));
        if (!query.empty() && searchable.find(query) == std::wstring::npos)
            continue;
        if (c.dirty)
            text += L"  [待同步]";
        SendMessageW(ControlOf(w, List), LB_ADDSTRING, 0, reinterpret_cast<LPARAM>(text.c_str()));
        w.conversations.push_back(c);
    }
    SendMessageW(ControlOf(w, List), LB_SETCURSEL, 0, 0);
    Set(w, ChatName, w.conversations.empty() ? L"" : Field(w.conversations.front().data, "title"));
    StatusText(w, w.conversations.empty() ? L"还没有对话。打开一条记录，开始交流。"
                                          : L"显示 " + std::to_wstring(w.conversations.size()) +
                                                L" 个会话 · 只属于当前账号");
}
void OpenChatHistory(const std::string &record) {
    auto &w =
        Create(Mode::ChatHistory,
               record.empty() ? L"Mnote · 全部 AI 对话" : L"Mnote · 本条记录的对话", 900, 760);
    w.chatRecordId = record;
    Label(w, Title, record.empty() ? L"全部 AI 对话" : L"这一条，我们聊过", 22);
    Label(w, Subtitle, record.empty() ? L"每段讨论，都有它的记录。" : L"只展示本条记录关联的会话",
          74);
    Edit(w, Search, L"", 120, 38, 200, false);
    SendMessageW(ControlOf(w, Search), EM_SETCUEBANNER, TRUE,
                 reinterpret_cast<LPARAM>(L"搜索会话标题、消息和记录摘要"));
    Button(w, ChatRefresh, L"刷新同步", 0, 0, 112);
    Add(w, List, L"LISTBOX", L"",
        LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL | WS_HSCROLL | WS_TABSTOP | WS_BORDER, 28,
        170, 840, 400);
    Edit(w, ChatName, L"", 0, 38, 120, false);
    Button(w, ChatRename, L"重命名", 0, 0, 104);
    Button(w, Delete, L"删除会话", 0, 0, 108);
    Button(w, ChatButton, L"继续对话", 0, 0, 134);
    Button(w, ChatNew, L"新建对话", 0, 0, 132);
    Button(w, Cancel, L"返回", 0, 0, 100);
    Label(w, Status, L"", 0);
    EnableWindow(ControlOf(w, ChatNew), !record.empty());
    LoadChatHistory(w);
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
}
void SetProfileForm(Window &w, const Json &p) {
    w.loading = true;
    w.profileId = p.value("id", std::string());
    Set(w, ModelLabel, Field(p, "label"));
    Set(w, ModelEndpoint, Field(p, "base_url"));
    Set(w, ModelId, Field(p, "model"));
    Set(w, ModelKey, Field(p, "key"));
    Set(w, ModelLimit, std::to_wstring(p.value("context_chars", 180000)));
    SendMessageW(ControlOf(w, ModelVision), BM_SETCHECK,
                 p.value("vision", false) ? BST_CHECKED : BST_UNCHECKED, 0);
    w.loading = false;
    w.dirty = false;
}
Json ProfileForm(Window &w) {
    Json p = {{"id", w.profileId.empty() ? NewId() : w.profileId},
              {"label", Utf8(Text(ControlOf(w, ModelLabel)))},
              {"base_url", Utf8(Text(ControlOf(w, ModelEndpoint)))},
              {"model", Utf8(Text(ControlOf(w, ModelId)))},
              {"key", Utf8(Text(ControlOf(w, ModelKey)))},
              {"vision", Checked(w, ModelVision)},
              {"context_chars", 180000}};
    try {
        p["context_chars"] = std::stoi(Text(ControlOf(w, ModelLimit)));
    } catch (...) {
        throw std::runtime_error("chat_context");
    }
    if (p["context_chars"] < 1000 || p["context_chars"] > 500000)
        throw std::runtime_error("chat_context");
    Ai::Store::validateProfile(p);
    return p;
}
void OpenChatModels() {
    for (const auto &item : windows)
        if (item.second->mode == Mode::ChatModels && item.second->scope == Scope()) {
            ShowWindow(item.first, SW_SHOW);
            SetForegroundWindow(item.first);
            return;
        }
    auto &w = Create(Mode::ChatModels, L"Mnote · AI 模型配置", 760, 880);
    Label(w, Title, L"你的思考伙伴", 24);
    w.placements.back().h = 44;
    Label(w, Subtitle, L"OpenAI-compatible · 密钥仅本机保存，不进入聊天同步或导出", 78);
    Add(w, ChatProfile, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 28, 124, 474, 220);
    Button(w, ModelNew, L"添加配置", 518, 124, 146);
    Label(w, 29101, L"配置名称", 178);
    Edit(w, ModelLabel, L"", 208, 38, 80, false);
    Label(w, 29102, L"API 基址 · HTTPS，例如 https://api.example.com/v1", 266);
    Edit(w, ModelEndpoint, L"", 296, 38, 2048, false);
    Label(w, 29103, L"模型 ID", 354);
    Edit(w, ModelId, L"", 384, 38, 200, false);
    Label(w, 29104, L"API Key · Windows DPAPI 加密，不上传到 Mnote", 442);
    auto key = Edit(w, ModelKey, L"", 472, 38, 4096, false);
    SendMessageW(key, EM_SETPASSWORDCHAR, L'●', 0);
    Add(w, ModelVision, L"BUTTON", L"这个模型支持图片输入（请单独测试图片能力）",
        BS_AUTOCHECKBOX | WS_TABSTOP, 28, 532, 630, 32);
    Label(w, 29105, L"输入字符预算 · 超过时明确阻止，不静默截断（1000—500000）", 590);
    Edit(w, ModelLimit, L"180000", 620, 38, 6, false);
    Button(w, ModelTest, L"测试文字与流式", 28, 682, 178);
    Button(w, ModelImageTest, L"测试图片能力", 220, 682, 166);
    Button(w, ModelDelete, L"删除此配置", 400, 682, 150);
    Label(w, 29106, L"测试仅发送固定合成内容，可能产生少量服务商费用。", 738);
    Label(w, 29107, L"兼容接口能力因服务商而异；聊天只在你确认发送后调用。", 774);
    Button(w, Save, L"保存并设为默认", 28, 0, 192);
    Button(w, Cancel, L"返回", 236, 0, 100);
    Label(w, Status, L"模型配置按当前账号在本机保存。其他设备需要单独填写密钥。", 0);
    w.extent = 826;
    ChatProfileList(w);
    SetProfileForm(w, w.profiles.empty() ? Json::object() : ChosenProfile(w));
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
}
void ChatMaterialsPage(Window &w) {
    Json data;
    if (w.chatId.empty()) {
        data = {{"thought", w.record.data.value("comment", Json(""))},
                {"excerpt", Object(w.record.data, "source").value("text", Json(""))},
                {"original", Utf8(OriginalText(w.record.data))},
                {"source", Object(w.record.data, "source")},
                {"note", "以发送时勾选的模块为准。截图将缩放为最长边 1600 "
                         "像素以内后发送，不生成公开图片链接。"}};
    } else {
        data = w.conversation.data.at("snapshot");
        if (data.contains("images"))
            for (auto &img : data["images"])
                img["data_base64"] = "[已保留实际图片数据，不在文字页显示]";
        if (!w.conversation.conflict.empty())
            data["本机冲突内容副本_未发给模型"] =
                w.conversation.conflict.value("messages", Json::array());
    }
    auto &view = Create(Mode::ShareText, L"Mnote · 本次 AI 资料", 800, 780);
    Label(view, Title, L"AI 可以使用的记录资料", 20);
    auto text = Edit(view, Original, Wide(data.dump(2)), 82, 540, 400000);
    SendMessageW(text, EM_SETREADONLY, TRUE, 0);
    Button(view, Cancel, L"关闭", 28, 0, 100);
    Layout(view);
    ShowWindow(view.hwnd, SW_SHOW);
}
void SendChat(Window &w) {
    if (w.chatReceiving)
        return;
    auto input = Utf8(Text(ControlOf(w, ChatInput)));
    if (input.empty()) {
        SetFocus(ControlOf(w, ChatInput));
        return;
    }
    if (w.profiles.empty()) {
        OpenChatModels();
        StatusText(w, L"添加模型后返回此页即可发送，记录已经保存。");
        return;
    }
    auto p = ChosenProfile(w);
    auto record = ChatRecord(w.scope, w.chatRecordId);
    Json modules = Json::array();
    if (w.chatId.empty()) {
        for (const auto &item :
             std::vector<std::pair<int, const char *>>{{ChatThought, "thought"},
                                                       {ChatExcerpt, "excerpt"},
                                                       {ChatOriginal, "original"},
                                                       {ChatImages, "images"},
                                                       {ChatMetadata, "metadata"}})
            if (Checked(w, item.first))
                modules.push_back(item.second);
        if (modules.empty()) {
            StatusText(w, L"至少选择一个资料模块。");
            return;
        }
        if (Checked(w, ChatImages) && !record.assets.empty() && !p.value("vision", false)) {
            StatusText(w, Ai::Store::error(std::runtime_error("chat_vision")));
            return;
        }
    } else
        modules = w.conversation.data.at("snapshot").at("modules");
    std::wstring disclosure =
        L"将本条记录中所选资料、实际截图及本会话历史发送到：\n" + Field(p, "base_url") +
        L"\n模型：" + Field(p, "model") + L"\n\n所选模块：" + Wide(modules.dump()) +
        L"\n\n仅授权本次记录对话，不改变记录的其他 AI/MCP "
        L"权限。可能产生模型费用；登录账号后聊天和资料快照会随账号同步。服务商可能独立保留数据。";
    if (record.data.value("ai_access", std::string("local_only")) == "deny" ||
        record.data.value("ai_access", std::string("local_only")) == "local_only")
        disclosure += L"\n\n本条当前禁止远程 AI 自动读取。是否明确给予这一会话单独的远程发送许可？";
    if (MessageBoxW(w.hwnd, disclosure.c_str(), L"Mnote · 确认资料与模型",
                    MB_YESNO | MB_ICONQUESTION) != IDYES)
        return;
    if (w.chatId.empty()) {
        w.conversation = chats->create(w.scope, record, modules, p);
        w.chatId = w.conversation.data.at("id");
        chats->scratch(w.scope, record.id, "");
        Layout(w);
    }
    auto consent = Hash(record.data.value("ai_access", std::string()) + "\n" +
                        Library::fingerprint(record) + "\n" + Ai::Store::sanitizeModel(p).dump());
    chats->draft(w.scope, w.chatId, input);
    w.chatStop = std::make_shared<std::atomic_bool>(false);
    w.chatReceiving = true;
    EnableWindow(ControlOf(w, ChatSend), FALSE);
    EnableWindow(ControlOf(w, ChatStop), TRUE);
    EnableWindow(ControlOf(w, ChatProfile), FALSE);
    EnableWindow(ControlOf(w, ChatInput), FALSE);
    StatusText(w, L"先保存消息并校验同步，再向你选择的模型发送…");
    auto scope = w.scope, id = w.chatId;
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    auto stop = w.chatStop;
    chatWorkers.emplace_back([scope, id, p, input, consent, hwnd, serial, stop] {
        try {
            chats->send(scope, id, input, p, consent, true, *stop,
                        [hwnd, serial, scope](const Ai::Conversation &c) {
                            Post([hwnd, serial, scope, c] {
                                if (auto form = Find(hwnd, serial); form && Scope() == scope) {
                                    ChatTranscriptText(*form, c);
                                    if (c.draft.empty() && form->chatReceiving) {
                                        form->loading = true;
                                        Set(*form, ChatInput, L"");
                                        form->loading = false;
                                    }
                                }
                            });
                        });
            Post([hwnd, serial, scope] {
                if (auto form = Find(hwnd, serial); form && Scope() == scope) {
                    form->chatReceiving = false;
                    ChatTranscriptText(*form, form->conversation);
                    EnableWindow(ControlOf(*form, ChatSend), TRUE);
                    EnableWindow(ControlOf(*form, ChatStop), FALSE);
                    EnableWindow(ControlOf(*form, ChatProfile), TRUE);
                    EnableWindow(ControlOf(*form, ChatInput), TRUE);
                    StatusText(*form, L"已保存会话。可以继续提问，或新开一个讨论方向。");
                }
                Load();
            });
        } catch (const std::exception &e) {
            auto message = Ai::Store::error(e);
            Post([hwnd, serial, scope, message] {
                if (auto form = Find(hwnd, serial); form && Scope() == scope) {
                    form->chatReceiving = false;
                    EnableWindow(ControlOf(*form, ChatSend), TRUE);
                    EnableWindow(ControlOf(*form, ChatStop), FALSE);
                    EnableWindow(ControlOf(*form, ChatProfile), TRUE);
                    EnableWindow(ControlOf(*form, ChatInput), TRUE);
                    StatusText(*form, message);
                    try {
                        auto c = chats->get(scope, form->chatId);
                        ChatTranscriptText(*form, c);
                        form->loading = true;
                        Set(*form, ChatInput, Wide(c.draft));
                        form->loading = false;
                    } catch (...) {
                    }
                }
                Load();
            });
        }
    });
}
void ChatCommand(Window &w, int id, int event) {
    if (w.scope != Scope())
        throw std::runtime_error("account_changed");
    if (id == Cancel) {
        PostMessageW(w.hwnd, WM_CLOSE, 0, 0);
        return;
    }
    if (w.mode == Mode::Chat) {
        if (id == ChatSend)
            SendChat(w);
        else if (id == ChatStop && w.chatStop) {
            w.chatStop->store(true);
            StatusText(w, L"正在停止，已收到的内容会保留…");
        } else if (id == ChatHistory)
            OpenChatHistory(w.chatRecordId);
        else if (id == ChatNew)
            OpenChat(ChatRecord(w.scope, w.chatRecordId));
        else if (id == ChatMaterials)
            ChatMaterialsPage(w);
        else if (id == ChatRefresh && !w.chatReceiving && !w.chatId.empty()) {
            auto scope = w.scope, cid = w.chatId;
            Run(
                w, [scope, cid] { chats->resolve(scope, cid); },
                [](Window &form) {
                    auto c = chats->get(form.scope, form.chatId);
                    ChatTranscriptText(form, c);
                    Set(form, ChatInput, Wide(c.draft));
                    StatusText(
                        form,
                        L"已读取云端历史。本机冲突副本（如有）保留在资料页，不会发送给模型。");
                });
        } else if (id == ChatRetry && !w.busy && !w.chatReceiving && !w.chatId.empty()) {
            auto messages = ChatMessages(w.conversation);
            if (messages.empty() ||
                !Mnote::ChatTranscript::CanRetry(messages, messages.size() - 1, false))
                return;
            if (!Text(ControlOf(w, ChatInput)).empty() &&
                MessageBoxW(w.hwnd, L"用上一问替换当前输入框中的草稿？", L"Mnote · 用上一问继续",
                            MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            for (auto it = w.conversation.data["messages"].rbegin();
                 it != w.conversation.data["messages"].rend(); ++it)
                if (it->value("role", std::string()) == "user") {
                    Set(w, ChatInput, Field(*it, "content"));
                    SetFocus(ControlOf(w, ChatInput));
                    StatusText(w, L"上一问已填入输入框；检查后点击发送。已有回复不会删除。 ");
                    break;
                }
        } else if (id == ChatInput && event == EN_CHANGE && !w.loading)
            SetTimer(w.hwnd, 7, 600, nullptr);
        else if (id == ChatSuggestOne || id == ChatSuggestTwo || id == ChatSuggestThree)
            Set(w, ChatInput,
                id == ChatSuggestOne
                    ? L"帮我梳理这条记录，区分我的想法、引用的内容和需要验证的问题。"
                : id == ChatSuggestTwo
                    ? L"针对我的想法，提出一个有价值的不同视角，不要只附和我。"
                    : L"这条记录能转化成什么具体行动？请帮我选一个很小的下一步。");
        return;
    }
    if (w.mode == Mode::ChatModels) {
        if (w.busy)
            return;
        if (id == ChatProfile && event == CBN_SELCHANGE)
            SetProfileForm(w, ChosenProfile(w));
        else if (id == ModelNew)
            SetProfileForm(w, Json::object());
        else if (id == Save) {
            auto p = ProfileForm(w);
            chats->saveProfile(w.scope, p);
            w.profileId = p["id"];
            ChatProfileList(w);
            w.dirty = false;
            StatusText(w, L"配置已安全保存为默认模型。已有会话不会自动发送。");
        } else if (id == ModelDelete && !w.profileId.empty() &&
                   MessageBoxW(w.hwnd, L"仅删除本机模型配置和密钥，不删除聊天历史？", L"Mnote",
                               MB_YESNO | MB_ICONQUESTION) == IDYES) {
            chats->eraseProfile(w.scope, w.profileId);
            ChatProfileList(w);
            SetProfileForm(w, w.profiles.empty() ? Json::object() : ChosenProfile(w));
        } else if (id == ModelTest || id == ModelImageTest) {
            auto p = ProfileForm(w);
            if (MessageBoxW(
                    w.hwnd,
                    L"向所填服务商发送固定合成测试内容？不会发送你的记录，可能产生少量费用。",
                    L"Mnote · 连接测试", MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            auto scope = w.scope;
            auto image = id == ModelImageTest;
            auto stop = std::make_shared<std::atomic_bool>(false);
            w.chatStop = stop;
            Run(
                w, [p, scope, image, stop] { chats->test(scope, p, image, *stop); },
                [image](Window &form) {
                    StatusText(form,
                               image
                                   ? L"图片输入与流式回复测试通过。请按测试结果勾选图片能力并保存。"
                                   : L"文字输入与流式回复测试通过。图片能力请单独测试。");
                });
        }
        return;
    }
    if (w.mode == Mode::ChatHistory) {
        if (id == Search && event == EN_CHANGE) {
            LoadChatHistory(w);
            return;
        }
        if (id == ChatRefresh) {
            auto scope = w.scope;
            Run(
                w,
                [scope] {
                    if (library->account().signedIn())
                        library->sync();
                    chats->sync(scope);
                },
                [](Window &form) {
                    LoadChatHistory(form);
                    Load();
                });
            return;
        }
        if (id == ChatNew && !w.chatRecordId.empty()) {
            OpenChat(ChatRecord(w.scope, w.chatRecordId));
            return;
        }
        auto index = SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0);
        if (index < 0 || static_cast<std::size_t>(index) >= w.conversations.size())
            return;
        auto c = w.conversations[static_cast<std::size_t>(index)];
        auto cid = c.data.at("id").get<std::string>();
        if (id == List && event == LBN_SELCHANGE)
            Set(w, ChatName, Field(c.data, "title"));
        else if (id == ChatButton || (id == List && event == LBN_DBLCLK))
            OpenChat(ChatRecord(w.scope, c.data.at("record_id").get<std::string>()), cid);
        else if (id == ChatRename) {
            chats->rename(w.scope, cid, Utf8(Text(ControlOf(w, ChatName))));
            LoadChatHistory(w);
            Sync();
        } else if (id == Delete &&
                   MessageBoxW(
                       w.hwnd,
                       L"删除这段对话和它的资料快照？原记录不受影响，删除会同步到其他设备。",
                       L"Mnote · 删除对话", MB_YESNO | MB_ICONQUESTION) == IDYES) {
            chats->erase(w.scope, cid);
            LoadChatHistory(w);
            Load();
            Sync();
        }
    }
}
void OpenMarkdown(Window &source, bool shares = false) {
    if (source.scope != library->account().scope) {
        StatusText(source, L"账号已变化，请关闭此页并在首页重新选择。");
        return;
    }
    if (!library->account().signedIn()) {
        StatusText(source, shares ? L"请先登录后查看分享管理。"
                                  : L"请先登录并刷新同步，再导出当前账号的记录。");
        return;
    }
    if (source.showTrash && !shares) {
        StatusText(source, L"请返回正常记录列表后选择导出。");
        return;
    }
    auto &w = Create(shares ? Mode::Shares : Mode::Markdown,
                     shares ? L"Mnote · 导出链接管理" : L"Mnote · 导出给 AI", 800, 720);
    Label(w, Title, shares ? L"分享管理" : L"带走一些灵感", 24);
    Label(w, Subtitle,
          shares ? L"留住分享时的画面 · 打开即预览，双击查看全部图片。\r\n"
                   L"撤销只影响分享链接，原始记录保留；已下载的副本无法收回。"
                 : L"把选中的想法与上下文，整理成一份 Markdown。\r\n"
                   L"点击卡片勾选 · 最多 100 条已同步记录 · 图片经确认分享，可撤销。",
          76);
    Add(w, List, L"LISTBOX", L"",
        WS_TABSTOP | WS_VSCROLL | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | LBS_OWNERDRAWFIXED |
            LBS_HASSTRINGS | (shares ? 0 : LBS_MULTIPLESEL),
        28, 216, 700, 340);
    if (!shares) {
        for (const auto &r : source.filtered)
            if (Library::exportable(r) && (!source.selecting || source.selectedIds.count(r.id)))
                w.records.push_back(r);
        for (const auto &r : w.records) {
            auto body = r.data.value("comment", std::string());
            if (body.empty())
                body = r.data.value("source", Json::object()).value("text", std::string());
            auto label = Wide(r.data.value("created_at", std::string())) + L"  " + TagText(r.data) +
                         L"  " + Wide(body);
            std::replace(label.begin(), label.end(), L'\n', L' ');
            std::replace(label.begin(), label.end(), L'\r', L' ');
            if (label.size() > 220)
                label.resize(220);
            SendMessageW(ControlOf(w, List), LB_ADDSTRING, 0,
                         reinterpret_cast<LPARAM>(label.c_str()));
        }
        Button(w, SelectAll, L"全选可用", 28, 164, 132);
        Button(w, ClearSelection, L"清空", 172, 164, 100);
        Button(w, ExportPreview, L"查看当前截图", 288, 164, 160);
        if (source.selecting)
            SendMessageW(ControlOf(w, List), LB_SETSEL, TRUE, -1);
    } else {
        Button(w, ExportShares, L"刷新分享列表", 0, 164, 216);
        Button(w, ExportPreview, L"查看分享图片", 28, 164, 160);
        Button(w, ShareText, L"展开全部文字", 204, 164, 150);
    }
    Button(w, Save, shares ? L"撤销选中导出链接" : L"导出 Markdown", 0, 0, 200);
    Button(w, Cancel, L"关闭", 28, 0, 120);
    Label(w, Status, L"已选择 0 条 · 可导出 " + std::to_wstring(w.records.size()) + L" 条", 0);
    if (!shares)
        EnableWindow(ControlOf(w, Save), source.selecting && !w.records.empty());
    Layout(w);
    ShowWindow(w.hwnd, SW_SHOW);
    SetForegroundWindow(w.hwnd);
    if (shares)
        ShareList(w);
    else {
        if (source.selecting) {
            Set(w, Save, L"导出 " + std::to_wstring(w.records.size()) + L" 条记录");
            StatusText(w, L"已选 " + std::to_wstring(w.records.size()) +
                              L" 条；仅显示所选且可导出的记录。");
        }
        auto hwnd = w.hwnd;
        auto serial = w.serial;
        auto scope = w.scope;
        auto records = w.records;
        Enqueue([hwnd, serial, scope, records] {
            // Decode a bounded number of thumbnails off the UI thread. Full image is opened
            // explicitly.
            int count = 0;
            for (const auto &record : records) {
                if (++count > 100)
                    break;
                if (library->account().scope != scope)
                    break;
                fs::path path;
                for (auto role : {"annotated", "original", "context"}) {
                    auto it = record.assets.find(role);
                    if (it != record.assets.end()) {
                        path = it->second;
                        break;
                    }
                }
                if (path.empty())
                    continue;
                std::unique_ptr<Gdiplus::Bitmap> decoded(Gdiplus::Bitmap::FromFile(path.c_str()));
                if (!decoded || decoded->GetLastStatus() != Gdiplus::Ok || !decoded->GetWidth() ||
                    !decoded->GetHeight() ||
                    static_cast<std::uint64_t>(decoded->GetWidth()) * decoded->GetHeight() >
                        32000000)
                    continue;
                double ratio = std::min(160.0 / decoded->GetWidth(), 120.0 / decoded->GetHeight());
                auto thumb = std::make_shared<Gdiplus::Bitmap>(
                    std::max(1, int(decoded->GetWidth() * ratio)),
                    std::max(1, int(decoded->GetHeight() * ratio)), PixelFormat32bppARGB);
                {
                    Gdiplus::Graphics graphics(thumb.get());
                    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
                    graphics.DrawImage(decoded.get(), 0, 0, thumb->GetWidth(), thumb->GetHeight());
                }
                Post([hwnd, serial, scope, id = record.id, thumb] {
                    auto form = Find(hwnd, serial);
                    if (!form || library->account().scope != scope)
                        return;
                    form->thumbnails[id] = thumb;
                    InvalidateRect(ControlOf(*form, List), nullptr, FALSE);
                });
            }
        });
    }
}
void ExportRecords(Window &w, const std::vector<Record> &records);
void MarkdownCommand(Window &w, int id) {
    if (w.mode == Mode::Shares) {
        if (id == ShareText) {
            auto index = SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0);
            if (index < 0 || static_cast<std::size_t>(index) >= w.shares.size())
                return;
            auto share = w.shares.at(static_cast<std::size_t>(index));
            if (!share.value("text_available", false)) {
                StatusText(w,
                           L"旧版分享未保存文字快照，无法还原当时的文字。重新导出后可保留文字。");
                return;
            }
            auto scope = w.scope, exportId = share.at("id").get<std::string>();
            auto text = std::make_shared<std::string>();
            Run(
                w,
                [scope, exportId, text] { *text = library->markdownExportText(scope, exportId); },
                [scope, exportId, text](Window &) {
                    if (scope != library->account().scope)
                        return;
                    auto &page = Create(Mode::ShareText, L"Mnote · 分享时的文字", 820, 800);
                    page.shareId = exportId;
                    Label(page, Title, L"分享时的文字 · 独立快照", 20);
                    Edit(page, Original, Wide(*text), 82, 550, 8 * 1024 * 1024);
                    SendMessageW(ControlOf(page, Original), EM_SETREADONLY, TRUE, 0);
                    Button(page, Cancel, L"返回", 28, 0, 120);
                    Layout(page);
                    ShowWindow(page.hwnd, SW_SHOW);
                    SetForegroundWindow(page.hwnd);
                });
            return;
        }
        if (id == ExportShares)
            ShareList(w);
        if (id == ExportPreview) {
            auto index = SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0);
            if (index < 0 || static_cast<std::size_t>(index) >= w.shares.size())
                return;
            std::string exportId = w.shares.at(static_cast<std::size_t>(index)).at("id");
            auto scope = w.scope;
            auto images = std::make_shared<Json>();
            Run(
                w,
                [scope, exportId, images] {
                    *images = library->markdownExportImages(scope, exportId);
                },
                [exportId, images](Window &form) { OpenShareImages(form, exportId, *images); });
            return;
        }
        if (id != Save)
            return;
        auto index = SendMessageW(ControlOf(w, List), LB_GETCURSEL, 0, 0);
        if (index < 0 || static_cast<std::size_t>(index) >= w.shares.size())
            return;
        if (MessageBoxW(w.hwnd,
                        L"撤销本次导出的全部图片链接？原始记录不删除。已下载的副本无法收回。",
                        L"Mnote · 撤销链接", MB_YESNO | MB_ICONQUESTION) != IDYES)
            return;
        std::string exportId = w.shares.at(static_cast<std::size_t>(index)).at("id");
        auto scope = w.scope;
        Run(
            w, [scope, exportId] { library->revokeMarkdownExport(scope, exportId); },
            [](Window &form) {
                notify(L"图片链接已撤销，原记录保留。", false);
                ShareList(form);
            });
        return;
    }
    if (id == ExportPreview) {
        if (w.scope != library->account().scope) {
            StatusText(w, L"账号已切换，请重新打开导出页面。");
            return;
        }
        auto index = SendMessageW(ControlOf(w, List), LB_GETCARETINDEX, 0, 0);
        if (index < 0 || static_cast<std::size_t>(index) >= w.records.size())
            return;
        auto record = w.records.at(static_cast<std::size_t>(index));
        if (record.assets.empty()) {
            StatusText(w, L"这条记录没有保存截图。");
            return;
        }
        w.record = record;
        w.draft.data = record.data;
        w.draft.assets = record.assets;
        OpenImage(w);
        return;
    }
    auto list = ControlOf(w, List);
    if (id == SelectAll) {
        if (w.records.size() > 100) {
            StatusText(w, L"超过 100 条，请缩小首页筛选范围或手动多选。");
            return;
        }
        SendMessageW(list, LB_SETSEL, TRUE, -1);
    }
    if (id == ClearSelection)
        SendMessageW(list, LB_SETSEL, FALSE, -1);
    int count = static_cast<int>(SendMessageW(list, LB_GETSELCOUNT, 0, 0));
    EnableWindow(ControlOf(w, Save), count > 0 && count <= 100);
    Set(w, Save, count > 0 ? L"导出 " + std::to_wstring(count) + L" 条记录" : L"选择要导出的记录");
    StatusText(w, L"已选择 " + std::to_wstring(count) + L" / " + std::to_wstring(w.records.size()) +
                      L" 条");
    if (id != Save)
        return;
    if (count < 1 || count > 100) {
        StatusText(w, L"请选择 1 至 100 条记录。");
        return;
    }
    std::vector<int> indices(static_cast<std::size_t>(count));
    SendMessageW(list, LB_GETSELITEMS, static_cast<WPARAM>(count),
                 reinterpret_cast<LPARAM>(indices.data()));
    std::vector<Record> records;
    for (int index : indices)
        records.push_back(w.records.at(static_cast<std::size_t>(index)));
    ExportRecords(w, records);
}
void ExportRecords(Window &w, const std::vector<Record> &records) {
    if (w.scope != library->account().scope || !library->account().signedIn() || records.empty() ||
        records.size() > 100 || std::any_of(records.begin(), records.end(), [](const Record &r) {
            return !Library::exportable(r);
        })) {
        StatusText(w, L"请选择 1 至 100 条当前账号已同步且允许导出的记录，必要时先刷新同步。");
        return;
    }
    if (MessageBoxW(w.hwnd,
                    L"导出所选记录的想法、摘录、原文和截图链接？\r\n\r\n仅这些图片生成独立分享快照"
                    L"，任何持有链接的人均可访问。请确认截图可分享。\r\n原笔记权限不变。链接可在设"
                    L"置 → 分享管理"
                    L"中撤销，但已下载的副本无法收回。",
                    L"Mnote · 确认导出", MB_YESNO | MB_ICONQUESTION) != IDYES)
        return;
    wchar_t path[32768] = L"Mnote-export.md";
    OPENFILENAMEW dialog{};
    dialog.lStructSize = sizeof(dialog);
    dialog.hwndOwner = w.hwnd;
    dialog.lpstrTitle = L"Mnote · 保存 Markdown";
    dialog.lpstrFilter = L"Markdown (*.md)\0*.md\0\0";
    dialog.lpstrFile = path;
    dialog.nMaxFile = 32768;
    dialog.lpstrDefExt = L"md";
    dialog.Flags = OFN_EXPLORER | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    if (!GetSaveFileNameW(&dialog))
        return;
    fs::path target(path);
    if (target.extension() != L".md") {
        StatusText(w, L"请使用 .md 文件扩展名。");
        return;
    }
    auto scope = w.scope;
    Run(
        w, [scope, records, target] { library->exportMarkdown(scope, records, target); },
        [target](Window &form) {
            StatusText(form, L"已保存：" + target.wstring());
            notify(L"Markdown 导出成功，图片链接可在管理中撤销。", false);
        });
}
void Command(Window &w, int id, int event) {
    if(w.mode==Mode::Chat||w.mode==Mode::ChatHistory||w.mode==Mode::ChatModels) {
        try { ChatCommand(w,id,event); } catch(const std::exception &e) { StatusText(w,Ai::Store::error(e)); }
        return;
    }
    if(w.mode==Mode::Editor&&id==ChatButton){
        if(w.dirty&&MessageBoxW(w.hwnd,L"本次尚未保存的修改不会进入聊天资料。继续查看已保存记录的对话？",L"Mnote",MB_YESNO|MB_ICONQUESTION)!=IDYES)return;
        auto history=chats->list(w.scope,w.record.id);if(history.empty())OpenChat(ChatRecord(w.scope,w.record.id));else OpenChatHistory(w.record.id);return;
    }
    if (w.mode == Mode::Editor && id == TagsPicker && event == CBN_SELCHANGE && !w.busy) {
        if (w.scope != library->account().scope) {
            StatusText(w, L"账号已变化，请重新打开记录。");
            return;
        }
        auto picker = ControlOf(w, TagsPicker);
        auto index = SendMessageW(picker, CB_GETCURSEL, 0, 0);
        if (index > 0)
            try {
                auto current = Text(ControlOf(w, TagsInput));
                auto selected = Text(picker);
                auto values = Mnote::Tags(current + (current.empty() ? L"" : L"，") + selected);
                Set(w, TagsInput, TagText(Json{{"tags", values}}));
            } catch (const std::exception &error) {
                StatusText(w, ErrorText(error));
            }
        SendMessageW(picker, CB_SETCURSEL, 0, 0);
        return;
    }
    if (w.mode == Mode::Library) {
        if (w.busy)
            return;
        if (id == FilterToggle) {
            w.filtersOpen = !w.filtersOpen;
            Set(w, FilterToggle, w.filtersOpen ? L"收起筛选" : L"筛选");
            Layout(w);
            return;
        }
        if (id == AllRecords) {
            w.selecting = false;
            w.selectedIds.clear();
            w.showTrash = false;
            Set(w, Trash, L"回收站");
            Populate(w);
            return;
        }
        if (id == MultiSelect) {
            if (!w.showTrash) {
                w.selecting = true;
                SelectionControls(w);
            }
            return;
        }
        if (id == MultiCancel) {
            w.selecting = false;
            w.selectedIds.clear();
            SelectionControls(w);
            StatusText(w, L"已退出多选。");
            return;
        }
        if (id == MultiAll) {
            for (const auto &r : w.filtered) {
                if (w.selectedIds.size() >= 100)
                    break;
                w.selectedIds.insert(r.id);
            }
            SelectionControls(w);
            return;
        }
        if (id == MultiDelete) {
            if (!w.selecting || w.selectedIds.empty() || w.scope != library->account().scope)
                return;
            auto scope = w.scope;
            std::vector<Record> snapshot;
            for (const auto &r : w.filtered)
                if (w.selectedIds.count(r.id))
                    snapshot.push_back(r);
            auto prompt = L"删除选中的 " + std::to_wstring(w.selectedIds.size()) +
                          L" 条记录？\r\n移入回收站，登录后的删除会同步到其他设备。";
            if (MessageBoxW(w.hwnd, prompt.c_str(), L"Mnote · 批量删除",
                            MB_YESNO | MB_ICONQUESTION) != IDYES)
                return;
            auto result = std::make_shared<std::pair<int, int>>(0, 0);
            Run(
                w,
                [snapshot, scope, result] {
                    for (const auto &r : snapshot) {
                        try {
                            library->erase(scope, r.id, library->fingerprint(r));
                            chats->prune(scope);
                            ++result->first;
                        } catch (const std::exception &) {
                            ++result->second;
                        }
                    }
                },
                [result](Window &form) {
                    if (!result->second) {
                        form.selecting = false;
                        form.selectedIds.clear();
                    }
                    SelectionControls(form);
                    Load();
                    Sync();
                    notify(L"已删除 " + std::to_wstring(result->first) + L" 条；未删除 " +
                               std::to_wstring(result->second) + L" 条。" +
                               (result->second ? L"记录或账号可能已变化，请刷新后重试。"
                                               : L"可在回收站恢复。"),
                           result->second > 0);
                });
            return;
        }
        if (id == SettingsButton) {
            OpenSettings();
            return;
        }
        if (id == BatchExport) {
            if (!w.selecting) {
                if (w.showTrash)
                    return;
                w.selecting = true;
                w.selectedIds.clear();
                SelectionControls(w);
                StatusText(w, L"点击卡片勾选记录，然后导出已选。");
            } else {
                std::vector<Record> records;
                for (const auto &record : w.filtered)
                    if (w.selectedIds.count(record.id))
                        records.push_back(record);
                ExportRecords(w, records);
            }
            return;
        }
        if (id == Updates) {
            OpenUpdate();
            return;
        }
        if (id == Search && event == EN_CHANGE)
            Populate(w);
        else if (id == List && event == LBN_SELCHANGE)
            RefreshReader(w);
        else if ((id == TagFilter || id == KindFilter || id == ChatFilter) && event == CBN_SELCHANGE)
            Populate(w);
        else if (!w.selecting && (id == OpenRecord || (id == List && event == LBN_DBLCLK)))
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
            w.selecting = false;
            w.selectedIds.clear();
            w.showTrash = !w.showTrash;
            Set(w, Trash, w.showTrash ? L"返回记录" : L"回收站");
            Populate(w);
        }
        return;
    }
    if (w.mode == Mode::Reader) {
        if(id==ChatButton) {
            auto history=chats->list(w.scope,w.record.id);
            if(history.empty())OpenChat(w.record);else OpenChatHistory(w.record.id);
        } else if (id == ReaderCrop || id == ReaderContext) {
            w.previewRole = id == ReaderContext ? L"context" : L"annotated";
            OpenImage(w);
        } else if (id == ReaderSource) {
            auto url = Field(Object(w.record.data, "source"), "url");
            if (url.rfind(L"https://", 0) == 0 || url.rfind(L"http://", 0) == 0)
                ShellExecuteW(w.hwnd, L"open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
            else
                notify(L"仅直接打开 HTTP(S) 网页；其他应用链接请从编辑页复制。", true);
        }
        return;
    }
    if (id == MoreOptions && w.mode == Mode::Editor) {
        w.moreOptions = !w.moreOptions;
        int extent = 0;
        for (const auto &p : w.placements) {
            int control = GetDlgCtrlID(p.control);
            if (control == Save || control == SaveChat || control == Cancel || control == Delete || control == Status)
                continue;
            if (p.y >= 10000 && !w.moreOptions)
                continue;
            extent = std::max(extent, (p.y >= 10000 ? p.y - 10000 : p.y) + p.h + 24);
        }
        w.extent = extent;
        Set(w, MoreOptions, w.moreOptions ? L"收起更多选项" : L"更多选项 · 摘录、标签与上下文");
        Layout(w);
        return;
    }
    if (w.mode == Mode::Image) {
        if (id == ImageRole && event == CBN_SELCHANGE) {
            if (!w.shareId.empty()) {
                LoadShareImage(w);
                return;
            }
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
            if (!w.shareId.empty() && !w.preview) {
                LoadShareImage(w);
                return;
            }
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
    if (w.mode == Mode::Settings) {
        if(id==ChatModels)OpenChatModels();
        if(id==ChatHistory)OpenChatHistory();
        if (id == ExportShares) {
            w.scope = library->account().scope;
            OpenMarkdown(w, true);
        }
        if (id == AccountButton)
            OpenAccount();
        if (id == Updates)
            OpenUpdate();
        return;
    }
    if (w.mode == Mode::Markdown || w.mode == Mode::Shares) {
        if (w.mode == Mode::Shares && id == List && event == LBN_DBLCLK) {
            MarkdownCommand(w, ExportPreview);
            return;
        }
        MarkdownCommand(w, id);
        return;
    }
    if (w.mode == Mode::Update) {
        if (id == UpdateCheck)
            CheckUpdate(w);
        else if (id == UpdatePage)
            ShellExecuteW(w.hwnd, L"open", Updater::Page, nullptr, nullptr, SW_SHOWNORMAL);
        else if (id == UpdateDownload && w.release) {
            if (MessageBoxW(
                    w.hwnd,
                    L"从 Mnote 服务器下载更新？可能产生网络流量。校验通过后仍需你点击安装。",
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
    if (id == Save || id == SaveChat)
        SaveEditor(w,id==SaveChat);
    else if (id == Delete) {
        if (MessageBoxW(w.hwnd, L"将这条记录移入回收站？关联 AI 会话及资料快照会同时删除（恢复记录不会恢复聊天），登录后同步到其他设备。",
                        L"Mnote · 删除记录", MB_YESNO | MB_ICONQUESTION) != IDYES)
            return;
        auto scope = w.scope, recordId = w.record.id, baseline = w.baseline;
        Run(
            w, [scope, recordId, baseline] { library->erase(scope, recordId, baseline); chats->prune(scope); },
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
            Gdiplus::Pen pen(Gdiplus::Color(255, 36, 73, 78), 3.0f);
            graphics.DrawRectangle(&pen, x + static_cast<float>(left * ratio),
                                   y + static_cast<float>(top * ratio),
                                   static_cast<float>((right - left) * ratio),
                                   static_cast<float>((bottom - top) * ratio));
        }
    }
}
void QueueShareCover(Window &w, const Json &share) {
    auto id = share.at("id").get<std::string>();
    if (w.busy || w.thumbnails.count(id) || w.shareLoading.count(id) || w.shareErrors.count(id))
        return;
    w.shareLoading.insert(id);
    auto hwnd = w.hwnd;
    auto serial = w.serial;
    auto scope = w.scope;
    int generation = w.shareGeneration;
    Enqueue([hwnd, serial, scope, generation, id] {
        std::shared_ptr<Gdiplus::Bitmap> bitmap;
        std::wstring caption;
        try {
            auto images = library->markdownExportImages(scope, id);
            if (images.empty())
                throw std::runtime_error("no_images");
            auto cover = images.front();
            for (const auto &asset : images)
                if (asset.at("role") == "annotated") {
                    cover = asset;
                    break;
                }
            bitmap = ShareBitmap(library->markdownExportImage(scope, id, cover), true);
            auto role = cover.at("role").get<std::string>();
            caption = L"记录 " + std::to_wstring(cover.at("record_index").get<int>()) + L" · " +
                      (role == "context"    ? L"完整页面"
                       : role == "original" ? L"圈选原图"
                                            : L"批注图");
        } catch (const std::exception &) {
        }
        Post([hwnd, serial, scope, generation, id, bitmap, caption] {
            auto form = Find(hwnd, serial);
            if (!form || form->shareGeneration != generation || scope != library->account().scope)
                return;
            form->shareLoading.erase(id);
            if (bitmap) {
                form->thumbnails[id] = bitmap;
                form->shareCoverLabels[id] = caption;
                form->shareCacheOrder.push_back(id);
                while (form->shareCacheOrder.size() > 16) {
                    form->thumbnails.erase(form->shareCacheOrder.front());
                    form->shareCacheOrder.pop_front();
                }
                StatusText(*form, L"已显示 " + std::to_wstring(form->thumbnails.size()) +
                                      L" 次分享的图片预览 · 双击卡片查看全部");
            } else
                form->shareErrors.insert(id);
            InvalidateRect(ControlOf(*form, List), nullptr, FALSE);
        });
    });
}
void DrawShare(Window &w, const DRAWITEMSTRUCT &item) {
    if (item.itemID >= w.shares.size())
        return;
    const auto &share = w.shares.at(item.itemID);
    std::string id = share.at("id");
    int count = share.at("image_count");
    RECT r = item.rcItem;
    FillRect(item.hDC, &r, backgroundBrush);
    r.bottom -= Scale(w, 12);
    auto brush =
        CreateSolidBrush((item.itemState & ODS_SELECTED) ? Selected : Background);
    auto pen = CreatePen(PS_SOLID, Scale(w, 1), Border);
    auto oldBrush = SelectObject(item.hDC, brush);
    auto oldPen = SelectObject(item.hDC, pen);
    RoundRect(item.hDC, r.left, r.top, r.right, r.bottom, Scale(w, 12), Scale(w, 12));
    SelectObject(item.hDC, oldBrush);
    SelectObject(item.hDC, oldPen);
    DeleteObject(brush);
    DeleteObject(pen);
    r.left += Scale(w, 18);
    r.right -= Scale(w, 18);
    r.top += Scale(w, 12);
    r.bottom = r.top + Scale(w, 24);
    RECT date = r;
    date.right -= Scale(w, 225);
    auto stamp = Wide(share.at("created").get<std::string>());
    if (stamp.size() >= 16 && stamp[10] == L'T') {
        stamp = stamp.substr(0, 16);
        stamp[10] = L' ';
        stamp += L" UTC";
    }
    DrawTextLine(item.hDC, date, stamp, Ink, w.font);
    RECT counter = r;
    counter.left = counter.right - Scale(w, 220);
    DrawTextLine(item.hDC, counter,
                 std::to_wstring(share.at("record_count").get<int>()) + L" 条记录 · " +
                     std::to_wstring(count) + L" 张图片",
                 Muted, w.font, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
    r.top += Scale(w, 34);
    r.bottom = r.top + Scale(w, 100);
    auto excerpt = Wide(
        share.value("text_preview", std::string("旧版分享未保存文字快照，无法还原当时的文字。")));
    DrawTextLine(item.hDC, r, excerpt, Ink, w.font,
                 DT_WORDBREAK | DT_END_ELLIPSIS | DT_EDITCONTROL);
    r.top = r.bottom + Scale(w, 12);
    r.bottom =
        r.top + std::max<LONG>(Scale(w, 24), item.rcItem.bottom - item.rcItem.top - Scale(w, 212));
    FillRect(item.hDC, &r, backgroundBrush);
    auto found = w.thumbnails.find(id);
    if (found != w.thumbnails.end()) {
        Gdiplus::Graphics graphics(item.hDC);
        auto image = found->second;
        double ratio = std::min(double(r.right - r.left) / image->GetWidth(),
                                double(r.bottom - r.top) / image->GetHeight());
        int width = int(image->GetWidth() * ratio), height = int(image->GetHeight() * ratio);
        graphics.DrawImage(image.get(), r.left + (r.right - r.left - width) / 2,
                           r.top + (r.bottom - r.top - height) / 2, width, height);
        auto &order = w.shareCacheOrder;
        order.erase(std::remove(order.begin(), order.end(), id), order.end());
        order.push_back(id);
    } else {
        DrawTextLine(item.hDC, r,
                     count == 0                ? L"文字分享 · 没有图片"
                     : w.shareErrors.count(id) ? L"预览暂不可用 · 点击刷新重试"
                                               : L"正在读取当时的图片…",
                     Muted, w.font, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        if (count > 0)
            QueueShareCover(w, share);
    }
    r.top = r.bottom + Scale(w, 10);
    r.bottom = r.top + Scale(w, 24);
    DrawTextLine(item.hDC, r,
                 w.shareCoverLabels.count(id) ? w.shareCoverLabels[id] : L"当次导出的独立快照",
                 Muted, w.font);
    DrawTextLine(item.hDC, r, L"查看全部图片  ›", Accent, w.font,
                 DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
}
void DrawRecord(Window &w, const DRAWITEMSTRUCT &item) {
    const auto &rows = w.mode == Mode::Markdown ? w.records : w.filtered;
    if (item.itemID >= rows.size())
        return;
    const auto &record = rows[item.itemID];
    auto r = item.rcItem;
    HDC dc = item.hDC;
    bool chosen = w.mode == Mode::Library && w.selecting ? w.selectedIds.count(record.id) > 0
                                                         : (item.itemState & ODS_SELECTED) != 0;
    if (w.mode == Mode::Library) {
        FillRect(dc, &r, backgroundBrush);
        r.top += Scale(w, 6);
        r.bottom -= Scale(w, 6);
        r.right -= 1;
        auto brush = CreateSolidBrush(chosen ? Selected : Paper);
        auto pen = CreatePen(PS_SOLID, std::max(1, Scale(w, 1)), chosen ? Accent : Border);
        auto previousBrush = SelectObject(dc, brush);
        auto previousPen = SelectObject(dc, pen);
        RoundRect(dc, r.left, r.top, r.right, r.bottom, Scale(w, 12), Scale(w, 12));
        SelectObject(dc, previousPen);
        SelectObject(dc, previousBrush);
        DeleteObject(pen);
        DeleteObject(brush);
        auto card = r;
        r.left += Scale(w, 14);
        r.right -= Scale(w, 14);
        r.top += Scale(w, 12);
        const auto content = PresentRecord(record);
        int badgeX = r.left;
        for (const auto &category : content.categories) {
            SIZE categorySize{};
            auto previousFont = SelectObject(dc, w.label);
            GetTextExtentPoint32W(dc, category.c_str(), static_cast<int>(category.size()), &categorySize);
            SelectObject(dc, previousFont);
            RECT badge{badgeX, r.top, badgeX + categorySize.cx + Scale(w, 16), r.top + Scale(w, 20)};
            auto accent = CreateSolidBrush(category == L"摘录" ? Copper : Accent);
            auto oldBrush = SelectObject(dc, accent);
            auto oldPen = SelectObject(dc, GetStockObject(NULL_PEN));
            RoundRect(dc, badge.left, badge.top, badge.right, badge.bottom, Scale(w, 6), Scale(w, 6));
            SelectObject(dc, oldPen);
            SelectObject(dc, oldBrush);
            DeleteObject(accent);
            DrawTextLine(dc, badge, category, Paper, w.label,
                         DT_CENTER | DT_VCENTER | DT_SINGLELINE);
            badgeX = badge.right + Scale(w, 6);
        }
        RECT time{badgeX + Scale(w, 4), r.top, r.right, r.top + Scale(w, 20)};
        if (w.selecting) {
            RECT check{r.right - Scale(w, 18), r.top + Scale(w, 1), r.right, r.top + Scale(w, 19)};
            DrawFrameControl(dc, &check, DFC_BUTTON, DFCS_BUTTONCHECK | (chosen ? DFCS_CHECKED : 0));
            time.right = check.left - Scale(w, 8);
        }
        auto stamp = Field(record.data, "created_at");
        if (stamp.size() >= 16 && stamp[10] == L'T') {
            stamp = stamp.substr(0, 16);
            stamp[10] = L' ';
            stamp += L" UTC";
        }
        DrawTextLine(dc, time, stamp, Muted, w.label,
                     DT_RIGHT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        int contentTop = r.top + Scale(w, 30);
        if (!content.comment.empty()) {
            RECT caption{r.left, contentTop, r.right, contentTop + Scale(w, 16)};
            DrawTextLine(dc, caption, content.commentLabel, Accent, w.label);
            RECT thought{r.left, caption.bottom + Scale(w, 4), r.right,
                         caption.bottom + Scale(w, 46)};
            DrawTextLine(dc, thought, content.comment, Ink, w.font,
                         DT_WORDBREAK | DT_EDITCONTROL | DT_END_ELLIPSIS | DT_NOPREFIX);
            contentTop = thought.bottom + Scale(w, 10);
        }
        if (content.hasMaterial) {
            // A separate, bounded surface makes source material distinct from the user's voice.
            RECT material{r.left, contentTop, r.right,
                          contentTop + Scale(w, content.comment.empty() ? 94 : 82)};
            auto materialBrush = CreateSolidBrush(Surface);
            FillRect(dc, &material, materialBrush);
            DeleteObject(materialBrush);
            RECT quoteLine{material.left, material.top, material.left + Scale(w, 2), material.bottom};
            auto quoteBrush = CreateSolidBrush(Copper);
            FillRect(dc, &quoteLine, quoteBrush);
            DeleteObject(quoteBrush);
            RECT caption{material.left + Scale(w, 10), material.top + Scale(w, 7),
                         material.right - Scale(w, 10), material.top + Scale(w, 23)};
            auto materialLabel = content.materialLabel;
            if (!record.assets.empty() && !content.material.empty()) materialLabel += L" · 截图";
            DrawTextLine(dc, caption, materialLabel, Copper, w.label);
            RECT excerpt{caption.left, caption.bottom + Scale(w, 4), caption.right,
                         material.bottom - Scale(w, 7)};
            if (!record.assets.empty()) {
                QueueLibraryThumbnail(w, record);
                const int boxWidth = Scale(w, 86), boxHeight = material.bottom - caption.top - Scale(w, 7);
                RECT imageBox{material.right - Scale(w, 10) - boxWidth, caption.top,
                              material.right - Scale(w, 10), caption.top + boxHeight};
                auto image = w.thumbnails.find(record.id);
                if (image != w.thumbnails.end()) {
                    auto bitmap = image->second;
                    const double ratio = std::min(double(boxWidth) / bitmap->GetWidth(),
                                                  double(boxHeight) / bitmap->GetHeight());
                    int iw = int(bitmap->GetWidth() * ratio), ih = int(bitmap->GetHeight() * ratio);
                    Gdiplus::Graphics graphics(dc);
                    graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
                    graphics.DrawImage(bitmap.get(), imageBox.left + (boxWidth - iw) / 2,
                                       imageBox.top + (boxHeight - ih) / 2, iw, ih);
                } else {
                    DrawTextLine(dc, imageBox, w.shareErrors.count(record.id) ? L"图片暂不可用" : L"载入截图…",
                                 Muted, w.label, DT_CENTER | DT_VCENTER | DT_WORDBREAK);
                }
                excerpt.right = imageBox.left - Scale(w, 10);
            }
            DrawTextLine(dc, excerpt, content.material.empty() ? L"已保留页面截图" : content.material,
                         Ink, w.font, DT_WORDBREAK | DT_EDITCONTROL | DT_END_ELLIPSIS | DT_NOPREFIX);
        } else if (content.comment.empty()) {
            RECT empty{r.left, contentTop, r.right, contentTop + Scale(w, 42)};
            const auto url = Field(Object(record.data, "source"), "url");
            DrawTextLine(dc, empty, url.empty() ? L"暂无文字预览" : url, Muted, w.font,
                         DT_WORDBREAK | DT_EDITCONTROL | DT_END_ELLIPSIS | DT_NOPREFIX);
        }
        r.right = card.right - Scale(w, 14);
        r.top = card.bottom - Scale(w, 54);
        r.bottom = r.top + Scale(w, 20);
        const auto tags = record.data.value("tags", Json::array());
        int x = r.left;
        for (std::size_t i = 0; i < tags.size(); ++i) {
            auto tag = L"# " + Wide(tags[i].get<std::string>());
            SIZE size{};
            auto previousFont = SelectObject(dc, w.label);
            GetTextExtentPoint32W(dc, tag.c_str(), static_cast<int>(tag.size()), &size);
            SelectObject(dc, previousFont);
            const int remaining = static_cast<int>(r.right) - x;
            if (remaining < Scale(w, 34))
                break;
            const int reserve = i + 1 < tags.size() ? Scale(w, 42) : 0;
            const int chipWidth = std::min<int>(size.cx + Scale(w, 14), remaining - reserve);
            if (chipWidth < Scale(w, 38)) {
                RECT overflow{x, r.top, r.right, r.bottom};
                DrawTextLine(dc, overflow, L"+" + std::to_wstring(tags.size() - i), Muted, w.label);
                break;
            }
            RECT chip{x, r.top, x + chipWidth, r.bottom};
            auto chipBrush = CreateSolidBrush(Background);
            auto chipPen = CreatePen(PS_SOLID, std::max(1, Scale(w, 1)), Border);
            auto oldBrush = SelectObject(dc, chipBrush);
            auto oldPen = SelectObject(dc, chipPen);
            RoundRect(dc, chip.left, chip.top, chip.right, chip.bottom, Scale(w, 8), Scale(w, 8));
            SelectObject(dc, oldPen);
            SelectObject(dc, oldBrush);
            DeleteObject(chipPen);
            DeleteObject(chipBrush);
            chip.left += Scale(w, 7);
            chip.right -= Scale(w, 7);
            DrawTextLine(dc, chip, tag, Muted, w.label);
            x += chipWidth + Scale(w, 6);
        }
        r.top = card.bottom - Scale(w, 28);
        r.bottom = card.bottom - Scale(w, 8);
        auto sync = record.state == "synced" ? L"已同步" : record.state == "local" ? L"本机保存"
                   : record.state == "error" ? L"同步待处理" : L"等待同步";
        DrawTextLine(dc, r, sync, Muted, w.label);
        if(w.chatCounts[record.id]>0)DrawTextLine(dc,r,L"AI · "+std::to_wstring(w.chatCounts[record.id]),Accent,w.label,DT_RIGHT|DT_VCENTER|DT_SINGLELINE);
        if (item.itemState & ODS_FOCUS) {
            InflateRect(&card, -Scale(w, 3), -Scale(w, 3));
            DrawFocusRect(dc, &card);
        }
        return;
    }
    HBRUSH brush = CreateSolidBrush(chosen ? Selected : Background);
    if (w.mode == Mode::Markdown || (w.mode == Mode::Library && w.selecting)) {
        FillRect(dc, &r, backgroundBrush);
        r.top += Scale(w, 5);
        r.bottom -= Scale(w, 5);
        auto oldBrush = SelectObject(dc, brush);
        auto pen = CreatePen(PS_SOLID, 1, chosen ? Accent : Border);
        auto oldPen = SelectObject(dc, pen);
        RoundRect(dc, r.left, r.top, r.right - 1, r.bottom, Scale(w, 10), Scale(w, 10));
        SelectObject(dc, oldPen);
        SelectObject(dc, oldBrush);
        DeleteObject(pen);
        RECT check{r.right - Scale(w, 46), r.top + Scale(w, 34), r.right - Scale(w, 22),
                   r.top + Scale(w, 58)};
        DrawFrameControl(dc, &check, DFC_BUTTON, DFCS_BUTTONCHECK | (chosen ? DFCS_CHECKED : 0));
        r.right -= Scale(w, 46);
    } else
        FillRect(dc, &r, brush);
    DeleteObject(brush);
    int pad = Scale(w, 18);
    r.left += pad;
    r.right -= pad;
    if (w.mode == Mode::Markdown && !record.assets.empty()) {
        auto thumb = w.thumbnails.find(record.id);
        if (thumb != w.thumbnails.end()) {
            Gdiplus::Graphics graphics(dc);
            auto &image = thumb->second;
            double factor = std::min(double(Scale(w, 110)) / image->GetWidth(),
                                     double(Scale(w, 76)) / image->GetHeight());
            graphics.DrawImage(image.get(), r.left, r.top + Scale(w, 10),
                               int(image->GetWidth() * factor), int(image->GetHeight() * factor));
        } else {
            RECT label = r;
            label.right = label.left + Scale(w, 110);
            DrawTextLine(dc, label, L"截图 · 查看大图", Muted, w.font, DT_VCENTER | DT_WORDBREAK);
        }
        r.left += Scale(w, 126);
    }
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
        if((w.mode==Mode::Chat||w.mode==Mode::ChatHistory||w.mode==Mode::ChatModels)&&LOWORD(wp)!=WA_INACTIVE) {
            if(w.scope!=Scope()){if(w.chatStop)w.chatStop->store(true);PostMessageW(w.hwnd,WM_CLOSE,0,0);return 0;}
            if(w.mode==Mode::Chat&&!w.chatReceiving)ChatProfileList(w);
        }
        if ((w.mode == Mode::Shares || w.mode == Mode::ShareText ||
             (w.mode == Mode::Image && !w.shareId.empty())) &&
            LOWORD(wp) != WA_INACTIVE && w.scope != library->account().scope) {
            PostMessageW(w.hwnd, WM_CLOSE, 0, 0);
            return 0;
        }
        if (w.mode == Mode::Settings && LOWORD(wp) != WA_INACTIVE) {
            auto account = library->account();
            Set(w, AccountButton,
                L"账号与同步\n" + (account.signedIn()
                                       ? Wide(account.username) + L" · 管理登录、同步和本机导入"
                                       : L"登录后，在不同设备之间同步记录。"));
        }
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
        info->ptMinTrackSize = {Scale(w, w.mode == Mode::Library ? 780 : 680),
                                Scale(w, w.mode == Mode::Shares || w.mode == Mode::Library || w.mode == Mode::Chat ? 620 : 480)};
        return 0;
    }
    case WM_DPICHANGED: {
        w.dpi = HIWORD(wp);
        Fonts(w);
        RECT bounds{};
        if (lp)
            bounds = *reinterpret_cast<const RECT *>(lp);
        else
            GetWindowRect(w.hwnd, &bounds);
        SetWindowPos(w.hwnd, nullptr, bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top,
                     SWP_NOZORDER | SWP_NOACTIVATE);
        if (w.mode == Mode::Library)
            Populate(w); // Re-measure variable cards using the new DPI; keep the selected record.
        Layout(w);
        return 0;
    }
    case WM_COMMAND:
        Command(w, LOWORD(wp), HIWORD(wp));
        return 0;
    case WM_MEASUREITEM: {
        auto item = reinterpret_cast<MEASUREITEMSTRUCT *>(lp);
        // LB_SETITEMHEIGHT has a documented 255-pixel limit. Variable owner-draw
        // measurement supports tall mixed-content cards without clipping at high DPI.
        item->itemHeight = static_cast<UINT>(w.mode == Mode::Library && item->itemID < w.filtered.size()
                                ? RecordRowHeight(w, w.filtered[item->itemID])
                                : Scale(w, w.mode == Mode::Shares ? 384 : 108));
        return TRUE;
    }
    case WM_DRAWITEM: {
        auto &item = *reinterpret_cast<DRAWITEMSTRUCT *>(lp);
        if (item.CtlID == List) {
            if (w.mode == Mode::Shares)
                DrawShare(w, item);
            else
                DrawRecord(w, item);
        } else if (w.mode == Mode::Reader && (item.CtlID == ReaderCrop || item.CtlID == ReaderContext)) {
            FillRect(item.hDC, &item.rcItem, backgroundBrush);
            if (auto found = w.readerImages.find(item.CtlID); found != w.readerImages.end()) {
                Gdiplus::Graphics graphics(item.hDC);
                graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
                graphics.DrawImage(found->second.get(), static_cast<INT>(item.rcItem.left), static_cast<INT>(item.rcItem.top),
                                   static_cast<INT>(item.rcItem.right - item.rcItem.left), static_cast<INT>(item.rcItem.bottom - item.rcItem.top));
                if (item.itemState & ODS_FOCUS)
                    DrawFocusRect(item.hDC, &item.rcItem);
            } else
                DrawTextLine(item.hDC, item.rcItem, Text(item.hwndItem), Muted, w.font,
                             DT_WORDBREAK | DT_VCENTER);
        } else if (item.CtlID == Preview)
            DrawImage(w, item.hDC, item.rcItem);
        else
            DrawButton(item);
        return TRUE;
    }
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLORBTN:
        if (w.mode == Mode::Reader) {
            int id = GetDlgCtrlID(reinterpret_cast<HWND>(lp));
            if (id == 28102 || id == ReaderExcerpt) {
                auto dc = reinterpret_cast<HDC>(wp);
                SetBkMode(dc, OPAQUE);
                SetBkColor(dc, id == 28102 ? Accent : Surface);
                SetTextColor(dc, id == 28102 ? RGB(255,255,255) : Ink);
                SetDCBrushColor(dc, id == 28102 ? Accent : Surface);
                return reinterpret_cast<LRESULT>(GetStockObject(DC_BRUSH));
            }
        }
        SetBkMode(reinterpret_cast<HDC>(wp), TRANSPARENT);
        {
            int id = GetDlgCtrlID(reinterpret_cast<HWND>(lp));
            SetTextColor(reinterpret_cast<HDC>(wp), id == Status || id == Subtitle ? Muted
                         : id == 2400 || (id >= 28101 && id <= 28105) ? Copper : Ink);
        }
        return reinterpret_cast<LRESULT>(backgroundBrush);
    case WM_CTLCOLORLISTBOX:
        if (w.mode == Mode::Library && reinterpret_cast<HWND>(lp) == ControlOf(w, List)) {
            SetBkColor(reinterpret_cast<HDC>(wp), Background);
            SetTextColor(reinterpret_cast<HDC>(wp), Ink);
            return reinterpret_cast<LRESULT>(backgroundBrush);
        }
        [[fallthrough]];
    case WM_CTLCOLOREDIT:
        SetBkColor(reinterpret_cast<HDC>(wp), w.mode == Mode::Reader ? Background : Surface);
        SetTextColor(reinterpret_cast<HDC>(wp), Ink);
        return reinterpret_cast<LRESULT>(w.mode == Mode::Reader ? backgroundBrush : whiteBrush);
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
        } else if (w.mode == Mode::Reader) {
            auto pen = CreatePen(PS_SOLID, 1, Border);
            auto old = SelectObject(dc, pen);
            for (const auto &p : w.placements) {
                int id = GetDlgCtrlID(p.control);
                if (id >= 28102 && id <= 28105 && p.y > 50) {
                    int y = Scale(w, p.y - w.scroll - 12);
                    MoveToEx(dc, Scale(w, p.x), y, nullptr);
                    RECT r{}; GetClientRect(w.hwnd, &r);
                    LineTo(dc, r.right - Scale(w, 28), y);
                }
            }
            SelectObject(dc, old); DeleteObject(pen);
        } else if (w.mode == Mode::Library) {
            RECT client{};
            GetClientRect(w.hwnd, &client);
            int width = MulDiv(client.right, 96, w.dpi);
            int sidebar = width >= 1080 ? 180 : 152;
            auto pen = CreatePen(PS_SOLID, 1, Border);
            auto old = SelectObject(dc, pen);
            MoveToEx(dc, Scale(w, sidebar), 0, nullptr);
            LineTo(dc, Scale(w, sidebar), client.bottom);
            if (width >= 1080) {
                int split = sidebar + 48 + std::clamp((width - sidebar) * 38 / 100, 330, 410);
                MoveToEx(dc, Scale(w, split), 0, nullptr);
                LineTo(dc, Scale(w, split), client.bottom);
                MoveToEx(dc, Scale(w, split + 24), Scale(w, 82), nullptr);
                LineTo(dc, client.right - Scale(w, 24), Scale(w, 82));
            }
            SelectObject(dc, old);
            DeleteObject(pen);
            RECT tab{Scale(w, 24), Scale(w, 74), Scale(w, 66), Scale(w, 80)};
            auto accent = CreateSolidBrush(Accent);
            FillRect(dc, &tab, accent);
            DeleteObject(accent);
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
        if(wp==7&&w.mode==Mode::Chat){KillTimer(w.hwnd,7);if(!w.chatReceiving){auto value=Utf8(Text(ControlOf(w,ChatInput)));if(w.chatId.empty())chats->scratch(w.scope,w.chatRecordId,value);else chats->draft(w.scope,w.chatId,value);}return 0;}
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
        if(w.mode==Mode::Chat) {
            if(w.chatStop)w.chatStop->store(true);
            if(w.scope==Scope()&&!w.chatReceiving){auto value=Utf8(Text(ControlOf(w,ChatInput)));if(w.chatId.empty())chats->scratch(w.scope,w.chatRecordId,value);else chats->draft(w.scope,w.chatId,value);}
        }
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
        if (w->brand)
            DeleteObject(w->brand);
        if (w->reading)
            DeleteObject(w->reading);
        if (w->label)
            DeleteObject(w->label);
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
    auto parent = windows.find(GetParent(item.hwndItem));
    bool revoke =
        item.CtlID == Save && parent != windows.end() && parent->second->mode == Mode::Shares;
    bool primary = (item.CtlID == Save && !revoke) || item.CtlID == Login ||
                   item.CtlID == NewNote || item.CtlID == 1007;
    bool disabled = (item.itemState & ODS_DISABLED) != 0;
    bool navigation = parent != windows.end() && parent->second->mode == Mode::Library &&
                      (item.CtlID == AllRecords || item.CtlID == Trash || item.CtlID == SettingsButton ||
                       item.CtlID == AccountButton);
    bool navSelected = navigation && (item.CtlID == AllRecords ? !parent->second->showTrash
                                      : item.CtlID == Trash && parent->second->showTrash);
    COLORREF fill = disabled ? Surface : primary ? Accent : navSelected ? Selected : Background;
    if (item.itemState & ODS_SELECTED)
        fill = primary ? RGB(27, 57, 61) : Selected;
    auto brush = CreateSolidBrush(fill);
    auto pen = CreatePen(PS_SOLID, 1, primary || navigation ? fill : Border);
    auto oldBrush = SelectObject(item.hDC, brush), oldPen = SelectObject(item.hDC, pen);
    RoundRect(item.hDC, r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, 14, 14);
    SelectObject(item.hDC, oldBrush);
    SelectObject(item.hDC, oldPen);
    DeleteObject(brush);
    DeleteObject(pen);
    auto font = reinterpret_cast<HFONT>(SendMessageW(item.hwndItem, WM_GETFONT, 0, 0));
    if (parent != windows.end() && parent->second->mode == Mode::Settings &&
        (item.CtlID == AccountButton || item.CtlID == Updates || item.CtlID == ExportShares)) {
        auto &w = *parent->second;
        auto caption = Text(item.hwndItem);
        auto split = caption.find(L'\n');
        RECT title = r;
        title.left += Scale(w, 22);
        title.right -= Scale(w, 46);
        title.top += Scale(w, 19);
        title.bottom = title.top + Scale(w, 26);
        DrawTextLine(item.hDC, title, caption.substr(0, split), Ink, font);
        title.top += Scale(w, 32);
        title.bottom += Scale(w, 32);
        if (split != std::wstring::npos)
            DrawTextLine(item.hDC, title, caption.substr(split + 1), Muted, font);
        RECT arrow = r;
        arrow.left = arrow.right - Scale(w, 36);
        DrawTextLine(item.hDC, arrow, L"›", Muted, font, DT_VCENTER | DT_SINGLELINE);
        if (item.itemState & ODS_FOCUS) {
            InflateRect(&r, -4, -4);
            DrawFocusRect(item.hDC, &r);
        }
        return true;
    }
    DrawTextLine(item.hDC, r, Text(item.hwndItem),
                 disabled  ? Muted
                 : revoke || item.CtlID == Delete || item.CtlID == MultiDelete ? Danger
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
    chats = std::make_unique<Ai::Store>(*library);
    backgroundBrush = CreateSolidBrush(Background);
    whiteBrush = CreateSolidBrush(Surface);
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
    auto &w = Create(Mode::Library, L"Mnote · 我的知识库", 1240, 900);
    home = w.hwnd;
    auto brand = Add(w, Brand, L"STATIC", L"Mnote", SS_LEFT, 24, 22, 144, 48);
    SendMessageW(brand, WM_SETFONT, reinterpret_cast<WPARAM>(w.brand), TRUE);
    Label(w, Title, L"全部记录", 24);
    Label(w, ReaderTitle, L"记录", 28);
    Label(w, Subtitle, L"本机记录", 70);
    Button(w, AllRecords, L"全部记录", 12, 102, 156);
    Button(w, AccountButton, L"登录账号", 12, 0, 156);
    Button(w, FilterToggle, L"筛选", 0, 84, 78);
    Button(w, SettingsButton, L"设置", 0, 0, 100);
    Button(w, NewNote, L"随手记", 28, 110, 132);
    Button(w, Capture, L"截图", 170, 110, 132);
    Button(w, Refresh, L"刷新与同步", 0, 110, 124);
    Add(w, Search, L"EDIT", L"", WS_BORDER | ES_AUTOHSCROLL | WS_TABSTOP, 28, 174, 360, 38);
    SendMessageW(ControlOf(w, Search), EM_SETCUEBANNER, TRUE,
                 reinterpret_cast<LPARAM>(L"搜索想法、摘录、原文与标签"));
    Add(w, TagFilter, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 0, 174, 190, 280);
    Add(w, KindFilter, L"COMBOBOX", L"", CBS_DROPDOWNLIST | WS_TABSTOP, 0, 174, 198, 280);
    for (auto value : {L"全部类型", L"想法", L"待办", L"稍后回顾", L"摘录"})
        SendMessageW(ControlOf(w, KindFilter), CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(value));
    SendMessageW(ControlOf(w, KindFilter), CB_SETCURSEL, 0, 0);
    Add(w,ChatFilter,L"COMBOBOX",L"",CBS_DROPDOWNLIST|WS_TABSTOP,0,214,300,200);
    for(auto label:{L"全部聊天状态",L"已与 AI 聊过",L"尚未与 AI 聊过"})SendMessageW(ControlOf(w,ChatFilter),CB_ADDSTRING,0,reinterpret_cast<LPARAM>(label));
    SendMessageW(ControlOf(w,ChatFilter),CB_SETCURSEL,0,0);
    Add(w, List, L"LISTBOX", L"",
        LBS_OWNERDRAWVARIABLE | LBS_HASSTRINGS | LBS_NOTIFY | LBS_NOINTEGRALHEIGHT | WS_VSCROLL |
            WS_TABSTOP,
        28, 230, 900, 400);
    Button(w, Trash, L"回收站", 28, 0, 120);
    Button(w, BatchExport, L"选择导出", 314, 110, 124);
    Button(w, OpenRecord, L"查看 / 修改", 0, 0, 148);
    Button(w, MultiSelect, L"多选", 160, 0, 100);
    Button(w, MultiCancel, L"取消多选", 28, 0, 100);
    Button(w, MultiAll, L"全选当前", 140, 0, 130);
    Button(w, MultiDelete, L"删除已选", 0, 0, 148);
    SetWindowSubclass(ControlOf(w, List), LibraryListProc, 1, 0);
    Label(w, Status, L"Ctrl+Shift+F8 随手记  ·  Ctrl+Shift+F9 截图摘录", 0);
    auto readerState = std::make_unique<Window>();
    readerState->serial = ++nextSerial;
    readerState->mode = Mode::Reader;
    readerState->scope = w.scope;
    readerState->dpi = w.dpi;
    auto raw = readerState.get();
    HWND reader = CreateWindowExW(WS_EX_CONTROLPARENT, ClassName, L"记录正文",
                                  WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_VSCROLL,
                                  0, 0, 500, 600, w.hwnd,
                                  reinterpret_cast<HMENU>(static_cast<INT_PTR>(ReaderPanel)), instance, raw);
    if (!reader)
        throw std::runtime_error("window_failed");
    readerState->hwnd = reader;
    Fonts(*readerState);
    windows.emplace(reader, std::move(readerState));
    w.reader = reader;
    Layout(w);
    SelectionControls(w);
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
    for(auto &item:windows)if(item.second->chatStop)item.second->chatStop->store(true);
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
    for(auto &thread:chatWorkers)if(thread.joinable())thread.join();
    chatWorkers.clear();
    while (!windows.empty())
        DestroyWindow(windows.begin()->first);
    home = nullptr;
    chats.reset();
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
        if (item.second->mode != Mode::Reader)
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
            count += chats->sync(scope);
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
        if(message.wParam==VK_RETURN&&GetKeyState(VK_CONTROL)<0&&w.mode==Mode::Chat){try{SendChat(w);}catch(const std::exception &e){StatusText(w,Ai::Store::error(e));}return true;}
        if (message.wParam == VK_ESCAPE) {
            if (w.mode == Mode::Library && w.selecting) {
                if (w.busy)
                    return true;
                w.selecting = false;
                w.selectedIds.clear();
                SelectionControls(w);
                StatusText(w, L"已退出多选。");
                return true;
            }
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

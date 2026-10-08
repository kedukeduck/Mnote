#include <windows.h>
#include <windowsx.h>

#include "workspace.hpp"

#include <commctrl.h>
#include <gdiplus.h>
#include <shellapi.h>
#include <shlobj.h>
#include <dwmapi.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cwchar>
#include <limits>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

constexpr wchar_t kMainWindowClass[] = L"PersonalCapture.MessageWindow";
constexpr wchar_t kOverlayWindowClass[] = L"PersonalCapture.CaptureOverlay";
constexpr wchar_t kSingleInstanceName[] = L"Local\\PersonalCapture.Windows.V1";
constexpr wchar_t kAppName[] = L"Mnote";

constexpr UINT kTrayIconId = 1;
constexpr UINT kHotkeyId = 1;
constexpr UINT kTrayMessage = WM_APP + 1;
constexpr UINT kToolbarHeight = 0;
constexpr UINT kQuickHotkeyId = 2;
constexpr UINT kCommandQuickNote = 40005;

constexpr UINT kCommandNewCapture = 40001;
constexpr UINT kCommandOpenInbox = 40002;
constexpr UINT kCommandExit = 40003;
constexpr UINT kCommandSyncPending = 40004;

const CLSID kPngEncoder = {
    0x557cf406,
    0x1a04,
    0x11d3,
    {0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e}};

constexpr int kControlSelect = 1001;
constexpr int kControlPen = 1002;
constexpr int kControlHighlighter = 1003;
constexpr int kControlUndo = 1004;
constexpr int kControlKindLabel = 1005;
constexpr int kControlKind = 1006;
constexpr int kControlSave = 1007;
constexpr int kControlCancel = 1008;
constexpr int kControlCommentLabel = 1009;
constexpr int kControlComment = 1010;

enum class Tool {
    Select,
    Pen,
    Highlighter,
};

struct Stroke {
    Tool tool = Tool::Pen;
    std::vector<POINT> points;
};

struct CaptureFrame {
    int virtualX = 0;
    int virtualY = 0;
    int width = 0;
    int height = 0;
    HDC memoryDc = nullptr;
    HBITMAP bitmap = nullptr;
    HGDIOBJ previousBitmap = nullptr;
    std::wstring sourceWindowTitle;
    std::wstring sourceProcessPath;
};

struct OverlayControls {
    HWND select = nullptr;
    HWND pen = nullptr;
    HWND highlighter = nullptr;
    HWND undo = nullptr;
    HWND kindLabel = nullptr;
    HWND kind = nullptr;
    HWND save = nullptr;
    HWND cancel = nullptr;
    HWND commentLabel = nullptr;
    HWND comment = nullptr;
};

struct SaveOutcome {
    std::wstring annotatedImage;
    std::string syncState;
    std::wstring syncError;
};

HINSTANCE g_instance = nullptr;
HWND g_mainWindow = nullptr;
HWND g_overlayWindow = nullptr;
HANDLE g_singleInstanceMutex = nullptr;
ULONG_PTR g_gdiplusToken = 0;
UINT g_taskbarCreatedMessage = 0;
NOTIFYICONDATAW g_trayIcon{};

CaptureFrame g_capture;
OverlayControls g_controls;
Tool g_tool = Tool::Select;
RECT g_selection{};
POINT g_dragAnchor{};
bool g_selecting = false;
bool g_drawing = false;
std::vector<Stroke> g_strokes;
std::atomic<unsigned long> g_fileSequence{0};
std::optional<Mnote::Workspace::Draft> g_annotationDraft;
Mnote::Workspace::AnnotationDone g_annotationDone;

LRESULT CALLBACK MainWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam);
LRESULT CALLBACK OverlayWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam);

bool HasSelection() {
    return g_selection.right - g_selection.left >= 2 &&
           g_selection.bottom - g_selection.top >= 2;
}

RECT NormalizedRect(POINT first, POINT second) {
    RECT result{};
    result.left = std::min(first.x, second.x);
    result.top = std::min(first.y, second.y);
    result.right = std::max(first.x, second.x);
    result.bottom = std::max(first.y, second.y);
    return result;
}

POINT ClampPointToClient(HWND window, POINT point) {
    RECT client{};
    GetClientRect(window, &client);
    point.x = std::clamp(point.x, client.left, client.right);
    point.y = std::clamp(point.y, client.top, client.bottom);
    return point;
}

bool PointInSelection(POINT point) {
    return HasSelection() && PtInRect(&g_selection, point) != FALSE;
}

void ResetCaptureFrame() {
    if (g_capture.memoryDc != nullptr) {
        if (g_capture.previousBitmap != nullptr) {
            SelectObject(g_capture.memoryDc, g_capture.previousBitmap);
        }
        DeleteDC(g_capture.memoryDc);
    }
    if (g_capture.bitmap != nullptr) {
        DeleteObject(g_capture.bitmap);
    }
    g_capture = CaptureFrame{};
}

std::wstring GetWindowCaption(HWND window) {
    if (window == nullptr) {
        return {};
    }
    const int length = GetWindowTextLengthW(window);
    if (length <= 0) {
        return {};
    }
    std::vector<wchar_t> buffer(static_cast<std::size_t>(length) + 1U, L'\0');
    const int copied = GetWindowTextW(window, buffer.data(), length + 1);
    if (copied <= 0) {
        return {};
    }
    return std::wstring(buffer.data(), static_cast<std::size_t>(copied));
}

std::wstring GetWindowProcessPath(HWND window) {
    if (window == nullptr) {
        return {};
    }
    DWORD processId = 0;
    GetWindowThreadProcessId(window, &processId);
    if (processId == 0) {
        return {};
    }
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, processId);
    if (process == nullptr) {
        return {};
    }
    std::vector<wchar_t> buffer(32768U, L'\0');
    DWORD length = static_cast<DWORD>(buffer.size());
    std::wstring result;
    if (QueryFullProcessImageNameW(process, 0, buffer.data(), &length) != FALSE) {
        result.assign(buffer.data(), static_cast<std::size_t>(length));
    }
    CloseHandle(process);
    return result;
}

bool CaptureVirtualDesktop(HWND sourceWindow, std::wstring& error) {
    ResetCaptureFrame();

    g_capture.virtualX = GetSystemMetrics(SM_XVIRTUALSCREEN);
    g_capture.virtualY = GetSystemMetrics(SM_YVIRTUALSCREEN);
    g_capture.width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    g_capture.height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    g_capture.sourceWindowTitle = GetWindowCaption(sourceWindow);
    g_capture.sourceProcessPath = GetWindowProcessPath(sourceWindow);

    if (g_capture.width <= 0 || g_capture.height <= 0 || static_cast<std::int64_t>(g_capture.width)*g_capture.height>32000000) {
        error = L"桌面尺寸无效或超过 3200 万像素，请临时减少显示器或分辨率后重试。";
        ResetCaptureFrame();
        return false;
    }

    HDC screenDc = GetDC(nullptr);
    if (screenDc == nullptr) {
        error = L"无法读取当前桌面。";
        ResetCaptureFrame();
        return false;
    }

    g_capture.memoryDc = CreateCompatibleDC(screenDc);
    if (g_capture.memoryDc == nullptr) {
        ReleaseDC(nullptr, screenDc);
        error = L"无法创建截图缓冲区。";
        ResetCaptureFrame();
        return false;
    }

    g_capture.bitmap = CreateCompatibleBitmap(screenDc, g_capture.width, g_capture.height);
    if (g_capture.bitmap == nullptr) {
        ReleaseDC(nullptr, screenDc);
        error = L"无法创建截图位图；虚拟桌面可能过大。";
        ResetCaptureFrame();
        return false;
    }
    g_capture.previousBitmap = SelectObject(g_capture.memoryDc, g_capture.bitmap);

    const BOOL copied = BitBlt(
        g_capture.memoryDc,
        0,
        0,
        g_capture.width,
        g_capture.height,
        screenDc,
        g_capture.virtualX,
        g_capture.virtualY,
        SRCCOPY | CAPTUREBLT);
    ReleaseDC(nullptr, screenDc);

    if (copied == FALSE) {
        error = L"Windows 拒绝了本次桌面截图。受保护内容或安全桌面不能被捕获。";
        ResetCaptureFrame();
        return false;
    }
    GdiFlush();
    return true;
}

bool EnsureDirectory(const std::wstring& path) {
    if (CreateDirectoryW(path.c_str(), nullptr) != FALSE) {
        return true;
    }
    if (GetLastError() != ERROR_ALREADY_EXISTS) {
        return false;
    }
    const DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES &&
           (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0U;
}

bool GetApplicationDirectory(std::wstring& result) {
    DWORD required = GetEnvironmentVariableW(L"LOCALAPPDATA", nullptr, 0);
    std::wstring localAppData;
    if (required > 1U) {
        std::vector<wchar_t> buffer(static_cast<std::size_t>(required), L'\0');
        const DWORD copied = GetEnvironmentVariableW(
            L"LOCALAPPDATA", buffer.data(), static_cast<DWORD>(buffer.size()));
        if (copied > 0U && copied < buffer.size()) {
            localAppData.assign(buffer.data(), static_cast<std::size_t>(copied));
        }
    }

    if (localAppData.empty()) {
        wchar_t fallback[MAX_PATH]{};
        if (SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, SHGFP_TYPE_CURRENT, fallback) != S_OK) {
            return false;
        }
        localAppData = fallback;
    }

    std::wstring appDirectory = localAppData + L"\\PersonalCapture";
    if (!EnsureDirectory(appDirectory)) {
        return false;
    }
    result = std::move(appDirectory);
    return true;
}

std::string WideToUtf8(std::wstring_view value) {
    if (value.empty()) {
        return {};
    }
    if (value.size() > static_cast<std::size_t>((std::numeric_limits<int>::max)())) {
        return {};
    }
    const int inputLength = static_cast<int>(value.size());
    const int required = WideCharToMultiByte(
        CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), inputLength, nullptr, 0, nullptr, nullptr);
    if (required <= 0) {
        return {};
    }
    std::string result(static_cast<std::size_t>(required), '\0');
    const int copied = WideCharToMultiByte(
        CP_UTF8,
        WC_ERR_INVALID_CHARS,
        value.data(),
        inputLength,
        result.data(),
        required,
        nullptr,
        nullptr);
    if (copied != required) {
        return {};
    }
    return result;
}

std::string EscapeJsonUtf8(std::string_view value) {
    static constexpr char kHex[] = "0123456789abcdef";
    std::string result;
    result.reserve(value.size() + 16U);
    for (const unsigned char character : value) {
        switch (character) {
        case '"':
            result += "\\\"";
            break;
        case '\\':
            result += "\\\\";
            break;
        case '\b':
            result += "\\b";
            break;
        case '\f':
            result += "\\f";
            break;
        case '\n':
            result += "\\n";
            break;
        case '\r':
            result += "\\r";
            break;
        case '\t':
            result += "\\t";
            break;
        default:
            if (character < 0x20U) {
                result += "\\u00";
                result.push_back(kHex[(character >> 4U) & 0x0FU]);
                result.push_back(kHex[character & 0x0FU]);
            } else {
                result.push_back(static_cast<char>(character));
            }
            break;
        }
    }
    return result;
}

std::string JsonString(std::wstring_view value) {
    return std::string("\"") + EscapeJsonUtf8(WideToUtf8(value)) + "\"";
}

std::wstring FileNameFromPath(std::wstring_view path) {
    const std::size_t separator = path.find_last_of(L"\\/");
    return std::wstring(separator == std::wstring_view::npos ? path : path.substr(separator + 1U));
}

std::wstring FormatUtcTimestamp() {
    SYSTEMTIME utc{};
    GetSystemTime(&utc);
    wchar_t buffer[64]{};
    std::swprintf(
        buffer,
        sizeof(buffer) / sizeof(buffer[0]),
        L"%04u-%02u-%02uT%02u:%02u:%02u.%03uZ",
        static_cast<unsigned>(utc.wYear),
        static_cast<unsigned>(utc.wMonth),
        static_cast<unsigned>(utc.wDay),
        static_cast<unsigned>(utc.wHour),
        static_cast<unsigned>(utc.wMinute),
        static_cast<unsigned>(utc.wSecond),
        static_cast<unsigned>(utc.wMilliseconds));
    return buffer;
}

void ConfigurePen(Gdiplus::Pen& pen) {
    pen.SetLineJoin(Gdiplus::LineJoinRound);
    pen.SetStartCap(Gdiplus::LineCapRound);
    pen.SetEndCap(Gdiplus::LineCapRound);
}

void DrawStroke(
    Gdiplus::Graphics& graphics,
    const Stroke& stroke,
    int offsetX,
    int offsetY) {
    if (stroke.points.empty()) {
        return;
    }

    const bool highlighter = stroke.tool == Tool::Highlighter;
    const Gdiplus::Color color = highlighter
        ? Gdiplus::Color(96, 255, 221, 0)
        : Gdiplus::Color(255, 239, 68, 68);
    const Gdiplus::REAL width = highlighter ? 14.0F : 3.5F;
    Gdiplus::Pen pen(color, width);
    ConfigurePen(pen);

    std::vector<Gdiplus::PointF> points;
    points.reserve(stroke.points.size());
    for (const POINT point : stroke.points) {
        points.emplace_back(
            static_cast<Gdiplus::REAL>(point.x - offsetX),
            static_cast<Gdiplus::REAL>(point.y - offsetY));
    }

    if (points.size() == 1U) {
        Gdiplus::SolidBrush brush(color);
        const Gdiplus::REAL radius = width / 2.0F;
        graphics.FillEllipse(
            &brush,
            points[0].X - radius,
            points[0].Y - radius,
            width,
            width);
        return;
    }
    graphics.DrawLines(&pen, points.data(), static_cast<INT>(points.size()));
}

void DrawAllStrokes(
    Gdiplus::Graphics& graphics,
    int offsetX,
    int offsetY,
    const Gdiplus::Rect* clip) {
    const Gdiplus::GraphicsState state = graphics.Save();
    if (clip != nullptr) {
        graphics.SetClip(*clip);
    }
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    graphics.SetCompositingMode(Gdiplus::CompositingModeSourceOver);
    for (const Stroke& stroke : g_strokes) {
        DrawStroke(graphics, stroke, offsetX, offsetY);
    }
    graphics.Restore(state);
}

std::string BuildJson(
    const std::wstring& id,
    const std::wstring& originalFileName,
    const std::wstring& annotatedFileName,
    const std::wstring& comment,
    const std::string& kind,
    const std::wstring& createdAtUtc,
    const std::string& aiAccess,
    bool localRecord,
    const std::string& syncState,
    const std::wstring& syncError,
    const std::string* originalBase64,
    const std::string* annotatedBase64) {
    const int selectionWidth = g_selection.right - g_selection.left;
    const int selectionHeight = g_selection.bottom - g_selection.top;
    const int screenX = g_capture.virtualX + g_selection.left;
    const int screenY = g_capture.virtualY + g_selection.top;

    std::ostringstream json;
    json << "{\n"
         << "  \"schema_version\": 1,\n"
         << "  \"id\": " << JsonString(id) << ",\n"
         << "  \"created_at\": " << JsonString(createdAtUtc) << ",\n"
         << "  \"kind\": \"" << kind << "\",\n"
         << "  \"comment\": " << JsonString(comment) << ",\n"
         << "  \"source\": {\n"
         << "    \"type\": \"screen\",\n"
         << "    \"app_name\": " << JsonString(FileNameFromPath(g_capture.sourceProcessPath)) << ",\n"
         << "    \"app_id\": " << JsonString(FileNameFromPath(g_capture.sourceProcessPath)) << ",\n"
         << "    \"window_title\": " << JsonString(g_capture.sourceWindowTitle) << ",\n"
         << "    \"text\": \"\"\n"
         << "  },\n"
         << "  \"capture\": {\n"
         << "    \"virtual_screen\": { \"x\": " << g_capture.virtualX
         << ", \"y\": " << g_capture.virtualY
         << ", \"width\": " << g_capture.width
         << ", \"height\": " << g_capture.height << " },\n"
         << "    \"selection_screen\": { \"x\": " << screenX
         << ", \"y\": " << screenY
         << ", \"width\": " << selectionWidth
         << ", \"height\": " << selectionHeight << " },\n"
         << "    \"coordinate_space\": \"windows_virtual_desktop_physical_pixels\"\n"
         << "  },\n"
         << "  \"annotations\": [";

    for (std::size_t strokeIndex = 0; strokeIndex < g_strokes.size(); ++strokeIndex) {
        const Stroke& stroke = g_strokes[strokeIndex];
        const bool highlighter = stroke.tool == Tool::Highlighter;
        if (strokeIndex != 0U) {
            json << ',';
        }
        json << "\n    { \"tool\": \"" << (highlighter ? "highlighter" : "pen")
             << "\", \"color\": \"" << (highlighter ? "#ffdd00" : "#ef4444")
             << "\", \"width\": " << (highlighter ? "14.0" : "3.5")
             << ", \"opacity\": " << (highlighter ? "0.376" : "1.0")
             << ", \"points\": [";
        for (std::size_t pointIndex = 0; pointIndex < stroke.points.size(); ++pointIndex) {
            if (pointIndex != 0U) {
                json << ',';
            }
            json << "{\"x\":" << stroke.points[pointIndex].x - g_selection.left
                 << ",\"y\":" << stroke.points[pointIndex].y - g_selection.top << '}';
        }
        json << "] }";
    }
    if (!g_strokes.empty()) {
        json << '\n';
    }
    json << "  ],\n"
         << "  \"ai_access\": \"" << aiAccess << "\"";
    if (localRecord) {
        json << ",\n"
             << "  \"local_files\": { \"original\": " << JsonString(originalFileName)
             << ", \"annotated\": " << JsonString(annotatedFileName) << " },\n"
             << "  \"sync_state\": \"" << syncState << "\",\n"
             << "  \"sync_error\": " << JsonString(syncError);
    }
    if (originalBase64 != nullptr && annotatedBase64 != nullptr) {
        json << ",\n"
             << "  \"assets\": {\n"
             << "    \"original\": { \"content_type\": \"image/png\", \"data_base64\": \""
             << *originalBase64 << "\" },\n"
             << "    \"annotated\": { \"content_type\": \"image/png\", \"data_base64\": \""
             << *annotatedBase64 << "\" }\n"
             << "  }";
    }
    json << "\n}\n";
    return json.str();
}

void UpdateSaveAvailability() {
    EnableWindow(g_controls.save,HasSelection());
}
void SetTool(Tool tool) {
    if(tool!=Tool::Select && !HasSelection())tool=Tool::Select;
    g_tool=tool;
    SetWindowTextW(g_controls.select,tool==Tool::Select ? L"● 框选 · 1" : L"框选 · 1");
    SetWindowTextW(g_controls.pen,tool==Tool::Pen ? L"● 自由笔 · 2" : L"自由笔 · 2");
    SetWindowTextW(g_controls.highlighter,tool==Tool::Highlighter ? L"● 高亮 · 3" : L"高亮 · 3");
}
void ApplyDefaultFont(HWND control) {
    if (control != nullptr) {
        SendMessageW(control, WM_SETFONT, reinterpret_cast<WPARAM>(Mnote::Workspace::Font()), TRUE);
    }
}

void CreateOverlayControls(HWND parent) {
    auto button=[&](int id,const wchar_t* text) {auto control=CreateWindowExW(0,L"BUTTON",text,WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_OWNERDRAW,
        0,0,0,0,parent,reinterpret_cast<HMENU>(static_cast<INT_PTR>(id)),g_instance,nullptr);ApplyDefaultFont(control);return control;};
    g_controls.select=button(kControlSelect,L"框选 · 1");g_controls.pen=button(kControlPen,L"自由笔 · 2");
    g_controls.highlighter=button(kControlHighlighter,L"高亮 · 3");g_controls.undo=button(kControlUndo,L"撤销");
    g_controls.save=button(kControlSave,L"完成批注");g_controls.cancel=button(kControlCancel,L"取消 · Esc");
    SetTool(Tool::Select);UpdateSaveAvailability();
}
struct ToolbarArea {
    RECT image{};
    RECT visible{};
    std::int64_t area = 0;
};
BOOL CALLBACK FindToolbarMonitor(HMONITOR, HDC, LPRECT monitor, LPARAM parameter) {
    auto &candidate = *reinterpret_cast<ToolbarArea *>(parameter);
    RECT visible{};
    if (IntersectRect(&visible, &candidate.image, monitor) &&
        visible.right - visible.left >= 230 && visible.bottom - visible.top >= 150) {
        const auto area = static_cast<std::int64_t>(visible.right - visible.left) *
                          (visible.bottom - visible.top);
        if (area > candidate.area) {
            candidate.visible = visible;
            candidate.area = area;
        }
    }
    return TRUE;
}
bool FindToolbarArea(const RECT &image, POINT anchor, RECT &visible) {
    MONITORINFO monitor{sizeof(MONITORINFO), {}, {}, 0};
    if (GetMonitorInfoW(MonitorFromPoint(anchor, MONITOR_DEFAULTTONEAREST), &monitor) &&
        IntersectRect(&visible, &image, &monitor.rcMonitor) &&
        visible.right - visible.left >= 230 && visible.bottom - visible.top >= 150)
        return true;
    ToolbarArea candidate{image, {}, 0};
    EnumDisplayMonitors(nullptr, nullptr, FindToolbarMonitor,
                        reinterpret_cast<LPARAM>(&candidate));
    visible = candidate.visible;
    return candidate.area > 0;
}
void LayoutOverlayControls(HWND window) {
    RECT client{};
    GetClientRect(window, &client);
    // Keep the entire tool group inside one real monitor, including on virtual
    // desktops with gaps between monitors and on narrower retained screenshots.
    RECT screen = client;
    MapWindowPoints(window, nullptr, reinterpret_cast<POINT *>(&screen), 2);
    POINT anchor{screen.left + (HasSelection() ? g_selection.left : client.right / 2),
                 screen.top + (HasSelection() ? g_selection.bottom : 16)};
    RECT visible{};
    if (FindToolbarArea(screen, anchor, visible)) {
        MapWindowPoints(nullptr, window, reinterpret_cast<POINT *>(&visible), 2);
        client = visible;
    }
    const int available = static_cast<int>(client.right - client.left) - 16;
    const int columns = std::clamp((available + 8) / 110, 1, 6);
    const int rows = (6 + columns - 1) / columns;
    const int width = columns * 110 - 8, height = rows * 46 - 8;
    const int left = static_cast<int>(client.left) + 8;
    const int top = static_cast<int>(client.top) + 8;
    int x = static_cast<int>(client.left) + (available - width) / 2 + 8;
    int y = top + 8;
    if (HasSelection()) {
        x = static_cast<int>(g_selection.left);
        y = g_selection.bottom + height + 12 < client.bottom
                ? static_cast<int>(g_selection.bottom) + 12
                : static_cast<int>(g_selection.top) - height - 12;
    }
    x = std::clamp(x, left, std::max(left, static_cast<int>(client.right) - width - 8));
    y = std::clamp(y, top, std::max(top, static_cast<int>(client.bottom) - height - 8));
    HWND buttons[] = {g_controls.select, g_controls.pen, g_controls.highlighter,
                      g_controls.undo, g_controls.save, g_controls.cancel};
    for (int i = 0; i < 6; ++i)
        MoveWindow(buttons[i], x + (i % columns) * 110, y + (i / columns) * 46,
                   102, 38, TRUE);
}

void ShowTrayNotice(const wchar_t* title, const wchar_t* message, DWORD flags) {
    if (g_mainWindow == nullptr) {
        return;
    }
    g_trayIcon.uFlags = NIF_INFO;
    lstrcpynW(g_trayIcon.szInfoTitle, title, static_cast<int>(sizeof(g_trayIcon.szInfoTitle) / sizeof(wchar_t)));
    lstrcpynW(g_trayIcon.szInfo, message, static_cast<int>(sizeof(g_trayIcon.szInfo) / sizeof(wchar_t)));
    g_trayIcon.dwInfoFlags = flags;
    g_trayIcon.uTimeout = 5000;
    Shell_NotifyIconW(NIM_MODIFY, &g_trayIcon);
    g_trayIcon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
}

void ResetOverlayState() {
    g_controls = OverlayControls{};
    g_tool = Tool::Select;
    SetRectEmpty(&g_selection);
    g_selecting = false;
    g_drawing = false;
    g_strokes.clear();
}

void CloseOverlay() {
    HWND overlay = g_overlayWindow;
    if (overlay != nullptr) {
        DestroyWindow(overlay);
    }
    ResetCaptureFrame();
    ResetOverlayState();
}

void CancelCapture() {
    auto done = std::move(g_annotationDone);
    g_annotationDone = {};
    g_annotationDraft.reset();
    CloseOverlay();
    if (done)
        done(std::nullopt);
}

void SaveCurrentCapture() {
    if (!HasSelection() || !g_capture.bitmap || !g_annotationDraft)
        return;
    if (g_annotationDraft->scope != Mnote::Workspace::Scope()) {
        ShowTrayNotice(L"批注未应用", L"账号已经切换，请在当前账号重新打开记录。", NIIF_WARNING);
        CancelCapture();
        return;
    }
    try {
        auto draft = *g_annotationDraft;
        const int width = g_selection.right - g_selection.left;
        const int height = g_selection.bottom - g_selection.top;
        Gdiplus::Bitmap original(width, height, PixelFormat32bppARGB);
        Gdiplus::Bitmap annotated(width, height, PixelFormat32bppARGB);
        if (original.GetLastStatus() != Gdiplus::Ok ||
            annotated.GetLastStatus() != Gdiplus::Ok)
            throw std::runtime_error("invalid_image");
        {
            Gdiplus::Graphics graphics(&original);
            // Crop the immutable retained bitmap, not the live screen or its
            // HBITMAP display copy. Repeated annotation never loses context.
            if (graphics.DrawImage(draft.fullImage.get(), Gdiplus::Rect(0, 0, width, height),
                                   g_selection.left, g_selection.top, width, height,
                                   Gdiplus::UnitPixel) != Gdiplus::Ok)
                throw std::runtime_error("invalid_image");
        }
        {
            Gdiplus::Graphics graphics(&annotated);
            if (graphics.DrawImage(&original, 0, 0, width, height) != Gdiplus::Ok)
                throw std::runtime_error("invalid_image");
            Gdiplus::Rect clip(0, 0, width, height);
            DrawAllStrokes(graphics, g_selection.left, g_selection.top, &clip);
        }
        const auto capture = Mnote::Parse(BuildJson(
            Mnote::Wide(draft.data.at("id").get<std::string>()), L"", L"", L"", "thought",
            FormatUtcTimestamp(), "local_only", false, "", L"", nullptr, nullptr));
        draft.data["annotations"] = capture.at("annotations");
        draft.data["capture"] = capture.at("capture");
        const bool retainContext = draft.assets.count("context") != 0;
        draft.data["evidence"]["context"]["image"] = {
            {"retained", retainContext},
            {"asset_role", retainContext ? Mnote::Json("context") : Mnote::Json(nullptr)},
            {"width", g_capture.width}, {"height", g_capture.height},
            {"editor_width", width}, {"editor_height", height},
            {"annotation_coordinate_space", "selected_image_pixels"},
            {"coordinate_space", "context_image_pixels"}, {"selected_asset_role", "original"},
            {"selection", {{"left", g_selection.left}, {"top", g_selection.top},
                           {"right", g_selection.right}, {"bottom", g_selection.bottom}}}};
        if (draft.staging.empty())
            throw std::runtime_error("storage_write");
        if (draft.scope != Mnote::Workspace::Scope())
            throw std::runtime_error("account_changed");
        // Every confirmation writes a new pair. A failed second encode must never
        // overwrite the crop pair already accepted by the live editor draft.
        const auto attempt = L"crop-" + Mnote::Wide(Mnote::NewId());
        const auto originalPath = draft.staging / (attempt + L"-original.png");
        const auto annotatedPath = draft.staging / (attempt + L"-annotated.png");
        Mnote::Context::SavePng(original, originalPath);
        Mnote::Context::SavePng(annotated, annotatedPath);
        draft.assets["original"] = originalPath;
        draft.assets["annotated"] = annotatedPath;
        auto done = std::move(g_annotationDone);
        g_annotationDone = {};
        g_annotationDraft.reset();
        CloseOverlay();
        if (done) {
            if (draft.scope == Mnote::Workspace::Scope())
                done(std::move(draft));
            else
                done(std::nullopt);
        }
    } catch (const std::exception &error) {
        MessageBoxW(g_overlayWindow, Mnote::ErrorText(error).c_str(), kAppName,
                    MB_OK | MB_ICONWARNING);
    }
}

void BeginCapture() {
    if (g_overlayWindow != nullptr) {
        SetForegroundWindow(g_overlayWindow);
        return;
    }
    if (Mnote::Workspace::HasEditor()) {
        MessageBoxW(g_mainWindow, L"请先保存或关闭正在编辑的记录。", kAppName, MB_OK);
        return;
    }
    try {
        Mnote::Workspace::Draft draft;
        draft.scope = Mnote::Workspace::Scope();
        draft.source = Mnote::Context::Foreground();
        const auto timestamp = Mnote::Timestamp();
        draft.data = {{"schema_version", 1}, {"id", Mnote::NewId()},
                      {"created_at", timestamp}, {"updated_at", timestamp},
                      {"kind", "thought"}, {"comment", ""},
                      {"source", draft.source.metadata}, {"tags", Mnote::Json::array()},
                      {"ai_access", "local_only"}};
        // Freeze before the composer appears. No selection or annotation is
        // fabricated for a full-page snapshot.
        Mnote::Workspace::Hide();
        DwmFlush();
        std::wstring captureError;
        if (CaptureVirtualDesktop(draft.source.window, captureError)) {
            Gdiplus::Bitmap captured(g_capture.bitmap, nullptr);
            draft.fullImage.reset(captured.Clone(0, 0, g_capture.width, g_capture.height,
                                                PixelFormat32bppARGB));
            if (!draft.fullImage || draft.fullImage->GetLastStatus() != Gdiplus::Ok) {
                draft.fullImage.reset();
                captureError = L"无法保留本次截图，截图缓冲区不可用。";
            } else {
                draft.data["capture"] = {
                    {"virtual_screen", {{"x", g_capture.virtualX}, {"y", g_capture.virtualY},
                                        {"width", g_capture.width}, {"height", g_capture.height}}},
                    {"coordinate_space", "windows_virtual_desktop_physical_pixels"}};
                draft.data["evidence"]["context"]["image"] = {
                    {"retained", true}, {"asset_role", "context"},
                    {"width", g_capture.width}, {"height", g_capture.height},
                    {"coordinate_space", "context_image_pixels"}, {"purpose", "page_context"},
                    {"relation_to_quote", "unverified"},
                    {"selection_meaning", "full_viewport_not_quote_location"}};
            }
        }
        ResetCaptureFrame();
        if (!draft.fullImage &&
            MessageBoxW(g_mainWindow,
                        (captureError + L"\n\n是否继续创建纯文字记录？不会再次尝试截取受保护内容。")
                            .c_str(),
                        L"Mnote · 无法截图", MB_YESNO | MB_DEFBUTTON2 | MB_ICONWARNING) != IDYES)
            return;
        if (draft.scope != Mnote::Workspace::Scope())
            throw std::runtime_error("account_changed");
        draft.staging = Mnote::Workspace::Staging();
        if (draft.fullImage) {
            const auto path = draft.staging / L"context.png";
            Mnote::Context::SavePng(*draft.fullImage, path);
            draft.assets["context"] = path;
        }
        Mnote::Workspace::Compose(std::move(draft));
    } catch (const std::exception &error) {
        ResetCaptureFrame();
        MessageBoxW(g_mainWindow, Mnote::ErrorText(error).c_str(), kAppName,
                    MB_OK | MB_ICONWARNING);
    }
}

void BeginAnnotate(Mnote::Workspace::Draft draft, Mnote::Workspace::AnnotationDone done) {
    if (g_overlayWindow != nullptr) {
        SetForegroundWindow(g_overlayWindow);
        if (done)
            done(std::nullopt);
        return;
    }
    if (draft.scope != Mnote::Workspace::Scope() || !draft.fullImage ||
        draft.fullImage->GetLastStatus() != Gdiplus::Ok) {
        MessageBoxW(g_mainWindow, L"完整截图不可用或账号已经切换，无法进入批注。", kAppName,
                    MB_OK | MB_ICONWARNING);
        if (done)
            done(std::nullopt);
        return;
    }
    const auto width = draft.fullImage->GetWidth(), height = draft.fullImage->GetHeight();
    const int desktopWidth = GetSystemMetrics(SM_CXVIRTUALSCREEN);
    const int desktopHeight = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    const POINT overlayOrigin{GetSystemMetrics(SM_XVIRTUALSCREEN),
                              GetSystemMetrics(SM_YVIRTUALSCREEN)};
    if (desktopWidth <= 0 || desktopHeight <= 0 || width < 240 || height < 180 ||
        width > static_cast<UINT>(desktopWidth) || height > static_cast<UINT>(desktopHeight) ||
        static_cast<std::uint64_t>(width) * height > 32000000) {
        MessageBoxW(g_mainWindow,
                    L"这张截图的尺寸不适合当前屏幕进行原尺寸批注。请恢复截图时的显示器布局或分辨率后重试；原图和已有记录不会修改。",
                    L"Mnote · 暂时无法批注", MB_OK | MB_ICONINFORMATION);
        if (done)
            done(std::nullopt);
        return;
    }
    RECT toolbarArea{};
    const RECT imageArea{overlayOrigin.x, overlayOrigin.y,
                         overlayOrigin.x + static_cast<LONG>(width),
                         overlayOrigin.y + static_cast<LONG>(height)};
    if (!FindToolbarArea(imageArea, overlayOrigin, toolbarArea)) {
        MessageBoxW(g_mainWindow,
                    L"当前显示器布局没有足够空间放置批注工具，请恢复截图时的显示器布局后重试。原图和已有记录不会修改。",
                    L"Mnote · 暂时无法批注", MB_OK | MB_ICONINFORMATION);
        if (done)
            done(std::nullopt);
        return;
    }
    ResetCaptureFrame();
    ResetOverlayState();
    g_capture.width = static_cast<int>(width);
    g_capture.height = static_cast<int>(height);
    g_capture.virtualX = GetSystemMetrics(SM_XVIRTUALSCREEN);
    g_capture.virtualY = GetSystemMetrics(SM_YVIRTUALSCREEN);
    try {
        const auto screen = draft.data.value("capture", Mnote::Json::object())
                                .value("virtual_screen", Mnote::Json::object());
        g_capture.virtualX = screen.value("x", g_capture.virtualX);
        g_capture.virtualY = screen.value("y", g_capture.virtualY);
        g_capture.sourceWindowTitle = GetWindowCaption(draft.source.window);
        g_capture.sourceProcessPath = GetWindowProcessPath(draft.source.window);
        // Display the already-retained image. GetHBITMAP only copies that bitmap
        // into a GDI surface; it never captures a live window or the desktop.
        if (draft.fullImage->GetHBITMAP(Gdiplus::Color(255, 255, 255, 255), &g_capture.bitmap) !=
                Gdiplus::Ok ||
            !g_capture.bitmap)
            throw std::runtime_error("invalid_image");
        g_capture.memoryDc = CreateCompatibleDC(nullptr);
        if (!g_capture.memoryDc)
            throw std::runtime_error("invalid_image");
        g_capture.previousBitmap = SelectObject(g_capture.memoryDc, g_capture.bitmap);
        if (!g_capture.previousBitmap || g_capture.previousBitmap == HGDI_ERROR) {
            g_capture.previousBitmap = nullptr;
            throw std::runtime_error("invalid_image");
        }
    } catch (const std::exception &error) {
        ResetCaptureFrame();
        MessageBoxW(g_mainWindow, Mnote::ErrorText(error).c_str(), kAppName,
                    MB_OK | MB_ICONWARNING);
        if (done)
            done(std::nullopt);
        return;
    }
    g_annotationDraft = std::move(draft);
    g_annotationDone = std::move(done);
    try {
        const auto image=g_annotationDraft->data.value("evidence",Mnote::Json::object())
                .value("context",Mnote::Json::object()).value("image",Mnote::Json::object());
        if(image.contains("selection")) {
            const auto box=image.at("selection");
            RECT selection{box.at("left").get<LONG>(),box.at("top").get<LONG>(),
                           box.at("right").get<LONG>(),box.at("bottom").get<LONG>()};
            if(selection.left<0 || selection.top<0 || selection.right>g_capture.width ||
               selection.bottom>g_capture.height || selection.right<=selection.left || selection.bottom<=selection.top)
                throw std::runtime_error("invalid_image");
            std::vector<Stroke> restored;
            const auto annotations=g_annotationDraft->data.value("annotations",Mnote::Json::array());
            if(!annotations.is_array() || annotations.size()>4096)throw std::runtime_error("invalid_image");
            std::size_t count=0;
            for(const auto &annotation:annotations) {
                const auto tool=annotation.at("tool").get<std::string>();
                if(tool!="pen" && tool!="highlighter")throw std::runtime_error("invalid_image");
                Stroke stroke;stroke.tool=tool=="pen"?Tool::Pen:Tool::Highlighter;
                const auto points=annotation.at("points");
                if(!points.is_array() || (count+=points.size())>100000)throw std::runtime_error("invalid_image");
                for(const auto &point:points) {
                    const auto x=point.at("x").get<std::int64_t>()+selection.left;
                    const auto y=point.at("y").get<std::int64_t>()+selection.top;
                    if(x<0 || y<0 || x>g_capture.width || y>g_capture.height)throw std::runtime_error("invalid_image");
                    stroke.points.push_back({static_cast<LONG>(x),static_cast<LONG>(y)});
                }
                restored.push_back(std::move(stroke));
            }
            g_selection=selection;g_strokes=std::move(restored);
        }
    } catch(const std::exception &) {
        MessageBoxW(g_mainWindow,L"无法恢复已有批注；已确认的结果仍保留，请返回记录页检查。",kAppName,MB_OK|MB_ICONWARNING);
        CancelCapture();return;
    }
    g_overlayWindow = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        kOverlayWindowClass,
        L"Mnote - 冻结画面",
        WS_POPUP,
        overlayOrigin.x,
        overlayOrigin.y,
        g_capture.width,
        g_capture.height,
        g_mainWindow,
        nullptr,
        g_instance,
        nullptr);
    if (g_overlayWindow == nullptr) {
        MessageBoxW(g_mainWindow, L"无法创建截图编辑窗口。", kAppName, MB_OK | MB_ICONERROR);
        CancelCapture();
        return;
    }

    SetTool(Tool::Select);

    ShowWindow(g_overlayWindow, SW_SHOW);
    UpdateWindow(g_overlayWindow);
    SetWindowPos(
        g_overlayWindow,
        HWND_TOPMOST,
        overlayOrigin.x,
        overlayOrigin.y,
        g_capture.width,
        g_capture.height,
        SWP_SHOWWINDOW);
    SetForegroundWindow(g_overlayWindow);
    SetActiveWindow(g_overlayWindow);
    SetFocus(g_overlayWindow);
}

void AddPointToCurrentStroke(POINT point) {
    if (g_strokes.empty() || !PointInSelection(point)) {
        return;
    }
    Stroke& stroke = g_strokes.back();
    if (!stroke.points.empty()) {
        const POINT previous = stroke.points.back();
        const long deltaX = point.x - previous.x;
        const long deltaY = point.y - previous.y;
        if (deltaX * deltaX + deltaY * deltaY < 4L) {
            return;
        }
    }
    stroke.points.push_back(point);
}

void PaintOverlay(HWND window) {
    PAINTSTRUCT paint{};
    HDC dc = BeginPaint(window, &paint);
    if (dc == nullptr) {
        return;
    }

    RECT client{};
    GetClientRect(window, &client);
    if (g_capture.memoryDc != nullptr) {
        BitBlt(
            dc,
            0,
            0,
            client.right - client.left,
            client.bottom - client.top,
            g_capture.memoryDc,
            0,
            0,
            SRCCOPY);
    } else {
        FillRect(dc, &client, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
    }

    {
        Gdiplus::Graphics graphics(dc);
        graphics.SetCompositingMode(Gdiplus::CompositingModeSourceOver);
        if (HasSelection()) {
            const Gdiplus::SolidBrush shade(Gdiplus::Color(115, 0, 0, 0));
            const int width = client.right - client.left;
            const int height = client.bottom - client.top;
            const int selectionLeft = static_cast<int>(g_selection.left);
            const int selectionTop = static_cast<int>(g_selection.top);
            const int selectionRight = static_cast<int>(g_selection.right);
            const int selectionBottom = static_cast<int>(g_selection.bottom);
            graphics.FillRectangle(&shade, 0, 0, selectionLeft, height);
            graphics.FillRectangle(
                &shade, selectionRight, 0, width - selectionRight, height);
            graphics.FillRectangle(
                &shade,
                selectionLeft,
                0,
                selectionRight - selectionLeft,
                selectionTop);
            graphics.FillRectangle(
                &shade,
                selectionLeft,
                selectionBottom,
                selectionRight - selectionLeft,
                height - selectionBottom);

            const Gdiplus::Rect clip(
                selectionLeft,
                selectionTop,
                selectionRight - selectionLeft,
                selectionBottom - selectionTop);
            DrawAllStrokes(graphics, 0, 0, &clip);

            Gdiplus::Pen border(Gdiplus::Color(255, 56, 189, 248), 2.0F);
            border.SetDashStyle(Gdiplus::DashStyleDash);
            graphics.DrawRectangle(
                &border,
                selectionLeft,
                selectionTop,
                (std::max)(0, selectionRight - selectionLeft - 1),
                (std::max)(0, selectionBottom - selectionTop - 1));
        } else {
            const Gdiplus::SolidBrush shade(Gdiplus::Color(52, 0, 0, 0));
            graphics.FillRectangle(
                &shade, 0, 0, client.right - client.left, client.bottom - client.top);
        }
        graphics.Flush(Gdiplus::FlushIntentionSync);
    }

    RECT toolbar{0, 0, 0, 0};
    HBRUSH toolbarBrush = CreateSolidBrush(RGB(31, 34, 40));
    if (toolbarBrush != nullptr) {
        FillRect(dc, &toolbar, toolbarBrush);
        DeleteObject(toolbarBrush);
    }
    EndPaint(window, &paint);
}

void ShowTrayMenu(HWND window) {
    HMENU menu = CreatePopupMenu();
    if (menu == nullptr) {
        return;
    }
    AppendMenuW(menu, MF_STRING, kCommandNewCapture, L"记录\tCtrl+Shift+F9");
    AppendMenuW(menu, MF_STRING, kCommandSyncPending, L"刷新与同步");
    AppendMenuW(menu, MF_STRING, kCommandOpenInbox, L"我的知识库");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, kCommandExit, L"退出");
    POINT cursor{};
    GetCursorPos(&cursor);
    SetForegroundWindow(window);
    const UINT command = TrackPopupMenu(
        menu,
        TPM_RETURNCMD | TPM_RIGHTBUTTON,
        cursor.x,
        cursor.y,
        0,
        window,
        nullptr);
    DestroyMenu(menu);
    if (command != 0U) {
        PostMessageW(window, WM_COMMAND, command, 0);
    }
    PostMessageW(window, WM_NULL, 0, 0);
}

bool AddTrayIcon() {
    ZeroMemory(&g_trayIcon, sizeof(g_trayIcon));
    g_trayIcon.cbSize = sizeof(g_trayIcon);
    g_trayIcon.hWnd = g_mainWindow;
    g_trayIcon.uID = kTrayIconId;
    g_trayIcon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_trayIcon.uCallbackMessage = kTrayMessage;
    g_trayIcon.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    lstrcpynW(
        g_trayIcon.szTip,
        L"Mnote · 记录 - Ctrl+Shift+F9 / F8",
        static_cast<int>(sizeof(g_trayIcon.szTip) / sizeof(wchar_t)));
    return Shell_NotifyIconW(NIM_ADD, &g_trayIcon) != FALSE;
}

void RemoveTrayIcon() {
    if (g_trayIcon.hWnd != nullptr) {
        Shell_NotifyIconW(NIM_DELETE, &g_trayIcon);
        g_trayIcon.hWnd = nullptr;
    }
}

void HandleOverlayCommand(int command) {
    switch (command) {
    case kControlSelect:
        SetTool(Tool::Select);
        break;
    case kControlPen:
        SetTool(Tool::Pen);
        break;
    case kControlHighlighter:
        SetTool(Tool::Highlighter);
        break;
    case kControlUndo:
        if (!g_strokes.empty()) {
            g_strokes.pop_back();
            InvalidateRect(g_overlayWindow, nullptr, FALSE);
        } else {
            MessageBeep(MB_OK);
        }
        break;
    case kControlSave:
        SaveCurrentCapture();
        break;
    case kControlCancel:
        CancelCapture();
        break;
    default:
        break;
    }
}

LRESULT CALLBACK OverlayWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
    case WM_CREATE:
        CreateOverlayControls(window);
        LayoutOverlayControls(window);
        return 0;

    case WM_SIZE:
        LayoutOverlayControls(window);
        return 0;

    case WM_ERASEBKGND:
        return 1;

    case WM_PAINT:
        PaintOverlay(window);
        return 0;

    case WM_SETCURSOR:
        if (LOWORD(lParam) == HTCLIENT) {
            SetCursor(LoadCursorW(nullptr, IDC_CROSS));
            return TRUE;
        }
        break;

    case WM_LBUTTONDOWN: {
        SetFocus(window);
        POINT point{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
        if (g_tool == Tool::Select) {
            if (point.y < static_cast<LONG>(kToolbarHeight)) {
                return 0;
            }
            point = ClampPointToClient(window, point);
            g_dragAnchor = point;
            g_selection = NormalizedRect(point, point);
            g_strokes.clear();
            g_selecting = true;
            UpdateSaveAvailability();
            SetCapture(window);
            InvalidateRect(window, nullptr, FALSE);
        } else if (PointInSelection(point)) {
            Stroke stroke{};
            stroke.tool = g_tool;
            stroke.points.push_back(point);
            g_strokes.push_back(std::move(stroke));
            g_drawing = true;
            SetCapture(window);
            InvalidateRect(window, nullptr, FALSE);
        }
        return 0;
    }

    case WM_MOUSEMOVE: {
        POINT point{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
        if (g_selecting) {
            point = ClampPointToClient(window, point);
            g_selection = NormalizedRect(g_dragAnchor, point);
            UpdateSaveAvailability();
            LayoutOverlayControls(window);
            InvalidateRect(window, nullptr, FALSE);
        } else if (g_drawing) {
            AddPointToCurrentStroke(point);
            InvalidateRect(window, nullptr, FALSE);
        }
        return 0;
    }

    case WM_LBUTTONUP: {
        POINT point{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
        if (g_selecting) {
            point = ClampPointToClient(window, point);
            g_selection = NormalizedRect(g_dragAnchor, point);
            g_selecting = false;
            if (!HasSelection()) {
                SetRectEmpty(&g_selection);
            }
            UpdateSaveAvailability();
            InvalidateRect(window, nullptr, FALSE);
        } else if (g_drawing) {
            AddPointToCurrentStroke(point);
            g_drawing = false;
            InvalidateRect(window, nullptr, FALSE);
        }
        if (GetCapture() == window) {
            ReleaseCapture();
        }
        return 0;
    }

    case WM_CAPTURECHANGED:
        g_selecting = false;
        g_drawing = false;
        return 0;

    case WM_COMMAND:
        if (HIWORD(wParam) == BN_CLICKED) {
            HandleOverlayCommand(LOWORD(wParam));
            return 0;
        }
        break;

    case WM_DRAWITEM:
        return Mnote::Workspace::DrawButton(*reinterpret_cast<DRAWITEMSTRUCT*>(lParam));

    case WM_CTLCOLORSTATIC: {
        HDC controlDc = reinterpret_cast<HDC>(wParam);
        SetTextColor(controlDc, RGB(238, 242, 247));
        SetBkMode(controlDc, TRANSPARENT);
        return reinterpret_cast<LRESULT>(GetStockObject(NULL_BRUSH));
    }

    case WM_CLOSE:
        CancelCapture();
        return 0;

    case WM_DISPLAYCHANGE:
        ShowTrayNotice(L"批注已取消", L"显示器布局发生变化，原始截图和记录草稿仍然保留。", NIIF_WARNING);
        CancelCapture();
        return 0;

    case WM_NCDESTROY:
        if (g_overlayWindow == window) {
            g_overlayWindow = nullptr;
        }
        return DefWindowProcW(window, message, wParam, lParam);

    default:
        break;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

LRESULT CALLBACK MainWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    if (g_taskbarCreatedMessage != 0U && message == g_taskbarCreatedMessage) {
        AddTrayIcon();
        return 0;
    }

    switch (message) {
    case WM_CREATE:
        g_mainWindow = window;
        if (!AddTrayIcon()) {
            MessageBoxW(window, L"无法创建系统托盘图标。", kAppName, MB_OK | MB_ICONWARNING);
        }
        if (RegisterHotKey(window, kHotkeyId, MOD_CONTROL | MOD_SHIFT | MOD_NOREPEAT, VK_F9) == FALSE) {
            MessageBoxW(
                window,
                L"Ctrl+Shift+F9 已被其他程序占用。你仍可使用托盘菜单中的“记录”。",
                kAppName,
                MB_OK | MB_ICONWARNING);
        }
        RegisterHotKey(window,kQuickHotkeyId,MOD_CONTROL|MOD_SHIFT|MOD_NOREPEAT,VK_F8);
        return 0;

    case WM_HOTKEY:
        if (wParam == kHotkeyId || wParam == kQuickHotkeyId)
            BeginCapture();
        return 0;

    case kTrayMessage:
        if (static_cast<UINT>(lParam) == WM_LBUTTONDBLCLK) {
            Mnote::Workspace::Show();
        } else if (static_cast<UINT>(lParam) == WM_RBUTTONUP ||
                   static_cast<UINT>(lParam) == WM_CONTEXTMENU) {
            ShowTrayMenu(window);
        }
        return 0;

    case WM_COMMAND:
        switch (LOWORD(wParam)) {
        case kCommandNewCapture:
            BeginCapture();
            break;
        case kCommandOpenInbox:
            Mnote::Workspace::Show();
            break;
        case kCommandSyncPending:
            Mnote::Workspace::Sync();
            break;
        case kCommandQuickNote:
            BeginCapture();
            break;
        case kCommandExit:
            if(Mnote::Workspace::CanExit())DestroyWindow(window);
            break;
        default:
            break;
        }
        return 0;

    case WM_DESTROY:
        if (g_overlayWindow != nullptr) {
            DestroyWindow(g_overlayWindow);
            g_overlayWindow = nullptr;
        }
        ResetCaptureFrame();
        ResetOverlayState();
        g_annotationDone = {};
        g_annotationDraft.reset();
        UnregisterHotKey(window, kHotkeyId);
        UnregisterHotKey(window, kQuickHotkeyId);
        RemoveTrayIcon();
        g_mainWindow = nullptr;
        PostQuitMessage(0);
        return 0;

    default:
        break;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

void EnableBestDpiAwareness() {
    if (SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) != FALSE) {
        return;
    }
    SetProcessDPIAware();
}

bool RegisterWindowClasses() {
    WNDCLASSEXW mainClass{};
    mainClass.cbSize = sizeof(mainClass);
    mainClass.lpfnWndProc = MainWindowProc;
    mainClass.hInstance = g_instance;
    mainClass.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    mainClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    mainClass.lpszClassName = kMainWindowClass;
    if (RegisterClassExW(&mainClass) == 0) {
        return false;
    }

    WNDCLASSEXW overlayClass{};
    overlayClass.cbSize = sizeof(overlayClass);
    overlayClass.style = CS_HREDRAW | CS_VREDRAW;
    overlayClass.lpfnWndProc = OverlayWindowProc;
    overlayClass.hInstance = g_instance;
    overlayClass.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    overlayClass.hCursor = LoadCursorW(nullptr, IDC_CROSS);
    overlayClass.hbrBackground = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
    overlayClass.lpszClassName = kOverlayWindowClass;
    return RegisterClassExW(&overlayClass) != 0;
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    int argc=0;wchar_t** argv=CommandLineToArgvW(GetCommandLineW(),&argc);
    int helper=Mnote::Context::Helper(argc,argv);LocalFree(argv);if(helper>=0)return helper;
    g_instance = instance;
    EnableBestDpiAwareness();

    g_singleInstanceMutex = CreateMutexW(nullptr, TRUE, kSingleInstanceName);
    if (g_singleInstanceMutex == nullptr) {
        MessageBoxW(nullptr, L"无法初始化应用。", kAppName, MB_OK | MB_ICONERROR);
        return 1;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        PostMessageW(FindWindowW(kMainWindowClass,nullptr),WM_COMMAND,kCommandOpenInbox,0);
        CloseHandle(g_singleInstanceMutex);
        g_singleInstanceMutex = nullptr;
        return 0;
    }

    INITCOMMONCONTROLSEX commonControls{};
    commonControls.dwSize = sizeof(commonControls);
    commonControls.dwICC = ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&commonControls);

    Gdiplus::GdiplusStartupInput gdiplusInput;
    if (Gdiplus::GdiplusStartup(&g_gdiplusToken, &gdiplusInput, nullptr) != Gdiplus::Ok) {
        MessageBoxW(nullptr, L"无法启动 Windows 图像组件。", kAppName, MB_OK | MB_ICONERROR);
        CloseHandle(g_singleInstanceMutex);
        g_singleInstanceMutex = nullptr;
        return 1;
    }

    g_taskbarCreatedMessage = RegisterWindowMessageW(L"TaskbarCreated");
    if (!RegisterWindowClasses()) {
        MessageBoxW(nullptr, L"无法注册应用窗口。", kAppName, MB_OK | MB_ICONERROR);
        Gdiplus::GdiplusShutdown(g_gdiplusToken);
        CloseHandle(g_singleInstanceMutex);
        return 1;
    }

    g_mainWindow = CreateWindowExW(
        0,
        kMainWindowClass,
        kAppName,
        WS_OVERLAPPED,
        0,
        0,
        0,
        0,
        nullptr,
        nullptr,
        instance,
        nullptr);
    if (g_mainWindow == nullptr) {
        MessageBoxW(nullptr, L"无法创建应用消息窗口。", kAppName, MB_OK | MB_ICONERROR);
        Gdiplus::GdiplusShutdown(g_gdiplusToken);
        CloseHandle(g_singleInstanceMutex);
        return 1;
    }

    try {
        std::wstring root;if(!GetApplicationDirectory(root))throw std::runtime_error("storage_write");
        Mnote::Workspace::Start(instance,root,[]{BeginCapture();},[](const std::wstring& text,bool error){
            ShowTrayNotice(error ? L"Mnote · 操作提示" : L"Mnote · 已保存",text.c_str(),error ? NIIF_WARNING : NIIF_INFO);
        },[]{DestroyWindow(g_mainWindow);}, BeginAnnotate);
    } catch(const std::exception& error) {MessageBoxW(nullptr,Mnote::ErrorText(error).c_str(),kAppName,MB_OK|MB_ICONERROR);return 1;}
    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (g_overlayWindow != nullptr && message.message == WM_KEYDOWN) {
            if(message.wParam>='1' && message.wParam<='3'){SetTool(message.wParam=='1' ? Tool::Select : message.wParam=='2' ? Tool::Pen : Tool::Highlighter);continue;}
            if (message.wParam == VK_ESCAPE) {
                CancelCapture();
                continue;
            }
            if (message.wParam == 'Z' && (GetKeyState(VK_CONTROL) & 0x8000) != 0) {
                if (!g_strokes.empty()) {
                    g_strokes.pop_back();
                    InvalidateRect(g_overlayWindow, nullptr, FALSE);
                } else {
                    MessageBeep(MB_OK);
                }
                continue;
            }
            if (message.wParam == VK_RETURN && (GetKeyState(VK_CONTROL) & 0x8000) != 0) {
                SaveCurrentCapture();
                continue;
            }
        }
        if(Mnote::Workspace::Translate(message))continue;
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

    Mnote::Workspace::Stop();
    ResetCaptureFrame();
    RemoveTrayIcon();
    Gdiplus::GdiplusShutdown(g_gdiplusToken);
    g_gdiplusToken = 0;
    if (g_singleInstanceMutex != nullptr) {
        ReleaseMutex(g_singleInstanceMutex);
        CloseHandle(g_singleInstanceMutex);
        g_singleInstanceMutex = nullptr;
    }
    return static_cast<int>(message.wParam);
}

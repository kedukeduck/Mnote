#include <windows.h>
#include <windowsx.h>

#include "sync.hpp"

#include <commctrl.h>
#include <gdiplus.h>
#include <shellapi.h>
#include <shlobj.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cwchar>
#include <limits>
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
constexpr UINT kToolbarHeight = 92;

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

    if (g_capture.width <= 0 || g_capture.height <= 0) {
        error = L"Windows 返回了无效的虚拟桌面尺寸。";
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

bool GetInboxDirectory(std::wstring& result) {
    std::wstring appDirectory;
    if (!GetApplicationDirectory(appDirectory)) {
        return false;
    }
    std::wstring inboxDirectory = appDirectory + L"\\Inbox";
    if (!EnsureDirectory(inboxDirectory)) {
        return false;
    }
    result = std::move(inboxDirectory);
    return true;
}

void OpenInbox() {
    std::wstring inbox;
    if (!GetInboxDirectory(inbox)) {
        MessageBoxW(
            g_overlayWindow != nullptr ? g_overlayWindow : g_mainWindow,
            L"无法创建本地 Inbox 目录。请检查 LOCALAPPDATA 目录权限。",
            kAppName,
            MB_OK | MB_ICONERROR);
        return;
    }
    const HINSTANCE opened = ShellExecuteW(
        nullptr, L"open", inbox.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    if (reinterpret_cast<INT_PTR>(opened) <= 32) {
        MessageBoxW(
            g_overlayWindow != nullptr ? g_overlayWindow : g_mainWindow,
            L"无法打开 Inbox 目录。",
            kAppName,
            MB_OK | MB_ICONERROR);
    }
}

std::wstring ReadControlText(HWND control) {
    if (control == nullptr) {
        return {};
    }
    const int length = GetWindowTextLengthW(control);
    if (length <= 0) {
        return {};
    }
    std::vector<wchar_t> buffer(static_cast<std::size_t>(length) + 1U, L'\0');
    const int copied = GetWindowTextW(control, buffer.data(), length + 1);
    if (copied <= 0) {
        return {};
    }
    return std::wstring(buffer.data(), static_cast<std::size_t>(copied));
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

std::wstring MakeCaptureId() {
    SYSTEMTIME local{};
    GetLocalTime(&local);
    const unsigned long sequence = g_fileSequence.fetch_add(1U, std::memory_order_relaxed) & 0xFFFFU;
    wchar_t buffer[96]{};
    std::swprintf(
        buffer,
        sizeof(buffer) / sizeof(buffer[0]),
        L"%04u%02u%02u-%02u%02u%02u-%03u-%lu-%04lx",
        static_cast<unsigned>(local.wYear),
        static_cast<unsigned>(local.wMonth),
        static_cast<unsigned>(local.wDay),
        static_cast<unsigned>(local.wHour),
        static_cast<unsigned>(local.wMinute),
        static_cast<unsigned>(local.wSecond),
        static_cast<unsigned>(local.wMilliseconds),
        static_cast<unsigned long>(GetCurrentProcessId()),
        sequence);
    return buffer;
}

std::string SelectedKind() {
    const LRESULT selection = SendMessageW(g_controls.kind, CB_GETCURSEL, 0, 0);
    if (selection == 1) {
        return "later";
    }
    if (selection == 2) {
        return "todo";
    }
    return "thought";
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
         << "    \"app_id\": " << JsonString(g_capture.sourceProcessPath) << ",\n"
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

bool WriteFileContents(const std::wstring& path, const std::string& contents, std::wstring& error) {
    HANDLE file = CreateFileW(
        path.c_str(),
        GENERIC_WRITE,
        0,
        nullptr,
        CREATE_ALWAYS,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        error = L"无法创建 JSON 临时文件。";
        return false;
    }

    std::size_t offset = 0;
    bool success = true;
    while (offset < contents.size()) {
        const std::size_t remaining = contents.size() - offset;
        const DWORD chunk = static_cast<DWORD>((std::min)(
            remaining, static_cast<std::size_t>((std::numeric_limits<DWORD>::max)())));
        DWORD written = 0;
        if (WriteFile(file, contents.data() + offset, chunk, &written, nullptr) == FALSE ||
            written == 0U) {
            success = false;
            break;
        }
        offset += static_cast<std::size_t>(written);
    }
    if (success && FlushFileBuffers(file) == FALSE) {
        success = false;
    }
    CloseHandle(file);
    if (!success) {
        DeleteFileW(path.c_str());
        error = L"写入 JSON 文件时发生错误。";
    }
    return success;
}

bool WriteJsonAtomically(const std::wstring& path, const std::string& contents, std::wstring& error) {
    const std::wstring temporary = path + L".tmp";
    if (!WriteFileContents(temporary, contents, error)) {
        return false;
    }
    if (MoveFileExW(
            temporary.c_str(),
            path.c_str(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) == FALSE) {
        DeleteFileW(temporary.c_str());
        error = L"无法原子更新 JSON 记录。";
        return false;
    }
    return true;
}

bool SavePngAtomically(
    Gdiplus::Bitmap& bitmap,
    const std::wstring& destination,
    std::wstring& error) {
    const std::wstring temporary = destination + L".tmp";
    DeleteFileW(temporary.c_str());
    if (bitmap.Save(temporary.c_str(), &kPngEncoder, nullptr) != Gdiplus::Ok) {
        DeleteFileW(temporary.c_str());
        error = L"PNG 临时文件保存失败。";
        return false;
    }
    HANDLE file = CreateFileW(
        temporary.c_str(),
        GENERIC_WRITE,
        FILE_SHARE_READ,
        nullptr,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);
    if (file == INVALID_HANDLE_VALUE || FlushFileBuffers(file) == FALSE) {
        if (file != INVALID_HANDLE_VALUE) {
            CloseHandle(file);
        }
        DeleteFileW(temporary.c_str());
        error = L"无法刷新 PNG 临时文件。";
        return false;
    }
    CloseHandle(file);
    if (MoveFileExW(
            temporary.c_str(),
            destination.c_str(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) == FALSE) {
        DeleteFileW(temporary.c_str());
        error = L"无法完成 PNG 文件。";
        return false;
    }
    return true;
}

bool ReadBoundedFile(
    const std::wstring& path,
    std::string& contents,
    std::wstring& error) {
    HANDLE file = CreateFileW(
        path.c_str(),
        GENERIC_READ,
        FILE_SHARE_READ,
        nullptr,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        error = L"无法读取本地待同步 JSON。";
        return false;
    }
    LARGE_INTEGER size{};
    constexpr LONGLONG kMaximumLocalJsonBytes = 8LL * 1024LL * 1024LL;
    if (GetFileSizeEx(file, &size) == FALSE || size.QuadPart <= 0 ||
        size.QuadPart > kMaximumLocalJsonBytes) {
        CloseHandle(file);
        error = L"本地待同步 JSON 为空或超过 8 MiB。";
        return false;
    }
    contents.assign(static_cast<std::size_t>(size.QuadPart), '\0');
    std::size_t offset = 0;
    while (offset < contents.size()) {
        const DWORD amount = static_cast<DWORD>((std::min)(
            contents.size() - offset,
            static_cast<std::size_t>((std::numeric_limits<DWORD>::max)())));
        DWORD read = 0;
        if (ReadFile(file, contents.data() + offset, amount, &read, nullptr) == FALSE ||
            read == 0U) {
            CloseHandle(file);
            contents.clear();
            error = L"读取本地待同步 JSON 时发生错误。";
            return false;
        }
        offset += static_cast<std::size_t>(read);
    }
    CloseHandle(file);
    return true;
}

bool UpdateLocalSyncState(
    const std::wstring& jsonPath,
    std::string contents,
    const std::string& state,
    const std::wstring& syncError,
    std::wstring& error) {
    const std::string stateMarker = "\"sync_state\": \"";
    const std::size_t stateMarkerAt = contents.find(stateMarker);
    if (stateMarkerAt == std::string::npos) {
        error = L"本地 JSON 缺少同步状态。";
        return false;
    }
    const std::size_t stateStart = stateMarkerAt + stateMarker.size();
    const std::size_t stateEnd = contents.find('"', stateStart);
    if (stateEnd == std::string::npos) {
        error = L"本地 JSON 的同步状态无效。";
        return false;
    }
    contents.replace(stateStart, stateEnd - stateStart, state);

    const std::string errorMarker = "\"sync_error\": ";
    const std::size_t errorMarkerAt = contents.find(errorMarker);
    const std::size_t objectEnd = contents.rfind("\n}");
    if (errorMarkerAt == std::string::npos || objectEnd == std::string::npos ||
        objectEnd <= errorMarkerAt + errorMarker.size()) {
        error = L"本地 JSON 的同步错误字段无效。";
        return false;
    }
    const std::size_t errorStart = errorMarkerAt + errorMarker.size();
    contents.replace(errorStart, objectEnd - errorStart, JsonString(syncError));
    return WriteJsonAtomically(jsonPath, contents, error);
}

bool BuildRetryPayload(
    const std::string& localJson,
    const std::string& originalBase64,
    const std::string& annotatedBase64,
    std::string& payload,
    std::wstring& error) {
    const std::string localMarker = ",\n  \"local_files\":";
    const std::size_t marker = localJson.find(localMarker);
    if (marker == std::string::npos || marker == 0U) {
        error = L"本地 JSON 不是 Mnote V1 记录。";
        return false;
    }
    payload.assign(localJson.data(), marker);
    payload += ",\n  \"assets\": {\n";
    payload += "    \"original\": { \"content_type\": \"image/png\", \"data_base64\": \"";
    payload += originalBase64;
    payload += "\" },\n";
    payload += "    \"annotated\": { \"content_type\": \"image/png\", \"data_base64\": \"";
    payload += annotatedBase64;
    payload += "\" }\n  }\n}\n";
    return true;
}

bool SaveCaptureFiles(SaveOutcome& outcome, std::wstring& error) {
    if (!HasSelection() || g_capture.bitmap == nullptr) {
        error = L"请先框选要保存的区域。";
        return false;
    }

    std::wstring appDirectory;
    std::wstring inbox;
    if (!GetApplicationDirectory(appDirectory) || !GetInboxDirectory(inbox)) {
        error = L"无法创建本地 Inbox 目录。";
        return false;
    }

    std::wstring id;
    std::wstring originalPath;
    std::wstring annotatedPath;
    std::wstring jsonPath;
    for (unsigned attempt = 0; attempt < 100U; ++attempt) {
        id = MakeCaptureId();
        originalPath = inbox + L"\\" + id + L"-original.png";
        annotatedPath = inbox + L"\\" + id + L"-annotated.png";
        jsonPath = inbox + L"\\" + id + L".json";
        if (GetFileAttributesW(originalPath.c_str()) == INVALID_FILE_ATTRIBUTES &&
            GetFileAttributesW(annotatedPath.c_str()) == INVALID_FILE_ATTRIBUTES &&
            GetFileAttributesW(jsonPath.c_str()) == INVALID_FILE_ATTRIBUTES) {
            break;
        }
        id.clear();
    }
    if (id.empty()) {
        error = L"无法生成唯一的记录文件名。";
        return false;
    }

    const int width = g_selection.right - g_selection.left;
    const int height = g_selection.bottom - g_selection.top;
    Gdiplus::Bitmap source(g_capture.bitmap, nullptr);
    if (source.GetLastStatus() != Gdiplus::Ok) {
        error = L"无法读取冻结画面。";
        return false;
    }

    Gdiplus::Bitmap original(width, height, PixelFormat32bppARGB);
    Gdiplus::Bitmap annotated(width, height, PixelFormat32bppARGB);
    if (original.GetLastStatus() != Gdiplus::Ok || annotated.GetLastStatus() != Gdiplus::Ok) {
        error = L"无法创建裁剪后的图片。";
        return false;
    }

    {
        Gdiplus::Graphics graphics(&original);
        graphics.SetCompositingMode(Gdiplus::CompositingModeSourceCopy);
        graphics.SetCompositingQuality(Gdiplus::CompositingQualityHighQuality);
        graphics.SetInterpolationMode(Gdiplus::InterpolationModeNearestNeighbor);
        const Gdiplus::Status drawStatus = graphics.DrawImage(
            &source,
            Gdiplus::Rect(0, 0, width, height),
            g_selection.left,
            g_selection.top,
            width,
            height,
            Gdiplus::UnitPixel);
        if (drawStatus != Gdiplus::Ok) {
            error = L"裁剪截图时发生错误。";
            return false;
        }
        graphics.Flush(Gdiplus::FlushIntentionSync);
    }
    {
        Gdiplus::Graphics graphics(&annotated);
        graphics.SetCompositingMode(Gdiplus::CompositingModeSourceCopy);
        if (graphics.DrawImage(&original, 0, 0, width, height) != Gdiplus::Ok) {
            error = L"复制原始截图时发生错误。";
            return false;
        }
        graphics.SetCompositingMode(Gdiplus::CompositingModeSourceOver);
        const Gdiplus::Rect imageClip(0, 0, width, height);
        DrawAllStrokes(graphics, g_selection.left, g_selection.top, &imageClip);
        graphics.Flush(Gdiplus::FlushIntentionSync);
    }

    if (!SavePngAtomically(original, originalPath, error)) {
        DeleteFileW(originalPath.c_str());
        return false;
    }
    if (!SavePngAtomically(annotated, annotatedPath, error)) {
        DeleteFileW(originalPath.c_str());
        DeleteFileW(annotatedPath.c_str());
        return false;
    }

    const std::wstring comment = ReadControlText(g_controls.comment);
    const std::string kind = SelectedKind();
    const std::wstring createdAt = FormatUtcTimestamp();
    const std::wstring originalFileName = id + L"-original.png";
    const std::wstring annotatedFileName = id + L"-annotated.png";
    const PersonalCaptureSync::Settings settings =
        PersonalCaptureSync::LoadSettings(appDirectory);
    outcome.syncState = settings.enabled ? "pending" : (settings.error.empty() ? "disabled" : "error");
    outcome.syncError = settings.error;

    const std::string initialJson = BuildJson(
        id,
        originalFileName,
        annotatedFileName,
        comment,
        kind,
        createdAt,
        settings.aiAccess,
        true,
        outcome.syncState,
        outcome.syncError,
        nullptr,
        nullptr);
    if (!WriteJsonAtomically(jsonPath, initialJson, error)) {
        DeleteFileW(originalPath.c_str());
        DeleteFileW(annotatedPath.c_str());
        return false;
    }

    if (settings.enabled) {
        std::string originalBase64;
        std::string annotatedBase64;
        std::wstring syncPreparationError;
        PersonalCaptureSync::Result syncResult;
        if (!PersonalCaptureSync::Base64File(
                originalPath, originalBase64, syncPreparationError) ||
            !PersonalCaptureSync::Base64File(
                annotatedPath, annotatedBase64, syncPreparationError)) {
            syncResult.attempted = true;
            syncResult.error = syncPreparationError;
        } else {
            const std::string uploadJson = BuildJson(
                id,
                originalFileName,
                annotatedFileName,
                comment,
                kind,
                createdAt,
                settings.aiAccess,
                false,
                {},
                {},
                &originalBase64,
                &annotatedBase64);
            syncResult = PersonalCaptureSync::PutCapture(settings, id, uploadJson);
        }
        outcome.syncState = syncResult.succeeded ? "synced" : "error";
        outcome.syncError = syncResult.error;

        const std::string finalJson = BuildJson(
            id,
            originalFileName,
            annotatedFileName,
            comment,
            kind,
            createdAt,
            settings.aiAccess,
            true,
            outcome.syncState,
            outcome.syncError,
            nullptr,
            nullptr);
        std::wstring updateError;
        if (!WriteJsonAtomically(jsonPath, finalJson, updateError)) {
            outcome.syncState = "error";
            outcome.syncError = L"本地记录已保存，但无法写入最终同步状态。";
        }
    }

    outcome.annotatedImage = std::move(annotatedPath);
    return true;
}

void RetryPendingCaptures() {
    std::wstring appDirectory;
    std::wstring inbox;
    if (!GetApplicationDirectory(appDirectory) || !GetInboxDirectory(inbox)) {
        MessageBoxW(g_mainWindow, L"无法打开本地 Inbox。", kAppName, MB_OK | MB_ICONERROR);
        return;
    }
    const PersonalCaptureSync::Settings settings =
        PersonalCaptureSync::LoadSettings(appDirectory);
    if (!settings.enabled) {
        const wchar_t* message = settings.error.empty()
            ? L"尚未配置同步。请先创建 PersonalCapture\\settings.ini。"
            : settings.error.c_str();
        MessageBoxW(g_mainWindow, message, kAppName, MB_OK | MB_ICONINFORMATION);
        return;
    }

    WIN32_FIND_DATAW found{};
    const std::wstring pattern = inbox + L"\\*.json";
    HANDLE search = FindFirstFileW(pattern.c_str(), &found);
    if (search == INVALID_HANDLE_VALUE) {
        MessageBoxW(g_mainWindow, L"没有待同步记录。", kAppName, MB_OK | MB_ICONINFORMATION);
        return;
    }

    unsigned sent = 0;
    unsigned failed = 0;
    unsigned examined = 0;
    bool reachedLimit = false;
    do {
        if (examined >= 50U) {
            reachedLimit = true;
            break;
        }
        if ((found.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0U) {
            continue;
        }
        const std::wstring fileName = found.cFileName;
        if (fileName.size() <= 5U || fileName.substr(fileName.size() - 5U) != L".json") {
            continue;
        }
        const std::wstring id = fileName.substr(0, fileName.size() - 5U);
        const std::wstring jsonPath = inbox + L"\\" + fileName;
        std::string localJson;
        std::wstring operationError;
        if (!ReadBoundedFile(jsonPath, localJson, operationError)) {
            ++failed;
            ++examined;
            continue;
        }
        if (localJson.find("\"sync_state\": \"synced\"") != std::string::npos) {
            continue;
        }
        ++examined;
        if (!UpdateLocalSyncState(
                jsonPath, localJson, "pending", L"", operationError)) {
            ++failed;
            continue;
        }

        const std::wstring originalPath = inbox + L"\\" + id + L"-original.png";
        const std::wstring annotatedPath = inbox + L"\\" + id + L"-annotated.png";
        std::string originalBase64;
        std::string annotatedBase64;
        std::string payload;
        if (!PersonalCaptureSync::Base64File(
                originalPath, originalBase64, operationError) ||
            !PersonalCaptureSync::Base64File(
                annotatedPath, annotatedBase64, operationError) ||
            !BuildRetryPayload(
                localJson, originalBase64, annotatedBase64, payload, operationError)) {
            std::wstring stateWriteError;
            UpdateLocalSyncState(
                jsonPath, localJson, "error", operationError, stateWriteError);
            ++failed;
            continue;
        }

        const PersonalCaptureSync::Result result =
            PersonalCaptureSync::PutCapture(settings, id, payload);
        std::wstring stateWriteError;
        if (result.succeeded && UpdateLocalSyncState(
                jsonPath, localJson, "synced", L"", stateWriteError)) {
            ++sent;
        } else {
            const std::wstring finalError = result.succeeded
                ? L"服务已接收记录，但本机无法更新同步状态。"
                : result.error;
            UpdateLocalSyncState(
                jsonPath, localJson, "error", finalError, stateWriteError);
            ++failed;
        }
    } while (FindNextFileW(search, &found) != FALSE);
    FindClose(search);

    std::wstring summary = L"本次已同步 " + std::to_wstring(sent) +
        L" 条，失败 " + std::to_wstring(failed) + L" 条。";
    if (reachedLimit) {
        summary += L" 为避免长时间阻塞，每次最多处理 50 条；可再次点击继续。";
    }
    MessageBoxW(
        g_mainWindow,
        summary.c_str(),
        kAppName,
        MB_OK | (failed == 0U ? MB_ICONINFORMATION : MB_ICONWARNING));
}

void UpdateSaveAvailability() {
    if (g_controls.save != nullptr) {
        EnableWindow(g_controls.save, HasSelection() ? TRUE : FALSE);
    }
}

void SetTool(Tool tool) {
    if (tool != Tool::Select && !HasSelection()) {
        MessageBeep(MB_ICONINFORMATION);
        tool = Tool::Select;
    }
    g_tool = tool;
    if (g_overlayWindow != nullptr) {
        CheckRadioButton(
            g_overlayWindow,
            kControlSelect,
            kControlHighlighter,
            tool == Tool::Select
                ? kControlSelect
                : (tool == Tool::Pen ? kControlPen : kControlHighlighter));
        SetCursor(LoadCursorW(nullptr, IDC_CROSS));
    }
}

void ApplyDefaultFont(HWND control) {
    if (control != nullptr) {
        SendMessageW(control, WM_SETFONT, reinterpret_cast<WPARAM>(GetStockObject(DEFAULT_GUI_FONT)), TRUE);
    }
}

void CreateOverlayControls(HWND parent) {
    const DWORD buttonStyle = WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_AUTORADIOBUTTON | BS_PUSHLIKE;
    g_controls.select = CreateWindowExW(
        0, L"BUTTON", L"框选", buttonStyle | WS_GROUP,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlSelect)), g_instance, nullptr);
    g_controls.pen = CreateWindowExW(
        0, L"BUTTON", L"自由笔", buttonStyle,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlPen)), g_instance, nullptr);
    g_controls.highlighter = CreateWindowExW(
        0, L"BUTTON", L"荧光笔", buttonStyle,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlHighlighter)), g_instance, nullptr);
    g_controls.undo = CreateWindowExW(
        0, L"BUTTON", L"撤销", WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlUndo)), g_instance, nullptr);
    g_controls.kindLabel = CreateWindowExW(
        0, L"STATIC", L"类型", WS_CHILD | WS_VISIBLE | SS_CENTERIMAGE,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlKindLabel)), g_instance, nullptr);
    g_controls.kind = CreateWindowExW(
        0, L"COMBOBOX", nullptr, WS_CHILD | WS_VISIBLE | WS_TABSTOP | CBS_DROPDOWNLIST,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlKind)), g_instance, nullptr);
    g_controls.save = CreateWindowExW(
        0, L"BUTTON", L"保存", WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_DEFPUSHBUTTON,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlSave)), g_instance, nullptr);
    g_controls.cancel = CreateWindowExW(
        0, L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlCancel)), g_instance, nullptr);
    g_controls.commentLabel = CreateWindowExW(
        0, L"STATIC", L"评论", WS_CHILD | WS_VISIBLE | SS_CENTERIMAGE,
        0, 0, 0, 0, parent, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlCommentLabel)), g_instance, nullptr);
    g_controls.comment = CreateWindowExW(
        WS_EX_CLIENTEDGE,
        L"EDIT",
        nullptr,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_AUTOHSCROLL,
        0,
        0,
        0,
        0,
        parent,
        reinterpret_cast<HMENU>(static_cast<INT_PTR>(kControlComment)),
        g_instance,
        nullptr);

    const HWND controls[] = {
        g_controls.select,
        g_controls.pen,
        g_controls.highlighter,
        g_controls.undo,
        g_controls.kindLabel,
        g_controls.kind,
        g_controls.save,
        g_controls.cancel,
        g_controls.commentLabel,
        g_controls.comment,
    };
    for (HWND control : controls) {
        ApplyDefaultFont(control);
    }

    SendMessageW(g_controls.kind, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"thought"));
    SendMessageW(g_controls.kind, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"later"));
    SendMessageW(g_controls.kind, CB_ADDSTRING, 0, reinterpret_cast<LPARAM>(L"todo"));
    SendMessageW(g_controls.kind, CB_SETCURSEL, 0, 0);
    SendMessageW(g_controls.comment, EM_SETCUEBANNER, TRUE, reinterpret_cast<LPARAM>(L"这段内容让我想到……"));
    SendMessageW(g_controls.comment, EM_SETLIMITTEXT, 20000, 0);
    SetTool(Tool::Select);
    UpdateSaveAvailability();
}

void LayoutOverlayControls(HWND window) {
    RECT client{};
    GetClientRect(window, &client);
    const int width = client.right - client.left;
    constexpr int margin = 9;
    constexpr int rowHeight = 32;
    constexpr int gap = 6;

    int x = margin;
    MoveWindow(g_controls.select, x, 8, 68, rowHeight, TRUE);
    x += 68 + gap;
    MoveWindow(g_controls.pen, x, 8, 76, rowHeight, TRUE);
    x += 76 + gap;
    MoveWindow(g_controls.highlighter, x, 8, 84, rowHeight, TRUE);
    x += 84 + gap;
    MoveWindow(g_controls.undo, x, 8, 66, rowHeight, TRUE);

    const int cancelWidth = 68;
    const int saveWidth = 72;
    const int cancelX = (std::max)(margin, width - margin - cancelWidth);
    const int saveX = (std::max)(margin, cancelX - gap - saveWidth);
    MoveWindow(g_controls.save, saveX, 8, saveWidth, rowHeight, TRUE);
    MoveWindow(g_controls.cancel, cancelX, 8, cancelWidth, rowHeight, TRUE);

    const int comboWidth = 104;
    const int labelWidth = 38;
    const int comboX = (std::max)(x + 76, saveX - gap - comboWidth);
    MoveWindow(g_controls.kindLabel, comboX - labelWidth - 2, 8, labelWidth, rowHeight, TRUE);
    MoveWindow(g_controls.kind, comboX, 8, comboWidth, 220, TRUE);

    MoveWindow(g_controls.commentLabel, margin, 49, 46, 30, TRUE);
    const int editX = margin + 50;
    MoveWindow(g_controls.comment, editX, 49, (std::max)(80, width - editX - margin), 30, TRUE);
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
    CloseOverlay();
}

void SaveCurrentCapture() {
    SaveOutcome outcome;
    std::wstring error;
    if (!SaveCaptureFiles(outcome, error)) {
        MessageBoxW(g_overlayWindow, error.c_str(), kAppName, MB_OK | MB_ICONERROR);
        return;
    }
    CloseOverlay();
    if (outcome.syncState == "error") {
        ShowTrayNotice(
            L"本地记录已保存",
            L"同步未完成；原图、批注图和 JSON 已保留在 PersonalCapture\\Inbox。",
            NIIF_WARNING);
    } else if (outcome.syncState == "synced") {
        ShowTrayNotice(L"记录已保存并同步", L"双图与 JSON 已写入 Inbox 和同步服务。", NIIF_INFO);
    } else {
        ShowTrayNotice(L"记录已保存", L"原图、批注图和 JSON 已写入 PersonalCapture\\Inbox。", NIIF_INFO);
    }
}

void BeginCapture() {
    if (g_overlayWindow != nullptr) {
        SetForegroundWindow(g_overlayWindow);
        return;
    }

    HWND sourceWindow = GetForegroundWindow();
    if (sourceWindow == g_mainWindow) {
        sourceWindow = nullptr;
    } else if (sourceWindow != nullptr) {
        HWND rootWindow = GetAncestor(sourceWindow, GA_ROOT);
        if (rootWindow != nullptr) {
            sourceWindow = rootWindow;
        }
    }
    std::wstring error;
    if (!CaptureVirtualDesktop(sourceWindow, error)) {
        MessageBoxW(g_mainWindow, error.c_str(), kAppName, MB_OK | MB_ICONERROR);
        return;
    }

    ResetOverlayState();
    g_overlayWindow = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        kOverlayWindowClass,
        L"Mnote - 冻结画面",
        WS_POPUP,
        g_capture.virtualX,
        g_capture.virtualY,
        g_capture.width,
        g_capture.height,
        g_mainWindow,
        nullptr,
        g_instance,
        nullptr);
    if (g_overlayWindow == nullptr) {
        ResetCaptureFrame();
        MessageBoxW(g_mainWindow, L"无法创建截图编辑窗口。", kAppName, MB_OK | MB_ICONERROR);
        return;
    }

    SetTool(Tool::Select);

    ShowWindow(g_overlayWindow, SW_SHOW);
    UpdateWindow(g_overlayWindow);
    SetWindowPos(
        g_overlayWindow,
        HWND_TOPMOST,
        g_capture.virtualX,
        g_capture.virtualY,
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

    RECT toolbar{0, 0, client.right, static_cast<LONG>(kToolbarHeight)};
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
    AppendMenuW(menu, MF_STRING, kCommandNewCapture, L"新建采集\tCtrl+Shift+F9");
    AppendMenuW(menu, MF_STRING, kCommandSyncPending, L"同步待处理记录");
    AppendMenuW(menu, MF_STRING, kCommandOpenInbox, L"打开 Inbox");
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
        L"Mnote - Ctrl+Shift+F9",
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
        ShowTrayNotice(L"采集已取消", L"显示器布局在采集过程中发生变化，请重新截图。", NIIF_WARNING);
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
                L"Ctrl+Shift+F9 已被其他程序占用。你仍可双击托盘图标开始采集。",
                kAppName,
                MB_OK | MB_ICONWARNING);
        }
        return 0;

    case WM_HOTKEY:
        if (wParam == kHotkeyId) {
            BeginCapture();
        }
        return 0;

    case kTrayMessage:
        if (static_cast<UINT>(lParam) == WM_LBUTTONDBLCLK) {
            BeginCapture();
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
            OpenInbox();
            break;
        case kCommandSyncPending:
            RetryPendingCaptures();
            break;
        case kCommandExit:
            DestroyWindow(window);
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
        UnregisterHotKey(window, kHotkeyId);
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
    g_instance = instance;
    EnableBestDpiAwareness();

    g_singleInstanceMutex = CreateMutexW(nullptr, TRUE, kSingleInstanceName);
    if (g_singleInstanceMutex == nullptr) {
        MessageBoxW(nullptr, L"无法初始化应用。", kAppName, MB_OK | MB_ICONERROR);
        return 1;
    }
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        MessageBoxW(nullptr, L"Mnote 已经在系统托盘中运行。", kAppName, MB_OK | MB_ICONINFORMATION);
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

    MSG message{};
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        if (g_overlayWindow != nullptr && message.message == WM_KEYDOWN) {
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
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }

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

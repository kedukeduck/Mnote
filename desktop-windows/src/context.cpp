#include "context.hpp"
#include <algorithm>
#include <deque>
#include <set>
#include <shellapi.h>
#include <uiautomation.h>

namespace Mnote::Context {
namespace {
template <class T> struct Com {
    T *p = nullptr;
    ~Com() {
        if (p)
            p->Release();
    }
    T *operator->() const { return p; }
    T **out() { return &p; }
};
std::wstring Caption(HWND w) {
    int n = std::min(4096, GetWindowTextLengthW(w));
    std::wstring text(static_cast<std::size_t>(n) + 1, L'\0');
    text.resize(static_cast<std::size_t>(GetWindowTextW(w, text.data(), n + 1)));
    return text;
}
std::wstring Process(DWORD pid) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!process)
        return {};
    wchar_t path[32768];
    DWORD size = 32768;
    bool ok = QueryFullProcessImageNameW(process, 0, path, &size) != FALSE;
    CloseHandle(process);
    return ok ? fs::path(std::wstring(path, size)).filename().wstring() : L"";
}
Json Scan(const Source &source, bool linkOnly) {
    if (!Same(source))
        throw std::runtime_error("source_changed");
    Com<IUIAutomation> automation;
    if (FAILED(CoCreateInstance(CLSID_CUIAutomation, nullptr, CLSCTX_INPROC_SERVER,
                                IID_IUIAutomation, reinterpret_cast<void **>(automation.out()))))
        throw std::runtime_error("context_unavailable");
    Com<IUIAutomationElement> root;
    Com<IUIAutomationTreeWalker> walker;
    if (FAILED(automation->ElementFromHandle(source.window, root.out())) || !root.p ||
        FAILED(automation->get_ControlViewWalker(walker.out())) || !walker.p)
        throw std::runtime_error("context_unavailable");
    std::wstring text, url;
    std::set<std::wstring> seen;
    int checked = 0;
    bool truncated = false;
    auto deadline = GetTickCount64() + 1800;
    std::deque<IUIAutomationElement *> queue;
    root.p->AddRef();
    queue.push_back(root.p);
    std::wstring process = Process(source.process);
    std::transform(process.begin(), process.end(), process.begin(), towlower);
    bool browser = process == L"chrome.exe" || process == L"msedge.exe" ||
                   process == L"firefox.exe" || process == L"brave.exe";
    while (!queue.empty() && checked < 600 && GetTickCount64() < deadline && text.size() < 40000) {
        Com<IUIAutomationElement> node;
        node.p = queue.front();
        queue.pop_front();
        ++checked;
        BOOL password = TRUE, offscreen = TRUE;
        CONTROLTYPEID type = 0;
        if (FAILED(node->get_CurrentIsPassword(&password)) || password ||
            FAILED(node->get_CurrentIsOffscreen(&offscreen)) || offscreen)
            continue;
        node->get_CurrentControlType(&type);
        bool consumed = false;
        if (type == UIA_DocumentControlTypeId && !linkOnly) {
            Com<IUIAutomationTextPattern> pattern;
            Com<IUIAutomationTextRange> range;
            if (SUCCEEDED(node->GetCurrentPatternAs(UIA_TextPatternId, IID_IUIAutomationTextPattern,
                                                    reinterpret_cast<void **>(pattern.out()))) &&
                pattern.p && SUCCEEDED(pattern->get_DocumentRange(range.out())) && range.p) {
                BSTR value = nullptr;
                if (SUCCEEDED(range->GetText(40001, &value)) && value) {
                    std::wstring part(value, SysStringLen(value));
                    SysFreeString(value);
                    if (!part.empty() && seen.insert(part).second) {
                        if (!text.empty())
                            text += L"\r\n";
                        text += part;
                        consumed = true;
                    }
                }
            }
        }
        if (type == UIA_TextControlTypeId && !consumed && !linkOnly) {
            BSTR value = nullptr;
            if (SUCCEEDED(node->get_CurrentName(&value)) && value) {
                std::wstring part(value, SysStringLen(value));
                SysFreeString(value);
                if (!part.empty() && seen.insert(part).second) {
                    if (!text.empty())
                        text += L"\r\n";
                    text += part;
                }
            }
        }
        if (type == UIA_EditControlTypeId) {
            // Read only an explicitly named browser address bar, never arbitrary
            // input values.
            BSTR name = nullptr;
            node->get_CurrentName(&name);
            std::wstring label = name ? name : L"";
            SysFreeString(name);
            std::transform(label.begin(), label.end(), label.begin(), towlower);
            if (browser &&
                (label == L"address and search bar" || label == L"地址和搜索栏" ||
                 label == L"搜索或输入地址" || label == L"search with google or enter address")) {
                Com<IUIAutomationValuePattern> pattern;
                if (SUCCEEDED(
                        node->GetCurrentPatternAs(UIA_ValuePatternId, IID_IUIAutomationValuePattern,
                                                  reinterpret_cast<void **>(pattern.out()))) &&
                    pattern.p) {
                    BSTR value = nullptr;
                    if (SUCCEEDED(pattern->get_CurrentValue(&value)) && value) {
                        url.assign(value, SysStringLen(value));
                        SysFreeString(value);
                    }
                }
            }
            continue;
        }
        if (consumed || (linkOnly && type == UIA_DocumentControlTypeId))
            continue;
        Com<IUIAutomationElement> child;
        walker->GetFirstChildElement(node.p, child.out());
        while (child.p && queue.size() < 600 && GetTickCount64() < deadline) {
            IUIAutomationElement *current = child.p;
            child.p = nullptr;
            queue.push_back(current);
            walker->GetNextSiblingElement(current, child.out());
        }
    }
    truncated =
        !queue.empty() || text.size() > 40000 || checked >= 600 || GetTickCount64() >= deadline;
    for (auto *node : queue)
        node->Release();
    if (text.size() > 40000)
        text.resize(40000);
    if (!text.empty() && text.back() >= 0xd800 && text.back() <= 0xdbff)
        text.pop_back();
    if (url.rfind(L"https://", 0) != 0 && url.rfind(L"http://", 0) != 0)
        url.clear();
    if (url.size() > 8192)
        url.clear();
    if (!Same(source))
        throw std::runtime_error("source_changed");
    return {{"text", Utf8(text)},
            {"url", Utf8(url)},
            {"truncated", truncated},
            {"checked_nodes", checked}};
}
} // namespace
Source Foreground() {
    Source source;
    source.window = GetAncestor(GetForegroundWindow(), GA_ROOT);
    GetWindowThreadProcessId(source.window, &source.process);
    if (source.process == GetCurrentProcessId()) {
        source.window = nullptr;
        source.process = 0;
    }
    source.metadata = {{"type", "screen"},
                       {"app_id", Utf8(Process(source.process))},
                       {"app_name", Utf8(Process(source.process))},
                       {"window_title", Utf8(Caption(source.window))},
                       {"text", ""}};
    return source;
}
bool Same(const Source &source) {
    DWORD pid = 0;
    GetWindowThreadProcessId(source.window, &pid);
    return source.window && IsWindow(source.window) && pid == source.process &&
           pid != GetCurrentProcessId() &&
           (!source.metadata.contains("window_title") ||
            source.metadata.at("window_title") == Utf8(Caption(source.window)));
}
Json ReadPage(const Source &source, const fs::path &root, bool linkOnly) {
    if (!Same(source))
        throw std::runtime_error("source_changed");
    wchar_t executable[32768];
    DWORD n = GetModuleFileNameW(nullptr, executable, 32768);
    if (!n || n >= 32768)
        throw std::runtime_error("context_unavailable");
    auto folder = root / L"ContextTemp";
    fs::create_directories(folder);
    auto output = folder / (Wide(NewId()) + L".json");
    std::wstring command = L"\"" + std::wstring(executable, n) +
                           (linkOnly ? L"\" --read-link " : L"\" --read-context ") +
                           std::to_wstring(reinterpret_cast<UINT_PTR>(source.window)) + L" " +
                           std::to_wstring(source.process) + L" \"" + output.wstring() + L"\"";
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(executable, command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                        nullptr, nullptr, &startup, &process))
        throw std::runtime_error("context_unavailable");
    CloseHandle(process.hThread);
    DWORD wait = WaitForSingleObject(process.hProcess, 4500);
    DWORD code = 1;
    if (wait != WAIT_OBJECT_0) {
        TerminateProcess(process.hProcess, 124);
        WaitForSingleObject(process.hProcess, 1000);
    }
    GetExitCodeProcess(process.hProcess, &code);
    CloseHandle(process.hProcess);
    try {
        if (code != 0 || wait != WAIT_OBJECT_0)
            throw std::runtime_error("context_unavailable");
        auto result = Parse(Read(output, 512U * 1024U));
        fs::remove(output);
        if (!Same(source))
            throw std::runtime_error("source_changed");
        return result;
    } catch (...) {
        std::error_code error;
        fs::remove(output, error);
        throw;
    }
}
std::shared_ptr<Gdiplus::Bitmap> Screenshot(const Source &source) {
    if (!Same(source) || GetAncestor(GetForegroundWindow(), GA_ROOT) != source.window)
        throw std::runtime_error("source_changed");
    int x = GetSystemMetrics(SM_XVIRTUALSCREEN), y = GetSystemMetrics(SM_YVIRTUALSCREEN),
        w = GetSystemMetrics(SM_CXVIRTUALSCREEN), h = GetSystemMetrics(SM_CYVIRTUALSCREEN);
    if (w <= 0 || h <= 0 || static_cast<std::int64_t>(w) * h > 32000000)
        throw std::runtime_error("image_too_large");
    HDC screen = GetDC(nullptr), memory = CreateCompatibleDC(screen);
    HBITMAP bitmap = CreateCompatibleBitmap(screen, w, h);
    HGDIOBJ previous = SelectObject(memory, bitmap);
    BOOL ok = BitBlt(memory, 0, 0, w, h, screen, x, y, SRCCOPY | CAPTUREBLT);
    std::shared_ptr<Gdiplus::Bitmap> result;
    if (ok) {
        Gdiplus::Bitmap sourceBitmap(bitmap, nullptr);
        result.reset(sourceBitmap.Clone(0, 0, w, h, PixelFormat32bppARGB));
    }
    SelectObject(memory, previous);
    DeleteObject(bitmap);
    DeleteDC(memory);
    ReleaseDC(nullptr, screen);
    if (!result || result->GetLastStatus() != Gdiplus::Ok)
        throw std::runtime_error("invalid_image");
    return result;
}
void SavePng(Gdiplus::Bitmap &bitmap, const fs::path &path) {
    const CLSID encoder = {
        0x557cf406, 0x1a04, 0x11d3, {0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e}};
    fs::create_directories(path.parent_path());
    auto temporary = path;
    temporary += L"." + Wide(NewId()) + L".part";
    if (bitmap.Save(temporary.c_str(), &encoder, nullptr) != Gdiplus::Ok ||
        !MoveFileExW(temporary.c_str(), path.c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        DeleteFileW(temporary.c_str());
        throw std::runtime_error("storage_write");
    }
}
int Helper(int argc, wchar_t **argv) {
    if (argc != 5 ||
        (std::wstring(argv[1]) != L"--read-context" && std::wstring(argv[1]) != L"--read-link"))
        return -1;
    HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    int result = 1;
    try {
        Source source;
        source.window = reinterpret_cast<HWND>(static_cast<UINT_PTR>(std::stoull(argv[2])));
        source.process = static_cast<DWORD>(std::stoul(argv[3]));
        AtomicWrite(fs::path(argv[4]),
                    Scan(source, std::wstring(argv[1]) == L"--read-link").dump());
        result = 0;
    } catch (const std::exception &) {
    }
    if (SUCCEEDED(com))
        CoUninitialize();
    return result;
}
} // namespace Mnote::Context

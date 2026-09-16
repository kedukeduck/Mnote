#include "updater.hpp"
#include <winhttp.h>
#include <shellapi.h>
#include <regex>
#include <array>

namespace Mnote::Updater {
namespace {
constexpr wchar_t Api[] = L"https://chenyu.online/heartnote-capture/updates/releases.json";
constexpr std::size_t MaxPackage = 128U * 1024U * 1024U;
struct Internet {
    HINTERNET handle = nullptr;
    ~Internet() {
        if (handle)
            WinHttpCloseHandle(handle);
    }
    operator HINTERNET() const { return handle; }
};
struct File {
    HANDLE handle = INVALID_HANDLE_VALUE;
    ~File() {
        if (handle != INVALID_HANDLE_VALUE)
            CloseHandle(handle);
    }
};
struct Url {
    std::wstring host, path;
    INTERNET_PORT port = 0;
    explicit Url(const std::wstring &url) {
        URL_COMPONENTS c{};
        c.dwStructSize = sizeof(c);
        c.dwHostNameLength = c.dwUrlPathLength = c.dwExtraInfoLength = c.dwUserNameLength =
            c.dwPasswordLength = static_cast<DWORD>(-1);
        if (url.size() > 16384 || url.find_first_of(L"\r\n\\#") != std::wstring::npos ||
            !WinHttpCrackUrl(url.c_str(), 0, 0, &c) || c.nScheme != INTERNET_SCHEME_HTTPS ||
            c.nPort != 443 || c.dwUserNameLength || c.dwPasswordLength)
            throw std::runtime_error("unsafe_update_url");
        host.assign(c.lpszHostName, c.dwHostNameLength);
        path.assign(c.lpszUrlPath, c.dwUrlPathLength);
        if (c.dwExtraInfoLength)
            path.append(c.lpszExtraInfo, c.dwExtraInfoLength);
        port = c.nPort;
    }
};
std::array<int, 4> Version(const std::string &value) {
    static const std::regex pattern(
        "(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(-test)?");
    std::smatch match;
    if (!std::regex_match(value, match, pattern))
        throw std::runtime_error("invalid_update_version");
    return {std::stoi(match[1]), std::stoi(match[2]), std::stoi(match[3]),
            match[4].matched ? 0 : 1};
}
std::string Get(std::wstring url, bool download, std::size_t limit,
                std::function<void(int)> progress = {}) {
    Internet session{WinHttpOpen(L"Mnote-Updater", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                 WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0)};
    if (!session.handle)
        throw std::runtime_error("update_network");
    WinHttpSetTimeouts(session, 5000, 10000, 15000, 15000);
    auto deadline = GetTickCount64() + 180000;
    for (int redirects = 0; redirects <= 5; redirects++) {
        if (download ? !AllowedDownload(url) : url != Api)
            throw std::runtime_error("unsafe_update_url");
        Url parsed(url);
        Internet connection{WinHttpConnect(session, parsed.host.c_str(), parsed.port, 0)};
        Internet request{WinHttpOpenRequest(connection, L"GET", parsed.path.c_str(), nullptr,
                                            WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                            WINHTTP_FLAG_SECURE)};
        if (!connection.handle || !request.handle)
            throw std::runtime_error("update_network");
        DWORD disabled =
            WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_AUTHENTICATION;
        if (!WinHttpSetOption(request, WINHTTP_OPTION_DISABLE_FEATURE, &disabled, sizeof(disabled)))
            throw std::runtime_error("update_network");
        if (!WinHttpSendRequest(
                request,
                download ? L"Accept: application/octet-stream\r\nCache-Control: no-cache\r\n"
                         : L"Accept: application/json\r\nCache-Control: no-cache\r\n",
                static_cast<DWORD>(-1), nullptr, 0, 0, 0) ||
            !WinHttpReceiveResponse(request, nullptr))
            throw std::runtime_error("update_network");
        DWORD status = 0, size = sizeof(status);
        if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                                 nullptr, &status, &size, nullptr))
            throw std::runtime_error("update_network");
        if (download &&
            (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
            wchar_t location[16385];
            DWORD length = sizeof(location);
            if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_LOCATION, nullptr, location, &length,
                                     nullptr))
                throw std::runtime_error("unsafe_update_url");
            url = location;
            continue;
        }
        if (status != 200)
            throw std::runtime_error(status == 403 || status == 429 ? "update_rate_limited"
                                                                    : "update_network");
        std::string bytes;
        char buffer[32768];
        DWORD count = 0;
        int last = -1;
        while (true) {
            if (GetTickCount64() > deadline)
                throw std::runtime_error("update_network");
            if (!WinHttpReadData(request, buffer, sizeof(buffer), &count))
                throw std::runtime_error("update_network");
            if (!count)
                break;
            if (bytes.size() + count > limit)
                throw std::runtime_error("update_integrity");
            bytes.append(buffer, count);
            int percent = static_cast<int>(bytes.size() * 100 / limit);
            if (progress && percent != last) {
                last = percent;
                progress(percent);
            }
        }
        return bytes;
    }
    throw std::runtime_error("unsafe_update_url");
}
void Verify(const std::string &bytes, const Release &release) {
    if (bytes.size() != release.size || Hash(bytes) != release.sha256 || bytes.size() < 64 ||
        bytes.substr(0, 2) != "MZ")
        throw std::runtime_error("update_integrity");
    std::size_t offset = 0;
    for (int i = 0; i < 4; i++)
        offset |= static_cast<std::size_t>(static_cast<unsigned char>(bytes[60 + i])) << (i * 8);
    if (offset > bytes.size() - 6 || bytes.compare(offset, 4, "PE\0\0", 4) != 0 ||
        static_cast<unsigned char>(bytes[offset + 4]) != 0x64 ||
        static_cast<unsigned char>(bytes[offset + 5]) != 0x86)
        throw std::runtime_error("update_integrity");
}
void Validate(const Release &release) {
    Version(release.version);
    auto expected = "https://chenyu.online/heartnote-capture/updates/files/mnote-windows-v" +
                    release.version + "/Mnote-Windows-" + release.version + "-Setup.exe";
    if (release.url != expected || release.sha256.size() != 64 ||
        release.sha256.find_first_not_of("abcdef0123456789") != std::string::npos ||
        !release.size || release.size > MaxPackage)
        throw std::runtime_error("invalid_update_release");
}
} // namespace
int Compare(const std::string &a, const std::string &b) {
    auto x = Version(a), y = Version(b);
    return x == y ? 0 : x < y ? -1 : 1;
}
bool AllowedDownload(const std::wstring &url) {
    try {
        Url parsed(url);
        return parsed.host == L"chenyu.online" &&
               std::regex_match(
                   parsed.path,
                   std::wregex(L"/heartnote-capture/updates/files/"
                               L"mnote-windows-v[0-9]+\\.[0-9]+\\.[0-9]+(-test)?/"
                               L"Mnote-Windows-[0-9]+\\.[0-9]+\\.[0-9]+(-test)?-Setup\\.exe"));
    } catch (const std::exception &) {
        return false;
    }
}
std::optional<Release> Select(const std::string &json, const std::string &current) {
    auto installed = Version(current);
    auto releases = Parse(json);
    if (!releases.is_array() || releases.size() > 100)
        throw std::runtime_error("invalid_update_release");
    std::optional<Release> best;
    for (const auto &item : releases) {
        std::string tag = item.value("tag_name", std::string());
        const std::string prefix = "mnote-windows-v";
        if (item.value("draft", false) || tag.rfind(prefix, 0) != 0)
            continue;
        auto version = tag.substr(prefix.size());
        std::array<int, 4> parsed;
        try {
            parsed = Version(version);
        } catch (const std::exception &) {
            continue;
        }
        if (installed[3] && (item.value("prerelease", false) || !parsed[3]))
            continue;
        if (Compare(version, current) <= 0 || (best && Compare(version, best->version) <= 0))
            continue;
        auto name = "Mnote-Windows-" + version + "-Setup.exe",
             url = "https://chenyu.online/heartnote-capture/updates/files/" + tag + "/" + name;
        for (const auto &asset : item.value("assets", Json::array())) {
            if (asset.value("name", std::string()) != name)
                continue;
            std::string digest = asset.value("digest", std::string());
            std::size_t size = asset.value("size", std::size_t(0));
            if (asset.value("browser_download_url", std::string()) != url || digest.size() != 71 ||
                digest.rfind("sha256:", 0) != 0 ||
                digest.substr(7).find_first_not_of("abcdef0123456789") != std::string::npos ||
                !size || size > MaxPackage)
                throw std::runtime_error("invalid_update_release");
            best = Release{version, url, digest.substr(7), item.value("body", std::string()), size};
            auto notes = Wide(best->notes);
            if (notes.size() > 12000) {
                notes.resize(12000);
                if (notes.back() >= 0xd800 && notes.back() <= 0xdbff)
                    notes.pop_back();
                best->notes = Utf8(notes) + "\n…";
            }
        }
    }
    return best;
}
std::optional<Release> Check() {
    return Select(Get(Api, false, 8U * 1024U * 1024U), Utf8(Current));
}
fs::path Download(const fs::path &root, const Release &release, std::function<void(int)> progress) {
    Validate(release);
    auto file = root / L"Updates" / (Wide(release.sha256) + L".exe");
    if (fs::exists(file))
        try {
            VerifyPackage(file, release);
            if (progress)
                progress(100);
            return file;
        } catch (const std::exception &) {
        }
    auto bytes = Get(Wide(release.url), true, release.size, progress);
    Verify(bytes, release);
    AtomicWrite(file, bytes);
    return file;
}
void VerifyPackage(const fs::path &path, const Release &release) {
    Validate(release);
    Verify(Read(path, MaxPackage), release);
}
void Launch(const fs::path &path, const Release &release) {
    File lock{CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0,
                          nullptr)};
    if (lock.handle == INVALID_HANDLE_VALUE)
        throw std::runtime_error("update_integrity");
    VerifyPackage(path, release);
    SHELLEXECUTEINFOW launch{};
    launch.cbSize = sizeof(launch);
    launch.fMask = SEE_MASK_NOCLOSEPROCESS;
    launch.lpVerb = L"open";
    launch.lpFile = path.c_str();
    // The main window can disappear before background sync finishes shutting down.
    // Wait for the process, not just its window, before replacing the mapped executable.
    auto parameters = L"/UPDATE /UPDATEPID=" + std::to_wstring(GetCurrentProcessId());
    launch.lpParameters = parameters.c_str();
    launch.nShow = SW_SHOWNORMAL;
    if (!ShellExecuteExW(&launch))
        throw std::runtime_error("update_launch");
    if (launch.hProcess)
        CloseHandle(launch.hProcess);
}
std::wstring Error(const std::exception &error) {
    std::string code = error.what();
    if (code == "update_rate_limited")
        return L"Mnote 更新服务暂时受限，请稍后重试。";
    if (code == "invalid_update_release" || code == "unsafe_update_url" ||
        code == "update_integrity")
        return L"更新信息或安装包校验失败，未启动安装。请重新检查更新。";
    if (code == "update_launch")
        return L"无法启动安装器，旧版仍在运行。可从官方发布页手动下载。";
    return L"更新未完成，请检查网络和存储空间后重试。现有记录不受影响。";
}
} // namespace Mnote::Updater

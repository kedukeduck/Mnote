#include "sync.hpp"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cwctype>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

namespace PersonalCaptureSync {
namespace {

constexpr std::uint64_t kMaximumAssetBytes = 16ULL * 1024ULL * 1024ULL;
constexpr std::size_t kMaximumPayloadBytes = 32U * 1024U * 1024U;

struct ParsedUrl {
    bool secure = false;
    std::wstring host;
    INTERNET_PORT port = 0;
    std::wstring basePath;
};

class InternetHandle {
public:
    explicit InternetHandle(HINTERNET value = nullptr) : value_(value) {}
    ~InternetHandle() {
        if (value_ != nullptr) {
            WinHttpCloseHandle(value_);
        }
    }
    InternetHandle(const InternetHandle&) = delete;
    InternetHandle& operator=(const InternetHandle&) = delete;
    HINTERNET get() const { return value_; }
    explicit operator bool() const { return value_ != nullptr; }

private:
    HINTERNET value_;
};

std::wstring Trim(std::wstring value) {
    const auto whitespace = [](wchar_t character) {
        return std::iswspace(static_cast<wint_t>(character)) != 0;
    };
    value.erase(value.begin(), std::find_if_not(value.begin(), value.end(), whitespace));
    value.erase(std::find_if_not(value.rbegin(), value.rend(), whitespace).base(), value.end());
    return value;
}

std::wstring ReadSetting(
    const std::wstring& path,
    const wchar_t* key,
    const wchar_t* fallback = L"") {
    std::vector<wchar_t> buffer(32768U, L'\0');
    const DWORD copied = GetPrivateProfileStringW(
        L"sync",
        key,
        fallback,
        buffer.data(),
        static_cast<DWORD>(buffer.size()),
        path.c_str());
    return Trim(std::wstring(buffer.data(), static_cast<std::size_t>(copied)));
}

std::string WideToAscii(std::wstring_view value) {
    std::string result;
    result.reserve(value.size());
    for (wchar_t character : value) {
        if (character > 0x7f) {
            return {};
        }
        result.push_back(static_cast<char>(character));
    }
    return result;
}

bool IsPrivateHost(std::wstring host) {
    while (host.size() >= 2U && host.front() == L'[' && host.back() == L']') {
        host = host.substr(1U, host.size() - 2U);
    }
    if (CompareStringOrdinal(host.c_str(), -1, L"localhost", -1, TRUE) == CSTR_EQUAL) {
        return true;
    }

    IN_ADDR address4{};
    if (InetPtonW(AF_INET, host.c_str(), &address4) == 1) {
        const auto* bytes = reinterpret_cast<const unsigned char*>(&address4);
        return bytes[0] == 10U ||
               bytes[0] == 127U ||
               (bytes[0] == 172U && bytes[1] >= 16U && bytes[1] <= 31U) ||
               (bytes[0] == 192U && bytes[1] == 168U) ||
               (bytes[0] == 169U && bytes[1] == 254U);
    }

    IN6_ADDR address6{};
    if (InetPtonW(AF_INET6, host.c_str(), &address6) == 1) {
        const auto* bytes = reinterpret_cast<const unsigned char*>(&address6);
        bool loopback = true;
        for (std::size_t index = 0; index < 15U; ++index) {
            loopback = loopback && bytes[index] == 0U;
        }
        loopback = loopback && bytes[15] == 1U;
        const bool uniqueLocal = (bytes[0] & 0xfeU) == 0xfcU;
        const bool linkLocal = bytes[0] == 0xfeU && (bytes[1] & 0xc0U) == 0x80U;
        return loopback || uniqueLocal || linkLocal;
    }
    return false;
}

bool ParseAndValidateUrl(
    const std::wstring& url,
    ParsedUrl& parsed,
    std::wstring& error) {
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    parts.dwSchemeLength = static_cast<DWORD>(-1);
    parts.dwHostNameLength = static_cast<DWORD>(-1);
    parts.dwUserNameLength = static_cast<DWORD>(-1);
    parts.dwPasswordLength = static_cast<DWORD>(-1);
    parts.dwUrlPathLength = static_cast<DWORD>(-1);
    parts.dwExtraInfoLength = static_cast<DWORD>(-1);
    if (url.empty() || url.size() > 8192U ||
        WinHttpCrackUrl(url.c_str(), static_cast<DWORD>(url.size()), 0, &parts) == FALSE) {
        error = L"settings.ini 中的 server_url 不是有效 URL。";
        return false;
    }
    if (parts.nScheme != INTERNET_SCHEME_HTTP && parts.nScheme != INTERNET_SCHEME_HTTPS) {
        error = L"server_url 只支持 http 或 https。";
        return false;
    }
    if (parts.dwHostNameLength == 0U || parts.dwUserNameLength != 0U ||
        parts.dwPasswordLength != 0U || parts.dwExtraInfoLength != 0U) {
        error = L"server_url 必须是无账号、无查询参数的服务根地址。";
        return false;
    }

    parsed.secure = parts.nScheme == INTERNET_SCHEME_HTTPS;
    parsed.host.assign(parts.lpszHostName, parts.dwHostNameLength);
    parsed.port = parts.nPort;
    if (!parsed.secure && !IsPrivateHost(parsed.host)) {
        error = L"公网或普通域名同步必须使用 HTTPS；HTTP 仅允许 localhost 或私有地址。";
        return false;
    }

    if (parts.dwUrlPathLength > 0U) {
        parsed.basePath.assign(parts.lpszUrlPath, parts.dwUrlPathLength);
    }
    while (parsed.basePath.size() > 1U && parsed.basePath.back() == L'/') {
        parsed.basePath.pop_back();
    }
    if (parsed.basePath == L"/") {
        parsed.basePath.clear();
    }
    return true;
}

std::wstring WinHttpFailure(const wchar_t* stage) {
    const DWORD code = GetLastError();
    return std::wstring(stage) + L"失败（WinHTTP " + std::to_wstring(code) + L"）。";
}

} // namespace

Settings LoadSettings(const std::wstring& appDirectory) {
    Settings settings;
    const std::wstring path = appDirectory + L"\\settings.ini";
    if (GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES) {
        return settings;
    }

    settings.serverUrl = ReadSetting(path, L"server_url");
    settings.writeToken = ReadSetting(path, L"write_token");
    const std::wstring aiAccess = ReadSetting(path, L"ai_access", L"local_only");
    settings.aiAccess = WideToAscii(aiAccess);
    if (settings.aiAccess != "deny" && settings.aiAccess != "local_only" &&
        settings.aiAccess != "remote_no_memory" && settings.aiAccess != "remote_memory") {
        settings.aiAccess = "deny";
        settings.error = L"settings.ini 中的 ai_access 无效；本条记录已按 deny 处理。";
        return settings;
    }

    if (settings.serverUrl.empty() && settings.writeToken.empty()) {
        return settings;
    }
    if (settings.serverUrl.empty() || settings.writeToken.empty()) {
        settings.error = L"settings.ini 必须同时填写 server_url 和 write_token。";
        return settings;
    }
    if (settings.writeToken.size() > 8192U) {
        settings.error = L"settings.ini 中的 write_token 过长。";
        return settings;
    }

    ParsedUrl parsed;
    if (!ParseAndValidateUrl(settings.serverUrl, parsed, settings.error)) {
        return settings;
    }
    while (!settings.serverUrl.empty() && settings.serverUrl.back() == L'/') {
        settings.serverUrl.pop_back();
    }
    settings.enabled = true;
    return settings;
}

bool Base64File(
    const std::wstring& path,
    std::string& encoded,
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
        error = L"无法读取待同步图片。";
        return false;
    }

    LARGE_INTEGER size{};
    if (GetFileSizeEx(file, &size) == FALSE || size.QuadPart <= 0 ||
        static_cast<std::uint64_t>(size.QuadPart) > kMaximumAssetBytes) {
        CloseHandle(file);
        error = L"待同步图片为空或超过 16 MiB 上限。";
        return false;
    }
    std::vector<unsigned char> bytes(static_cast<std::size_t>(size.QuadPart));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const DWORD amount = static_cast<DWORD>((std::min)(
            bytes.size() - offset,
            static_cast<std::size_t>((std::numeric_limits<DWORD>::max)())));
        DWORD read = 0;
        if (ReadFile(file, bytes.data() + offset, amount, &read, nullptr) == FALSE || read == 0U) {
            CloseHandle(file);
            error = L"读取待同步图片时发生错误。";
            return false;
        }
        offset += static_cast<std::size_t>(read);
    }
    CloseHandle(file);

    static constexpr char kAlphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    encoded.clear();
    encoded.reserve(((bytes.size() + 2U) / 3U) * 4U);
    for (std::size_t index = 0; index < bytes.size(); index += 3U) {
        const std::uint32_t first = bytes[index];
        const std::uint32_t second = index + 1U < bytes.size() ? bytes[index + 1U] : 0U;
        const std::uint32_t third = index + 2U < bytes.size() ? bytes[index + 2U] : 0U;
        const std::uint32_t block = (first << 16U) | (second << 8U) | third;
        encoded.push_back(kAlphabet[(block >> 18U) & 0x3fU]);
        encoded.push_back(kAlphabet[(block >> 12U) & 0x3fU]);
        encoded.push_back(index + 1U < bytes.size() ? kAlphabet[(block >> 6U) & 0x3fU] : '=');
        encoded.push_back(index + 2U < bytes.size() ? kAlphabet[block & 0x3fU] : '=');
    }
    return true;
}

Result PutCapture(
    const Settings& settings,
    const std::wstring& captureId,
    const std::string& jsonPayload) {
    Result result;
    if (!settings.enabled) {
        result.error = settings.error;
        return result;
    }
    result.attempted = true;
    if (jsonPayload.empty() || jsonPayload.size() > kMaximumPayloadBytes ||
        jsonPayload.size() > static_cast<std::size_t>((std::numeric_limits<DWORD>::max)())) {
        result.error = L"同步请求超过 32 MiB 上限。";
        return result;
    }

    ParsedUrl parsed;
    if (!ParseAndValidateUrl(settings.serverUrl, parsed, result.error)) {
        return result;
    }
    const std::wstring requestPath = parsed.basePath + L"/v1/captures/" + captureId;

    InternetHandle session(WinHttpOpen(
        L"MnoteCapture/0.1",
        parsed.secure ? WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY : WINHTTP_ACCESS_TYPE_NO_PROXY,
        WINHTTP_NO_PROXY_NAME,
        WINHTTP_NO_PROXY_BYPASS,
        0));
    if (!session) {
        result.error = WinHttpFailure(L"初始化同步");
        return result;
    }
    WinHttpSetTimeouts(session.get(), 3000, 3000, 5000, 5000);

    InternetHandle connection(WinHttpConnect(session.get(), parsed.host.c_str(), parsed.port, 0));
    if (!connection) {
        result.error = WinHttpFailure(L"连接同步服务");
        return result;
    }

    InternetHandle request(WinHttpOpenRequest(
        connection.get(),
        L"PUT",
        requestPath.c_str(),
        nullptr,
        WINHTTP_NO_REFERER,
        WINHTTP_DEFAULT_ACCEPT_TYPES,
        parsed.secure ? WINHTTP_FLAG_SECURE : 0));
    if (!request) {
        result.error = WinHttpFailure(L"创建同步请求");
        return result;
    }
    DWORD redirectPolicy = WINHTTP_OPTION_REDIRECT_POLICY_NEVER;
    if (WinHttpSetOption(
            request.get(),
            WINHTTP_OPTION_REDIRECT_POLICY,
            &redirectPolicy,
            sizeof(redirectPolicy)) == FALSE) {
        result.error = WinHttpFailure(L"设置同步安全策略");
        return result;
    }

    const std::wstring headers =
        L"Content-Type: application/json\r\nAuthorization: Bearer " + settings.writeToken;
    const DWORD payloadSize = static_cast<DWORD>(jsonPayload.size());
    if (WinHttpSendRequest(
            request.get(),
            headers.c_str(),
            static_cast<DWORD>(-1),
            const_cast<char*>(jsonPayload.data()),
            payloadSize,
            payloadSize,
            0) == FALSE) {
        result.error = WinHttpFailure(L"发送同步请求");
        return result;
    }
    if (WinHttpReceiveResponse(request.get(), nullptr) == FALSE) {
        result.error = WinHttpFailure(L"接收同步响应");
        return result;
    }

    DWORD status = 0;
    DWORD statusSize = sizeof(status);
    if (WinHttpQueryHeaders(
            request.get(),
            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            WINHTTP_HEADER_NAME_BY_INDEX,
            &status,
            &statusSize,
            WINHTTP_NO_HEADER_INDEX) == FALSE) {
        result.error = WinHttpFailure(L"读取同步响应");
        return result;
    }
    if (status < 200U || status >= 300U) {
        result.error = L"同步服务返回 HTTP " + std::to_wstring(status) + L"。";
        return result;
    }
    result.succeeded = true;
    return result;
}

} // namespace PersonalCaptureSync

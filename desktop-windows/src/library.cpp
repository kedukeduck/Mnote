#include "library.hpp"
#include <algorithm>
#include <bcrypt.h>
#include <chrono>
#include <cwctype>
#include <iomanip>
#include <set>
#include <sstream>
#include <stdexcept>
#include <wincrypt.h>

namespace Mnote {
namespace {
constexpr std::size_t AssetLimit = 16U * 1024U * 1024U;
bool Hex(const std::string &value, std::size_t length) {
    return value.size() == length &&
           value.find_first_not_of("0123456789abcdef") == std::string::npos;
}
std::wstring Trim(std::wstring value) {
    auto space = [](wchar_t c) { return std::iswspace(c) != 0; };
    value.erase(value.begin(), std::find_if_not(value.begin(), value.end(), space));
    value.erase(std::find_if_not(value.rbegin(), value.rend(), space).base(), value.end());
    return value;
}
std::wstring Lower(std::wstring value) {
    if (value.empty())
        return value;
    int count = LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_LOWERCASE, value.data(),
                              static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr, 0);
    if (!count)
        return value;
    std::wstring result(static_cast<std::size_t>(count), L'\0');
    LCMapStringEx(LOCALE_NAME_INVARIANT, LCMAP_LOWERCASE, value.data(),
                  static_cast<int>(value.size()), result.data(), count, nullptr, nullptr, 0);
    return result;
}
Json NormalizeTags(const Json &input) {
    if (!input.is_array() || input.size() > 4096)
        throw std::runtime_error("invalid_tags");
    Json result = Json::array();
    std::set<std::wstring> seen;
    for (const auto &item : input) {
        if (!item.is_string())
            throw std::runtime_error("invalid_tags");
        auto tag = Trim(Wide(item.get<std::string>()));
        if (!tag.empty() && tag[0] == L'#')
            tag = Trim(tag.substr(1));
        if (tag.empty())
            continue;
        if (tag.size() > 32 || tag.find_first_of(L",，;；\r\n\t") != std::wstring::npos ||
            std::any_of(tag.begin(), tag.end(), [](wchar_t c) { return c < 32 || c == 127; }))
            throw std::runtime_error("invalid_tags");
        if (seen.insert(Lower(tag)).second)
            result.push_back(Utf8(tag));
        if (result.size() > 20)
            throw std::runtime_error("invalid_tags");
    }
    return result;
}
void NormalizeOptionalFields(Json &data) {
    if (!data.is_object())
        throw std::runtime_error("invalid_record");
    if (!data.contains("source") || data["source"].is_null())
        data["source"] = Json::object();
    if (!data["source"].is_object())
        throw std::runtime_error("invalid_record");
    if (data.contains("comment") && data["comment"].is_null())
        data["comment"] = "";
    for (const char *key : {"text", "url"})
        if (data["source"].contains(key) && data["source"][key].is_null())
            data["source"][key] = "";
    if (data.contains("evidence")) {
        if (data["evidence"].is_null())
            data.erase("evidence");
        else if (data["evidence"].is_object() && data["evidence"].contains("context")) {
            auto &context = data["evidence"]["context"];
            if (context.is_null())
                data["evidence"].erase("context");
            else if (context.is_object())
                for (const char *key : {"text", "image"})
                    if (context.contains(key) && context[key].is_null())
                        context.erase(key);
        }
    }
}
void Validate(Json &data, bool hasImage) {
    NormalizeOptionalFields(data);
    if (!data.is_object() || !SafeId(data.value("id", std::string())))
        throw std::runtime_error("invalid_record");
    auto source = data.value("source", Json::object());
    if (!source.is_object())
        throw std::runtime_error("invalid_record");
    std::wstring note = Wide(data.value("comment", std::string())),
                 quote = Wide(source.value("text", std::string()));
    std::wstring url = Wide(source.value("url", std::string()));
    std::wstring original = Wide(data.value("evidence", Json::object())
                                     .value("context", Json::object())
                                     .value("text", Json::object())
                                     .value("full_text", std::string()));
    if (note.size() > 20000 || quote.size() > 100000 || original.size() > 40000 ||
        url.size() > 8192)
        throw std::runtime_error("text_too_long");
    if (!url.empty() &&
        (url.find_first_of(L"\r\n\t") != std::wstring::npos ||
         url.find(L':') == std::wstring::npos ||
         url.substr(0, url.find(L':'))
                 .find_first_not_of(L"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
                                    L"RSTUVWXYZ0123456789+.-") != std::wstring::npos))
        throw std::runtime_error("invalid_url");
    if (!hasImage && Trim(note).empty() && Trim(quote).empty() && Trim(original).empty() &&
        url.empty())
        throw std::runtime_error("empty_record");
    data["tags"] = NormalizeTags(data.value("tags", Json::array()));
    std::string access = data.value("ai_access", std::string("local_only"));
    if (access != "deny" && access != "local_only" && access != "remote_no_memory" &&
        access != "remote_memory")
        throw std::runtime_error("invalid_record");
    data["ai_access"] = access;
    if (data.dump().size() > 8U * 1024U * 1024U)
        throw std::runtime_error("record_too_large");
}
void Png(const std::string &bytes) {
    if (bytes.size() < 24 || bytes.size() > AssetLimit ||
        bytes.compare(0, 8, "\x89PNG\r\n\x1a\n", 8) != 0)
        throw std::runtime_error("invalid_image");
    auto integer = [&](std::size_t at) {
        std::uint64_t n = 0;
        for (int i = 0; i < 4; i++)
            n = n * 256 + static_cast<unsigned char>(bytes[at + static_cast<std::size_t>(i)]);
        return n;
    };
    auto width = integer(16), height = integer(20);
    if (!width || !height || width > 32768 || height > 32768 || width * height > 32000000)
        throw std::runtime_error("image_too_large");
}
std::string Protect(const std::string &bytes, bool decrypt) {
    DATA_BLOB input{static_cast<DWORD>(bytes.size()),
                    reinterpret_cast<BYTE *>(const_cast<char *>(bytes.data()))},
        output{};
    BOOL ok = decrypt ? CryptUnprotectData(&input, nullptr, nullptr, nullptr, nullptr,
                                           CRYPTPROTECT_UI_FORBIDDEN, &output)
                      : CryptProtectData(&input, L"Mnote account", nullptr, nullptr, nullptr,
                                         CRYPTPROTECT_UI_FORBIDDEN, &output);
    if (!ok)
        throw std::runtime_error("session_locked");
    std::string result(reinterpret_cast<char *>(output.pbData), output.cbData);
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return result;
}
std::int64_t Now() {
    return std::chrono::duration_cast<std::chrono::seconds>(
               std::chrono::system_clock::now().time_since_epoch())
        .count();
}
void Success(const PersonalCaptureSync::Response &response) {
    if (response.status < 200 || response.status >= 300)
        throw std::runtime_error("http_" + std::to_string(response.status));
}
std::string ErrorCode(const std::exception &error) {
    std::string code = error.what();
    const std::set<std::string> known = {
        "http_401",        "http_403",      "http_404",           "http_409",      "login_required",
        "account_changed", "network_io",    "response_too_large", "invalid_image", "asset_mismatch",
        "invalid_json",    "storage_write", "image_too_large"};
    return known.count(code) ? code : "sync_failed";
}
} // namespace

std::string Utf8(const std::wstring &value) {
    if (value.empty())
        return {};
    int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(),
                                   static_cast<int>(value.size()), nullptr, 0, nullptr, nullptr);
    if (!size)
        throw std::runtime_error("invalid_text");
    std::string result(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
                        result.data(), size, nullptr, nullptr);
    return result;
}
std::wstring Wide(const std::string &value) {
    if (value.empty())
        return {};
    int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                                   static_cast<int>(value.size()), nullptr, 0);
    if (!size)
        throw std::runtime_error("invalid_text");
    std::wstring result(static_cast<std::size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
                        result.data(), size);
    return result;
}
std::string Hash(const std::string &bytes) {
    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    unsigned char digest[32];
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0)
        throw std::runtime_error("crypto_failed");
    NTSTATUS status = BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0);
    if (status == 0)
        status = BCryptHashData(hash, reinterpret_cast<PUCHAR>(const_cast<char *>(bytes.data())),
                                static_cast<ULONG>(bytes.size()), 0);
    if (status == 0)
        status = BCryptFinishHash(hash, digest, sizeof(digest), 0);
    if (hash)
        BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    if (status != 0)
        throw std::runtime_error("crypto_failed");
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (auto b : digest)
        out << std::setw(2) << static_cast<int>(b);
    return out.str();
}
std::string Read(const fs::path &path, std::size_t limit) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ,
                              FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                              OPEN_EXISTING, 0, nullptr);
    if (file == INVALID_HANDLE_VALUE)
        throw std::runtime_error("storage_read");
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(file, &size) || size.QuadPart < 0 ||
        static_cast<std::uint64_t>(size.QuadPart) > limit) {
        CloseHandle(file);
        throw std::runtime_error("storage_read");
    }
    std::string bytes(static_cast<std::size_t>(size.QuadPart), '\0');
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        DWORD read = 0;
        if (!ReadFile(file, bytes.data() + offset, static_cast<DWORD>(bytes.size() - offset), &read,
                      nullptr) ||
            !read) {
            CloseHandle(file);
            throw std::runtime_error("storage_read");
        }
        offset += read;
    }
    CloseHandle(file);
    return bytes;
}
void AtomicWrite(const fs::path &path, const std::string &bytes) {
    fs::create_directories(path.parent_path());
    fs::path temporary = path;
    temporary += L"." + Wide(NewId()) + L".part";
    HANDLE file = CreateFileW(temporary.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE)
        throw std::runtime_error("storage_write");
    DWORD written = 0;
    bool ok = WriteFile(file, bytes.data(), static_cast<DWORD>(bytes.size()), &written, nullptr) &&
              written == bytes.size() && FlushFileBuffers(file);
    CloseHandle(file);
    if (ok)
        ok = MoveFileExW(temporary.c_str(), path.c_str(),
                         MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != FALSE;
    if (!ok) {
        DeleteFileW(temporary.c_str());
        throw std::runtime_error("storage_write");
    }
}
Json Parse(const std::string &bytes) {
    try {
        return Json::parse(bytes, [](int depth, Json::parse_event_t, Json &) {
            if (depth > 64)
                throw std::runtime_error("json_depth");
            return true;
        });
    } catch (const std::exception &) {
        throw std::runtime_error("invalid_json");
    }
}
bool SafeId(const std::string &value) {
    return !value.empty() && value.size() <= 128 && value.find("..") == std::string::npos &&
           value.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRS"
                                   "TUVWXYZ0123456789._-") == std::string::npos &&
           std::isalnum(static_cast<unsigned char>(value[0]));
}
std::string NewId() {
    GUID id{};
    if (CoCreateGuid(&id) != S_OK)
        throw std::runtime_error("id_failed");
    wchar_t buffer[40]{};
    StringFromGUID2(id, buffer, 40);
    std::wstring value(buffer);
    return Utf8(value.substr(1, 36));
}
std::string Timestamp() {
    SYSTEMTIME time{};
    GetSystemTime(&time);
    char buffer[40];
    std::snprintf(buffer, sizeof(buffer), "%04u-%02u-%02uT%02u:%02u:%02u.%03uZ", time.wYear,
                  time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond,
                  time.wMilliseconds);
    return buffer;
}
Json Tags(const std::wstring &input) {
    if (input.size() > 4096)
        throw std::runtime_error("invalid_tags");
    Json tags = Json::array();
    std::wstring part;
    for (wchar_t c : input + L",") {
        if (std::wstring(L",，;；\r\n").find(c) != std::wstring::npos) {
            if (!Trim(part).empty())
                tags.push_back(Utf8(part));
            part.clear();
        } else
            part += c;
    }
    return NormalizeTags(tags);
}
std::wstring TagText(const Json &record) {
    std::wstring text;
    for (const auto &tag : record.value("tags", Json::array())) {
        if (!text.empty())
            text += L"，";
        text += Wide(tag.get<std::string>());
    }
    return text;
}
Json TextContext(const std::wstring &text, const std::string &origin, const std::wstring &quote) {
    if (text.size() > 40000)
        throw std::runtime_error("text_too_long");
    std::size_t start = quote.empty() ? std::wstring::npos : text.find(quote);
    bool unique = start != std::wstring::npos && text.find(quote, start + 1) == std::wstring::npos;
    Json result = {{"full_text", Utf8(text)},
                   {"origin", origin},
                   {"extent", "provided_text"},
                   {"offset_unit", "utf16_code_units"},
                   {"match", unique                        ? "unique"
                             : start == std::wstring::npos ? "not_found"
                                                           : "ambiguous"},
                   {"start", nullptr},
                   {"end", nullptr}};
    if (unique) {
        result["start"] = start;
        result["end"] = start + quote.size();
    }
    return result;
}
std::wstring ErrorText(const std::exception &error) {
    std::string code = error.what();
    if (code == "source_changed")
        return L"原应用窗口已变化或未在前台，请回到原页面后重新打开随手记。";
    if (code == "context_unavailable")
        return L"应用未提供页面文字或读取超时。可以手动粘贴原文，或保留页面截图。";
    if (code == "editor_open")
        return L"请先保存或关闭正在编辑的记录，再开始新的摘录。";
    if (code == "clipboard_unavailable" || code == "clipboard_busy")
        return L"剪贴板没有可用文字或正被占用，请先复制文字再重试。";
    if (code == "account_changed")
        return L"账号已变化，操作未写入其他账号。请重新打开记录。";
    if (code == "record_changed" || code == "http_409")
        return L"记录已有其他修改，本机内容保留，未覆盖云端。请核对后再操作。";
    if (code == "http_401" || code == "http_403" || code == "login_required")
        return L"请重新登录；用户名、密码或激活码可能不正确，或会话已过期。";
    if (code == "invalid_tags")
        return L"每条最多 20 个标签，每个最多 32 字；请用逗号分隔。";
    if (code == "text_too_long")
        return L"内容过长：想法最多 2 万字，摘录 10 万字，原文 4 "
               L"万字。未截断保存。";
    if (code == "empty_record")
        return L"请至少保留想法、摘录、原文、链接或截图中的一种。";
    if (code == "invalid_server" || code == "invalid_url")
        return L"请检查链接。同步服务器需 HTTPS（本机或私有 IP 可用 HTTP）。";
    if (code == "session_locked")
        return L"此 Windows 用户无法解密会话，请重新登录。原记录未删除。";
    if (code == "more_records_pending")
        return L"本轮已同步一部分记录，请再次刷新继续拉取。";
    if (code == "record_unavailable")
        return L"记录已删除或不可读取，未覆盖原数据。";
    if (code == "invalid_image" || code == "image_too_large" || code == "asset_mismatch")
        return L"图片不可读取、超过限制或校验失败。未保存不完整的附件。";
    return L"操作未完成，已有记录和输入内容保留。请检查网络、磁盘空间后重试。";
}

Library::Library(fs::path root, Transport transport)
    : root_(std::move(root)), transport_(std::move(transport)) {
    if (!transport_)
        transport_ = PersonalCaptureSync::Request;
    fs::create_directories(root_);
    if (fs::exists(root_ / L"account.session")) {
        try {
            auto saved = Parse(Protect(Read(root_ / L"account.session", 64U * 1024U), true));
            account_.server = Wide(saved.at("server"));
            account_.id = saved.at("id");
            account_.username = saved.at("username");
            account_.token = Wide(saved.at("token"));
            account_.expires = saved.at("expires");
            if (!Hex(account_.id, 32))
                throw std::runtime_error("session_locked");
            account_.scope = Hash(Utf8(account_.server) + "\n" + account_.id);
        } catch (const std::exception &) {
            account_ = Account{};
            account_.scope = "locked";
            account_.id = "locked";
            account_.username = "会话不可用，请重新登录";
        }
    }
}
Account Library::account() {
    std::lock_guard<std::recursive_mutex> guard(mutex_);
    return account_;
}
void Library::interrupt() {
    std::lock_guard<std::recursive_mutex> guard(mutex_);
    ++generation_;
}
fs::path Library::directory(const std::string &scope) {
    if (scope != "guest" && !Hex(scope, 64))
        throw std::runtime_error("session_locked");
    auto path = root_ / L"Library" / Wide(scope);
    fs::create_directories(path / L"records");
    return path;
}
void Library::require(const std::string &scope) {
    if (scope != account_.scope)
        throw std::runtime_error("account_changed");
}
PersonalCaptureSync::Response Library::request(const Account &a, const std::wstring &method,
                                               const std::wstring &path, const std::string &payload,
                                               std::size_t limit, int revision) {
    PersonalCaptureSync::Settings settings;
    settings.enabled = true;
    settings.serverUrl = a.server;
    settings.writeToken = a.token;
    return transport_(settings, method, path, payload, limit, revision);
}
void Library::login(const std::wstring &server, const std::wstring &username,
                    const std::wstring &password, const std::wstring &invitation) {
    std::uint64_t start;
    {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        start = generation_;
    }
    Account next;
    next.server = Trim(server);
    while (!next.server.empty() && next.server.back() == L'/')
        next.server.pop_back();
    Json body = {{"username", Utf8(Trim(username))}, {"password", Utf8(password)}};
    if (!invitation.empty())
        body["invitation"] = Utf8(Trim(invitation));
    auto response =
        request(next, L"POST", invitation.empty() ? L"/v1/auth/login" : L"/v1/auth/activate",
                body.dump(), 65536);
    Success(response);
    auto session = Parse(response.body);
    next.id = session.at("account_id");
    next.username = session.at("username");
    next.token = Wide(session.at("access_token"));
    next.expires = session.at("expires_at");
    if (!Hex(next.id, 32) || next.token.rfind(L"mns_", 0) != 0 || next.token.size() > 8192 ||
        next.expires <= Now())
        throw std::runtime_error("invalid_session");
    for (wchar_t c : next.token)
        if (c < 33 || c > 126)
            throw std::runtime_error("invalid_session");
    next.scope = Hash(Utf8(next.server) + "\n" + next.id);
    Json saved = {{"server", Utf8(next.server)},
                  {"id", next.id},
                  {"username", next.username},
                  {"token", Utf8(next.token)},
                  {"expires", next.expires}};
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    if (generation_ != start)
        throw std::runtime_error("account_changed");
    AtomicWrite(root_ / L"account.session", Protect(saved.dump(), false));
    account_ = next;
    ++generation_;
}
void Library::logout() {
    Account old;
    {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        old = account_;
        if (fs::exists(root_ / L"account.session") && !fs::remove(root_ / L"account.session"))
            throw std::runtime_error("storage_write");
        account_ = Account{};
        ++generation_;
    }
    if (!old.token.empty())
        try {
            request(old, L"POST", L"/v1/auth/logout", "{}", 65536);
        } catch (const std::exception &) {
        }
}
Record Library::readRecord(const fs::path &file) {
    Json saved = Parse(Read(file));
    Record record;
    record.file = file;
    if (saved.contains("record")) {
        record.data = saved.at("record");
        record.id = record.data.at("id");
        record.state = saved.value("state", std::string("local"));
        record.error = saved.value("error", std::string());
        record.revision = saved.value("revision", 0);
        record.deleted = saved.value("deleted", false);
        record.operation = saved.value("operation", std::string("upsert"));
        auto entries = saved.value("assets", Json::object());
        for (const auto &item : entries.items()) {
            std::string filename = item.value();
            if (!Hex(filename.substr(0, 64), 64) || filename.size() != 68 ||
                filename.substr(64) != ".png")
                throw std::runtime_error("invalid_asset_path");
            record.assets[item.key()] =
                file.parent_path().parent_path() / L"assets" / Wide(filename);
        }
    } else {
        record.data = saved;
        record.id = record.data.at("id");
        record.state = "local";
        auto entries = saved.value("local_files", Json::object());
        for (const auto &item : entries.items()) {
            std::wstring filename = Wide(item.value());
            if (filename.empty() || fs::path(filename).filename() != fs::path(filename) ||
                filename.find(L':') != std::wstring::npos)
                throw std::runtime_error("invalid_asset_path");
            record.assets[item.key()] = file.parent_path() / filename;
        }
        for (const char *key : {"local_files", "sync_state", "sync_error"})
            record.data.erase(key);
    }
    if (!SafeId(record.id))
        throw std::runtime_error("invalid_record");
    NormalizeOptionalFields(record.data);
    record.data["tags"] = NormalizeTags(record.data.value("tags", Json::array()));
    return record;
}
std::vector<Record> Library::list(const std::string &scope) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    require(scope);
    std::map<std::string, Record> found;
    std::vector<fs::path> folders;
    if (scope == "guest" && fs::exists(root_ / L"Inbox"))
        folders.push_back(root_ / L"Inbox");
    folders.push_back(directory(scope) / L"records");
    for (const auto &folder : folders)
        for (const auto &entry : fs::directory_iterator(folder)) {
            if (!entry.is_regular_file() || entry.path().extension() != L".json")
                continue;
            auto record = readRecord(entry.path());
            found[record.id] = std::move(record);
        }
    std::vector<Record> result;
    for (auto &entry : found)
        result.push_back(std::move(entry.second));
    std::sort(result.begin(), result.end(), [](const Record &a, const Record &b) {
        return a.data.value("created_at", std::string()) >
               b.data.value("created_at", std::string());
    });
    return result;
}
void Library::publish(const std::string &scope, const Record &record) {
    auto folder = directory(scope);
    Json assets = Json::object();
    for (const auto &item : record.assets) {
        if (item.first != "original" && item.first != "annotated" && item.first != "context")
            throw std::runtime_error("invalid_image");
        auto bytes = Read(item.second, AssetLimit);
        Png(bytes);
        std::string name = Hash(bytes) + ".png";
        auto path = folder / L"assets" / Wide(name);
        if (!fs::exists(path))
            AtomicWrite(path, bytes);
        assets[item.first] = name;
    }
    Json saved = {{"record", record.data},     {"revision", record.revision},
                  {"state", record.state},     {"error", record.error},
                  {"deleted", record.deleted}, {"operation", record.operation},
                  {"assets", assets}};
    AtomicWrite(folder / L"records" / (Wide(record.id) + L".json"), saved.dump(2));
}
std::string Library::fingerprint(const Record &record) {
    auto source = record.data.value("source", Json::object());
    auto text = record.data.value("evidence", Json::object())
                    .value("context", Json::object())
                    .value("text", Json::object());
    return Hash(Json{{"comment", record.data.value("comment", std::string())},
                     {"ai_access", record.data.value("ai_access", std::string("local_only"))},
                     {"quote", source.value("text", std::string())},
                     {"kind", record.data.value("kind", std::string("thought"))},
                     {"url", source.value("url", std::string())},
                     {"original", text.value("full_text", std::string())},
                     {"tags", record.data.value("tags", Json::array())},
                     {"deleted", record.deleted}}
                    .dump());
}
Record Library::save(const std::string &scope, Json data,
                     const std::map<std::string, fs::path> &assets, const std::string &baseline) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    require(scope);
    Record record;
    record.data = std::move(data);
    record.assets = assets;
    if (baseline.empty()) {
        record.id = record.data.value("id", NewId());
        for (const auto &current : list(scope))
            if (current.id == record.id)
                throw std::runtime_error("record_changed");
        record.data["id"] = record.id;
        record.data["schema_version"] = 1;
        if (!record.data.contains("created_at"))
            record.data["created_at"] = Timestamp();
    } else {
        std::string id = record.data.at("id");
        auto records = list(scope);
        auto found = std::find_if(records.begin(), records.end(),
                                  [&](const Record &r) { return r.id == id; });
        if (found == records.end() || found->deleted)
            throw std::runtime_error("record_unavailable");
        if (fingerprint(*found) != baseline)
            throw std::runtime_error("record_changed");
        Json edits = record.data;
        record = *found;
        record.data["comment"] = edits.value("comment", std::string());
        record.data["ai_access"] =
            edits.value("ai_access", record.data.value("ai_access", std::string("local_only")));
        record.data["tags"] = edits.value("tags", Json::array());
        record.data["kind"] =
            edits.value("kind", record.data.value("kind", std::string("thought")));
        auto source = edits.value("source", Json::object());
        std::string quote = source.value("text", std::string()),
                    before =
                        record.data.value("source", Json::object()).value("text", std::string());
        auto text = edits.value("evidence", Json::object())
                        .value("context", Json::object())
                        .value("text", Json::object());
        std::string original = text.value("full_text", std::string()),
                    oldOriginal = record.data.value("evidence", Json::object())
                                      .value("context", Json::object())
                                      .value("text", Json::object())
                                      .value("full_text", std::string());
        record.data["source"]["url"] = source.value("url", std::string());
        record.data["source"]["text"] = quote;
        if (quote != before || original != oldOriginal) {
            auto &context = record.data["evidence"]["context"];
            if (!context.is_object())
                context = Json::object();
            if (original.empty())
                context.erase("text");
            else {
                auto previous = context.value("text", Json::object());
                auto next =
                    TextContext(Wide(original),
                                original != oldOriginal
                                    ? "user_edited"
                                    : previous.value("origin", std::string("user_supplied")),
                                Wide(quote));
                for (const char *key : {"relation_to_quote", "source_package", "source_url"})
                    if (previous.contains(key))
                        next[key] = previous[key];
                context["text"] = next;
            }
            auto &edited = context["text_edit"];
            if (quote != before) {
                edited["quote_modified"] = true;
                record.data["evidence"]["exact_text"] = {{"text", quote},
                                                         {"delivered_by", "user_edited"}};
                record.data["source"]["text_origin"] = "user_edited";
                if (record.data["source"].contains("selectors") &&
                    record.data["source"]["selectors"].is_object())
                    record.data["source"]["selectors"]["validation_status"] =
                        "invalidated_by_user_edit";
            }
            if (original != oldOriginal)
                edited["original_modified"] = true;
            edited["edited_at"] = Now() * 1000;
        }
    }
    Validate(record.data, !record.assets.empty());
    record.state = scope == "guest" ? "local" : "pending";
    record.error.clear();
    publish(scope, record);
    return readRecord(directory(scope) / L"records" / (Wide(record.id) + L".json"));
}
void Library::erase(const std::string &scope, const std::string &id, const std::string &baseline) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    require(scope);
    for (auto record : list(scope))
        if (record.id == id) {
            if (fingerprint(record) != baseline)
                throw std::runtime_error("record_changed");
            record.deleted = true;
            record.operation = "delete";
            record.state = scope == "guest" ? "local" : "pending";
            record.error.clear();
            publish(scope, record);
            return;
        }
    throw std::runtime_error("record_unavailable");
}
void Library::restore(const std::string &scope, const std::string &id) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    require(scope);
    for (auto record : list(scope))
        if (record.id == id && record.deleted) {
            record.deleted = false;
            record.operation = record.revision > 0 ? "restore" : "upsert";
            record.state = scope == "guest" ? "local" : "pending";
            publish(scope, record);
            return;
        }
    throw std::runtime_error("record_unavailable");
}
int Library::importGuest(const std::string &scope) {
    std::lock_guard<std::recursive_mutex> lock(mutex_);
    require(scope);
    if (scope == "guest")
        return 0;
    std::map<std::string, Record> guests;
    for (auto folder : {root_ / L"Inbox", directory("guest") / L"records"})
        if (fs::exists(folder))
            for (const auto &item : fs::directory_iterator(folder))
                if (item.is_regular_file() && item.path().extension() == L".json") {
                    auto r = readRecord(item.path());
                    guests[r.id] = r;
                }
    auto existing = list(scope);
    std::set<std::string> ids;
    for (const auto &r : existing)
        ids.insert(r.id);
    int count = 0;
    for (auto &item : guests)
        if (!item.second.deleted && !ids.count(item.first)) {
            auto record = item.second;
            record.revision = 0;
            record.state = "pending";
            record.error.clear();
            publish(scope, record);
            ++count;
        }
    return count;
}

int Library::sync() {
    std::unique_lock<std::mutex> runner(syncMutex_, std::try_to_lock);
    if (!runner.owns_lock())
        return 0;
    Account owner;
    std::uint64_t generation;
    {
        std::lock_guard<std::recursive_mutex> lock(mutex_);
        owner = account_;
        generation = generation_;
    }
    if (!owner.signedIn())
        return 0;
    if (owner.expires <= Now())
        throw std::runtime_error("login_required");
    auto check = [&]() {
        require(owner.scope);
        if (generation != generation_)
            throw std::runtime_error("account_changed");
    };
    int changed = 0;
    bool failed = false;
    for (auto snapshot : list(owner.scope)) {
        if (snapshot.state == "synced")
            continue;
        try {
            {
                std::lock_guard<std::recursive_mutex> lock(mutex_);
                check();
            }
            PersonalCaptureSync::Response response;
            std::wstring path = L"/v1/captures/" + Wide(snapshot.id);
            if (snapshot.deleted) {
                if (snapshot.revision == 0) {
                    auto exists = request(owner, L"GET", path);
                    if (exists.status == 404)
                        response = {200, Json{{"revision", 0}}.dump()};
                    else {
                        Success(exists);
                        auto remote = Parse(exists.body);
                        Record same;
                        same.data = remote;
                        same.deleted = true;
                        same.data["tags"] = NormalizeTags(same.data.value("tags", Json::array()));
                        if (fingerprint(same) != fingerprint(snapshot))
                            throw std::runtime_error("http_409");
                        snapshot.revision = remote.at("revision");
                    }
                }
                if (snapshot.revision > 0)
                    response = request(owner, L"DELETE", path, "", 65536, snapshot.revision);
            } else if (snapshot.operation == "restore") {
                response = request(owner, L"POST", path + L"/restore",
                                   Json{{"base_revision", snapshot.revision}}.dump());
            } else {
                Json payload = snapshot.data;
                for (const char *key : {"assets", "revision", "deleted", "updated_at"})
                    payload.erase(key);
                payload["base_revision"] =
                    snapshot.revision > 0 ? Json(snapshot.revision) : Json(nullptr);
                if (snapshot.revision == 0) {
                    payload["assets"] = Json::object();
                    for (const auto &asset : snapshot.assets) {
                        std::string encoded;
                        std::wstring ignored;
                        if (!PersonalCaptureSync::Base64File(asset.second.wstring(), encoded,
                                                             ignored))
                            throw std::runtime_error("invalid_image");
                        payload["assets"][asset.first] = {{"content_type", "image/png"},
                                                          {"data_base64", encoded}};
                    }
                }
                response = request(owner, L"PUT", path, payload.dump());
            }
            if (snapshot.deleted && response.status == 404)
                response = {200, Json{{"revision", snapshot.revision}}.dump()};
            Success(response);
            int revision = Parse(response.body).at("revision");
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            check();
            for (auto current : list(owner.scope))
                if (current.id == snapshot.id) {
                    current.revision = revision;
                    if (fingerprint(current) == fingerprint(snapshot)) {
                        current.state = "synced";
                        current.error.clear();
                        current.operation = "upsert";
                    }
                    publish(owner.scope, current);
                    ++changed;
                    break;
                }
        } catch (const std::exception &error) {
            std::string code = ErrorCode(error);
            if (code == "account_changed" || code == "http_401" || code == "http_403")
                throw std::runtime_error(code);
            failed = true;
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            check();
            for (auto current : list(owner.scope))
                if (current.id == snapshot.id && fingerprint(current) == fingerprint(snapshot)) {
                    current.state = "error";
                    current.error = code;
                    publish(owner.scope, current);
                    break;
                }
        }
    }
    fs::path cursorPath = directory(owner.scope) / L"cursor.json";
    std::int64_t cursor =
        fs::exists(cursorPath) ? Parse(Read(cursorPath)).value("cursor", std::int64_t(0)) : 0;
    for (int pageNumber = 0; pageNumber < 40; pageNumber++) {
        {
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            check();
        }
        auto response =
            request(owner, L"GET", L"/v1/changes?after=" + std::to_wstring(cursor) + L"&limit=50");
        Success(response);
        auto page = Parse(response.body);
        auto changes = page.at("changes");
        std::int64_t previous = cursor;
        if (!changes.is_array() || changes.size() > 500)
            throw std::runtime_error("invalid_feed");
        for (const auto &change : changes) {
            std::int64_t sequence = change.at("sequence");
            if (sequence <= previous)
                throw std::runtime_error("invalid_feed");
            previous = sequence;
            std::string id = change.at("capture_id"), op = change.at("operation");
            if (!SafeId(id))
                throw std::runtime_error("invalid_feed");
            int eventRevision = change.value("revision", 0);
            Record next;
            next.id = id;
            next.state = "synced";
            next.revision = eventRevision;
            bool tombstone = op == "delete" || op == "purge";
            if (!tombstone && op != "upsert" && op != "restore")
                throw std::runtime_error("invalid_feed");
            if (!tombstone && !change.contains("record"))
                continue; // Server may have purged a historical upsert.
            if (!tombstone) {
                next.data = change.at("record");
                if (next.data.at("id") != id)
                    throw std::runtime_error("invalid_feed");
                next.revision = next.data.at("revision");
                next.deleted = next.data.value("deleted", false);
                tombstone = next.deleted;
            }
            std::string baseline;
            {
                std::lock_guard<std::recursive_mutex> lock(mutex_);
                check();
                bool skip = false;
                for (const auto &current : list(owner.scope))
                    if (current.id == id) {
                        if (current.state != "synced" || current.revision > next.revision) {
                            skip = true;
                            break;
                        }
                        baseline = fingerprint(current);
                        if (tombstone) {
                            next.data = current.data;
                            next.assets = current.assets;
                        }
                        break;
                    }
                if (skip)
                    continue;
            }
            if (tombstone) {
                next.deleted = true;
                if (next.data.empty())
                    next.data = {{"id", id}, {"tags", Json::array()}};
            } else {
                auto attachments = next.data.value("assets", Json::object());
                for (const char *role : {"original", "annotated", "context"})
                    if (attachments.contains(role)) {
                        auto asset = attachments.at(role);
                        std::string hash = asset.at("sha256");
                        std::size_t size = asset.at("size");
                        if (!Hex(hash, 64) || size == 0 || size > AssetLimit ||
                            asset.value("content_type", std::string()) != "image/png")
                            throw std::runtime_error("asset_mismatch");
                        auto destination = directory(owner.scope) / L"assets" / Wide(hash + ".png");
                        if (!fs::exists(destination) ||
                            Hash(Read(destination, AssetLimit)) != hash) {
                            auto downloaded =
                                request(owner, L"GET",
                                        L"/v1/captures/" + Wide(id) + L"/assets/" + Wide(role), "",
                                        AssetLimit);
                            Success(downloaded);
                            if (downloaded.body.size() != size || Hash(downloaded.body) != hash)
                                throw std::runtime_error("asset_mismatch");
                            Png(downloaded.body);
                            {
                                std::lock_guard<std::recursive_mutex> lock(mutex_);
                                check();
                                AtomicWrite(destination, downloaded.body);
                            }
                        }
                        next.assets[role] = destination;
                    }
                auto image = next.data.value("evidence", Json::object())
                                 .value("context", Json::object())
                                 .value("image", Json::object());
                if (image.value("retained", false) && !next.assets.count("context"))
                    throw std::runtime_error("asset_mismatch");
                next.data.erase("assets");
                Validate(next.data, !next.assets.empty());
            }
            {
                std::lock_guard<std::recursive_mutex> lock(mutex_);
                check();
                bool unchanged = true;
                for (const auto &current : list(owner.scope))
                    if (current.id == id &&
                        (current.state != "synced" ||
                         (!baseline.empty() && fingerprint(current) != baseline)))
                        unchanged = false;
                if (unchanged) {
                    publish(owner.scope, next);
                    ++changed;
                }
            }
        }
        std::int64_t nextCursor = page.at("next_sequence");
        if (nextCursor != previous || (page.value("has_more", false) && changes.empty()))
            throw std::runtime_error("invalid_feed");
        {
            std::lock_guard<std::recursive_mutex> lock(mutex_);
            check();
            AtomicWrite(cursorPath, Json{{"cursor", nextCursor}}.dump());
        }
        cursor = nextCursor;
        if (!page.value("has_more", false)) {
            if (failed)
                throw std::runtime_error("sync_failed");
            return changed;
        }
    }
    throw std::runtime_error("more_records_pending");
}
} // namespace Mnote

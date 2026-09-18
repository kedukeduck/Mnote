#pragma once
#include "sync.hpp"
#include <filesystem>
#include <functional>
#include <json.hpp>
#include <mutex>
#include <string>
#include <vector>
#include <windows.h>

namespace Mnote {
using Json = nlohmann::json;
namespace fs = std::filesystem;
std::string Utf8(const std::wstring &value);
std::wstring Wide(const std::string &value);
std::string Hash(const std::string &bytes);
std::string Read(const fs::path &path, std::size_t limit = 8U * 1024U * 1024U);
void AtomicWrite(const fs::path &path, const std::string &bytes);
Json Parse(const std::string &bytes);
Json Tags(const std::wstring &input);
std::wstring TagText(const Json &record);
std::wstring ErrorText(const std::exception &error);
Json TextContext(const std::wstring &text, const std::string &origin, const std::wstring &quote);
std::string NewId();
std::string Timestamp();
bool SafeId(const std::string &value);

struct Account {
    std::string scope = "guest", id, username;
    std::wstring server = L"https://chenyu.online/heartnote-capture", token;
    std::int64_t expires = 0;
    bool signedIn() const { return !id.empty(); }
};
struct Record {
    Json data = Json::object();
    std::string id, state, error, operation = "upsert";
    int revision = 0;
    bool deleted = false;
    fs::path file;
    std::map<std::string, fs::path> assets;
};
using Transport = std::function<PersonalCaptureSync::Response(
    const PersonalCaptureSync::Settings &, const std::wstring &, const std::wstring &,
    const std::string &, std::size_t, int)>;

class Library {
  public:
    explicit Library(fs::path root, Transport transport = {});
    Account account();
    void login(const std::wstring &server, const std::wstring &username,
               const std::wstring &password, const std::wstring &invitation = L"");
    void logout();
    std::vector<Record> list(const std::string &scope);
    Record save(const std::string &scope, Json record,
                const std::map<std::string, fs::path> &assets = {},
                const std::string &baseline = "");
    void erase(const std::string &scope, const std::string &id, const std::string &baseline);
    void restore(const std::string &scope, const std::string &id);
    int sync();
    void interrupt();
    int importGuest(const std::string &scope);
    fs::path root() const { return root_; }
    static std::string fingerprint(const Record &record);
    static bool exportable(const Record &record);
    void exportMarkdown(const std::string &scope, const std::vector<Record> &records,
                        const fs::path &destination);
    Json markdownExports(const std::string &scope);
    std::string markdownExportText(const std::string &scope, const std::string &id);
    Json existingTags(const std::string &scope);
    Json markdownExportImages(const std::string &scope, const std::string &id);
    std::string markdownExportImage(const std::string &scope, const std::string &id,
                                    const Json &asset);
    void revokeMarkdownExport(const std::string &scope, const std::string &id);

  private:
    fs::path root_;
    std::recursive_mutex mutex_;
    std::mutex syncMutex_;
    Account account_;
    Transport transport_;
    std::uint64_t generation_ = 0;
    fs::path directory(const std::string &scope);
    void require(const std::string &scope);
    Record readRecord(const fs::path &file);
    void publish(const std::string &scope, const Record &record);
    PersonalCaptureSync::Response request(const Account &account, const std::wstring &method,
                                          const std::wstring &path, const std::string &payload = {},
                                          std::size_t limit = 8U * 1024U * 1024U, int revision = 0);
};
} // namespace Mnote

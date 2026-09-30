#pragma once
#include "library.hpp"
#include <atomic>
#include <memory>

namespace Mnote::Ai {
constexpr std::size_t ConversationLimit = 8U * 1024U * 1024U;
struct Conversation {
    Json data = Json::object();
    int revision = 0;
    bool dirty = false, deleted = false;
    std::string draft, consent, parentGeneration;
    Json conflict = Json::object(); // Local recovery copy, never submitted to a model or sync API.
};
using Delta = std::function<void(const std::string &)>;
using Provider = std::function<void(const Json &, const Json &, const Delta &, std::atomic_bool &)>;

// A separate account-scoped store. No chat or credential fields are written into records.
class Store {
  public:
    Store(Library &library, Transport transport = {}, Provider provider = {});
    Json profiles(const std::string &scope); // Local-only DPAPI protected profiles, including keys.
    void saveProfile(const std::string &scope, Json profile, bool makeDefault = true);
    void eraseProfile(const std::string &scope, const std::string &id);
    Json defaultProfile(const std::string &scope);
    void test(const std::string &scope, const Json &profile, bool image, std::atomic_bool &stop);
    std::vector<Conversation> list(const std::string &scope, const std::string &record = "");
    Conversation get(const std::string &scope, const std::string &id);
    Conversation create(const std::string &scope, const Record &record, const Json &modules,
                        const Json &profile);
    void draft(const std::string &scope, const std::string &id, const std::string &value);
    std::string scratch(const std::string &scope, const std::string &record);
    void scratch(const std::string &scope, const std::string &record, const std::string &value);
    void resolve(const std::string &scope, const std::string &id);
    void rename(const std::string &scope, const std::string &id, const std::string &title);
    void erase(const std::string &scope, const std::string &id);
    void prune(const std::string &scope);
    int sync(const std::string &scope);
    void send(const std::string &scope, const std::string &id, const std::string &input,
              const Json &profile, const std::string &consentFingerprint, bool confirmed,
              std::atomic_bool &stop, const std::function<void(const Conversation &)> &update);
    static bool chatted(const Conversation &conversation);
    static Json payload(const Conversation &conversation, const Json &profile);
    static Json snapshot(const Record &record, const Json &modules);
    static Json sanitizeModel(const Json &profile);
    static bool snapshotMatches(const Record &, const Json &snapshot);
    static void validateProfile(const Json &profile);
    static void validate(const Json &data);
    static std::wstring error(const std::exception &error);
    static void request(const Json &profile, const Json &payload, const Delta &delta,
                        std::atomic_bool &stop);

  private:
    Library &library_;
    Transport transport_;
    Provider provider_;
    std::recursive_mutex mutex_;
    fs::path directory(const std::string &scope);
    Account require(const std::string &scope);
    Record parent(const std::string &scope, const std::string &id);
    std::string parentGeneration(const std::string &scope, const std::string &id);
    Conversation read(const fs::path &path);
    void write(const std::string &scope, const Conversation &conversation);
    void upload(const std::string &scope, Conversation &conversation);
    PersonalCaptureSync::Response api(const Account &, const std::wstring &, const std::wstring &,
                                      const std::string & = "", int revision = 0);
};
} // namespace Mnote::Ai

#include "../src/ai_chat.hpp"
#include <gdiplus.h>
#include <iostream>
using namespace Mnote;
namespace {
void Require(bool ok, const char *code) {
    if (!ok)
        throw std::runtime_error(code);
}
std::string Consent(const Record &r, const Json &p) {
    return Hash(r.data.value("ai_access", std::string()) + "\n" + Library::fingerprint(r) + "\n" +
                Ai::Store::sanitizeModel(p).dump());
}
} // namespace
int wmain(int argc, wchar_t **argv) {
    if (argc != 4)
        return 2;
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ULONG_PTR graphics = 0;
    Gdiplus::GdiplusStartupInput g;
    Gdiplus::GdiplusStartup(&graphics, &g, nullptr);
    int result = 0;
    try {
        fs::path root = argv[1];
        std::wstring endpoint = argv[2], invitation = argv[3];
        Library first(root / L"chat-first"), second(root / L"chat-second");
        first.login(endpoint, L"windows-chat-live", L"only-synthetic-password-123", invitation);
        auto scope = first.account().scope;
        Json note = {{"id", NewId()},
                     {"created_at", Timestamp()},
                     {"kind", "thought"},
                     {"comment", "Synthetic personal thought"},
                     {"source",
                      {{"text", "Synthetic excerpt"},
                       {"url", "https://source.invalid/fixture"},
                       {"type", "clipboard"}}},
                     {"tags", Json::array({"synthetic"})},
                     {"ai_access", "local_only"}};
        auto record = first.save(scope, note, {{"original", root / L"fixture.png"}});
        Json p = {{"id", NewId()},
                  {"label", "Mock, never invoked"},
                  {"base_url", "https://model.invalid/v1"},
                  {"model", "mock-image"},
                  {"key", "sk_SYNTHETIC_LOCAL_ONLY"},
                  {"vision", true}};
        int calls = 0;
        Ai::Provider provider = [&](const Json &, const Json &body, const Ai::Delta &delta,
                                    std::atomic_bool &) {
            ++calls;
            Require(body["store"] == false, "store_default");
            Require(body.dump().find("data:image/jpeg;base64,") != std::string::npos,
                    "image_bytes_missing");
            delta("Synthetic answer");
        };
        Ai::Store chats(first, {}, provider), other(second, {}, provider);
        auto c = chats.create(scope, record,
                              Json::array({"thought", "excerpt", "images", "metadata"}), p);
        auto id = c.data.at("id").get<std::string>();
        std::atomic_bool stop(false);
        chats.send(scope, id, "First synthetic question", p, Consent(record, p), true, stop,
                   [](const auto &) {});
        Require(calls == 1 && chats.get(scope, id).revision == 2, "first_send_CAS");
        second.login(endpoint, L"windows-chat-live", L"only-synthetic-password-123");
        second.sync();
        other.sync(scope);
        auto pulled = other.get(scope, id);
        Require(pulled.data["snapshot"] == chats.get(scope, id).data["snapshot"],
                "snapshot_cross_device");
        Require(pulled.data["messages"].size() == 2, "history_pull");
        auto secondRecord = second.list(scope).front();
        other.send(scope, id, "Second device question", p, Consent(secondRecord, p), true, stop,
                   [](const auto &) {});
        chats.sync(scope);
        Require(chats.get(scope, id).data["messages"].size() == 4, "history_continue");
        chats.rename(scope, id, "First device title");
        other.rename(scope, id, "Second device title");
        chats.sync(scope);
        bool conflict = false;
        try {
            other.sync(scope);
        } catch (const std::exception &e) {
            conflict = std::string(e.what()) == "chat_conflict";
        }
        Require(conflict, "CAS_conflict_missing");
        other.resolve(scope, id);
        Require(!other.get(scope, id).conflict.empty(), "local_conflict_lost");
        Require(other.get(scope, id).data["title"] == "First device title", "explicit_resolution");
        record = first.list(scope).front();
        first.erase(scope, record.id, Library::fingerprint(record));
        first.sync();
        chats.sync(scope);
        second.sync();
        other.sync(scope);
        Require(chats.list(scope).empty() && other.list(scope).empty(), "delete_cascade");
        first.restore(scope, record.id);
        first.sync();
        chats.sync(scope);
        second.sync();
        other.sync(scope);
        Require(chats.list(scope).empty() && other.list(scope).empty(), "restore_resurrection");
        Require(calls == 2, "unexpected_model_invocation");
        first.logout();
        second.logout();
        std::cout << "Windows AI local-server integration passed: HTTP CAS headers, JPEG snapshot, "
                     "account pull, continuation, conflict copy, delete/restore isolation; no real "
                     "provider calls\n";
    } catch (const std::exception &e) {
        std::cerr << "Windows AI local-server integration failed: " << e.what() << "\n";
        result = 1;
    }
    Gdiplus::GdiplusShutdown(graphics);
    CoUninitialize();
    return result;
}

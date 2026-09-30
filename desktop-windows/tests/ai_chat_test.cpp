#include "../src/ai_chat.hpp"
#include <gdiplus.h>
#include <iostream>
#include <set>

using namespace Mnote;
using Response = PersonalCaptureSync::Response;
namespace {
int checks = 0;
void Expect(bool condition) {
    ++checks;
    if (!condition)
        throw std::runtime_error("AI assertion " + std::to_string(checks));
}
template <class F> void Throws(F action, const std::string &code = "") {
    bool failed = false;
    try {
        action();
    } catch (const std::exception &e) {
        failed = true;
        if (!code.empty()) {
            if (e.what() != code)
                std::cerr << "Expected " << code << ", got " << e.what() << "\n";
            Expect(e.what() == code);
        }
    }
    Expect(failed);
}
Json Profile() {
    return {{"id", NewId()},
            {"label", "Synthetic only"},
            {"base_url", "https://models.invalid/v1"},
            {"model", "mock-vision"},
            {"key", "sk_SYNTHETIC_SECRET_NEVER_REAL"},
            {"vision", true},
            {"context_chars", 180000}};
}
Json Note() {
    return {
        {"id", NewId()},
        {"created_at", Timestamp()},
        {"schema_version", 1},
        {"kind", "thought"},
        {"comment", "我的想法 THOUGHT"},
        {"ai_access", "local_only"},
        {"tags", {"合成"}},
        {"source",
         {{"type", "clipboard"},
          {"text", "摘录 EXCERPT"},
          {"url", "https://source.invalid/record"}}},
        {"evidence",
         {{"context",
           {{"text", {{"full_text", "原文 ORIGINAL"}, {"relation_to_quote", "unverified"}}}}}}}};
}
std::string Consent(const Record &record, const Json &profile) {
    return Hash(record.data.value("ai_access", std::string()) + "\n" +
                Library::fingerprint(record) + "\n" + Ai::Store::sanitizeModel(profile).dump());
}
struct Server {
    Json records = Json::object(), recordEvents = Json::array(), conversations = Json::object(),
         events = Json::array();
    int modelCalls = 0, chatPuts = 0;
    bool conflict = false, offline = false;
    std::vector<std::string> methods;
    Response call(const PersonalCaptureSync::Settings &settings, const std::wstring &method,
                  const std::wstring &path, const std::string &bytes, std::size_t, int revision) {
        methods.push_back(Utf8(method + L" " + path));
        if (path == L"/v1/auth/login")
            return {200, Json{{"account_id", std::string(32, 'b')},
                              {"username", "chat-test"},
                              {"access_token", "mns_SYNTHETIC_ACCOUNT"},
                              {"expires_at", 4102444800LL}}
                             .dump()};
        if (path == L"/v1/auth/logout")
            return {200, "{}"};
        Expect(settings.writeToken == L"mns_SYNTHETIC_ACCOUNT");
        if (path.rfind(L"/v1/changes?", 0) == 0) {
            auto at = std::stoll(path.substr(path.find(L"after=") + 6));
            Json changes = Json::array();
            for (const auto &event : recordEvents)
                if (event.at("sequence").get<std::int64_t>() > at)
                    changes.push_back(event);
            return {200, Json{{"changes", changes},
                              {"next_sequence",
                               recordEvents.empty()
                                   ? at
                                   : recordEvents.back().at("sequence").get<std::int64_t>()},
                              {"has_more", false}}
                             .dump()};
        }
        if (path.rfind(L"/v1/captures/", 0) == 0) {
            auto id = Utf8(path.substr(13));
            if (method == L"PUT") {
                auto data = Parse(bytes);
                int rev = records.contains(id) ? records[id].at("revision").get<int>() : 0;
                if (rev && data.value("base_revision", -1) != rev)
                    return {409, "{}"};
                data.erase("base_revision");
                data.erase("assets");
                data["revision"] = rev + 1;
                records[id] = data;
                recordEvents.push_back({{"sequence", recordEvents.size() + 1},
                                        {"revision", rev + 1},
                                        {"operation", "upsert"},
                                        {"capture_id", id},
                                        {"record", data}});
                return {200, data.dump()};
            }
            if (!records.contains(id))
                return {404, "{}"};
            if (method == L"DELETE") {
                records[id]["deleted"] = true;
                records[id]["revision"] = revision + 1;
                recordEvents.push_back({{"sequence", recordEvents.size() + 1},
                                        {"revision", revision + 1},
                                        {"operation", "delete"},
                                        {"capture_id", id}});
            }
            return {200, records[id].dump()};
        }
        if (offline)
            throw std::runtime_error("network_io");
        if (path.rfind(L"/v1/chat/changes?", 0) == 0) {
            auto at = std::stoll(path.substr(path.find(L"after=") + 6));
            Json changes = Json::array();
            for (const auto &event : events)
                if (event.at("sequence").get<std::int64_t>() > at)
                    changes.push_back(event);
            return {200,
                    Json{{"changes", changes},
                         {"next_cursor",
                          events.empty() ? at : events.back().at("sequence").get<std::int64_t>()},
                         {"has_more", false}}
                        .dump()};
        }
        if (path.rfind(L"/v1/chat/conversations/", 0) == 0) {
            auto id = Utf8(path.substr(23)); // Keep the route seam explicit and checked below.
            id = Utf8(path.substr(std::wstring(L"/v1/chat/conversations/").size()));
            if (method == L"GET")
                return conversations.contains(id) ? Response{200, conversations[id].dump()}
                                                  : Response{404, "{}"};
            if (conflict)
                return {409, "{}"};
            int current =
                conversations.contains(id) ? conversations[id].at("revision").get<int>() : 0;
            if (revision != current)
                return {409, "{}"};
            auto body =
                method == L"DELETE" ? conversations.value(id, Json::object()) : Parse(bytes);
            if (method == L"PUT") {
                ++chatPuts;
                Ai::Store::validate(body);
                Expect(body.dump().find("sk_SYNTHETIC") == std::string::npos);
                Expect(records.contains(body.at("record_id")));
            }
            if (method == L"DELETE" && !current)
                return {404, "{}"};
            events.push_back({{"sequence", events.size() + 1},
                              {"id", id},
                              {"record_id", body.at("record_id")},
                              {"deleted", method == L"DELETE"},
                              {"revision", current + 1}});
            if (method == L"DELETE") {
                conversations.erase(id);
                return {200, Json{{"id", id}, {"deleted", true}, {"revision", current + 1}}.dump()};
            }
            body["revision"] = current + 1;
            conversations[id] = body;
            return {200, body.dump()};
        }
        throw std::runtime_error("unexpected_fake_route");
    }
    Transport transport() {
        return [this](const auto &s, const auto &m, const auto &p, const auto &b, std::size_t limit,
                      int revision) { return call(s, m, p, b, limit, revision); };
    }
};
void Cases(const fs::path &root) {
    auto profile = Profile();
    Ai::Store::validateProfile(profile);
    for (const auto &url :
         {"http://models.invalid/v1", "https://user:pass@models.invalid/v1",
          "https://models.invalid/v1?key=secret", "https://models.invalid/v1#secret"}) {
        auto p = profile;
        p["base_url"] = url;
        Throws([&] { Ai::Store::validateProfile(p); }, "chat_endpoint");
    }
    auto bad = profile;
    bad["key"] = "bad\r\nheader";
    Throws([&] { Ai::Store::validateProfile(bad); }, "chat_key");
    Expect(Ai::Store::sanitizeModel(profile).size() == 3);
    Library local(root / L"local");
    int calls = 0;
    Json sent;
    std::function<void(std::atomic_bool &)> during;
    Ai::Store store(
        local, {},
        [&](const Json &p, const Json &body, const Ai::Delta &delta, std::atomic_bool &stop) {
            ++calls;
            sent = body;
            Expect(p["key"] == profile["key"]);
            Expect(body["store"] == false);
            Expect(body["stream"] == true);
            delta("思考 ");
            if (during)
                during(stop);
            delta("完整回复");
        });
    store.saveProfile("guest", profile);
    Expect(store.defaultProfile("guest")["id"] == profile["id"]);
    auto encrypted = Read(root / L"local" / L"ai-chat" / L"guest" / L"models.dpapi");
    Expect(encrypted.find("sk_SYNTHETIC") == std::string::npos);
    auto another = Profile();
    another["model"] = "another";
    store.saveProfile("guest", another);
    Expect(store.profiles("guest").size() == 2);
    Expect(store.defaultProfile("guest")["id"] == another["id"]);
    store.eraseProfile("guest", another["id"]);
    Expect(store.profiles("guest").size() == 1);
    auto record = local.save("guest", Note());
    store.scratch("guest", record.id, "first unsent draft");
    Expect(store.scratch("guest", record.id) == "first unsent draft");
    auto c = store.create("guest", record, Json::array({"thought"}), profile);
    auto id = c.data.at("id").get<std::string>();
    Expect(store.list("guest").empty());
    Expect(c.data["snapshot"]["thought"] == "我的想法 THOUGHT");
    Expect(c.data["snapshot"]["excerpt"] == "");
    Expect(c.data["snapshot"]["original"] == "");
    Expect(c.data["snapshot"]["source_url"] == "");
    Expect(local.list("guest").front().data["ai_access"] == "local_only");
    store.draft("guest", id, "preserved draft");
    Expect(store.get("guest", id).draft == "preserved draft");
    std::atomic_bool stop(false);
    int updates = 0;
    Throws(
        [&] {
            store.send("guest", id, "question", profile, "wrong", true, stop,
                       [&](const auto &) { ++updates; });
        },
        "chat_consent");
    Expect(calls == 0);
    store.send("guest", id, "question", profile, Consent(record, profile), true, stop,
               [&](const auto &) { ++updates; });
    Expect(calls == 1 && updates >= 3);
    c = store.get("guest", id);
    Expect(c.data["messages"].size() == 2);
    Expect(c.data["messages"].back()["content"] == "思考 完整回复");
    Expect(Ai::Store::chatted(c));
    Expect(c.draft.empty());
    Expect(store.list("guest", record.id).size() == 1);
    Expect(sent.dump().find("EXCERPT") == std::string::npos);
    Expect(sent.dump().find("ORIGINAL") == std::string::npos);
    Expect(sent.dump().find("source.invalid") == std::string::npos);
    auto snapshot = c.data["snapshot"];
    auto androidSnapshot = snapshot;
    androidSnapshot["fingerprint"] = "different-platform-fingerprint";
    Expect(Ai::Store::snapshotMatches(record, androidSnapshot));
    auto updated = record.data;
    updated["comment"] = "changed";
    record = local.save("guest", updated, {}, Library::fingerprint(record));
    Expect(!Ai::Store::snapshotMatches(record, androidSnapshot));
    store.send("guest", id, "second", profile, Consent(record, profile), true, stop,
               [](const auto &) {});
    c = store.get("guest", id);
    Expect(c.data["snapshot"] == snapshot);
    Expect(c.data["messages"].size() == 4);
    Expect(sent["messages"].size() == 5);
    store.rename("guest", id, "Renamed");
    Expect(store.get("guest", id).data["title"] == "Renamed");
    during = [](auto &cancel) { cancel.store(true); };
    store.send("guest", id, "stop", profile, Consent(record, profile), true, stop,
               [](const auto &) {});
    c = store.get("guest", id);
    Expect(c.data["messages"].back()["status"] == "stopped");
    Expect(c.data["messages"].back()["content"] == "思考 ");
    stop.store(false);
    during = {};
    auto fresh = store.create("guest", record,
                              Json::array({"thought", "excerpt", "original", "metadata"}), profile);
    Expect(!Ai::Store::chatted(fresh));
    auto low = profile;
    low["context_chars"] = 1000;
    auto large = record.data;
    large["comment"] = std::string(3000, 'x');
    auto big = local.save("guest", large, {}, Library::fingerprint(record));
    auto limited = store.create("guest", big, Json::array({"thought"}), low);
    int before = calls;
    Throws(
        [&] {
            store.send("guest", limited.data["id"], "question", low, Consent(big, low), true, stop,
                       [](const auto &) {});
        },
        "chat_context");
    Expect(calls == before);
    Expect(store.get("guest", limited.data["id"]).data["messages"].empty());
    Expect(store.get("guest", limited.data["id"]).draft == "question");
    store.erase("guest", id);
    Throws([&] { store.get("guest", id); }, "chat_deleted");
    Expect(store.list("guest").empty());
    auto imagePath = root / L"synthetic.png";
    Gdiplus::Bitmap pixel(3200, 1800, PixelFormat24bppRGB);
    Gdiplus::Graphics g(&pixel);
    g.Clear(Gdiplus::Color(255, 36, 73, 78));
    CLSID png{};
    CLSIDFromString(L"{557cf406-1a04-11d3-9a73-0000f81ef32e}", &png);
    Expect(pixel.Save(imagePath.c_str(), &png, nullptr) == Gdiplus::Ok);
    auto photo = local.save("guest", Note(), {{"annotated", imagePath}, {"context", imagePath}});
    auto image = store.create("guest", photo, Json::array({"images"}), profile);
    Expect(image.data["snapshot"]["images"].size() == 2);
    auto b64 = image.data["snapshot"]["images"][0]["data_base64"].get<std::string>();
    Expect(b64.rfind("/9j/", 0) == 0);
    Expect(b64.find('\0') == std::string::npos);
    auto noVision = profile;
    noVision["vision"] = false;
    Throws([&] { Ai::Store::payload(image, noVision); }, "chat_vision");
    auto imageBody = Ai::Store::payload(image, profile);
    Expect(imageBody.dump().find("data:image/jpeg;base64,") != std::string::npos);
    Expect(imageBody.dump().find("https://") == std::string::npos);
    // Deletion destroys snapshot bodies and drafts, even when the note is restored later.
    auto photoId = image.data["id"].get<std::string>();
    store.draft("guest", photoId, "private draft");
    local.erase("guest", photo.id, Library::fingerprint(photo));
    local.restore("guest", photo.id);
    // A restore before deferred pruning must still never expose the prior snapshot.
    Throws([&] { store.get("guest", photoId); }, "chat_deleted");
    store.prune("guest");
    Throws([&] { store.get("guest", photoId); }, "chat_deleted");
    auto tomb = Read(root / L"local" / L"ai-chat" / L"guest" / (Wide(photoId) + L".json"));
    Expect(tomb.find("data_base64") == std::string::npos &&
           tomb.find("private draft") == std::string::npos);
    // A crashed, expired placeholder can be deleted without a new billed request.
    auto expired = store.create("guest", big, Json::array({"thought"}), profile);
    auto expiredId = expired.data.at("id").get<std::string>();
    auto expiredPath = root / L"local" / L"ai-chat" / L"guest" / (Wide(expiredId) + L".json");
    auto envelope = Parse(Read(expiredPath));
    envelope["conversation"]["updated_at"] = "2000-01-01T00:00:00.000Z";
    envelope["conversation"]["messages"] = Json::array({{{"id", NewId()}, {"role", "assistant"},
        {"content", "interrupted private partial"}, {"created_at", "2000-01-01T00:00:00.000Z"},
        {"status", "generating"}, {"request_id", NewId()}, {"model", "mock"}, {"error", ""}}});
    AtomicWrite(expiredPath, envelope.dump());
    store.erase("guest", expiredId);
    Throws([&] { store.get("guest", expiredId); }, "chat_deleted");
    Expect(Read(expiredPath).find("interrupted private partial") == std::string::npos);
    // Real account wrapper with fully synthetic transport: acquire CAS before provider.
    Server server;
    Library signedLibrary(root / L"account", server.transport());
    signedLibrary.login(L"https://sync.invalid", L"test", L"fake");
    auto scope = signedLibrary.account().scope;
    auto synced = signedLibrary.save(scope, Note());
    int remoteCalls = 0;
    Ai::Store remote(
        signedLibrary, server.transport(),
        [&](const Json &, const Json &body, const Ai::Delta &delta, std::atomic_bool &) {
            ++remoteCalls;
            Expect(server.chatPuts > 0);
            Expect(body.dump().find("mns_SYNTHETIC") == std::string::npos);
            delta("mock account response");
        });
    auto session = remote.create(scope, synced, Json::array({"thought"}), profile);
    auto rid = session.data["id"].get<std::string>();
    remote.send(scope, rid, "hello", profile, Consent(synced, profile), true, stop,
                [](const auto &) {});
    Expect(remoteCalls == 1);
    Expect(remote.get(scope, rid).revision == 2 && !remote.get(scope, rid).dirty);
    remote.sync(scope);
    Expect(server.conversations[rid]["messages"].back()["status"] == "complete");
    // Conflict is explicit; resolving stores the local pending history separately and keeps draft.
    remote.rename(scope, rid, "local title");
    auto external = server.conversations[rid];
    external["title"] = "other device";
    external["revision"] = 3;
    server.conversations[rid] = external;
    server.events.push_back({{"sequence", server.events.size() + 1},
                             {"id", rid},
                             {"record_id", synced.id},
                             {"deleted", false},
                             {"revision", 3}});
    remote.draft(scope, rid, "unsent survives conflict");
    Throws([&] { remote.sync(scope); }, "chat_conflict");
    remote.resolve(scope, rid);
    Expect(remote.get(scope, rid).draft == "unsent survives conflict");
    Expect(remote.get(scope, rid).data["title"] == "other device");
    Expect(remote.get(scope, rid).conflict["title"] == "local title");
    server.offline = true;
    int oldCalls = remoteCalls;
    Throws([&] {
        remote.send(scope, rid, "offline", profile, Consent(synced, profile), true, stop,
                    [](const auto &) {});
    });
    Expect(remoteCalls == oldCalls);
    server.offline = false;
    remote.erase(scope, rid);
    remote.sync(scope);
    Expect(!server.conversations.contains(rid));
    signedLibrary.logout();
    Throws([&] { remote.list(scope); }, "account_changed");
    Expect(local.account().scope == "guest");
}
} // namespace
int wmain(int argc, wchar_t **argv) {
    if (argc != 2)
        return 2;
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ULONG_PTR token = 0;
    Gdiplus::GdiplusStartupInput input;
    Gdiplus::GdiplusStartup(&token, &input, nullptr);
    int result = 0;
    try {
        fs::create_directories(argv[1]);
        Cases(fs::path(argv[1]));
        std::cout << "Windows AI chat: " << checks << " checks passed\n";
    } catch (const std::exception &e) {
        std::cerr << e.what() << "\n";
        result = 1;
    }
    Gdiplus::GdiplusShutdown(token);
    CoUninitialize();
    return result;
}

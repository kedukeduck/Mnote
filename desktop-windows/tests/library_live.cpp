#include "library.hpp"
#include <iostream>
using namespace Mnote;
int wmain(int argc, wchar_t **argv) {
  if (argc != 4)
    return 2;
  try {
    fs::path root = argv[1];
    auto server = std::wstring(argv[2]), invitation = std::wstring(argv[3]);
    Library first(root / L"first");
    first.login(server, L"windows-live", L"test-password-only-123", invitation);
    auto scope = first.account().scope;
    auto rows = first.list(scope);
    if (!rows.empty())
      throw std::runtime_error("nonempty_fixture");
    Json data = {{"id", "windows-live-record"},
                 {"comment", "live thought"},
                 {"source", {{"type", "screen"}, {"text", "live quote"}}},
                 {"tags", Json::array({"同步", "test"})}};
    auto png = Read(root / L"fixture.png");
    auto record =
        first.save(scope, data, {{"original", root / L"fixture.png"}});
    first.sync();
    if (first.list(scope).at(0).state != "synced")
      throw std::runtime_error("upload_failed");
    Library second(root / L"second");
    second.login(server, L"windows-live", L"test-password-only-123");
    second.sync();
    auto remote = second.list(second.account().scope).at(0);
    if (remote.data.at("comment") != "live thought" ||
        Read(remote.assets.at("original")) != png ||
        remote.data.at("tags") != data.at("tags"))
      throw std::runtime_error("pull_failed");
    auto baseline = Library::fingerprint(remote);
    remote.data["comment"] = "edited on another device";
    remote.data["tags"] = Json::array();
    second.save(second.account().scope, remote.data, {}, baseline);
    second.sync();
    first.sync();
    auto edited = first.list(scope).at(0);
    if (edited.data.at("comment") != "edited on another device" ||
        !edited.data.at("tags").empty())
      throw std::runtime_error("edit_failed");
    first.erase(scope, edited.id, Library::fingerprint(edited));
    first.sync();
    second.sync();
    if (!second.list(second.account().scope).at(0).deleted)
      throw std::runtime_error("delete_failed");
    first.restore(scope, edited.id);
    first.sync();
    second.sync();
    if (second.list(second.account().scope).at(0).deleted)
      throw std::runtime_error("restore_failed");
    first.logout();
    second.logout();
    std::cout << "live account integration: activation, upload, pull, PNG "
                 "bytes, tags, edit, delete, restore and logout passed\n";
    return 0;
  } catch (const std::exception &error) {
    std::cerr << "live integration failed: " << error.what() << "\n";
    return 1;
  }
}

#include "../src/updater.hpp"
#include <iostream>
using namespace Mnote;
using namespace Mnote::Updater;
namespace {
int checks = 0;
void Expect(bool value) {
    if (!value)
        throw std::runtime_error("update assertion " + std::to_string(checks + 1));
    ++checks;
}
template <class F> void Throws(F action) {
    bool threw = false;
    try {
        action();
    } catch (const std::exception &) {
        threw = true;
    }
    Expect(threw);
}
Json Item(const std::string &version) {
    auto tag = "mnote-windows-v" + version, name = "Mnote-Windows-" + version + "-Setup.exe";
    return {{"tag_name", tag},
            {"draft", false},
            {"prerelease", version.find("-test") != std::string::npos},
            {"body", "更新说明"},
            {"assets", Json::array({{{"name", name},
                                     {"size", 100},
                                     {"digest", "sha256:" + std::string(64, 'a')},
                                     {"browser_download_url",
                                      "https://chenyu.online/heartnote-capture/updates/files/" +
                                          tag + "/" + name}}})}};
}
void Cases(const fs::path &root) {
    Expect(Compare("1.10.0-test", "1.9.9-test") > 0);
    Expect(Compare("1.7.0", "1.7.0-test") > 0);
    Expect(Compare("1.7.0-test", "1.7.0-test") == 0);
    for (auto bad : {"1.7", "01.7.0", "1.7.0-beta", "../1.7.0", "1000000.0.0"})
        Throws([&] { Compare(bad, "1.0.0"); });
    auto feed = Json::array({Item("1.9.0-test"), Item("1.10.0-test")});
    auto draft = Item("99.0.0-test");
    draft["draft"] = true;
    feed.push_back(draft);
    auto android = Item("99.0.0-test");
    android["tag_name"] = "mnote-android-v99.0.0-test";
    feed.push_back(android);
    Expect(Select(feed.dump(), "1.6.0-test")->version == "1.10.0-test");
    Expect(!Select(feed.dump(), "1.10.0-test"));
    Expect(!Select(feed.dump(), "1.6.0"));
    feed.push_back(Item("1.7.0"));
    Expect(Select(feed.dump(), "1.6.0")->version == "1.7.0");
    Expect(Select(Json::array({Item("1.7.0")}).dump(), "1.7.0-test")->version == "1.7.0");
    for (auto key : {"digest", "browser_download_url", "size"}) {
        auto item = Item("1.7.0-test");
        item["assets"][0][key] = "https://attacker.test/evil.exe";
        Throws([&] { Select(Json::array({item}).dump(), "1.6.0-test"); });
    }
    auto large = Item("1.7.0-test");
    large["assets"][0]["size"] = 129 * 1024 * 1024;
    Throws([&] { Select(Json::array({large}).dump(), "1.6.0-test"); });
    auto wrong = Item("1.7.0-test");
    wrong["assets"][0]["name"] = "wrong.exe";
    Expect(!Select(Json::array({wrong}).dump(), "1.6.0-test"));
    Throws([] { Select("{}", "1.6.0-test"); });
    Expect(!AllowedDownload(L"https://release-assets.githubusercontent.com/a?sig=temporary"));
    Expect(!AllowedDownload(L"https://github.com/kedukeduck/Mnote/releases/download/tag/a.exe"));
    Expect(
        AllowedDownload(Wide(Select(Json::array({Item("1.9.0-test")}).dump(), "1.8.0-test")->url)));
    for (auto suffix : {L"?token=secret", L"#fragment", L"/../secret", L"%2f.."})
        Expect(!AllowedDownload(
            Wide(Select(Json::array({Item("1.9.0-test")}).dump(), "1.8.0-test")->url) + suffix));
    for (auto bad :
         {L"http://github.com/kedukeduck/Mnote/releases/download/a",
          L"https://github.com.evil.test/a",
          L"https://user:pass@release-assets.githubusercontent.com/a",
          L"https://release-assets.githubusercontent.com:444/a",
          L"https://github.com/other/repo/releases/download/a",
          L"https://release-assets.githubusercontent.com/a#fragment", L"file:///tmp/evil.exe",
          L"https://evil.test/a", L"https://github.com\\@evil.test/a"})
        Expect(!AllowedDownload(bad));
    auto release = *Select(Json::array({Item("1.7.0-test")}).dump(), "1.6.0-test");
    auto unsafe = release;
    unsafe.sha256 = "../escape";
    Throws([&] { Download(root, unsafe, {}); });
    // Fake PE bytes are only a parser fixture; never executed.
    std::string bytes(100, '\0');
    bytes.replace(0, 2, "MZ");
    bytes[60] = 64;
    bytes.replace(64, 4, "PE\0\0", 4);
    bytes[68] = 0x64;
    bytes[69] = static_cast<char>(0x86);
    release.sha256 = Hash(bytes);
    auto path = root / L"Updates" / (Wide(release.sha256) + L".exe");
    AtomicWrite(path, bytes);
    VerifyPackage(path, release);
    ++checks;
    int progress = 0;
    Expect(Download(root, release, [&](int p) { progress = p; }) == path);
    Expect(progress == 100);
    auto corrupt = bytes;
    corrupt[40] = '!';
    AtomicWrite(path, corrupt);
    Throws([&] { VerifyPackage(path, release); });
    Throws([&] { Launch(path, release); }); // Integrity rejection happens before ShellExecute.
    AtomicWrite(path, bytes.substr(0, 50));
    Throws([&] { VerifyPackage(path, release); });
    bytes[68] = 0x4c;
    bytes[69] = 0x01;
    release.sha256 = Hash(bytes);
    AtomicWrite(path, bytes);
    Throws([&] { VerifyPackage(path, release); });
    bytes[60] = static_cast<char>(0xff);
    bytes[61] = static_cast<char>(0xff);
    release.sha256 = Hash(bytes);
    AtomicWrite(path, bytes);
    Throws([&] { VerifyPackage(path, release); });
    Expect(!fs::exists(root / L"account.session"));
}
} // namespace
int wmain(int argc, wchar_t **argv) {
    if (argc < 2)
        return 2;
    try {
        Cases(argv[1]);
        if (argc == 3) {
            auto release = Select(Read(argv[2], 8 * 1024 * 1024), "1.5.0-test");
            if (!release)
                throw std::runtime_error("no public upgrade found");
            auto path = Download(fs::path(argv[1]) / L"live", *release, {});
            VerifyPackage(path, *release);
            std::cout << "public installer downloaded and verified: " << release->version << "\n";
            Check();
        }
        std::cout << "updater tests: " << checks << " checks passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << "\n";
        return 1;
    }
}

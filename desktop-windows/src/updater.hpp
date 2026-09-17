#pragma once
#include "library.hpp"
#include <optional>
namespace Mnote::Updater {
inline constexpr wchar_t Current[] = L"1.10.1-test";
inline constexpr wchar_t Page[] = L"https://chenyu.online/heartnote-capture/updates/";
struct Release {
    std::string version, url, sha256, notes;
    std::size_t size = 0;
};
int Compare(const std::string &first, const std::string &second);
std::optional<Release> Select(const std::string &json, const std::string &current);
bool AllowedDownload(const std::wstring &url);
std::optional<Release> Check();
fs::path Download(const fs::path &root, const Release &release, std::function<void(int)> progress);
void VerifyPackage(const fs::path &file, const Release &release);
void Launch(const fs::path &file, const Release &release);
std::wstring Error(const std::exception &error);
} // namespace Mnote::Updater

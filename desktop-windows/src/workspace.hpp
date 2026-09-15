#pragma once
#include "context.hpp"
namespace Mnote::Workspace {
struct Draft {
    Json data = Json::object();
    std::map<std::string, fs::path> assets;
    std::shared_ptr<Gdiplus::Bitmap> fullImage;
    Context::Source source;
    fs::path staging;
    std::string scope;
};
void Start(HINSTANCE instance, const fs::path &root, std::function<void()> capture,
           std::function<void(const std::wstring &, bool)> notice);
void Stop();
bool CanExit();
bool HasEditor();
void Show();
void Hide();
void QuickNote();
void Compose(Draft draft);
void Sync();
std::string Scope();
fs::path Staging();
bool Translate(MSG &message);
bool DrawButton(const DRAWITEMSTRUCT &item);
HFONT Font();
} // namespace Mnote::Workspace

#pragma once
#include "library.hpp"
#include <gdiplus.h>
#include <memory>
namespace Mnote::Context {
struct Source {
    HWND window = nullptr;
    DWORD process = 0;
    Json metadata = Json::object();
};
Source Foreground();
bool Same(const Source &source);
Json ReadPage(const Source &source, const fs::path &root, bool linkOnly = false);
std::shared_ptr<Gdiplus::Bitmap> Screenshot(const Source &source);
void SavePng(Gdiplus::Bitmap &bitmap, const fs::path &path);
int Helper(int argc, wchar_t **argv);
} // namespace Mnote::Context

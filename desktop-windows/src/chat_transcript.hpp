#pragma once

#include <windows.h>
#include <string>
#include <vector>

namespace Mnote::ChatTranscript {
struct Message {
    std::string id;
    bool own = false;
    std::wstring text;
    std::wstring model;
    std::string status;
    std::wstring error;
};

// A native, selectable, inert rich-text transcript. No network or persistence here.
HWND Create(HWND parent, int id, int retryCommand, int dpi);
void Update(HWND window, const std::vector<Message> &messages, bool receiving);
void SetDpi(HWND window, int dpi);
bool CanRetry(const std::vector<Message> &messages, std::size_t index, bool receiving);
} // namespace Mnote::ChatTranscript

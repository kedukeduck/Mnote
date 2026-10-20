#pragma once
#include "library.hpp"
#include <atomic>

namespace Mnote::Transcript {
// These requests use the existing account token, never a transcription-service secret.
class Client {
  public:
    explicit Client(Account account, Transport transport = {});
    Json config();
    Json create(const std::string &share, const std::string &basis, const std::string &note,
                const std::string &agreement, bool consent, const std::string &requestId = "");
    Json get(const std::string &id);
    void erase(const std::string &id);
  private:
    Account account_;
    Transport transport_;
    Json request(const std::wstring &method, const std::wstring &path, const Json &body = nullptr);
};
bool JobId(const std::string &id);
std::string NewRequestId();
std::wstring Text(const Json &job);
std::wstring Error(const std::exception &error);
std::wstring Progress(const Json &job);
std::wstring ShareUrl(const std::wstring &share);
} // namespace Mnote::Transcript

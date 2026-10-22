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
                const std::string &agreement, bool consent, const std::string &requestId = "",
                const std::string &captureId = "");
    Json list(unsigned offset = 0, unsigned limit = 50);
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
bool RetryableSubmission(const std::string &code);
unsigned RetryDelay(const Json &request);
bool SubmissionExpired(const Json &request, std::int64_t now);
// Account-scoped outbox and notification transitions, with no bearer credentials.
// Uncertain submissions keep the original UUID until explicitly retried or removed.
class TaskJournal {
  public:
    TaskJournal(const fs::path &root, const Account &account);
    void prepare(const Json &request);
    Json pending();
    std::vector<std::string> waiting();
    bool accepted(const std::string &requestId, const Json &job);
    void problem(const std::string &requestId, const std::string &code);
    void discard(const std::string &requestId);
    bool cancelled(const std::string &requestId);
    void forgetJob(const std::string &jobId);
    std::vector<Json> observe(const Json &jobs);
  private:
    fs::path path_;
    Json read();
};
} // namespace Mnote::Transcript

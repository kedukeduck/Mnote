#include "transcript_import.hpp"
#include <algorithm>
#include <cwctype>
#include <ctime>
#include <set>

namespace Mnote::Transcript {
namespace {
std::mutex journalMutex;
void SaveJournal(const fs::path &path,const Json &data) {
    auto encoded=data.dump();
    if(encoded.size()>2U*1024U*1024U)throw std::runtime_error("history_full");
    AtomicWrite(path,encoded);
}
}
bool JobId(const std::string &id) {
    if (id.size() != 36) return false;
    for (std::size_t i=0;i<id.size();++i) {
        if (i==8||i==13||i==18||i==23) { if(id[i]!='-') return false; }
        else if (!((id[i]>='0'&&id[i]<='9')||(id[i]>='a'&&id[i]<='f'))) return false;
    }
    return true;
}
std::string NewRequestId() {
    auto id=NewId();
    for(auto &letter:id)if(letter>='A'&&letter<='F')letter=static_cast<char>(letter-'A'+'a');
    if(!JobId(id))throw std::runtime_error("invalid_job");
    return id;
}
Client::Client(Account account, Transport transport)
    : account_(std::move(account)), transport_(transport ? std::move(transport) : PersonalCaptureSync::Request) {
    if (!account_.signedIn() || account_.token.empty()) throw std::runtime_error("login_required");
}
Json Client::request(const std::wstring &method, const std::wstring &path, const Json &body) {
    PersonalCaptureSync::Settings settings;
    settings.enabled=true; settings.serverUrl=account_.server; settings.writeToken=account_.token;
    auto response=transport_(settings,method,L"/v1/transcripts/"+path,
                             body.is_null()?std::string():body.dump(),4U*1024U*1024U,0);
    auto data=Parse(response.body);
    if(response.status<200||response.status>=300) {
        auto detail=data.value("error",Json::object());
        auto code=detail.is_object()?detail.value("code",std::string("transcript_failed")):"transcript_failed";
        // Never surface server-provided text, URLs, tokens or response bodies.
        static const std::vector<std::string> allowed={"not_configured","upstream_unavailable","account_busy",
            "account_limit","daily_limit","submission_uncertain","platform_restricted","image_post_unsupported",
            "browser_unavailable","browser_busy","timeout","source_forbidden","source_rate_limited",
            "network_failed","unsafe_url","too_long","too_large","not_found","agreement_required",
            "invalid_rights","invalid_request","unauthorized","session_expired","consent_required",
            "media_unavailable","invalid_media","transcription_failed","not_ready","queue_full",
            "history_full","task_expired","result_too_large"};
        if(std::find(allowed.begin(),allowed.end(),code)==allowed.end()) code="transcript_failed";
        throw std::runtime_error(code);
    }
    return data;
}
Json Client::config() { return request(L"GET",L"config"); }
Json Client::list(unsigned offset,unsigned limit) {
    if(limit<1||limit>50||offset>100000)throw std::runtime_error("invalid_request");
    auto result=request(L"GET",L"jobs?limit="+std::to_wstring(limit)+L"&offset="+std::to_wstring(offset));
    if(!result.contains("jobs")||!result["jobs"].is_array()||result["jobs"].size()>limit)
        throw std::runtime_error("invalid_job");
    for(const auto &job:result["jobs"])
        if(!job.is_object()||!JobId(job.value("id",std::string())))throw std::runtime_error("invalid_job");
    if(result.value("has_more",false)&&(!result.contains("next_offset")||!result["next_offset"].is_number_unsigned()||
        result["next_offset"].get<unsigned>()<=offset||result["next_offset"].get<unsigned>()>100000))
        throw std::runtime_error("invalid_job");
    return result;
}
Json Client::create(const std::string &share,const std::string &basis,const std::string &note,
                    const std::string &agreement,bool consent,const std::string &requestId,const std::string &captureId) {
    if(!consent || (basis!="owned"&&basis!="authorized"&&basis!="open_license") ||
       note.empty() || (basis!="owned"&&Wide(note).size()<8) || agreement.empty())
        throw std::runtime_error("consent_required");
    if(share.empty()||share.size()>16384) throw std::runtime_error("invalid_request");
    auto key=requestId.empty()?NewRequestId():requestId;
    if(!JobId(key))throw std::runtime_error("invalid_request");
    Json body={{"share_text",share},{"rights_basis",basis},{"rights_note",note},
        {"agreement_version",agreement},{"consent",true},{"client_request_id",key}};
    if(!captureId.empty()) {
        if(!SafeId(captureId))throw std::runtime_error("invalid_request");
        body["capture_id"]=captureId;
    }
    auto result=request(L"POST",L"jobs",body).at("job");
    if(!JobId(result.value("id",std::string()))) throw std::runtime_error("invalid_job");
    return result;
}
Json Client::get(const std::string &id) {
    if(!JobId(id)) throw std::runtime_error("invalid_job");
    auto result=request(L"GET",L"jobs/"+Wide(id)).at("job");
    if(result.value("id",std::string())!=id) throw std::runtime_error("invalid_job");
    return result;
}
void Client::erase(const std::string &id) {
    if(!JobId(id)) throw std::runtime_error("invalid_job");
    request(L"DELETE",L"jobs/"+Wide(id));
}
std::wstring Text(const Json &job) {
    if(job.value("status",std::string())!="completed") throw std::runtime_error("invalid_job");
    auto text=Wide(job.at("text").get<std::string>());
    if(text.empty()) throw std::runtime_error("empty_transcript");
    if(text.size()>100000) throw std::runtime_error("text_too_long");
    return text;
}
std::wstring Progress(const Json &job) {
    auto stage=job.value("stage",std::string());
    if(stage=="resolving") return L"正在解析视频链接…";
    if(stage=="downloading") return L"正在获取视频音轨…";
    if(stage=="transcribing") return L"正在生成文字稿，请耐心等待…";
    if(stage=="completed") return L"文字稿已生成，请校对后导入摘录。";
    return L"已提交，正在排队…";
}
std::wstring Error(const std::exception &error) {
    std::string code=error.what();
    if(code=="login_required"||code=="unauthorized"||code=="session_expired") return L"请先在设置中登录 Mnote 账号，再解析链接。";
    if(code=="account_changed") return L"账号已变化，本次结果不会写入记录。请重新打开解析。";
    if(code=="not_configured"||code=="upstream_unavailable"||code=="browser_unavailable") return L"文字稿服务暂不可用，请稍后重试；当前草稿未改变。";
    if(code=="account_busy"||code=="browser_busy") return L"已有解析任务进行中，请稍后重试。";
    if(code=="account_limit") return L"本账号每小时最多提交 3 次，请稍后重试。";
    if(code=="daily_limit") return L"文字稿服务今日最多提交 40 次，请次日再试。";
    if(code=="submission_uncertain") return L"任务提交状态暂不确定，请稍后再试，避免重复提交。";
    if(code=="platform_restricted"||code=="source_forbidden") return L"平台暂不允许读取该视频，请稍后重试；不会绕过登录或访问限制。";
    if(code=="image_post_unsupported") return L"这是一条图文内容，目前仅支持音视频。";
    if(code=="too_long") return L"视频超过 30 分钟，请换一个较短的视频。";
    if(code=="too_large") return L"视频音轨超过服务容量限制，请换一个视频。";
    if(code=="unsafe_url"||code=="invalid_request") return L"请粘贴支持平台的公开视频分享链接。";
    if(code=="consent_required"||code=="agreement_required"||code=="invalid_rights") return L"请选择内容权利来源、填写说明并勾选声明。";
    if(code=="text_too_long") return L"完整文字稿超过摘录 10 万字限制，未截断或导入；请在文字稿网页下载完整文件。";
    if(code=="empty_transcript") return L"没有识别到文字，请确认音视频中有清晰人声。";
    if(code=="media_unavailable"||code=="invalid_media") return L"未取得可解码的音轨，请换一个公开音视频链接；记录内容未改变。";
    if(code=="transcription_failed") return L"音轨转写未成功，可在解析任务中查看并重新提交；记录内容未改变。";
    if(code=="task_expired")return L"任务过期前未能完成，请重新提交链接；已保存记录未改变。";
    if(code=="result_too_large")return L"文字稿超过解析服务保存容量，未自动截断或写入记录。";
    if(code=="history_full")return L"解析历史已满，请先清理旧任务；现有记录不受影响。";
    if(code=="submission_unconfirmed")return L"超过 24 小时仍无法确认原任务，已停止自动提交。请重新添加链接并确认权利声明；记录未改变。";
    if(code=="cancelled") return L"已取消解析，原来的记录草稿保持不变。";
    if(code=="timeout") return L"解析超时，请稍后重试；原来的记录草稿保持不变。";
    return L"文字稿解析失败，请检查网络后重试；当前草稿未改变。";
}
std::wstring ShareUrl(const std::wstring &share) {
    auto start=share.find(L"https://");
    if(start==std::wstring::npos) return L"";
    auto end=start;
    while(end<share.size()&&!iswspace(share[end])&&share[end]!=L'<'&&share[end]!=L'>'&&share[end]!=L'"'&&share[end]!=L'\''&&share[end]!=L']') ++end;
    auto url=share.substr(start,end-start);
    while(!url.empty()&&(url.back()==L'。'||url.back()==L'，'||url.back()==L')'||url.back()==L'）'||url.back()==L']'))url.pop_back();
    if(url.size()>8192) return L"";
    // Link is user-supplied context only; never fetched by the client.
    return url;
}
TaskJournal::TaskJournal(const fs::path &root,const Account &account) {
    if(!account.signedIn()||account.scope.empty())throw std::runtime_error("login_required");
    path_=root/L"transcript-tasks"/Wide(Hash(account.scope+"|"+account.id+"|"+Utf8(account.server))+".json");
}
Json TaskJournal::read() {
    if(!fs::exists(path_))return {{"pending",Json::object()},{"observed",Json::object()},{"problems",Json::object()},
        {"queued_at",Json::object()},{"last_attempt",Json::object()},{"attempts",Json::object()},{"discarded",Json::object()}};
    auto data=Parse(Read(path_,2U*1024U*1024U));
    if(!data.is_object()||!data.value("pending",Json()).is_object()||!data.value("observed",Json()).is_object())
        throw std::runtime_error("invalid_job");
    for(auto key:{"problems","queued_at","last_attempt","attempts","discarded"})
        if(!data.value(key,Json()).is_object())data[key]=Json::object();
    return data;
}
void TaskJournal::prepare(const Json &request) {
    auto id=request.value("client_request_id",std::string());
    if(!JobId(id)||request.dump().size()>32768||!request.value("consent",false))throw std::runtime_error("invalid_request");
    // Whitelist fields: a caller cannot accidentally persist account tokens here.
    Json safe=Json::object();
    for(auto key:{"client_request_id","share_text","rights_basis","rights_note","agreement_version","consent","capture_id"})
        if(request.contains(key))safe[key]=request[key];
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();
    if(data["discarded"].contains(id))throw std::runtime_error("cancelled");
    if(!data["pending"].contains(id)&&data["pending"].size()>=100)throw std::runtime_error("account_limit");
    if(data["pending"].contains(id)&&data["pending"][id]!=safe)throw std::runtime_error("invalid_request");
    auto now=static_cast<std::int64_t>(std::time(nullptr));
    if(!data["queued_at"].contains(id))data["queued_at"][id]=now;
    data["last_attempt"][id]=now;
    data["attempts"][id]=std::min(1000,data["attempts"].value(id,0)+1);
    data["pending"][id]=safe;SaveJournal(path_,data);
}
Json TaskJournal::pending() {
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();Json result=Json::array();
    for(const auto &entry:data["pending"].items()) {
        auto request=entry.value();
        if(data["problems"].contains(entry.key()))request["problem_code"]=data["problems"][entry.key()];
        request["queued_at"]=data["queued_at"].value(entry.key(),static_cast<std::int64_t>(std::time(nullptr)));
        request["last_attempt"]=data["last_attempt"].value(entry.key(),std::int64_t(0));
        request["attempts"]=data["attempts"].value(entry.key(),0);
        result.push_back(request);
    }
    return result;
}
std::vector<std::string> TaskJournal::waiting() {
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();std::vector<std::string> ids;
    for(const auto &entry:data["observed"].items())
        if((entry.value()=="queued"||entry.value()=="processing")&&JobId(entry.key()))ids.push_back(entry.key());
    return ids;
}
bool TaskJournal::accepted(const std::string &requestId,const Json &job) {
    auto id=job.value("id",std::string());if(!JobId(id))throw std::runtime_error("invalid_job");
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();
    if(data["discarded"].contains(requestId))return false;
    data["pending"].erase(requestId);
    data["problems"].erase(requestId);
    for(auto key:{"queued_at","last_attempt","attempts"})data[key].erase(requestId);
    // Even an immediately terminal response should produce one completion notice.
    if(!data["observed"].contains(id))data["observed"][id]="queued";
    SaveJournal(path_,data);return true;
}
void TaskJournal::problem(const std::string &requestId,const std::string &code) {
    static const std::set<std::string> safe={"account_changed","account_busy","account_limit","daily_limit",
        "submission_uncertain","network_io","upstream_unavailable","timeout","not_configured","unsafe_url",
        "invalid_request","agreement_required","invalid_rights","consent_required","unauthorized","session_expired",
        "submission_unconfirmed","browser_busy","queue_full","not_ready","network_failed","history_full"};
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();
    if(data["pending"].contains(requestId)) {
        data["problems"][requestId]=safe.count(code)?code:"transcript_failed";SaveJournal(path_,data);
    }
}
void TaskJournal::discard(const std::string &requestId) {
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();data["pending"].erase(requestId);data["problems"].erase(requestId);
    if(JobId(requestId))data["discarded"][requestId]=true;
    for(auto key:{"queued_at","last_attempt","attempts"})data[key].erase(requestId);
    SaveJournal(path_,data);
}
bool TaskJournal::cancelled(const std::string &requestId) {
    if(!JobId(requestId))return false;
    std::lock_guard<std::mutex> lock(journalMutex);return read()["discarded"].contains(requestId);
}
void TaskJournal::forgetJob(const std::string &id) {
    if(!JobId(id))return;
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();data["observed"][id]="deleted";SaveJournal(path_,data);
}
std::vector<Json> TaskJournal::observe(const Json &jobs) {
    std::lock_guard<std::mutex> lock(journalMutex);auto data=read();std::vector<Json> notices;
    for(const auto &job:jobs) {
        auto id=job.value("id",std::string()),state=job.value("status",std::string());
        if(!JobId(id)||(state!="queued"&&state!="processing"&&state!="completed"&&state!="failed"))continue;
        auto requestId=job.contains("client_request_id")&&job["client_request_id"].is_string()?job["client_request_id"].get<std::string>():std::string();
        bool ourPending=JobId(requestId)&&data["pending"].contains(requestId);
        auto previous=data["observed"].value(id,std::string());
        if(previous=="completed"||previous=="failed"||previous=="deleted"||data["discarded"].contains(requestId)) {
            if(ourPending){data["pending"].erase(requestId);data["problems"].erase(requestId);
                for(auto key:{"queued_at","last_attempt","attempts"})data[key].erase(requestId);}continue;
        }
        if((state=="completed"||state=="failed")&&
           (previous=="queued"||previous=="processing"||ourPending))notices.push_back(job);
        data["observed"][id]=state;
        if(ourPending){data["pending"].erase(requestId);data["problems"].erase(requestId);
            for(auto key:{"queued_at","last_attempt","attempts"})data[key].erase(requestId);}
    }
    SaveJournal(path_,data);return notices;
}
bool RetryableSubmission(const std::string &code) {
    static const std::set<std::string> codes={"","network_io","network_failed","upstream_unavailable","timeout",
        "submission_uncertain","account_busy","account_limit","daily_limit","browser_busy","queue_full",
        "not_ready","not_configured","unauthorized","session_expired"};
    return codes.count(code)!=0;
}
unsigned RetryDelay(const Json &request) {
    auto attempts=std::clamp(request.value("attempts",0),0,6);
    auto delay=std::min(900U,15U*(1U<<attempts));
    auto code=request.value("problem_code",std::string());
    if(code=="daily_limit")return 900;
    if(code=="account_limit")return std::max(300U,delay);
    return std::max(30U,delay);
}
bool SubmissionExpired(const Json &request,std::int64_t now) {
    auto code=request.value("problem_code",std::string());
    bool uncertain=code.empty()||code=="submission_uncertain"||code=="network_io"||code=="network_failed"||code=="upstream_unavailable"||code=="timeout";
    auto created=request.value("queued_at",now);
    return uncertain&&created>0&&now-created>24*60*60;
}
} // namespace Mnote::Transcript

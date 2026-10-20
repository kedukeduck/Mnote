#include "transcript_import.hpp"
#include <algorithm>
#include <cwctype>

namespace Mnote::Transcript {
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
            "invalid_rights","invalid_request","unauthorized","session_expired","consent_required"};
        if(std::find(allowed.begin(),allowed.end(),code)==allowed.end()) code="transcript_failed";
        throw std::runtime_error(code);
    }
    return data;
}
Json Client::config() { return request(L"GET",L"config"); }
Json Client::create(const std::string &share,const std::string &basis,const std::string &note,
                    const std::string &agreement,bool consent,const std::string &requestId) {
    if(!consent || (basis!="owned"&&basis!="authorized"&&basis!="open_license") ||
       note.empty() || (basis!="owned"&&Wide(note).size()<8) || agreement.empty())
        throw std::runtime_error("consent_required");
    if(share.empty()||share.size()>16384) throw std::runtime_error("invalid_request");
    auto key=requestId.empty()?NewRequestId():requestId;
    if(!JobId(key))throw std::runtime_error("invalid_request");
    auto result=request(L"POST",L"jobs",{{"share_text",share},{"rights_basis",basis},{"rights_note",note},
        {"agreement_version",agreement},{"consent",true},{"client_request_id",key}}).at("job");
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
    if(code=="cancelled") return L"已取消解析，原来的记录草稿保持不变。";
    if(code=="timeout") return L"解析超时，请稍后重试；原来的记录草稿保持不变。";
    return L"文字稿解析失败，请检查网络后重试；当前草稿未改变。";
}
std::wstring ShareUrl(const std::wstring &share) {
    auto start=share.find(L"https://");
    if(start==std::wstring::npos) return L"";
    auto end=start;
    while(end<share.size()&&!iswspace(share[end])&&share[end]!=L'<'&&share[end]!=L'>'&&share[end]!=L'"'&&share[end]!=L'\'') ++end;
    auto url=share.substr(start,end-start);
    while(!url.empty()&&(url.back()==L'。'||url.back()==L'，'||url.back()==L')'||url.back()==L'）'||url.back()==L']'))url.pop_back();
    if(url.size()>8192) return L"";
    // Link is user-supplied context only; never fetched by the client.
    return url;
}
} // namespace Mnote::Transcript

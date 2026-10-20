#include "../src/transcript_import.hpp"
#include "../src/draft_modules.hpp"
#include <iostream>
#include <stdexcept>
using namespace Mnote;
namespace {
int checks=0;
void Expect(bool value){++checks;if(!value)throw std::runtime_error("assertion "+std::to_string(checks));}
template<class F>void Throws(F f,const std::string &code){bool threw=false;try{f();}catch(const std::exception &e){threw=true;Expect(e.what()==code);}Expect(threw);}
}
int wmain(){
    try{
        Account account;account.id=std::string(32,'a');account.scope="account-test";
        account.token=L"mns_synthetic_only";account.server=L"https://example.invalid/mnote";
        const std::string id="12345678-1234-1234-1234-123456789abc";
        int calls=0;Json submitted;
        Transport transport=[&](const auto &settings,const auto &method,const auto &path,const auto &payload,std::size_t limit,int revision){
            ++calls;Expect(settings.writeToken==account.token&&settings.serverUrl==account.server);
            Expect(limit==4U*1024U*1024U&&revision==0);
            if(path==L"/v1/transcripts/config")return PersonalCaptureSync::Response{200,R"({"ready":true,"agreement_version":"test-v1"})"};
            if(path==L"/v1/transcripts/jobs"){
                Expect(method==L"POST");submitted=Parse(payload);
                return PersonalCaptureSync::Response{202,Json{{"job",{{"id",id},{"status","queued"}}}}.dump()};
            }
            Expect(path==L"/v1/transcripts/jobs/"+Wide(id));
            if(method==L"DELETE")return PersonalCaptureSync::Response{200,R"({"deleted":true})"};
            Expect(method==L"GET");
            return PersonalCaptureSync::Response{200,Json{{"job",{{"id",id},{"status","completed"},{"text","视频文字稿\n完整 END"}}}}.dump()};
        };
        Transcript::Client client(account,transport);
        Expect(client.config().at("ready")==true);
        Throws([&]{Transcript::Client guest(Account{},transport);},"login_required");
        auto before=calls;
        Throws([&]{client.create("https://example.invalid","owned","我的内容","test-v1",false);},"consent_required");
        Throws([&]{client.create("https://example.invalid","authorized","short","test-v1",true);},"consent_required");
        Throws([&]{client.create("https://example.invalid","invalid","note","test-v1",true);},"consent_required");
        Expect(calls==before);
        auto job=client.create("分享 https://v.douyin.com/test/","owned","我的内容","test-v1",true);
        Expect(job.at("id")==id&&submitted.at("consent")==true&&submitted.at("rights_basis")=="owned");
        Expect(Transcript::JobId(submitted.at("client_request_id")));
        Expect(!submitted.contains("token")&&!submitted.contains("owner_id"));
        auto retryId=Transcript::NewRequestId();
        client.create("same share","owned","my content","test-v1",true,retryId);
        auto firstSubmission=submitted;
        client.create("same share","owned","my content","test-v1",true,retryId);
        Expect(submitted==firstSubmission&&submitted.at("client_request_id")==retryId);
        auto completed=client.get(id);Expect(Transcript::Text(completed)==L"视频文字稿\n完整 END");
        client.erase(id);
        before=calls;
        Throws([&]{client.get("../../private");},"invalid_job");
        Throws([&]{client.erase("bad/id");},"invalid_job");Expect(calls==before);
        Throws([&]{Transcript::Text({{"status","queued"},{"text","not ready"}});},"invalid_job");
        Throws([&]{Transcript::Text({{"status","completed"},{"text",""}});},"empty_transcript");
        std::string maximum(100000,'x');
        Expect(Transcript::Text({{"status","completed"},{"text",maximum}}).size()==100000);
        Throws([&]{Transcript::Text({{"status","completed"},{"text",maximum+"x"}});},"text_too_long");
        Expect(Transcript::ShareUrl(L"复制打开 https://v.douyin.com/example/ 查看作品")==L"https://v.douyin.com/example/");
        Expect(Transcript::ShareUrl(L"【标题】https://b23.tv/example。 ")==L"https://b23.tv/example");
        Expect(Transcript::ShareUrl(L"http://not-secure.invalid").empty());
        Expect(!Transcript::JobId("12345678-1234-1234-1234-123456789abg"));
        Transcript::Client bad(account,[](const auto&,const auto&,const auto&,const auto&,std::size_t,int){
            return PersonalCaptureSync::Response{503,R"({"error":{"code":"upstream_unavailable","message":"SECRET MUST NEVER SURFACE"}})"};});
        Throws([&]{bad.config();},"upstream_unavailable");
        Expect(Transcript::Error(std::runtime_error("SECRET MUST NEVER SURFACE")).find(L"SECRET")==std::wstring::npos);
        Expect(Transcript::Progress({{"stage","transcribing"}}).find(L"生成")!=std::wstring::npos);
        Json draft={{"comment","my thought"},{"source",{{"type","video_transcript"},{"text","ASR text"},
            {"text_origin","video_transcript"},{"url","https://example.invalid/video"},
            {"transcription",{{"method","automatic_speech_recognition"},{"verified",false}}}}}};
        std::map<std::string,fs::path> assets;
        auto projected=draft;DraftModules modules;modules.link=false;
        ProjectModules(projected,assets,modules);
        Expect(projected["source"]["text_origin"]=="video_transcript"&&projected["source"]["transcription"]["verified"]==false);
        Expect(projected["source"]["url"]==""&&projected["source"]["type"]=="video_transcript");
        projected=draft;modules.excerpt=false;
        ProjectModules(projected,assets,modules);
        Expect(!projected["source"].contains("transcription")&&!projected["source"].contains("text_origin"));
        Expect(projected["source"]["text"]==""&&projected["comment"]=="my thought");
        auto folder=fs::temp_directory_path()/Wide("mnote-transcript-test-"+NewId());
        {
            Library local(folder);
            Json initial={{"kind","thought"},{"comment","untouched thought"},
                {"source",{{"type","clipboard"},{"text","original excerpt"},{"url",""}}},
                {"evidence",{{"exact_text",{{"text","original excerpt"},{"delivered_by","clipboard"}}}}},
                {"tags",Json::array({"kept"})},{"ai_access","local_only"}};
            auto original=local.save("guest",initial);
            auto edits=original.data;
            edits["source"]=draft["source"];
            auto saved=local.save("guest",edits,{},Library::fingerprint(original));
            Expect(saved.data["source"]["text_origin"]=="video_transcript");
            Expect(saved.data["source"]["transcription"]["verified"]==false);
            Expect(!saved.data["evidence"].contains("exact_text"));
            Expect(saved.data["comment"]=="untouched thought"&&saved.data["tags"]==Json::array({"kept"}));
            // A second import with identical wording must still replace clipboard provenance.
            initial["source"]["text"]="ASR text";
            auto sameText=local.save("guest",initial);
            edits=sameText.data;edits["source"]=draft["source"];
            auto sameSaved=local.save("guest",edits,{},Library::fingerprint(sameText));
            Expect(sameSaved.data["source"]["text_origin"]=="video_transcript"&&!sameSaved.data["evidence"].contains("exact_text"));
            // Android-origin ASR without the optional metadata object is normalized safely.
            edits=sameSaved.data;edits["source"].erase("transcription");
            edits["source"]["text"]="corrected Android transcript";edits["source"]["text_origin"]="user_edited";
            auto android=local.save("guest",edits,{},Library::fingerprint(sameSaved));
            Expect(android.data["source"]["transcription"]["verified"]==false&&!android.data["evidence"].contains("exact_text"));
            edits=saved.data;edits["source"].erase("transcription");
            edits["source"]["text"]="new clipboard text";edits["source"]["type"]="clipboard";edits["source"]["text_origin"]="clipboard";
            auto clipboard=local.save("guest",edits,{},Library::fingerprint(saved));
            Expect(!clipboard.data["source"].contains("transcription"));
            Expect(clipboard.data["source"]["type"]!="video_transcript");
        }
        // Only the unique synthetic test directory allocated immediately above is removed.
        fs::remove_all(folder);
        std::cout<<checks<<" transcript import checks passed\n";return 0;
    }catch(const std::exception&e){std::cerr<<e.what()<<"\n";return 1;}
}

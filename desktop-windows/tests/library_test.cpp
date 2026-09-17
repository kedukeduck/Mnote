#include "../src/library.hpp"
#include <cassert>
#include <iostream>
#include <chrono>

using namespace Mnote;
using Response=PersonalCaptureSync::Response;
namespace {
int checks=0;
void Expect(bool condition) {++checks;if(!condition) throw std::runtime_error("test assertion "+std::to_string(checks));}
template<class F> void Throws(F action,const std::string& code="") {
    bool threw=false;try {action();}catch(const std::exception& error){threw=true;if(!code.empty()) Expect(error.what()==code);}
    Expect(threw);
}
Json Note(const std::string& id="") {
    Json data={{"schema_version",1},{"kind","thought"},{"comment","我的想法"},
        {"source",{{"type","quick_note"},{"text",""}}},{"ai_access","local_only"},{"tags",Json::array()}};
    if(!id.empty()) data["id"]=id;return data;
}
struct FakeServer {
    int exports=0, revoked=0;
    Json cloud=Json::object(),events=Json::array();bool conflict=false,badAsset=false;int puts=0;
    std::function<void()> duringFeed;
    std::string bytes=std::string("\x89PNG\r\n\x1a\n",8)+std::string(16,'\0');
    FakeServer() {bytes[19]=1;bytes[23]=1;}
    Json session() {return {{"account_id",std::string(32,'a')},{"username","tester"},{"access_token","mns_test_only_token"},{"expires_at",4102444800LL}};}
    Response call(const PersonalCaptureSync::Settings&,const std::wstring& method,const std::wstring& path,const std::string& body,std::size_t,int revision) {
        if(path==L"/v1/auth/login" || path==L"/v1/auth/activate") return {200,session().dump()};
        if(path==L"/v1/auth/logout") return {200,"{}"};
        if(path==L"/v1/exports/markdown") {
            auto payload=Parse(body);Expect(payload.at("publish_images")==true);
            for(const auto& r:payload.at("records"))
                if(!cloud.contains(r.at("id")) || cloud.at(r.at("id")).at("revision")!=r.at("revision")) return {409,"{}"};
            ++exports;return {200,Json{{"id",std::string(32,'e')},{"markdown","# Mnote 记录导出\n\n我的想法\n"}}.dump()};
        }
        if(path==L"/v1/exports") return {200,Json{{"exports",Json::array({{{"id",std::string(32,'e')}}})}}.dump()};
        if(path==L"/v1/exports/"+std::wstring(32,L'e')) {
            if(method==L"GET")return {200,Json{{"images",Json::array({{{"name","1-context.png"},{"size",bytes.size()},{"role","context"},{"record_index",1}}})}}.dump()};
            ++revoked;return {200,"{}"};
        }
        if(path==L"/v1/exports/"+std::wstring(32,L'e')+L"/assets/1-context.png")return {200,bytes};
        if(path.rfind(L"/v1/changes?",0)==0) {
            if(duringFeed) {auto action=std::move(duringFeed);action();}
            auto start=path.find(L"after=")+6;std::int64_t after=std::stoll(path.substr(start));
            Json changes=Json::array();for(const auto& event:events) if(event.at("sequence").get<std::int64_t>()>after) changes.push_back(event);
            return {200,Json{{"changes",changes},{"next_sequence",events.empty() ? after : events.back().at("sequence").get<std::int64_t>()},{"has_more",false}}.dump()};
        }
        auto id=Utf8(path.substr(std::wstring(L"/v1/captures/").size()));
        if(id.find("/assets/")!=std::string::npos) return {200,badAsset ? "wrong bytes" : bytes};
        if(method==L"PUT") {
            ++puts;auto record=Parse(body);
            if(conflict || (cloud.contains(id) && record.value("base_revision",Json(nullptr))!=cloud.at(id).at("revision"))) return {409,"{}"};
            int version=cloud.contains(id) ? cloud.at(id).at("revision").get<int>()+1 : 1;
            record.erase("base_revision");record.erase("assets");record["revision"]=version;cloud[id]=record;
            events.push_back({{"sequence",events.size()+1},{"revision",version},{"operation","upsert"},{"capture_id",id},{"record",record}});
            return {200,record.dump()};
        }
        if(!cloud.contains(id)) return {404,"{}"};
        if(method==L"DELETE") {
            if(revision!=cloud.at(id).at("revision")) return {409,"{}"};
            cloud[id]["revision"]=revision+1;cloud[id]["deleted"]=true;
            events.push_back({{"sequence",events.size()+1},{"revision",revision+1},{"operation","delete"},{"capture_id",id}});
            return {200,cloud.at(id).dump()};
        }
        return {200,cloud.at(id).dump()};
    }
    Transport transport() {return [this](const auto& a,const auto& m,const auto& p,const auto& b,std::size_t n,int r){return call(a,m,p,b,n,r);};}
};
void Cases(const fs::path& folder) {
    Expect(Hash("abc")=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    Expect(Wide(Utf8(L"中文🌱"))==L"中文🌱");
    Expect(Tags(L"#灵感， TODO;todo\n灵感")==Json::array({"灵感","TODO"}));
    Expect(Tags(L"É，é").size()==1);
    Throws([]{Tags(std::wstring(33,L'字'));},"invalid_tags");
    auto context=TextContext(L"🌱前文摘录后文","user_supplied",L"摘录");Expect(context["start"]==4);Expect(context["end"]==6);
    Throws([]{Parse(std::string(100,'[')+std::string(100,']'));},"invalid_json");
    Expect(!SafeId("../escape"));Expect(!SafeId("bad/id"));
    Library optional(folder/L"optional");auto nullable=Note();nullable["source"]["url"]=nullptr;nullable["evidence"]=nullptr;
    auto normalized=optional.save("guest",nullable);Expect(normalized.data["source"]["url"]=="");
    auto editable=normalized.data;editable["kind"]="todo";editable["ai_access"]="deny";
    auto permissions=optional.save("guest",editable,{},Library::fingerprint(normalized));
    Expect(permissions.data["kind"]=="todo" && permissions.data["ai_access"]=="deny");
    Throws([&]{optional.save("guest",editable,{},Library::fingerprint(normalized));},"record_changed");
    FakeServer server;Library library(folder,server.transport());
    auto note=library.save("guest",Note());Expect(library.list("guest").size()==1);
    auto changed=note.data;changed["tags"]=Tags(L"工作，灵感");
    changed["source"]["text"]="选中的文字";changed["evidence"]["context"]["text"]=TextContext(L"原文选中的文字","user_supplied",L"选中的文字");
    auto edited=library.save("guest",changed,{},Library::fingerprint(note));
    Expect(edited.id==note.id);Expect(edited.data["tags"].size()==2);
    Expect(library.existingTags("guest")==Json::array({"工作","灵感"}));
    Expect(edited.data["evidence"]["context"]["text"]["origin"]=="user_edited");
    Throws([&]{library.save("guest",changed,{},Library::fingerprint(note));},"record_changed");
    library.erase("guest",edited.id,Library::fingerprint(edited));Expect(library.list("guest")[0].deleted);
    Expect(library.existingTags("guest").empty());
    library.restore("guest",edited.id);Expect(!library.list("guest")[0].deleted);
    library.login(L"https://example.test",L"tester",L"never-persist-this-password");auto account=library.account();
    Expect(account.signedIn());Expect(library.list(account.scope).empty());
    Expect(library.existingTags(account.scope).empty());
    Throws([&]{library.existingTags("guest");},"account_changed");
    auto encrypted=Read(folder/L"account.session");Expect(encrypted.find("mns_")==std::string::npos);Expect(encrypted.find("never-persist")==std::string::npos);
    Library reopened(folder,server.transport());Expect(reopened.account().scope==account.scope);
    Throws([&]{library.save("guest",Note());},"account_changed");
    Expect(library.importGuest(account.scope)==1);Expect(library.importGuest(account.scope)==0);
    Expect(library.existingTags(account.scope).size()==2);
    library.sync();auto synced=library.list(account.scope)[0];Expect(synced.state=="synced");Expect(synced.revision==1);
    Expect(server.cloud.at(synced.id).at("tags").size()==2);
    Expect(Library::exportable(synced));
    auto denied=synced;denied.data["ai_access"]="deny";Expect(!Library::exportable(denied));
    auto pending=synced;pending.state="pending";Expect(!Library::exportable(pending));
    library.exportMarkdown(account.scope,{synced},folder/L"export.md");
    Expect(Read(folder/L"export.md")=="# Mnote 记录导出\n\n我的想法\n");Expect(server.exports==1);
    Expect(library.markdownExports(account.scope).size()==1);
    auto images=library.markdownExportImages(account.scope,std::string(32,'e'));Expect(images.size()==1);
    Expect(library.markdownExportImage(account.scope,std::string(32,'e'),images[0])==server.bytes);
    Throws([&]{library.markdownExportImages("guest",std::string(32,'e'));},"account_changed");
    Throws([&]{library.markdownExportImages(account.scope,"../bad");},"invalid_export");
    auto badImage=images[0];badImage["name"]="../anything.png";
    Throws([&]{library.markdownExportImage(account.scope,std::string(32,'e'),badImage);},"invalid_export");
    badImage=images[0];badImage["size"]=1;
    Throws([&]{library.markdownExportImage(account.scope,std::string(32,'e'),badImage);},"asset_mismatch");
    library.revokeMarkdownExport(account.scope,std::string(32,'e'));Expect(server.revoked==1);
    Throws([&]{library.exportMarkdown(account.scope,{},folder/L"export.md");},"export_selection");
    Throws([&]{library.exportMarkdown(account.scope,{synced,synced},folder/L"export.md");},"export_changed");
    Throws([&]{library.exportMarkdown(account.scope,{pending},folder/L"export.md");},"export_changed");
    Throws([&]{library.exportMarkdown("guest",{synced},folder/L"export.md");},"account_changed");
    Throws([&]{library.revokeMarkdownExport(account.scope,"../bad");},"invalid_export");
    fs::create_directory(folder/L"cannot-overwrite-directory.md");
    Throws([&]{library.exportMarkdown(account.scope,{synced},folder/L"cannot-overwrite-directory.md");});
    Expect(server.revoked==2);
    auto clear=synced.data;clear["tags"]=Json::array();
    auto cleared=library.save(account.scope,clear,{},Library::fingerprint(synced));library.sync();
    Throws([&]{library.exportMarkdown(account.scope,{synced},folder/L"export.md");},"export_changed");
    Expect(server.cloud.at(synced.id).at("tags").empty());Expect(library.list(account.scope)[0].revision==2);
    auto stale=library.list(account.scope)[0];auto conflict=stale.data;conflict["comment"]="本机新修改";
    library.save(account.scope,conflict,{},Library::fingerprint(stale));server.conflict=true;
    Throws([&]{library.sync();},"sync_failed");Expect(library.list(account.scope)[0].data["comment"]=="本机新修改");
    Expect(library.list(account.scope)[0].error=="http_409");server.conflict=false;library.sync();
    synced=library.list(account.scope)[0];library.erase(account.scope,synced.id,Library::fingerprint(synced));
    library.sync();Expect(library.list(account.scope)[0].deleted);Expect(server.cloud.at(synced.id).at("deleted")==true);
    auto unsent=library.save(account.scope,Note());library.erase(account.scope,unsent.id,Library::fingerprint(unsent));
    library.sync();for(const auto& record:library.list(account.scope)) if(record.id==unsent.id) Expect(record.deleted && record.state=="synced");
    server.duringFeed=[&]{library.logout();};Throws([&]{library.sync();},"account_changed");Expect(library.account().scope=="guest");
    Expect(library.list("guest").size()==1);Expect(!fs::exists(folder/L"account.session"));

    FakeServer remote;Library cache(folder/L"downloads",remote.transport());cache.login(L"https://example.test",L"tester",L"password");
    auto scope=cache.account().scope;auto record=Note("remote-image");record["revision"]=1;
    record["assets"]["original"]={{"sha256",Hash(remote.bytes)},{"size",remote.bytes.size()},{"content_type","image/png"},{"href","https://foreign.test/must-not-request"}};
    record["unknown_field"]={{"preserved",true}};
    remote.events.push_back({{"sequence",1},{"revision",1},{"operation","upsert"},{"capture_id","remote-image"},{"record",record}});
    remote.badAsset=true;Throws([&]{cache.sync();},"asset_mismatch");Expect(cache.list(scope).empty());
    Expect(!fs::exists(folder/L"downloads"/L"Library"/Wide(scope)/L"cursor.json"));
    remote.badAsset=false;cache.sync();auto downloaded=cache.list(scope)[0];Expect(Read(downloaded.assets.at("original"))==remote.bytes);
    auto update=downloaded.data;update["tags"]=Tags(L"来自安卓");
    auto tagged=cache.save(scope,update,{},Library::fingerprint(downloaded));
    Expect(tagged.data["unknown_field"]["preserved"]==true);Expect(tagged.assets.size()==1);
    Expect(Read(tagged.assets.at("original"))==remote.bytes);
    Throws([&]{cache.save(scope,Note("../bad"));},"invalid_record");
}
}
int wmain(int argc,wchar_t** argv) {
    if(argc!=2) return 2;
    try {Cases(fs::path(argv[1]));std::cout<<"library tests: "<<checks<<" checks passed\n";return 0;}
    catch(const std::exception& error) {std::cerr<<"library tests failed: "<<error.what()<<"\n";return 1;}
}

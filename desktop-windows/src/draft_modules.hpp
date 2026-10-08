#pragma once
#include "library.hpp"

namespace Mnote {
struct DraftModules {
    bool thought = true, excerpt = true, original = true, link = true, page = true, crop = true,
         tags = true;
};
// Project a copy at the save boundary. Unchecked modules remain untouched in the live draft.
inline void ProjectModules(Json &data, std::map<std::string, fs::path> &assets,
                           const DraftModules &modules) {
    if (!data["source"].is_object()) data["source"] = Json::object();
    if (!data["evidence"].is_object()) data["evidence"] = Json::object();
    if (!data["evidence"]["context"].is_object()) data["evidence"]["context"] = Json::object();
    auto &source = data["source"];
    auto &evidence = data["evidence"];
    auto &context = evidence["context"];
    if (!modules.thought) data["comment"] = "";
    if (!modules.tags) data["tags"] = Json::array();
    if (!modules.excerpt) {
        source["text"] = "";
        for (const auto *key : {"selectors", "text_origin", "selected_text"}) source.erase(key);
        evidence.erase("exact_text");
        if (context.contains("text") && context["text"].is_object()) {
            auto &text = context["text"];
            text.erase("quote_start"); text.erase("quote_end");
            text.erase("start"); text.erase("end"); text.erase("match");
            text["relation_to_quote"] = "unverified";
        }
    }
    if (!modules.original) context.erase("text");
    if (!modules.link) {
        auto text=source.value("text",Json(""));
        auto origin=source.value("text_origin",Json("user_supplied"));
        source={{"type","user_supplied"},{"text",text},{"url",""}};
        if(modules.excerpt && !text.get<std::string>().empty()) source["text_origin"]=origin;
        for(auto role:{"text","image"})
            if(context.contains(role) && context[role].is_object())
                for(auto key:{"source_url","source_package","source_app","app_name","app_id","window_title","url_origin"})
                    context[role].erase(key);
    }
    if (!modules.page) assets.erase("context");
    if (!modules.crop) {
        assets.erase("original"); assets.erase("annotated");
        data.erase("annotations");
        if (data.contains("capture") && data["capture"].is_object()) data["capture"].erase("selection_screen");
    }
    const bool page = assets.count("context") != 0;
    const bool crop = assets.count("original") != 0 || assets.count("annotated") != 0;
    if (!page && !crop) {
        context.erase("image");
        data.erase("capture");
    } else {
        auto &image = context["image"];
        if (!image.is_object()) image = Json::object();
        image["retained"] = page;
        image["asset_role"] = page ? Json("context") : Json(nullptr);
        if (!crop) {
            for (const auto *key : {"selection", "selected_asset_role", "editor_width", "editor_height",
                                    "annotation_coordinate_space"}) image.erase(key);
            image["purpose"] = "page_context";
            image["selection_meaning"] = "full_viewport_not_quote_location";
        } else {
            image.erase("purpose"); image.erase("selection_meaning");
        }
    }
    data.erase("assets");
    data.erase("local_files");
}
} // namespace Mnote

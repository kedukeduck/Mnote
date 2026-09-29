#!/usr/bin/env python3
"""Opt-in local Chromium checks against preview-share-page.py's synthetic snapshots.

Requires the development-only Python Playwright installation. Never opens a
production share, reads an account, or allows external browser requests.
"""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright


def contrast(foreground, background):
    def luminance(color):
        rgb = [int(n) / 255 for n in re.findall(r"\d+", color)[:3]]
        assert len(rgb) == 3
        linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in rgb]
        return sum(a * b for a, b in zip(linear, (.2126, .7152, .0722)))
    a, b = sorted([luminance(foreground), luminance(background)])
    return (b + .05) / (a + .05)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8796")
    parser.add_argument("--output", type=Path, default=Path("docs/design/share-editorial/after"))
    args = parser.parse_args()
    origin = urlsplit(args.base)
    assert origin.scheme == "http" and origin.hostname == "127.0.0.1"
    assert not (origin.username or origin.password or origin.path or origin.query or origin.fragment)
    args.output.mkdir(parents=True, exist_ok=True)
    checked, ratios = [], {}
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        for width, height in ((320, 740), (390, 844), (768, 1024), (1280, 900)):
            context = browser.new_context(viewport={"width": width, "height": height})
            external, errors = [], []
            def route_request(route):
                if route.request.url.startswith(args.base + "/"):
                    route.continue_()
                else:
                    external.append(route.request.url)
                    route.abort()
            context.route("**/*", route_request)
            page = context.new_page()
            page.on("pageerror", lambda error: errors.append(str(error)))
            for number, variant in enumerate(("all", "thought", "excerpt", "image", "legacy", "source"), 1):
                url = args.base + "/c/" + format(number, "064x")
                response = page.goto(url)
                assert response.status == 200
                assert response.headers["cache-control"] == "no-store"
                assert response.headers["referrer-policy"] == "no-referrer"
                page.evaluate("document.fonts.ready")
                assert page.get_by_role("heading", name="本次分享", exact=True).count() == 1
                # Observe the live DOM before targeting image, navigation or disclosure controls.
                print(width, variant, "observed", page.locator("body").aria_snapshot()[:160])
                for image in page.get_by_role("img").all():
                    image.scroll_into_view_if_needed()
                    image.evaluate("(e) => e.decode()")
                    assert image.evaluate("(e) => e.complete && e.naturalWidth > 0")
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), (width, variant)
                assert page.locator("nav").count() == int(variant == "all")
                if variant in ("all", "legacy"):
                    assert page.locator("details").evaluate("(e) => e.open") == (variant == "legacy")
                    summary = page.locator("summary")
                    assert summary.bounding_box()["height"] >= 44
                    summary.focus()
                    assert summary.evaluate("(e) => getComputedStyle(e).outlineStyle") != "none"
                    summary.press("Space")
                    assert page.locator("details").evaluate("(e) => e.open") == (variant == "all")
                    summary.press("Enter")
                    assert page.locator("details").evaluate("(e) => e.open") == (variant == "legacy")
                if variant == "all":
                    nav = page.get_by_role("navigation", name="本次分享内容")
                    print("Navigation", nav.aria_snapshot())
                    for link in nav.get_by_role("link").all():
                        assert link.bounding_box()["height"] >= 44
                        target = link.get_attribute("href")
                        link.click()
                        assert page.url.endswith(target)
                        assert page.locator(target).count() == 1
                    if width == 390:
                        page.screenshot(path=str(args.output / "06-web-mobile-source.png"))
                        print("Original disclosure", page.locator("summary").aria_snapshot())
                        page.locator("summary").click()
                        assert page.locator("details").evaluate("(e) => e.open")
                        page.screenshot(path=str(args.output / "07-web-mobile-original.png"))
                        page.locator("summary").click()
                    source = page.locator(".source-link")
                    assert source.get_attribute("href") == "https://example.com/reading/meaning-of-notes"
                    assert {"noreferrer", "noopener"} <= set(source.get_attribute("rel").split())
                    assert source.bounding_box()["height"] >= 44
                    paper = page.locator("html").evaluate("(e) => getComputedStyle(e).backgroundColor")
                    for selector in (".thought-body", "h2", ".hint", "footer"):
                        color = page.locator(selector).first.evaluate("(e) => getComputedStyle(e).color")
                        ratios[selector] = contrast(color, paper)
                        assert ratios[selector] >= 4.5, (selector, ratios[selector])
                    quote_bg = page.locator(".quote-module").evaluate("(e) => getComputedStyle(e).backgroundColor")
                    quote_fg = page.locator(".quote-body").evaluate("(e) => getComputedStyle(e).color")
                    ratios["quote"] = contrast(quote_fg, quote_bg)
                    assert ratios["quote"] >= 4.5
                page.get_by_role("heading", name="本次分享", exact=True).scroll_into_view_if_needed()
                page.evaluate("window.scrollTo(0, 0)")
                if variant == "all" and width in (320, 390, 1280):
                    screen = {320: "narrow", 390: "mobile", 1280: "desktop"}[width]
                    page.screenshot(path=str(args.output / ("05-web-" + screen + ".png")))
                if width == 390 and variant != "all":
                    page.screenshot(path=str(args.output / ("08-web-" + variant + ".png")))
                if variant == "image":
                    link = page.get_by_role("link", name="打开圈选截图原图")
                    href = link.get_attribute("href")
                    with page.expect_navigation() as navigation:
                        link.click()
                    assert navigation.value.headers["content-type"].startswith("image/png")
                    assert page.url == href
                if variant == "thought":
                    page.locator(".thought-body").evaluate("(e) => e.textContent = 'long-unbroken-note'.repeat(400)")
                    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                if variant == "source":
                    page.locator(".source-domain").evaluate("(e) => e.textContent = 'long-domain'.repeat(20) + '.example'")
                    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                checked.append([width, variant])
            assert not external, external
            assert not errors, errors
            context.close()
        # The disclosure and links must work without JavaScript, as this page ships none.
        context = browser.new_context(java_script_enabled=False, viewport={"width": 390, "height": 844})
        context.route("**/*", lambda route: route.continue_() if route.request.url.startswith(args.base + "/") else route.abort())
        page = context.new_page()
        page.goto(args.base + "/c/" + format(1, "064x"))
        print("No-JS disclosure", page.locator("summary").aria_snapshot())
        page.locator("summary").click()
        assert page.locator("details").get_attribute("open") is not None
        context.close()
        browser.close()
    print(json.dumps({"responsive_cases": checked, "contrast": ratios, "external_requests": 0,
                      "keyboard_disclosure": "passed", "no_javascript": "passed"}, ensure_ascii=False))


if __name__ == "__main__":
    main()

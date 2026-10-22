#!/usr/bin/env python3
"""Private, consent-gated media transcription service. No paid APIs or login bypass."""
from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hmac
import importlib.metadata
import importlib.util
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

AGREEMENT_VERSION = "2026-10-12-v1"
MAX_DURATION = 1800
RUNTIME_VERSIONS = {"faster-whisper": "1.2.1", "av": "18.1.0"}
OWNER_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}\Z")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
URL_RE = re.compile(r"https?://[^\s<>\"'，。！；、（）【】]+", re.I)
# Copied rich-text/Markdown shares can contain the same link as both the label
# and destination. Keep formatting delimiters out of share candidates without
# narrowing URL_RE, which also redacts complete (potentially sensitive) URLs.
SHARE_URL_RE = re.compile(r"https?://[^\s<>\"'，。！；、（）【】\[\]`]+", re.I)
SITE_HOSTS = frozenset({"b23.tv", "bilibili.com", "www.bilibili.com", "m.bilibili.com",
                        "v.douyin.com", "www.douyin.com", "douyin.com", "www.iesdouyin.com"})
BILI_HOSTS = frozenset({"b23.tv", "bilibili.com", "www.bilibili.com", "m.bilibili.com"})
# Observed in Bilibili's anonymous playurl response; do not allow the shared suffix.
BILI_EXTRA_CDN_HOSTS = frozenset({"upos-hz-mirrorakam.akamaized.net"})
MAX_MEDIA_CANDIDATES = 3
DOUYIN_HOSTS = frozenset({"v.douyin.com", "www.douyin.com", "douyin.com", "www.iesdouyin.com"})
DOUYIN_EXTRA_CDN_HOSTS = frozenset({"v3-dy-o.zjcdn.com"})
BROWSER_TIMEOUT = 70
BROWSER_MAX_BYTES = 2 * 1024 * 1024
BROWSER_ERROR_CODES = frozenset({"platform_restricted", "browser_unavailable", "browser_busy", "timeout",
                               "image_post_unsupported", "source_rate_limited", "unsafe_url", "media_unavailable"})
AUDIO_EXTENSIONS = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".webm", ".mp4"}
ERRORS = {
    "unauthorized": "服务凭证无效或缺失。", "owner_invalid": "用户标识缺失或格式不正确。",
    "invalid_request": "请求格式不正确。", "consent_required": "请确认授权依据并同意当前版本的使用约定。",
    "invalid_url": "未找到有效的 HTTPS 分享链接。", "unsupported_source": "暂不支持此来源；可使用已配置的公开音频域名。",
    "unsafe_url": "链接或重定向不符合安全访问规则。", "network_failed": "无法安全读取公开媒体，请稍后重试。",
    "source_forbidden": "来源服务器拒绝读取（403/412），请稍后重试或更换来源。",
    "source_rate_limited": "音视频服务器限制了请求频率（429），请稍后重试。",
    "platform_restricted": "平台未提供可匿名读取的公开音频；本服务不登录账号、不导入个人 Cookie，也不绕过验证码或访问限制。",
    "browser_unavailable": "公开页面解析暂不可用，请稍后重试。",
    "browser_busy": "公开页面解析繁忙，请稍后重试。",
    "image_post_unsupported": "此链接是图文作品，暂不支持转写；背景音乐不代表图文正文。",
    "media_unavailable": "未找到可公开读取的音频流。", "too_long": "媒体时长超过 30 分钟限制。",
    "too_large": "媒体文件超过服务大小限制。", "invalid_media": "无法解码为有效音频。",
    "not_ready": "本地转写模型或音频工具尚未准备好。", "queue_full": "任务队列已满，请稍后再试。",
    "not_found": "任务不存在、已过期或不属于当前用户。", "not_completed": "任务尚未完成，暂时无法下载。",
    "interrupted": "服务重启中断了此任务，请确认授权后重新提交。", "timeout": "处理超时，请尝试较短的音频。",
    "transcription_failed": "本地转写失败，请检查音频后重新提交。", "internal_error": "服务暂时无法处理此请求。",
}


class ServiceError(Exception):
    def __init__(self, code: str, status: int = 400):
        self.code, self.status = code, status
        super().__init__(ERRORS.get(code, ERRORS["internal_error"]))


class Cancelled(Exception):
    pass


def clean_text(value: object, limit: int, redact_urls: bool = False) -> str:
    if not isinstance(value, str):
        raise ServiceError("invalid_request")
    value = re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", "", value).strip()
    return (URL_RE.sub("[链接已隐藏]", value) if redact_urls else value)[:limit]


def clean_declaration(value: object) -> str:
    value = clean_text(value, 1000)
    def redact_sensitive(match):
        try:
            parsed = urllib.parse.urlsplit(match[0])
            if parsed.username or parsed.password:
                return "[含凭证的链接已隐藏]"
            if parsed.query or parsed.fragment:
                return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", "")) + "[参数已隐藏]"
            return match[0]
        except ValueError:
            return "[链接已隐藏]"
    return URL_RE.sub(redact_sensitive, value)


@dataclasses.dataclass(frozen=True)
class Config:
    data_dir: Path
    token: str
    model_path: Path
    direct_hosts: tuple[str, ...] = ()
    max_bytes: int = 80 * 1024 * 1024
    queue_size: int = 4
    retention_hours: int = 24
    worker_timeout: int = 3600
    ffmpeg: str = "ffmpeg"
    ffprobe: str = "ffprobe"
    demo_audio: Path | None = None
    public_url: str = ""
    browser_socket: str = ""
    browser_token: str = dataclasses.field(default="", repr=False)

    @classmethod
    def from_env(cls):
        hosts = tuple(x.strip().lower() for x in os.environ.get("MNOTE_TRANSCRIPT_AUDIO_HOSTS", "").split(",") if x.strip())
        for host in hosts:
            if not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host) or ".." in host:
                raise ValueError("invalid direct-media hostname configuration")
        demo = os.environ.get("MNOTE_TRANSCRIPT_DEMO_AUDIO", "")
        public_url = os.environ.get("MNOTE_TRANSCRIPT_PUBLIC_URL", "").rstrip("/")
        if public_url:
            if validate_url(public_url, lambda host: True).query:
                raise ValueError("public API URL cannot contain query parameters")
        browser_socket = os.environ.get("MNOTE_TRANSCRIPT_BROWSER_SOCKET", "")
        browser_token = os.environ.get("MNOTE_TRANSCRIPT_BROWSER_TOKEN", "")
        if browser_socket and (not browser_socket.startswith("/") or "\x00" in browser_socket or
                               len(os.fsencode(browser_socket)) > 107 or
                               not re.fullmatch(r"[A-Za-z0-9._~-]{32,512}", browser_token)):
            raise ValueError("invalid browser helper configuration")
        return cls(Path(os.environ.get("MNOTE_TRANSCRIPT_DATA", "./data")).resolve(),
                   os.environ.get("MNOTE_TRANSCRIPT_TOKEN", ""),
                   Path(os.environ.get("MNOTE_TRANSCRIPT_MODEL", "./models/faster-whisper-small")).resolve(), hosts,
                   demo_audio=Path(demo).resolve() if demo else None, public_url=public_url,
                   browser_socket=browser_socket, browser_token=browser_token)

    def ready(self) -> bool:
        return self.readiness()[0]

    def readiness(self) -> tuple[bool, str]:
        # Read installed dist-info only: do not import inference/PyAV or load a model.
        for package, expected in RUNTIME_VERSIONS.items():
            try:
                actual = importlib.metadata.version(package)
            except importlib.metadata.PackageNotFoundError:
                return False, f"本地转写依赖缺失：请安装 {package}=={expected}。"
            if actual != expected:
                return False, f"本地转写依赖版本不兼容：请固定 {package}=={expected} 并重新验证。"
        if importlib.util.find_spec("faster_whisper") is None or importlib.util.find_spec("av") is None:
            return False, "本地转写依赖文件缺失：请按 requirements.txt 重新安装。"
        if not self.model_path.is_dir() or not (self.model_path / "model.bin").is_file():
            return False, "本地转写模型尚未准备好：请配置完整的本地 small 模型。"
        if not shutil.which(self.ffmpeg) or not shutil.which(self.ffprobe):
            return False, "音频工具缺失：请安装 FFmpeg 与 ffprobe。"
        return True, "本地 CPU 转写已就绪。"


def validate_url(url: str, allowed: Callable[[str], bool]) -> urllib.parse.SplitResult:
    if not isinstance(url, str) or len(url) > 4096 or re.search(r"[\s\x00-\x1f\x7f\\]", url):
        raise ServiceError("unsafe_url")
    try:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname or ""
        if parts.scheme.lower() != "https" or parts.username is not None or parts.password is not None or parts.fragment or parts.port not in (None, 443):
            raise ValueError()
        if not host or host.endswith(".") or "%" in parts.netloc or host != host.encode("idna").decode("ascii") or not re.fullmatch(r"[a-z0-9.-]+", host):
            raise ValueError()
        if not allowed(host):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ServiceError("unsafe_url") from None
    return parts


def parse_share_text(text: object, direct_hosts: tuple[str, ...]) -> dict:
    if not isinstance(text, str) or not text.strip() or len(text) > 8192:
        raise ServiceError("invalid_url")
    stripped = text.strip()
    # A malformed naked URL is not Markdown: never accept a different prefix
    # simply because a forbidden character resembles a formatting delimiter.
    if re.match(r"https?://", stripped, re.I) and not re.search(r"\s", stripped):
        if SHARE_URL_RE.fullmatch(stripped) is None:
            raise ServiceError("invalid_url")
    urls = set()
    for candidate in SHARE_URL_RE.findall(text):
        candidate = candidate.rstrip(".,;!?】）")
        # Only discard unmatched Markdown/prose closers, not a balanced ')' in
        # a path or signature such as ?sig=(value).
        unmatched_closers = candidate.count(")") - candidate.count("(")
        while unmatched_closers > 0 and candidate.endswith(")"):
            candidate = candidate[:-1].rstrip(".,;!?】）")
            unmatched_closers -= 1
        urls.add(candidate)
    if len(urls) != 1:
        raise ServiceError("invalid_url")
    url = next(iter(urls))
    parts = validate_url(url, lambda host: host in SITE_HOSTS or host in direct_hosts)
    host = parts.hostname
    if host in BILI_HOSTS:
        platform = "bilibili"
    elif host in DOUYIN_HOSTS:
        platform = "douyin"
    else:
        if Path(urllib.parse.unquote(parts.path)).suffix.lower() not in AUDIO_EXTENSIONS:
            raise ServiceError("unsupported_source")
        platform = "audio"
    # Strip fragments already rejected; never persist the surrounding copied share text.
    return {"url": url, "platform": platform, "host": host}


def public_addresses(host: str) -> list[str]:
    try:
        answers = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError:
        raise ServiceError("network_failed") from None
    addresses = list(dict.fromkeys(answer[4][0] for answer in answers))
    if not addresses:
        raise ServiceError("network_failed")
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if not ip.is_global or ip.is_multicast or ip.is_reserved or ip.is_loopback or ip.is_link_local or ip.is_unspecified or getattr(ip, "ipv4_mapped", None) or getattr(ip, "sixtofour", None) or getattr(ip, "teredo", None):
            raise ServiceError("unsafe_url")
    return addresses


class PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host: str, address: str, timeout: float):
        super().__init__(host, 443, timeout=timeout, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        # No second hostname lookup: the approved IP is pinned, while TLS checks the real host.
        self.sock = socket.create_connection((self.address, 443), self.timeout)
        # Publish the actual TLS socket before a potentially blocking handshake.
        # wrap_socket detaches the raw socket, so the download watchdog cannot
        # interrupt its default implicit handshake through the old self.sock.
        tls_socket = self._context.wrap_socket(self.sock, server_hostname=self.host,
                                              do_handshake_on_connect=False)
        self.sock = tls_socket
        tls_socket.do_handshake()


def bounded_addresses(host: str, cancel: Callable[[], bool] = lambda: False,
                      deadline_at: float | None = None) -> list[str]:
    # Isolate the OS resolver so stuck DNS cannot hold the single job worker forever.
    command = [sys.executable, str(Path(__file__).resolve()), "--resolve", host]
    with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as process:
        deadline = time.monotonic() + 15
        if deadline_at is not None:
            deadline = min(deadline, deadline_at)
        while process.poll() is None:
            if cancel() or time.monotonic() > deadline:
                process.kill()
                process.wait()
                if cancel():
                    raise Cancelled()
                raise ServiceError("timeout")
            time.sleep(0.05)
        raw = process.stdout.read(16384)
        if process.returncode != 0:
            raise ServiceError("unsafe_url" if process.returncode == 2 else "network_failed")
        try:
            values = json.loads(raw)
            if not isinstance(values, list) or not values:
                raise ValueError()
            return values
        except (ValueError, UnicodeError):
            raise ServiceError("network_failed") from None


class SafeClient:
    def __init__(self, resolver=None, connection=PinnedHTTPSConnection, deadline_seconds=120):
        self.resolver, self.connection, self.deadline_seconds = resolver, connection, deadline_seconds

    def fetch(self, url: str, allowed: Callable[[str], bool], limit: int,
              destination: Path | None = None, cancel: Callable[[], bool] = lambda: False,
              progress: Callable[[float], None] = lambda x: None,
              stop_redirect: Callable[[str], bool] = lambda url: False,
              deadline_at: float | None = None) -> tuple[bytes, str]:
        deadline = time.monotonic() + self.deadline_seconds
        if deadline_at is not None:
            deadline = min(deadline, deadline_at)
        for redirects in range(5):
            if cancel():
                raise Cancelled()
            if time.monotonic() >= deadline:
                raise ServiceError("timeout")
            parts = validate_url(url, allowed)
            addresses = self.resolver(parts.hostname) if self.resolver else bounded_addresses(parts.hostname, cancel, deadline)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ServiceError("timeout")
            connection = self.connection(parts.hostname, addresses[0], min(15, remaining))
            transport_socket = [None]
            finished = threading.Event()
            interrupted = threading.Event()
            def watchdog():
                while not finished.wait(0.1):
                    if cancel() or time.monotonic() >= deadline:
                        interrupted.set()
                        # HTTP/1.0 clears connection.sock while response.fp still owns it.
                        sock = transport_socket[0] or connection.sock
                        if sock:
                            with contextlib.suppress(OSError):
                                sock.shutdown(socket.SHUT_RDWR)
                        connection.close()
                        return
            watcher = threading.Thread(target=watchdog, daemon=True)
            watcher.start()
            try:
                path = urllib.parse.urlunsplit(("", "", parts.path or "/", parts.query, ""))
                connection.request("GET", path, headers={"User-Agent": "MnoteAuthorizedTranscript/1.0", "Accept-Encoding": "identity"})
                transport_socket[0] = connection.sock
                response = connection.getresponse()
                if response.status in (301, 302, 303, 307, 308):
                    location = response.getheader("Location")
                    if not location or redirects == 4:
                        raise ServiceError("unsafe_url")
                    url = urllib.parse.urljoin(url, location)
                    validate_url(url, allowed)
                    if stop_redirect(url):
                        return b"", url
                    continue
                if response.status in (403, 412):
                    raise ServiceError("source_forbidden")
                if response.status == 429:
                    raise ServiceError("source_rate_limited")
                if response.status != 200:
                    raise ServiceError("network_failed")
                encoding = response.getheader("Content-Encoding", "identity").lower()
                if encoding not in ("identity", ""):
                    raise ServiceError("invalid_media")
                try:
                    size = int(response.getheader("Content-Length", "0"))
                except ValueError:
                    size = 0
                if size > limit:
                    raise ServiceError("too_large")
                collected, count = [], 0
                with destination.open("wb") if destination else contextlib.nullcontext() as handle:
                    while True:
                        if cancel():
                            raise Cancelled()
                        if time.monotonic() >= deadline:
                            raise ServiceError("timeout")
                        chunk = response.read1(min(64 * 1024, limit - count + 1))
                        if interrupted.is_set():
                            if cancel():
                                raise Cancelled()
                            raise ServiceError("timeout")
                        if not chunk:
                            break
                        count += len(chunk)
                        if count > limit:
                            raise ServiceError("too_large")
                        if handle:
                            handle.write(chunk)
                        else:
                            collected.append(chunk)
                        progress(min(1, count / size) if size else 0)
                if size > 0 and count != size:
                    raise ServiceError("network_failed")
                return b"".join(collected), url
            except (OSError, http.client.HTTPException, ssl.SSLError):
                if interrupted.is_set():
                    if cancel():
                        raise Cancelled()
                    raise ServiceError("timeout") from None
                raise ServiceError("network_failed") from None
            finally:
                finished.set()
                connection.close()
                watcher.join(timeout=0.2)
        raise ServiceError("unsafe_url")

    def json(self, url: str, allowed: Callable[[str], bool]) -> dict:
        raw, _ = self.fetch(url, allowed, 1024 * 1024)
        try:
            result = json.loads(raw)
            if not isinstance(result, dict):
                raise ValueError()
            return result
        except (ValueError, UnicodeError):
            raise ServiceError("media_unavailable") from None


class UnixHTTPConnection(http.client.HTTPConnection):
    """The endpoint is an administrator-configured local socket, never a source URL."""
    def __init__(self, path: str):
        super().__init__("localhost", timeout=BROWSER_TIMEOUT)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(BROWSER_TIMEOUT)
        self.sock.connect(self.path)


def browser_resolve(config: Config, video_id: str, cancel: Callable[[], bool] = lambda: False) -> dict:
    if cancel():
        raise Cancelled()
    if not re.fullmatch(r"[0-9]{10,24}", video_id):
        raise ServiceError("invalid_request")
    if not config.browser_socket or not re.fullmatch(r"[A-Za-z0-9._~-]{32,512}", config.browser_token):
        raise ServiceError("browser_unavailable", 503)
    connection = UnixHTTPConnection(config.browser_socket)
    finished, interrupted = threading.Event(), threading.Event()
    transport_socket = [None]
    deadline = time.monotonic() + BROWSER_TIMEOUT

    def check():
        if cancel():
            raise Cancelled()
        if interrupted.is_set() or time.monotonic() >= deadline:
            raise ServiceError("timeout")

    def watchdog():
        while not finished.wait(0.05):
            if cancel() or time.monotonic() >= deadline:
                interrupted.set()
                sock = transport_socket[0] or connection.sock
                if sock:
                    with contextlib.suppress(OSError):
                        sock.shutdown(socket.SHUT_RDWR)
                connection.close()
                # Keep watching until request unwinds: cancellation can race
                # with connect() publishing a newly created socket.

    watcher = threading.Thread(target=watchdog, daemon=True)
    watcher.start()
    try:
        connection.request("POST", "/resolve", body=json.dumps({"video_id": video_id}).encode("ascii"),
                           headers={"Authorization": "Bearer " + config.browser_token,
                                    "Content-Type": "application/json", "Accept-Encoding": "identity"})
        check()
        transport_socket[0] = connection.sock
        response = connection.getresponse()
        check()
        # Never follow redirects or accept compressed/unbounded helper payloads.
        if 300 <= response.status < 400 or response.getheader("Content-Encoding", "identity").lower() not in ("", "identity"):
            raise ServiceError("browser_unavailable", 503)
        declared = response.getheader("Content-Length")
        size = int(declared) if declared is not None else None
        if size is not None and not 0 <= size <= BROWSER_MAX_BYTES:
            raise ServiceError("browser_unavailable", 503)
        chunks, count = [], 0
        while True:
            check()
            chunk = response.read1(min(65536, BROWSER_MAX_BYTES - count + 1))
            check()
            if not chunk:
                break
            count += len(chunk)
            if count > BROWSER_MAX_BYTES:
                raise ServiceError("browser_unavailable", 503)
            chunks.append(chunk)
        if size is not None and count != size:
            raise ServiceError("browser_unavailable", 503)
        data = json.loads(b"".join(chunks))
        if not isinstance(data, dict):
            raise ServiceError("browser_unavailable", 503)
        if "error" in data:
            error = data["error"]
            code = error.get("code") if isinstance(error, dict) else None
            if not isinstance(code, str) or code not in BROWSER_ERROR_CODES:
                code = "browser_unavailable"
            raise ServiceError(code, 503 if code in ("browser_unavailable", "browser_busy") else 400)
        if response.status != 200 or not isinstance(data.get("item"), dict):
            raise ServiceError("browser_unavailable", 503)
        return data["item"]
    except (OSError, http.client.HTTPException, ValueError, UnicodeError, RecursionError):
        check()
        raise ServiceError("browser_unavailable", 503) from None
    finally:
        finished.set()
        connection.close()
        watcher.join(timeout=0.2)


def cdn_allowed(host: str, platform: str, direct_hosts: tuple[str, ...]) -> bool:
    if platform == "audio":
        return host in direct_hosts
    if platform == "bilibili" and host in BILI_EXTRA_CDN_HOSTS:
        return True
    if platform == "douyin" and host in DOUYIN_EXTRA_CDN_HOSTS:
        return True
    domains = ("bilivideo.com", "bilivideo.cn", "bilivideo.net") if platform == "bilibili" else ("douyinvod.com", "bytecdn.cn", "ibytedtos.com")
    return any(host == domain or host.endswith("." + domain) for domain in domains)


class MediaResolver:
    def __init__(self, client: SafeClient, config: Config):
        self.client, self.config = client, config

    def resolve(self, source: dict) -> tuple[str, dict]:
        if source["platform"] == "audio":
            return source["url"], {"platform": "audio", "host": source["host"], "title": "公开音频"}
        if source["platform"] == "bilibili":
            return self.bilibili(source)
        return self.douyin(source)

    def resolve_candidates(self, source: dict, cancel: Callable[[], bool] = lambda: False) -> tuple[list[str], dict]:
        if cancel():
            raise Cancelled()
        if source["platform"] == "bilibili":
            return self.bilibili_candidates(source)
        if source["platform"] == "douyin":
            return self.douyin_candidates(source, cancel)
        url, public = self.resolve(source)
        return [url], public

    def bilibili(self, source: dict) -> tuple[str, dict]:
        urls, public = self.bilibili_candidates(source)
        return urls[0], public

    def bilibili_candidates(self, source: dict) -> tuple[list[str], dict]:
        url = source["url"]
        if source["host"] == "b23.tv":
            def canonical(candidate):
                parsed = urllib.parse.urlsplit(candidate)
                return parsed.hostname in BILI_HOSTS - {"b23.tv"} and re.search(r"/video/(BV[0-9A-Za-z]{10}|av[0-9]{1,16})(?:/|$)", parsed.path) is not None
            _, url = self.client.fetch(url, lambda host: host in BILI_HOSTS, 1024 * 1024, stop_redirect=canonical)
        parts = validate_url(url, lambda host: host in BILI_HOSTS)
        match = re.search(r"/video/(BV[0-9A-Za-z]{10}|av[0-9]{1,16})(?:/|$)", parts.path)
        if not match:
            raise ServiceError("media_unavailable")
        ident = match[1]
        param = ("bvid", ident) if ident.startswith("BV") else ("aid", ident[2:])
        endpoint = "https://api.bilibili.com/x/web-interface/view?" + urllib.parse.urlencode([param])
        view = self.client.json(endpoint, lambda host: host == "api.bilibili.com")
        if view.get("code") != 0 or not isinstance(view.get("data"), dict):
            raise ServiceError("platform_restricted")
        data = view["data"]
        try:
            page = int(urllib.parse.parse_qs(parts.query).get("p", ["1"])[0])
            pages = data["pages"]
            if not 1 <= page <= len(pages):
                raise ValueError()
            selected = pages[page - 1]
            duration = float(selected["duration"])
            cid = int(selected["cid"])
            if duration <= 0 or not math.isfinite(duration):
                raise ValueError()
        except (KeyError, ValueError, TypeError, IndexError):
            raise ServiceError("media_unavailable") from None
        if duration > MAX_DURATION:
            raise ServiceError("too_long")
        params = [param, ("cid", str(cid)), ("fnval", "16"), ("qn", "16"), ("fourk", "0")]
        play = self.client.json("https://api.bilibili.com/x/player/playurl?" + urllib.parse.urlencode(params), lambda host: host == "api.bilibili.com")
        try:
            if play.get("code") != 0:
                raise ServiceError("platform_restricted")
            audio = play["data"]["dash"]["audio"]
            if not isinstance(audio, list) or not audio:
                raise ValueError()
            stream = min(audio, key=lambda item: int(item.get("bandwidth", 10**12)))
            media_url = stream.get("baseUrl") or stream.get("base_url")
        except (KeyError, TypeError, ValueError, AttributeError):
            raise ServiceError("media_unavailable") from None
        supplied = [media_url]
        for key in ("backupUrl", "backup_url"):
            backups = stream.get(key)
            if isinstance(backups, list):
                supplied.extend(backups[:16])
        urls, rejected = [], False
        for candidate in supplied:
            if candidate is None:
                continue
            try:
                validate_url(candidate, lambda host: cdn_allowed(host, "bilibili", ()))
            except ServiceError:
                rejected = True
                continue
            if candidate not in urls:
                urls.append(candidate)
            if len(urls) >= MAX_MEDIA_CANDIDATES:
                break
        if not urls:
            raise ServiceError("unsafe_url" if rejected else "media_unavailable")
        return urls, {"platform": "bilibili", "host": "www.bilibili.com", "title": clean_text(data.get("title", "公开媒体"), 200, True), "duration_seconds": duration}

    def douyin(self, source: dict) -> tuple[str, dict]:
        urls, public = self.douyin_candidates(source)
        return urls[0], public

    @staticmethod
    def douyin_identity(url: str) -> tuple[str, bool] | None:
        parts = validate_url(url, lambda host: host in DOUYIN_HOSTS)
        # A numeric ID in a query or unrelated path is not evidence of the target.
        if parts.hostname == "v.douyin.com":
            return None
        match = re.fullmatch(r"/(share/video|video|share/note|note)/([0-9]{10,24})/?", parts.path)
        return (match[2], match[1] in ("note", "share/note")) if match else None

    def douyin_candidates(self, source: dict, cancel: Callable[[], bool] = lambda: False) -> tuple[list[str], dict]:
        if cancel():
            raise Cancelled()
        url = source["url"]
        identity = self.douyin_identity(url)
        if identity and identity[1]:
            raise ServiceError("image_post_unsupported")
        # For a confirmed ID, the isolated anonymous browser avoids fetching the
        # desktop challenge twice. It is not a fallback past an observed denial.
        if identity and self.config.browser_socket:
            return self.douyin_item(browser_resolve(self.config, identity[0], cancel), identity[0])
        raw, final_url = self.client.fetch(url, lambda host: host in DOUYIN_HOSTS, BROWSER_MAX_BYTES,
                                           cancel=cancel, stop_redirect=lambda target: self.douyin_identity(target) is not None)
        final_identity = self.douyin_identity(final_url)
        if not final_identity or (identity and identity[0] != final_identity[0]):
            raise ServiceError("platform_restricted")
        if final_identity[1]:
            raise ServiceError("image_post_unsupported")
        requested = final_identity[0]
        if not raw:
            if self.config.browser_socket:
                return self.douyin_item(browser_resolve(self.config, requested, cancel), requested)
            # The disabled-helper compatibility path still reads public embedded
            # metadata, but the short-link hop never downloads a transition page.
            raw, checked_url = self.client.fetch(final_url, lambda host: host in DOUYIN_HOSTS,
                                                  BROWSER_MAX_BYTES, cancel=cancel)
            if self.douyin_identity(checked_url) != final_identity:
                raise ServiceError("platform_restricted")
        html = raw.decode("utf-8", "replace")
        # Explicit login/verification walls are terminal. The helper must not be
        # used to evade a denial already observed by this request.
        if re.search(r"(?:login\s+(?:needed|required)|please\s+(?:log\s*in|sign\s*in)|"
                     r"captcha|verify\s+(?:you|your|to\s+continue)|登录后|请先?登录|验证码|安全验证)", html, re.I):
            raise ServiceError("platform_restricted")
        try:
            candidate = self.douyin_embedded(html, requested)
            return self.douyin_item(candidate, requested)
        except ServiceError as error:
            if error.code not in ("platform_restricted", "media_unavailable") or not self.config.browser_socket:
                raise
            return self.douyin_item(browser_resolve(self.config, requested, cancel), requested)

    @staticmethod
    def douyin_embedded(html: str, requested: str) -> dict:
        match = re.search(r'<script[^>]+id=["\']RENDER_DATA["\'][^>]*>(.*?)</script>', html, re.S)
        if not match:
            raise ServiceError("platform_restricted")
        try:
            # Unquoting valid raw JSON corrupts signed URL values such as %2F.
            try:
                data = json.loads(match[1])
            except ValueError:
                data = json.loads(urllib.parse.unquote(match[1]))
        except (ValueError, UnicodeError, RecursionError):
            raise ServiceError("platform_restricted") from None
        stack, candidate, visited = [data], None, 0
        while stack and visited < 2000:
            node = stack.pop()
            visited += 1
            if isinstance(node, dict):
                item_id = node.get("aweme_id", node.get("awemeId"))
                if str(item_id) == requested:
                    candidate = node
                    break
                stack.extend(node.values())
            elif isinstance(node, list):
                stack.extend(node)
        if not candidate:
            raise ServiceError("platform_restricted")
        return candidate

    @staticmethod
    def douyin_item(candidate: dict, requested: str) -> tuple[list[str], dict]:
        if str(candidate.get("aweme_id", candidate.get("awemeId"))) != requested:
            raise ServiceError("platform_restricted")
        # Never transcribe a photo post's music as if it were the post's content.
        if str(candidate.get("aweme_type")) in ("68", "150") or candidate.get("images") or candidate.get("image_post_info"):
            raise ServiceError("image_post_unsupported")
        try:
            video = candidate["video"]
            duration = float(video["duration"]) / 1000
            if not math.isfinite(duration) or duration <= 0:
                raise ValueError()
        except (KeyError, IndexError, TypeError, ValueError, OverflowError):
            raise ServiceError("platform_restricted") from None
        if duration > MAX_DURATION:
            raise ServiceError("too_long")
        rates = video.get("bit_rate")
        streams = []
        if isinstance(rates, list):
            for rate in rates[:64]:
                if not isinstance(rate, dict) or not isinstance(rate.get("play_addr"), dict):
                    continue
                try:
                    bandwidth = float(rate.get("bit_rate", rate.get("bandwidth", math.inf)))
                    if math.isfinite(bandwidth) and bandwidth > 0:
                        streams.append((bandwidth, rate["play_addr"]))
                except (TypeError, ValueError, OverflowError):
                    continue
        # Prefer a low-rate representation, but keep the normal player version
        # as a fallback: some low-rate CDN variants reject otherwise public works.
        # Retries replace the whole file; bytes from representations are never joined.
        address = min(streams, key=lambda stream: stream[0])[1] if streams else video.get("play_addr", {})
        primary = video.get("play_addr", {})
        supplied = address.get("url_list") if isinstance(address, dict) else None
        fallback = primary.get("url_list", []) if isinstance(primary, dict) else []
        if not isinstance(supplied, list) and not isinstance(fallback, list):
            raise ServiceError("media_unavailable")
        rejected = False
        def valid(values):
            nonlocal rejected
            cleaned = []
            for value in values[:32] if isinstance(values, list) else []:
                try:
                    validate_url(value, lambda host: cdn_allowed(host, "douyin", ()))
                except ServiceError:
                    rejected = True
                    continue
                if value not in cleaned:
                    cleaned.append(value)
            return cleaned
        low, player = valid(supplied), valid(fallback)
        # Prefer a different fallback CDN when the normal player supplies one.
        if low and player:
            host = urllib.parse.urlsplit(low[0]).hostname
            alternate = next((value for value in player if urllib.parse.urlsplit(value).hostname != host), player[0])
            player = [alternate] + [value for value in player if value != alternate]
        ordered = low[:1] + player[:1] + low[1:] + player[1:]
        urls = list(dict.fromkeys(ordered))[:MAX_MEDIA_CANDIDATES]
        if not urls:
            raise ServiceError("unsafe_url" if rejected else "media_unavailable")
        title = candidate.get("desc")
        return urls, {"platform": "douyin", "host": "www.douyin.com",
                      "title": clean_text(title if isinstance(title, str) else "公开媒体", 200, True),
                      "duration_seconds": duration}


class JobStore:
    def __init__(self, config: Config):
        self.config, self.lock = config, threading.RLock()
        config.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(config.data_dir, 0o700)
        self.path = config.data_dir / "jobs.sqlite3"
        with self.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, owner TEXT NOT NULL, private_url TEXT NOT NULL, payload TEXT NOT NULL, expires_at REAL NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS jobs_owner ON jobs(owner)")
        os.chmod(self.path, 0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            connection = sqlite3.connect(self.path, timeout=10)
            connection.execute("PRAGMA secure_delete=ON")
            connection.execute("PRAGMA journal_mode=DELETE")
            try:
                with connection:
                    yield connection
            finally:
                connection.close()

    def create(self, owner: str, source: dict, rights: dict) -> dict:
        now = time.time()
        ident = str(uuid.uuid4())
        job = {"id": ident, "status": "queued", "stage": "queued", "progress": 0, "error": None,
               "source": {"platform": source["platform"], "host": source["host"], "title": "等待解析"},
               "segments": [], "text": "", "created_at": now, "updated_at": now,
               "expires_at": now + self.config.retention_hours * 3600, "rights": rights}
        with self.db() as db:
            active = db.execute("SELECT payload FROM jobs").fetchall()
            if sum(json.loads(row[0])["status"] in ("queued", "processing") for row in active) >= self.config.queue_size:
                raise ServiceError("queue_full", 429)
            db.execute("INSERT INTO jobs VALUES (?,?,?,?,?)", (ident, owner, source["url"], json.dumps(job, ensure_ascii=False), job["expires_at"]))
        return job

    def get(self, ident: str, owner: str | None = None, private: bool = False) -> dict | None:
        with self.db() as db:
            query = "SELECT payload,private_url FROM jobs WHERE id=? AND expires_at>?"
            args = [ident, time.time()]
            if owner is not None:
                query += " AND owner=?"
                args.append(owner)
            row = db.execute(query, args).fetchone()
        if row is None:
            return None
        job = json.loads(row[0])
        if private:
            job["private_url"] = row[1]
        return job

    def update(self, ident: str, **fields) -> bool:
        with self.db() as db:
            row = db.execute("SELECT payload FROM jobs WHERE id=? AND expires_at>?", (ident, time.time())).fetchone()
            if not row:
                return False
            job = json.loads(row[0])
            if "progress" in fields:
                fields["progress"] = max(job["progress"], min(100, int(fields["progress"])))
            job.update(fields, updated_at=time.time())
            # Drop private acquisition URL at the first terminal state.
            if job["status"] in ("completed", "failed"):
                db.execute("UPDATE jobs SET payload=?,private_url='' WHERE id=?", (json.dumps(job, ensure_ascii=False), ident))
            else:
                db.execute("UPDATE jobs SET payload=? WHERE id=?", (json.dumps(job, ensure_ascii=False), ident))
        return True

    def delete(self, ident: str, owner: str) -> bool:
        with self.db() as db:
            result = db.execute("DELETE FROM jobs WHERE id=? AND owner=?", (ident, owner))
            return result.rowcount > 0

    def expired(self) -> list[str]:
        with self.db() as db:
            ids = [row[0] for row in db.execute("SELECT id FROM jobs WHERE expires_at<=?", (time.time(),))]
            db.execute("DELETE FROM jobs WHERE expires_at<=?", (time.time(),))
        return ids

    def recover(self):
        with self.db() as db:
            rows = db.execute("SELECT id,payload FROM jobs").fetchall()
            for ident, payload in rows:
                job = json.loads(payload)
                if job["status"] in ("queued", "processing"):
                    job.update(status="failed", stage="failed", error={"code": "interrupted", "message": ERRORS["interrupted"]}, updated_at=time.time())
                    db.execute("UPDATE jobs SET payload=?,private_url='' WHERE id=?", (json.dumps(job, ensure_ascii=False), ident))


def normalize_segments(segments: object) -> list[dict]:
    if not isinstance(segments, list) or len(segments) > 20000:
        raise ServiceError("transcription_failed")
    result, last_start = [], 0.0
    for item in segments:
        try:
            start, end = float(item["start"]), float(item["end"])
            text = clean_text(item["text"], 10000)
            if not math.isfinite(start) or not math.isfinite(end) or not 0 <= start <= end <= MAX_DURATION + 0.05 or start < last_start:
                raise ValueError()
        except (TypeError, KeyError, ValueError):
            raise ServiceError("transcription_failed") from None
        last_start = start
        if text:
            result.append({"start": round(start, 3), "end": round(end, 3), "text": text})
    return result


def timestamp(seconds: float, srt: bool = False) -> str:
    millis = max(0, round(seconds * 1000))
    hours, remainder = divmod(millis, 3600000)
    minutes, remainder = divmod(remainder, 60000)
    sec, fraction = divmod(remainder, 1000)
    return f"{hours:02}:{minutes:02}:{sec:02}{',' if srt else '.'}{fraction:03}"


def export_job(job: dict, format_name: str) -> tuple[str, str]:
    if job["status"] != "completed":
        raise ServiceError("not_completed", 409)
    if format_name == "txt":
        return job["text"] + "\n", "text/plain; charset=utf-8"
    if format_name == "md":
        title = re.sub(r"[\[\]<>#*_`\\]", "", job["source"].get("title", "转写结果"))
        lines = [f"# {title}", "", "> 本地自动转写，可能包含识别错误；授权依据为用户声明，未经服务核验。", ""]
        for item in job["segments"]:
            # Text remains plain, not executable HTML or links in Markdown export.
            text = re.sub(r"([\\`*_{}\[\]<>#])", r"\\\1", item["text"].replace("\n", " "))
            lines.extend([f"[{timestamp(item['start'])}] {text}", ""])
        return "\n".join(lines), "text/markdown; charset=utf-8"
    if format_name == "srt":
        blocks = []
        for number, item in enumerate(job["segments"], 1):
            text = re.sub(r"<[^>]*>", "", item["text"]).replace("-->", "→")
            text = "\n".join(line for line in text.splitlines() if line.strip())
            blocks.append(f"{number}\n{timestamp(item['start'], True)} --> {timestamp(item['end'], True)}\n{text}\n")
        return "\n".join(blocks), "application/x-subrip; charset=utf-8"
    raise ServiceError("invalid_request")


def run_process(command: list[str], cancel: Callable[[], bool], timeout: int, output=None, monitor: Callable[[], None] = lambda: None):
    started = time.monotonic()
    with subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output if output else subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, start_new_session=True) as process:
        while process.poll() is None:
            monitor()
            if cancel() or time.monotonic() - started > timeout:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                if cancel():
                    raise Cancelled()
                raise ServiceError("timeout")
            time.sleep(0.1)
        if process.returncode:
            raise ServiceError("invalid_media")


def transcribe_child(audio: Path, output: Path, model_path: Path, progress_path: Path):
    # Executed in a separate process, with no remote URL or shared token in argv.
    from faster_whisper import WhisperModel
    model = WhisperModel(str(model_path), device="cpu", compute_type="int8", cpu_threads=2,
                         num_workers=1, local_files_only=True)
    segments, info = model.transcribe(str(audio), beam_size=1, vad_filter=True)
    if info.duration > MAX_DURATION + 0.05:
        raise ServiceError("too_long")
    collected = []
    for segment in segments:
        collected.append({"start": segment.start, "end": segment.end, "text": segment.text})
        progress_path.write_text(str(max(0, min(1, segment.end / max(0.001, info.duration)))), encoding="ascii")
    normalized = normalize_segments(collected)
    output.write_text(json.dumps(normalized, ensure_ascii=False), encoding="utf-8")


class JobService:
    def __init__(self, config: Config, client=None, processor=None, start_worker=True):
        self.config, self.store = config, JobStore(config)
        self.client = client or SafeClient()
        self.resolver = MediaResolver(self.client, config)
        self.processor = processor or self.process
        self.pending = queue.Queue(maxsize=config.queue_size)
        self.stop_event = threading.Event()
        self.cancelled, self.cancel_lock = set(), threading.Lock()
        self.work_dir = config.data_dir / "work"
        self.work_dir.mkdir(exist_ok=True, mode=0o700)
        self.store.recover()
        for path in self.work_dir.iterdir():
            if UUID_RE.fullmatch(path.name):
                self.cleanup(path.name)
        self.thread = threading.Thread(target=self.worker, name="transcript-single-worker", daemon=True)
        if start_worker:
            self.thread.start()

    def cancelled_job(self, ident: str) -> bool:
        with self.cancel_lock:
            return self.stop_event.is_set() or ident in self.cancelled or self.store.get(ident) is None

    def cleanup(self, ident: str):
        if not UUID_RE.fullmatch(ident):
            return
        target = self.work_dir / ident
        if target.is_symlink():
            target.unlink()
        elif target.is_dir():
            shutil.rmtree(target, ignore_errors=True)

    def expire(self):
        for ident in self.store.expired():
            self.cleanup(ident)

    def submit(self, owner: str, body: dict) -> dict:
        if not isinstance(body, dict):
            raise ServiceError("invalid_request")
        if body.get("consent") is not True or body.get("agreement_version") != AGREEMENT_VERSION or body.get("rights_basis") not in ("owned", "authorized", "open_license"):
            raise ServiceError("consent_required")
        note = clean_declaration(body.get("rights_note", ""))
        if not note or not isinstance(body.get("rights_note"), str) or len(body["rights_note"]) > 1000 or (body["rights_basis"] != "owned" and len(note) < 8):
            raise ServiceError("consent_required")
        source = parse_share_text(body.get("share_text"), self.config.direct_hosts)
        self.expire()
        rights = {"basis": body["rights_basis"], "note": note, "agreement_version": AGREEMENT_VERSION,
                  "consented_at": time.time(), "verified": False}
        job = self.store.create(owner, source, rights)
        try:
            self.pending.put_nowait(job["id"])
        except queue.Full:
            self.store.delete(job["id"], owner)
            raise ServiceError("queue_full", 429) from None
        return job

    def get(self, ident: str, owner: str) -> dict:
        self.expire()
        job = self.store.get(ident, owner)
        if not job:
            raise ServiceError("not_found", 404)
        return job

    def delete(self, ident: str, owner: str):
        self.get(ident, owner)
        # Delete row first so even a racing worker cannot resurrect stored text.
        self.store.delete(ident, owner)
        self.cleanup(ident)

    def worker(self):
        while not self.stop_event.is_set():
            self.expire()
            try:
                ident = self.pending.get(timeout=1)
            except queue.Empty:
                continue
            try:
                job = self.store.get(ident, private=True)
                if not job or self.cancelled_job(ident):
                    continue
                self.store.update(ident, status="processing", stage="resolving", progress=5)
                segments = normalize_segments(self.processor(job))
                if not self.cancelled_job(ident):
                    self.store.update(ident, status="completed", stage="completed", progress=100,
                                      segments=segments, text="\n".join(segment["text"] for segment in segments))
            except Cancelled:
                pass
            except ServiceError as error:
                self.store.update(ident, status="failed", stage="failed", error={"code": error.code, "message": str(error)})
            except Exception:
                # Never send exception strings, stack traces, source URLs, or signed queries to users/logs.
                self.store.update(ident, status="failed", stage="failed", error={"code": "transcription_failed", "message": ERRORS["transcription_failed"]})
            finally:
                self.cleanup(ident)
                with self.cancel_lock:
                    self.cancelled.discard(ident)
                self.pending.task_done()

    def process(self, job: dict) -> list[dict]:
        if not self.config.ready():
            raise ServiceError("not_ready", 503)
        ident = job["id"]
        cancel = lambda: self.cancelled_job(ident)
        source = {**job["source"], "url": job["private_url"]}
        media_urls, public_source = self.resolver.resolve_candidates(source, cancel=cancel)
        if cancel():
            raise Cancelled()
        self.store.update(ident, source=public_source, stage="downloading", progress=15)
        directory = self.work_dir / ident
        directory.mkdir(mode=0o700)
        audio, wav, result, progress_path = directory / "input.media", directory / "audio.wav", directory / "result.json", directory / "progress.txt"
        def decode_candidate(deadline):
            if cancel():
                raise Cancelled()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ServiceError("timeout")
            # A successful HTTP download can still be a video-only rendition.
            # Replace, never append to, any WAV left by an earlier candidate.
            wav.unlink(missing_ok=True)
            formats = "mov,mp3,wav,ogg,flac,aac,matroska,webm"
            command = [self.config.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "2",
                       "-protocol_whitelist", "file,pipe", "-format_whitelist", formats, "-i", str(audio),
                       "-t", str(MAX_DURATION + 1), "-vn", "-map_metadata", "-1", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(wav)]
            # Decode only complete local files; network/playlist demuxers stay disabled.
            run_process(command, cancel, min(180, remaining))
            try:
                with wave.open(str(wav), "rb") as media:
                    duration = media.getnframes() / media.getframerate()
            except (wave.Error, EOFError, ZeroDivisionError):
                raise ServiceError("invalid_media") from None
            if duration > MAX_DURATION + 0.05:
                raise ServiceError("too_long")
            if duration <= 0:
                raise ServiceError("invalid_media")
        self.download_media(media_urls, source, audio, cancel,
                            lambda value: self.store.update(ident, progress=15 + 25 * value),
                            validate=decode_candidate)
        if cancel():
            raise Cancelled()
        self.store.update(ident, stage="transcribing", progress=50)
        command = [sys.executable, str(Path(__file__).resolve()), "--transcribe", str(wav), str(result), str(self.config.model_path), str(progress_path)]
        last_update = [0.0]
        def monitor():
            if time.monotonic() - last_update[0] < 2:
                return
            last_update[0] = time.monotonic()
            with contextlib.suppress(OSError, ValueError):
                value = float(progress_path.read_text(encoding="ascii"))
                if math.isfinite(value) and 0 <= value <= 1:
                    self.store.update(ident, progress=50 + 45 * value)
        try:
            run_process(command, cancel, self.config.worker_timeout, monitor=monitor)
        except ServiceError as error:
            if error.code == "invalid_media":
                raise ServiceError("transcription_failed") from None
            raise
        if result.stat().st_size > 8 * 1024 * 1024:
            raise ServiceError("transcription_failed")
        return json.loads(result.read_text(encoding="utf-8"))

    def download_media(self, urls: list[str], source: dict, destination: Path,
                       cancel: Callable[[], bool], progress: Callable[[float], None],
                       validate: Callable[[float], None] | None = None):
        # Bounded alternatives share one download/decode budget. A Douyin low-rate
        # MP4 may contain video only; use a normal player version if it cannot decode.
        deadline = time.monotonic() + 120
        last_error = None
        for candidate in urls[:MAX_MEDIA_CANDIDATES]:
            if cancel():
                raise Cancelled()
            if time.monotonic() >= deadline:
                raise ServiceError("timeout")
            parts = validate_url(candidate, lambda host: cdn_allowed(host, source["platform"], self.config.direct_hosts))
            if source["platform"] in ("bilibili", "douyin"):
                # Cross-host redirects must be supplied as separately validated backups.
                allowed = lambda host, expected=parts.hostname: host == expected
            else:
                allowed = lambda host: cdn_allowed(host, source["platform"], self.config.direct_hosts)
            try:
                # fetch opens with wb; a retry must never append to a partial first file.
                self.client.fetch(candidate, allowed, self.config.max_bytes, destination,
                                  cancel, progress, deadline_at=deadline)
                if validate is not None:
                    try:
                        validate(deadline)
                    except ServiceError as error:
                        if error.code != "invalid_media" or source["platform"] != "douyin":
                            raise
                        last_error = error
                        continue
                return
            except ServiceError as error:
                if error.code not in ("network_failed", "source_forbidden"):
                    raise
                last_error = error
        raise last_error or ServiceError("media_unavailable")

    def close(self):
        self.stop_event.set()
        if self.thread.is_alive():
            self.thread.join(timeout=5)


class Handler(BaseHTTPRequestHandler):
    server_version = "MnoteTranscript/1.0"

    def setup(self):
        super().setup()
        # Covers request-line/header parsing as well as JSON body reading.
        self.connection.settimeout(10)

    def log_message(self, format, *args):
        # No request paths, source URLs, bearer tokens, or owner identifiers in access logs.
        pass

    def respond(self, status: int, data=None, content_type="application/json; charset=utf-8", filename=None):
        body = json.dumps(data, ensure_ascii=False).encode() if content_type.startswith("application/json") else data if isinstance(data, bytes) else data.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()
        self.wfile.write(body)

    def authenticate(self):
        expected = "Bearer " + self.server.service.config.token
        actual = self.headers.get("Authorization", "")
        if not hmac.compare_digest(actual.encode("utf-8"), expected.encode("utf-8")):
            raise ServiceError("unauthorized", 401)
        owner = self.headers.get("X-Owner-Id", "")
        if not OWNER_RE.fullmatch(owner):
            raise ServiceError("owner_invalid", 400)
        return owner

    def route(self):
        service = self.server.service
        if len(self.path) > 512:
            raise ServiceError("invalid_request")
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        if path == "/health" and self.command == "GET":
            return self.respond(200, {"ok": True})
        if path in ("/demo/sample.wav", "/demo/license") and self.command == "GET":
            sample = service.config.demo_audio
            if not sample or not sample.is_file() or sample.stat().st_size > 2 * 1024 * 1024:
                raise ServiceError("not_found", 404)
            if path == "/demo/license":
                return self.respond(200, {"license": "CC0-1.0", "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
                                          "author": "Mnote demo", "text": "This is the Mnote transcription demo. This recording was created for testing."})
            return self.respond(200, sample.read_bytes(), "audio/wav", "mnote-demo-cc0.wav")
        if not path.startswith("/v1/"):
            raise ServiceError("not_found", 404)
        owner = self.authenticate()
        if path == "/v1/config" and self.command == "GET":
            ready, message = service.config.readiness()
            result = {"ready": ready, "agreement_version": AGREEMENT_VERSION,
                      "max_duration_seconds": MAX_DURATION, "retention_hours": service.config.retention_hours,
                      "message": message}
            if service.config.demo_audio and service.config.demo_audio.is_file() and service.config.public_url:
                result["demo_sample"] = {"url": service.config.public_url + "/demo/sample.wav", "license": "CC0-1.0",
                                         "license_url": service.config.public_url + "/demo/license"}
            return self.respond(200, result)
        if path == "/v1/jobs" and self.command == "POST":
            if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                raise ServiceError("invalid_request")
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 16 * 1024:
                    raise ValueError()
                body = json.loads(self.rfile.read(length))
            except (ValueError, UnicodeError):
                raise ServiceError("invalid_request") from None
            return self.respond(202, {"job": service.submit(owner, body)})
        match = re.fullmatch(r"/v1/jobs/([0-9a-f-]{36})(/download)?", path)
        if not match or not UUID_RE.fullmatch(match[1]):
            raise ServiceError("not_found", 404)
        ident, download = match.groups()
        if self.command == "DELETE" and not download:
            service.delete(ident, owner)
            return self.respond(200, {"deleted": True, "id": ident})
        if self.command == "GET":
            job = service.get(ident, owner)
            if download:
                values = urllib.parse.parse_qs(parsed.query)
                if set(values) != {"format"} or len(values["format"]) != 1:
                    raise ServiceError("invalid_request")
                format_name = values["format"][0]
                text, mime = export_job(job, format_name)
                return self.respond(200, text, mime, f"transcript-{ident}.{format_name}")
            return self.respond(200, {"job": job})
        raise ServiceError("not_found", 404)

    def dispatch(self):
        self.connection.settimeout(10)
        try:
            self.route()
        except ServiceError as error:
            self.respond(error.status, {"error": {"code": error.code, "message": str(error)}})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except Exception:
            self.respond(500, {"error": {"code": "internal_error", "message": ERRORS["internal_error"]}})

    do_GET = do_POST = do_DELETE = dispatch


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, service):
        self.service = service
        super().__init__(address, Handler)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--resolve":
        try:
            print(json.dumps(public_addresses(sys.argv[2])))
        except ServiceError as error:
            sys.exit(2 if error.code == "unsafe_url" else 1)
        return
    if len(sys.argv) == 6 and sys.argv[1] == "--transcribe":
        try:
            transcribe_child(Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), Path(sys.argv[5]))
        except Exception:
            sys.exit(1)
        return
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8791)
    args = parser.parse_args()
    config = Config.from_env()
    if len(config.token) < 32:
        parser.error("MNOTE_TRANSCRIPT_TOKEN must contain at least 32 characters")
    service = JobService(config)
    server = Server((args.host, args.port), service)
    print(f"transcription service listening on {args.host}:{args.port}; ready={config.ready()}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        service.close()


if __name__ == "__main__":
    main()

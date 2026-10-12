"""All source/media tests are synthetic or mocked; never fetch third-party examples."""
import contextlib
import dataclasses
import http.client
import io
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import service as api


def body(**extra):
    value = {"share_text": "这是我录制的音频 https://media.example.org/sample.wav?signature=SECRET",
             "rights_basis": "owned", "rights_note": "My own recording", "consent": True,
             "agreement_version": api.AGREEMENT_VERSION}
    value.update(extra)
    return value


def synthetic_wav(path, seconds=0.01):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(16000)
        output.writeframes(b"\x00\x00" * int(seconds * 16000))


class TempCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = api.Config(self.root / "data", "a" * 40, self.root / "model", ("media.example.org",))

    def tearDown(self):
        self.temp.cleanup()


class ConfigTests(TempCase):
    def prepare(self):
        self.config.model_path.mkdir()
        (self.config.model_path / "model.bin").write_bytes(b"synthetic-not-loaded")
        self.addCleanup(patch.stopall)
        patch.object(api.importlib.util, "find_spec", return_value=object()).start()
        patch.object(api.shutil, "which", return_value="configured-tool").start()

    def test_readiness_accepts_only_pinned_pair_without_model_loading(self):
        self.prepare()
        with patch.object(api.importlib.metadata, "version", side_effect=lambda name: api.RUNTIME_VERSIONS[name]), patch.object(api, "transcribe_child", side_effect=AssertionError("must not load model")):
            self.assertTrue(self.config.ready())
        for package, mismatch in [("av", "19.0.1"), ("faster-whisper", "1.2.0")]:
            versions = {**api.RUNTIME_VERSIONS, package: mismatch}
            with self.subTest(package=package), patch.object(api.importlib.metadata, "version", side_effect=lambda name: versions[name]):
                ready, message = self.config.readiness()
                self.assertFalse(ready)
                self.assertIn(package + "==" + api.RUNTIME_VERSIONS[package], message)
                self.assertIn("不兼容", message)

    def test_readiness_reports_missing_dependency_model_and_tools(self):
        self.prepare()
        with patch.object(api.importlib.metadata, "version", side_effect=api.importlib.metadata.PackageNotFoundError("av")):
            ready, message = self.config.readiness()
            self.assertFalse(ready)
            self.assertIn("依赖缺失", message)
        with patch.object(api.importlib.metadata, "version", side_effect=lambda name: api.RUNTIME_VERSIONS[name]):
            with patch.object(api.shutil, "which", return_value=None):
                self.assertIn("FFmpeg", self.config.readiness()[1])
            (self.config.model_path / "model.bin").unlink()
            self.assertIn("模型", self.config.readiness()[1])


class URLTests(unittest.TestCase):
    def test_chinese_share_and_platforms(self):
        for text, platform in [("复制链接 https://b23.tv/test123 。", "bilibili"),
                               ("视频 https://www.bilibili.com/video/BV1234567890/", "bilibili"),
                               ("视频 https://v.douyin.com/test123/", "douyin"),
                               ("https://www.douyin.com/video/1234567890123456789", "douyin"),
                               ("自己的 https://media.example.org/a.wav", "audio")]:
            with self.subTest(text=text):
                self.assertEqual(api.parse_share_text(text, ("media.example.org",))["platform"], platform)

    def test_untrusted_or_malformed_url(self):
        candidates = ["http://b23.tv/a", "https://b23.tv:444/a", "https://user:password@b23.tv/a",
                      "https://b23.tv/a#fragment", "https://b23.tv.evil.org/a", "https://localhost/a.wav",
                      "https://127.0.0.1/a.wav", "https://[::1]/a.wav", "https://b23.tv./a",
                      "https://b23.tv\\@evil.org/a", "https://b23%2etv/a", "https://b23.tv/a\nHost: evil",
                      "https://evil.org/a.wav", "https://媒体.example/a.wav"]
        for url in candidates:
            with self.subTest(url=url), self.assertRaises(api.ServiceError):
                api.validate_url(url, lambda host: host in api.SITE_HOSTS)

    def test_no_ambiguous_links_or_playlist(self):
        for value in [None, "", "not a link", "https://b23.tv/a https://b23.tv/b", "https://media.example.org/a.m3u8"]:
            with self.subTest(value=value), self.assertRaises(api.ServiceError):
                api.parse_share_text(value, ("media.example.org",))

    def test_cdn_boundary(self):
        self.assertTrue(api.cdn_allowed("audio.foo.bilivideo.com", "bilibili", ()))
        for host in ["bilivideo.com.evil.org", "evilbilivideo.com", "bilibili.com", "127.0.0.1"]:
            self.assertFalse(api.cdn_allowed(host, "bilibili", ()))
        self.assertFalse(api.cdn_allowed("sub.media.example.org", "audio", ("media.example.org",)))

    def test_all_dns_answers_must_be_public(self):
        blocked = ["127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                   "100.64.0.1", "224.0.0.1", "0.0.0.0", "::1", "fe80::1", "fc00::1", "ff02::1",
                   "::ffff:8.8.8.8", "2002:0808:0808::1"]
        for address in blocked:
            answers = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443)),
                       (socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 443))]
            with self.subTest(address=address), patch.object(socket, "getaddrinfo", return_value=answers), self.assertRaises(api.ServiceError):
                api.public_addresses("media.example.org")
        with patch.object(socket, "getaddrinfo", return_value=[(socket.AF_INET, 1, 6, "", ("8.8.8.8", 443))]):
            self.assertEqual(api.public_addresses("media.example.org"), ["8.8.8.8"])


class FakeResponse:
    def __init__(self, status=200, data=b"audio", headers=None):
        self.status, self.stream, self.headers = status, io.BytesIO(data), headers or {}

    def getheader(self, name, default=None):
        return self.headers.get(name, default)

    def read1(self, size):
        return self.stream.read(size)


class ClientTests(unittest.TestCase):
    def make_client(self, responses):
        self.calls, self.resolutions = [], []
        outer = self
        class FakeConnection:
            sock = None
            def __init__(self, host, address, timeout):
                outer.calls.append((host, address, timeout))
            def request(self, method, path, headers):
                outer.calls.append((method, path, headers))
            def getresponse(self):
                return responses.pop(0)
            def close(self):
                pass
        def resolve(host):
            self.resolutions.append(host)
            return ["8.8.8.8"]
        return api.SafeClient(resolve, FakeConnection)

    def test_pin_and_no_auth_forwarding(self):
        client = self.make_client([FakeResponse(data=b"hello")])
        self.assertEqual(client.fetch("https://media.example.org/a.wav?token=signed", lambda h: h == "media.example.org", 100)[0], b"hello")
        self.assertEqual(self.calls[0][1], "8.8.8.8")
        self.assertEqual(self.resolutions, ["media.example.org"])
        headers = self.calls[1][2]
        self.assertNotIn("Authorization", headers)
        self.assertNotIn("Cookie", headers)

    def test_redirect_revalidates_and_reresolves(self):
        client = self.make_client([FakeResponse(302, headers={"Location": "https://cdn.example.org/a.wav"}), FakeResponse()])
        client.fetch("https://media.example.org/a.wav", lambda h: h in ("media.example.org", "cdn.example.org"), 100)
        self.assertEqual(self.resolutions, ["media.example.org", "cdn.example.org"])

    def test_redirect_private_and_credentials_rejected_before_connect(self):
        for target in ["https://127.0.0.1/a.wav", "https://localhost/a.wav", "http://media.example.org/a.wav", "https://user:pass@media.example.org/a.wav"]:
            client = self.make_client([FakeResponse(302, headers={"Location": target})])
            with self.subTest(target=target), self.assertRaises(api.ServiceError):
                client.fetch("https://media.example.org/a.wav", lambda h: h == "media.example.org", 100)
            self.assertEqual(len(self.resolutions), 1)

    def test_streaming_and_declared_size_cap(self):
        for response in [FakeResponse(data=b"x" * 11), FakeResponse(headers={"Content-Length": "11"})]:
            client = self.make_client([response])
            with self.assertRaises(api.ServiceError) as error:
                client.fetch("https://media.example.org/a.wav", lambda h: True, 10)
            self.assertEqual(error.exception.code, "too_large")

    def test_content_encoding_and_redirect_limit(self):
        client = self.make_client([FakeResponse(headers={"Content-Encoding": "gzip"})])
        with self.assertRaises(api.ServiceError):
            client.fetch("https://media.example.org/a.wav", lambda h: True, 100)
        client = self.make_client([FakeResponse(302, headers={"Location": "/next"}) for _ in range(5)])
        with self.assertRaises(api.ServiceError):
            client.fetch("https://media.example.org/a.wav", lambda h: True, 100)
        self.assertEqual(len(self.resolutions), 5)

    def test_cancel_before_resolution(self):
        client = self.make_client([])
        with self.assertRaises(api.Cancelled):
            client.fetch("https://media.example.org/a.wav", lambda h: True, 100, cancel=lambda: True)
        self.assertEqual(self.resolutions, [])

    def test_b23_stops_at_canonical_without_html_request(self):
        client = self.make_client([FakeResponse(302, headers={"Location": "https://www.bilibili.com/video/BV1234567890/"})])
        _, final = client.fetch("https://b23.tv/fixture", lambda h: h in api.BILI_HOSTS, 100,
                                stop_redirect=lambda u: urllib_host(u) == "www.bilibili.com")
        self.assertEqual(self.resolutions, ["b23.tv"])
        self.assertIn("/video/BV", final)

    def test_real_connection_uses_approved_ip_and_original_tls_name(self):
        connection = api.PinnedHTTPSConnection("media.example.org", "8.8.8.8", 1)
        class Context:
            def wrap_socket(self, sock, server_hostname):
                self.hostname = server_hostname
                return sock
        context = Context()
        connection._context = context
        with patch.object(socket, "create_connection", return_value=object()) as create:
            connection.connect()
        create.assert_called_once_with(("8.8.8.8", 443), 1)
        self.assertEqual(context.hostname, "media.example.org")

    def test_hard_deadline_and_cancellation_interrupt_blocked_read(self):
        for cancellation in (False, True):
            blocked, released, stopped = threading.Event(), threading.Event(), threading.Event()
            class BlockingResponse(FakeResponse):
                def read1(self, size):
                    blocked.set()
                    released.wait(2)
                    return b""
            class Sock:
                def shutdown(self, how):
                    released.set()
            class Connection:
                sock = Sock()
                def __init__(self, *args):
                    pass
                def request(self, *args, **kwargs):
                    pass
                def getresponse(self):
                    return BlockingResponse()
                def close(self):
                    released.set()
            client = api.SafeClient(lambda host: ["8.8.8.8"], Connection, deadline_seconds=0.15 if not cancellation else 10)
            if cancellation:
                def stop_while_reading():
                    blocked.wait(1)
                    stopped.set()
                watcher = threading.Thread(target=stop_while_reading)
                watcher.start()
            started = time.monotonic()
            with self.assertRaises(api.Cancelled if cancellation else api.ServiceError) as error:
                client.fetch("https://media.example.org/a.wav", lambda host: True, 100, cancel=stopped.is_set)
            self.assertLess(time.monotonic() - started, 1)
            if cancellation:
                watcher.join(1)
            else:
                self.assertEqual(error.exception.code, "timeout")

    def test_dns_subprocess_is_killed_when_resolver_stalls(self):
        class Process:
            returncode = None
            stdout = io.BytesIO()
            killed = False
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def poll(self):
                return None
            def kill(self):
                self.killed = True
            def wait(self):
                return 1
        process = Process()
        with patch.object(api.subprocess, "Popen", return_value=process), patch.object(api.time, "monotonic", side_effect=[0, 16]), self.assertRaises(api.ServiceError) as error:
            api.bounded_addresses("media.example.org")
        self.assertTrue(process.killed)
        self.assertEqual(error.exception.code, "timeout")


def urllib_host(url):
    import urllib.parse
    return urllib.parse.urlsplit(url).hostname


class ResolverTests(TempCase):
    def test_bilibili_chooses_lowest_bandwidth(self):
        class Client:
            def json(self, url, allowed):
                if "web-interface" in url:
                    return {"code": 0, "data": {"title": "Synthetic title", "pages": [{"duration": 2, "cid": 123}]}}
                return {"code": 0, "data": {"dash": {"audio": [
                    {"bandwidth": 500, "baseUrl": "https://audio.bilivideo.com/high"},
                    {"bandwidth": 100, "baseUrl": "https://audio.bilivideo.com/low?secret=PRIVATE"}]}}}
        result, public = api.MediaResolver(Client(), self.config).resolve({"platform": "bilibili", "host": "www.bilibili.com", "url": "https://www.bilibili.com/video/BV1234567890/"})
        self.assertIn("/low", result)
        self.assertNotIn("PRIVATE", json.dumps(public))

    def test_bilibili_duration_rejects_before_playurl(self):
        class Client:
            def json(self, url, allowed):
                return {"code": 0, "data": {"title": "Synthetic", "pages": [{"duration": 1801, "cid": 123}]}}
        with self.assertRaises(api.ServiceError) as error:
            api.MediaResolver(Client(), self.config).resolve({"platform": "bilibili", "host": "www.bilibili.com", "url": "https://www.bilibili.com/video/BV1234567890/"})
        self.assertEqual(error.exception.code, "too_long")

    def test_douyin_unavailable_is_truthful(self):
        class Client:
            def fetch(self, *args, **kwargs):
                return b"<html>Login needed</html>", "https://www.douyin.com/video/1234567890123456789"
        with self.assertRaises(api.ServiceError) as error:
            api.MediaResolver(Client(), self.config).resolve({"platform": "douyin", "host": "v.douyin.com", "url": "https://v.douyin.com/fixture"})
        self.assertEqual(error.exception.code, "platform_restricted")

    def test_douyin_only_selects_consented_video_not_recommendation(self):
        import urllib.parse
        requested = "1234567890123456789"
        def item(ident, path):
            return {"aweme_id": ident, "desc": "Synthetic fixture", "video": {"duration": 1000,
                    "play_addr": {"url_list": ["https://media.douyinvod.com/" + path]}}}
        data = {"requested": item(requested, "correct"), "recommendation": item("9876543210987654321", "wrong")}
        class Client:
            def fetch(self, *args, **kwargs):
                raw = '<script id="RENDER_DATA">' + urllib.parse.quote(json.dumps(data)) + '</script>'
                return raw.encode(), "https://www.douyin.com/video/" + requested
        resolver = api.MediaResolver(Client(), self.config)
        source = {"platform": "douyin", "host": "v.douyin.com", "url": "https://v.douyin.com/fixture"}
        self.assertTrue(resolver.resolve(source)[0].endswith("/correct"))
        del data["requested"]
        with self.assertRaises(api.ServiceError) as error:
            resolver.resolve(source)
        self.assertEqual(error.exception.code, "platform_restricted")


class JobsTests(TempCase):
    def make_service(self, **kwargs):
        instance = api.JobService(self.config, **kwargs)
        self.addCleanup(instance.close)
        return instance

    def test_consent_literal_version_basis_and_note(self):
        service = self.make_service(start_worker=False)
        invalid = [{"consent": False}, {"consent": "true"}, {"consent": 1}, {"agreement_version": "old"},
                   {"rights_basis": "unknown"}, {"rights_note": ""}, {"rights_note": 123},
                   {"rights_basis": "authorized", "rights_note": "short"}, {"rights_basis": "open_license", "rights_note": "x"}]
        for fields in invalid:
            with self.subTest(fields=fields), self.assertRaises(api.ServiceError):
                service.submit("owner-a", body(**fields))
        self.assertEqual(service.pending.qsize(), 0)
        job = service.submit("owner-a", body(rights_basis="open_license", rights_note="CC0 https://example.org/license"))
        self.assertFalse(job["rights"]["verified"])
        self.assertLess(abs(time.time() - job["rights"]["consented_at"]), 1)
        self.assertIn("https://example.org/license", job["rights"]["note"])
        self.assertNotIn("SECRET", json.dumps(job))

    def test_declaration_scrubs_credentials_and_signed_query_but_keeps_license_url(self):
        service = self.make_service(start_worker=False)
        note = "CC0 https://example.org/license ; evidence https://example.org/file?token=SECRET ; https://user:password@example.org/file"
        job = service.submit("owner-a", body(rights_basis="open_license", rights_note=note))
        self.assertIn("https://example.org/license", job["rights"]["note"])
        self.assertNotIn("SECRET", json.dumps(job))
        self.assertNotIn("password", json.dumps(job))

    def test_ownership_get_delete_and_download(self):
        service = self.make_service(start_worker=False)
        job = service.submit("owner-a", body())
        for operation in [lambda: service.get(job["id"], "owner-b"), lambda: service.delete(job["id"], "owner-b")]:
            with self.assertRaises(api.ServiceError) as error:
                operation()
            self.assertEqual(error.exception.status, 404)
        self.assertEqual(service.get(job["id"], "owner-a")["id"], job["id"])
        service.delete(job["id"], "owner-a")
        self.assertFalse(service.store.update(job["id"], text="must not resurrect"))
        self.assertEqual(service.cancelled, set())

    def test_queue_bounded(self):
        service = self.make_service(start_worker=False)
        for _ in range(self.config.queue_size):
            service.submit("owner-a", body())
        with self.assertRaises(api.ServiceError) as error:
            service.submit("owner-a", body())
        self.assertEqual(error.exception.status, 429)

    def test_progress_monotonic(self):
        service = self.make_service(start_worker=False)
        job = service.submit("owner-a", body())
        service.store.update(job["id"], progress=70)
        service.store.update(job["id"], progress=10)
        self.assertEqual(service.get(job["id"], "owner-a")["progress"], 70)

    def test_restart_marks_interrupted_and_erases_temp(self):
        service = self.make_service(start_worker=False)
        job = service.submit("owner-a", body())
        directory = service.work_dir / job["id"]
        directory.mkdir()
        (directory / "audio.wav").write_bytes(b"synthetic")
        service.store.update(job["id"], status="processing", stage="transcribing", progress=50)
        restarted = self.make_service(start_worker=False)
        recovered = restarted.get(job["id"], "owner-a")
        self.assertEqual(recovered["status"], "failed")
        self.assertEqual(recovered["error"]["code"], "interrupted")
        self.assertEqual(restarted.store.get(job["id"], private=True)["private_url"], "")
        self.assertFalse(directory.exists())

    def test_expiry_read_enforcement_and_cleanup(self):
        service = self.make_service(start_worker=False)
        job = service.submit("owner-a", body())
        directory = service.work_dir / job["id"]
        directory.mkdir()
        (directory / "result.json").write_text("private", encoding="utf-8")
        with service.store.db() as db:
            db.execute("UPDATE jobs SET expires_at=? WHERE id=?", (time.time() - 1, job["id"]))
        with self.assertRaises(api.ServiceError):
            service.get(job["id"], "owner-a")
        self.assertFalse(directory.exists())
        self.assertIsNone(service.store.get(job["id"]))
        self.assertEqual(service.cancelled, set())

    def test_worker_complete_and_failure_sanitized(self):
        for fail in (False, True):
            def processor(job):
                directory = service.work_dir / job["id"]
                directory.mkdir()
                (directory / "audio").write_bytes(b"synthetic")
                if fail:
                    raise RuntimeError("https://user:password@media.example.org/?secret=SECRET traceback")
                return [{"start": 0, "end": 0.5, "text": "Synthetic speech https://example.org"}]
            service = self.make_service(processor=processor)
            job = service.submit("owner-a", body())
            wait_terminal(service, job["id"])
            result = service.get(job["id"], "owner-a")
            self.assertEqual(result["status"], "failed" if fail else "completed")
            self.assertNotIn("SECRET", json.dumps(result))
            self.assertFalse((service.work_dir / job["id"]).exists())
            if not fail:
                self.assertIn("https://example.org", result["text"])
                self.assertEqual(result["progress"], 100)
            service.close()

    def test_delete_active_prevents_resurrection(self):
        entered, release = threading.Event(), threading.Event()
        def processor(job):
            directory = service.work_dir / job["id"]
            directory.mkdir()
            (directory / "audio").write_bytes(b"private")
            entered.set()
            release.wait(2)
            return [{"start": 0, "end": 1, "text": "Must not return"}]
        service = self.make_service(processor=processor)
        job = service.submit("owner-a", body())
        self.assertTrue(entered.wait(2))
        service.delete(job["id"], "owner-a")
        release.set()
        deadline = time.monotonic() + 2
        while service.pending.unfinished_tasks and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertIsNone(service.store.get(job["id"]))
        self.assertFalse((service.work_dir / job["id"]).exists())

    def test_local_decode_and_asr_commands_no_urls_or_shell(self):
        class Client:
            def fetch(self, url, allowed, limit, destination, cancel, progress):
                synthetic_wav(destination)
                progress(1)
                return b"", url
        service = self.make_service(client=Client(), start_worker=False)
        job = service.submit("owner-a", body())
        calls = []
        def process(command, cancel, timeout, **kwargs):
            calls.append(command)
            if command[0] == "ffmpeg":
                synthetic_wav(Path(command[-1]))
            else:
                Path(command[4]).write_text('[{"start":0,"end":0.005,"text":"hello"}]', encoding="utf-8")
        with patch.object(api.Config, "ready", return_value=True), patch.object(api, "run_process", side_effect=process):
            result = service.process(service.store.get(job["id"], private=True))
        self.assertEqual(result[0]["text"], "hello")
        ffmpeg = calls[0]
        self.assertEqual(ffmpeg[ffmpeg.index("-protocol_whitelist") + 1], "file,pipe")
        self.assertNotIn("hls", ffmpeg[ffmpeg.index("-format_whitelist") + 1])
        self.assertTrue(all(not any("https://" in value or "SECRET" in value for value in call) for call in calls))


def wait_terminal(service, ident):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        job = service.store.get(ident)
        if job and job["status"] in ("completed", "failed"):
            # Wait for the finally cleanup before assertions.
            while service.pending.unfinished_tasks and time.monotonic() < deadline:
                time.sleep(0.01)
            return
        time.sleep(0.01)
    raise AssertionError("worker did not complete")


class FormatTests(unittest.TestCase):
    def job(self):
        return {"status": "completed", "text": "你好\nworld", "source": {"title": "Synthetic"},
                "segments": [{"start": 0.123, "end": 1.234, "text": "你好"}, {"start": 65, "end": 66.1, "text": "world"}]}

    def test_all_formats(self):
        job = self.job()
        self.assertEqual(api.export_job(job, "txt")[0], "你好\nworld\n")
        self.assertIn("[00:01:05.000] world", api.export_job(job, "md")[0])
        self.assertEqual(api.export_job(job, "srt")[0], "1\n00:00:00,123 --> 00:00:01,234\n你好\n\n2\n00:01:05,000 --> 00:01:06,100\nworld\n")

    def test_not_ready_and_invalid_format(self):
        with self.assertRaises(api.ServiceError):
            api.export_job({"status": "processing"}, "txt")
        with self.assertRaises(api.ServiceError):
            api.export_job(self.job(), "html")

    def test_untrusted_timestamps(self):
        for start, end in [(float("nan"), 1), (0, float("inf")), (-1, 1), (2, 1), (0, 1801)]:
            with self.subTest(start=start, end=end), self.assertRaises(api.ServiceError):
                api.normalize_segments([{"start": start, "end": end, "text": "x"}])
        with self.assertRaises(api.ServiceError):
            api.normalize_segments([{"start": 1, "end": 2, "text": "x"}, {"start": 0, "end": 1, "text": "y"}])

    def test_srt_and_markdown_injection_removed(self):
        job = self.job()
        job["segments"][0]["text"] = "<script>bad</script>\n\n --> *bold*"
        self.assertNotIn("<script>", api.export_job(job, "srt")[0])
        self.assertIn("\\<script\\>", api.export_job(job, "md")[0])


class HTTPTests(TempCase):
    def setUp(self):
        super().setUp()
        self.sample = self.root / "sample.wav"
        synthetic_wav(self.sample)
        self.config = dataclasses.replace(self.config, demo_audio=self.sample, public_url="https://media.example.org/api")
        self.service = api.JobService(self.config, start_worker=False)
        self.server = api.Server(("127.0.0.1", 0), self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.service.close()
        self.thread.join(2)
        super().tearDown()

    def request(self, method, path, value=None, owner="owner-a", token=None, authenticated=True, raw=None):
        headers = {"Content-Type": "application/json"}
        if authenticated:
            headers.update({"Authorization": "Bearer " + (token or self.config.token), "X-Owner-Id": owner})
        payload = raw if raw is not None else json.dumps(value).encode() if value is not None else None
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        connection.request(method, path, body=payload, headers=headers)
        response = connection.getresponse()
        result = response.read()
        status, response_headers = response.status, dict(response.getheaders())
        connection.close()
        if response_headers["Content-Type"].startswith("application/json"):
            result = json.loads(result)
        return status, result, response_headers

    def test_health_public_but_every_v1_requires_auth_owner(self):
        self.assertEqual(self.request("GET", "/health", authenticated=False)[0], 200)
        for method, path in [("GET", "/v1/config"), ("POST", "/v1/jobs"), ("GET", "/v1/jobs/" + "0" * 36), ("DELETE", "/v1/jobs/" + "0" * 36)]:
            self.assertEqual(self.request(method, path, body(), authenticated=False)[0], 401)
        self.assertEqual(self.request("GET", "/v1/config", token="bad")[0], 401)
        for owner in ["", "a" * 65, "bad owner", "../../other"]:
            self.assertEqual(self.request("GET", "/v1/config", owner=owner)[0], 400)

    def test_config_and_demo_do_not_load_model(self):
        with patch.object(api, "transcribe_child", side_effect=AssertionError("must not load model")):
            status, config, _ = self.request("GET", "/v1/config")
            self.assertEqual(status, 200)
            self.assertEqual(config["max_duration_seconds"], 1800)
            self.assertEqual(config["demo_sample"]["license"], "CC0-1.0")
        self.assertEqual(self.request("GET", "/demo/sample.wav", authenticated=False)[1], self.sample.read_bytes())
        self.assertEqual(self.request("GET", "/demo/license", authenticated=False)[1]["author"], "Mnote demo")
        self.assertEqual(self.request("GET", "/demo/../sample.wav", authenticated=False)[0], 404)

    def test_post_poll_download_delete_and_cross_owner(self):
        status, response, headers = self.request("POST", "/v1/jobs", body())
        self.assertEqual(status, 202)
        self.assertEqual(headers["Cache-Control"], "no-store")
        job = response["job"]
        base = "/v1/jobs/" + job["id"]
        self.assertEqual(self.request("GET", base, owner="owner-b")[0], 404)
        self.assertEqual(self.request("DELETE", base, owner="owner-b")[0], 404)
        self.assertEqual(self.request("GET", base + "/download?format=txt", owner="owner-b")[0], 404)
        self.assertEqual(self.request("GET", base + "/download?format=txt")[0], 409)
        self.service.store.update(job["id"], status="completed", stage="completed", progress=100, text="Synthetic",
                                  segments=[{"start": 0, "end": 1, "text": "Synthetic"}])
        for format_name in ["txt", "md", "srt"]:
            status, content, headers = self.request("GET", base + "/download?format=" + format_name)
            self.assertEqual(status, 200)
            self.assertIn("attachment", headers["Content-Disposition"])
        self.assertEqual(self.request("GET", base + "/download?format=txt&format=md")[0], 400)
        self.assertEqual(self.request("DELETE", base)[0], 200)
        self.assertEqual(self.request("GET", base)[0], 404)

    def test_bad_json_and_body_limit(self):
        self.assertEqual(self.request("POST", "/v1/jobs", raw=b"{")[0], 400)
        self.assertEqual(self.request("POST", "/v1/jobs", value=[])[0], 400)
        self.assertEqual(self.request("POST", "/v1/jobs", raw=b"x" * (16 * 1024 + 1))[0], 400)
        self.assertEqual(self.request("POST", "/v1/jobs", body(consent="true"))[0], 400)


if __name__ == "__main__":
    unittest.main()

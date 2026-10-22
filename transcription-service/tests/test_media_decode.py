"""Exercise the real local decoder with generated media; never contact a source."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import service as api


@unittest.skipUnless(shutil.which("ffmpeg"), "FFmpeg is not installed")
class LocalMediaDecodeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mnote-local-decode-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = self.root / "generated.media"
        self.ffmpeg = shutil.which("ffmpeg")
        self.config = api.Config(self.root / "data", "a" * 40, self.root / "unused-model",
                                 ("media.example.org",), ffmpeg=self.ffmpeg)
        self.share_text = "https://media.example.org/generated.mp4"
        self.fixtures = {self.share_text: self.fixture}
        self.fetches = []
        self.fetch_deadlines = []
        self.leave_partial_wav_on_failure = False
        self.partial_wavs = []
        case = self

        class LocalFixtureClient:
            def fetch(self, url, allowed, limit, destination, cancel, progress, **kwargs):
                if url not in case.fixtures:
                    raise AssertionError("Unexpected source: no network access is permitted")
                api.validate_url(url, allowed)
                if cancel():
                    raise api.Cancelled()
                case.fetches.append(url)
                case.fetch_deadlines.append(kwargs.get("deadline_at"))
                fixture = case.fixtures[url]
                size = fixture.stat().st_size
                if size > limit:
                    raise AssertionError("Synthetic fixture unexpectedly exceeds the download limit")
                shutil.copyfile(fixture, destination)
                progress(1)
                return b"", url

        self.service = api.JobService(self.config, client=LocalFixtureClient(), start_worker=False)
        self.addCleanup(self.service.close)
        self.asr_calls = []
        self.decode_calls = []
        self.original_run_process = api.run_process

    def generate(self, *arguments):
        subprocess.run([self.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                        *arguments, str(self.fixture)], check=True, timeout=15,
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def process(self):
        job = self.service.submit("synthetic-owner", {
            "share_text": self.share_text,
            "rights_basis": "owned", "rights_note": "Generated test tone and black video",
            "agreement_version": api.AGREEMENT_VERSION, "consent": True,
        })

        def local_decode_and_fake_asr(command, cancel, timeout, **kwargs):
            self.assertTrue(all("https://" not in item and "http://" not in item for item in command))
            if command[0] == self.ffmpeg:
                self.decode_calls.append(command)
                self.assertEqual(command[command.index("-protocol_whitelist") + 1], "file,pipe")
                formats = set(command[command.index("-format_whitelist") + 1].split(","))
                self.assertTrue(formats.isdisjoint({"hls", "dash", "concat", "image2", "sdp"}))
                self.assertEqual(Path(command[command.index("-i") + 1]).name, "input.media")
                wav = Path(command[-1])
                self.assertFalse(wav.exists(), "Each candidate must replace any previous partial WAV")
                try:
                    return self.original_run_process(command, cancel, min(timeout, 15), **kwargs)
                except api.ServiceError:
                    if self.leave_partial_wav_on_failure:
                        wav.write_bytes(b"partial output from a failed decode")
                        self.partial_wavs.append(wav)
                    raise
            self.assertEqual(command[0], sys.executable)
            self.assertEqual(command[2], "--transcribe")
            self.asr_calls.append(command)
            with wave.open(command[3], "rb") as decoded:
                self.assertEqual(decoded.getnchannels(), 1)
                self.assertEqual(decoded.getsampwidth(), 2)
                self.assertEqual(decoded.getframerate(), 16000)
                self.assertGreater(decoded.getnframes(), 0)
                self.assertLess(decoded.getnframes() / decoded.getframerate(), 0.5)
            Path(command[4]).write_text(json.dumps([
                {"start": 0, "end": 0.1, "text": "Synthetic ASR placeholder"},
            ]), encoding="utf-8")

        with patch.object(api.Config, "ready", return_value=True), \
                patch.object(api, "run_process", side_effect=local_decode_and_fake_asr):
            return self.service.process(self.service.store.get(job["id"], private=True))

    def test_extensionless_aac_mp4_decodes_before_asr(self):
        self.generate("-f", "lavfi", "-i", "sine=frequency=440:duration=0.1",
                      "-c:a", "aac", "-f", "mp4")
        self.assertEqual(self.process()[0]["text"], "Synthetic ASR placeholder")
        self.assertEqual(len(self.decode_calls), 1)
        self.assertEqual(len(self.asr_calls), 1)

    def test_fragmented_mp4_decodes_before_asr(self):
        self.generate("-f", "lavfi", "-i", "sine=frequency=440:duration=0.1",
                      "-c:a", "aac", "-movflags", "frag_keyframe+empty_moov", "-f", "mp4")
        self.assertEqual(self.process()[0]["text"], "Synthetic ASR placeholder")
        self.assertEqual(len(self.asr_calls), 1)

    def test_mp4_with_video_and_audio_uses_audio_only(self):
        self.generate("-f", "lavfi", "-i", "color=c=black:size=16x16:rate=10:duration=0.1",
                      "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1",
                      "-c:v", "mpeg4", "-c:a", "aac", "-shortest", "-f", "mp4")
        self.assertEqual(self.process()[0]["text"], "Synthetic ASR placeholder")
        self.assertEqual(len(self.asr_calls), 1)

    def test_video_without_audio_is_rejected_before_asr(self):
        self.generate("-f", "lavfi", "-i", "color=c=black:size=16x16:rate=10:duration=0.1",
                      "-c:v", "mpeg4", "-an", "-f", "mp4")
        with self.assertRaises(api.ServiceError) as error:
            self.process()
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(self.asr_calls, [])

    def test_corrupt_media_is_rejected_before_asr(self):
        self.fixture.write_bytes(b"not a valid media container\x00\x01")
        with self.assertRaises(api.ServiceError) as error:
            self.process()
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(self.asr_calls, [])

    def test_playlist_is_not_enabled_as_a_decoder_fallback(self):
        # The rejected manifest refers only to a nonexistent local fixture, never a network URL.
        self.fixture.write_text("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:0.1,\nmissing.ts\n#EXT-X-ENDLIST\n",
                                encoding="ascii")
        with self.assertRaises(api.ServiceError) as error:
            self.process()
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(self.asr_calls, [])

    def douyin_candidates(self, first, second):
        self.share_text = "https://www.douyin.com/video/1234567890123456789"
        urls = ["https://v3-dy-o.zjcdn.com/synthetic-low",
                "https://v3-dy-o.zjcdn.com/synthetic-player"]
        self.fixtures = dict(zip(urls, [first, second]))
        return patch.object(self.service.resolver, "resolve_candidates", return_value=(urls, {
            "platform": "douyin", "host": "www.douyin.com", "title": "Synthetic video", "duration_seconds": 0.1,
        }))

    def test_douyin_video_only_low_rate_falls_back_to_player_with_audio(self):
        self.generate("-f", "lavfi", "-i", "color=c=black:size=16x16:rate=10:duration=0.1",
                      "-c:v", "mpeg4", "-an", "-f", "mp4")
        silent = self.root / "video-only.media"
        self.fixture.replace(silent)
        self.generate("-f", "lavfi", "-i", "color=c=black:size=16x16:rate=10:duration=0.1",
                      "-f", "lavfi", "-i", "sine=frequency=440:duration=0.1",
                      "-c:v", "mpeg4", "-c:a", "aac", "-shortest", "-f", "mp4")
        self.leave_partial_wav_on_failure = True
        with self.douyin_candidates(silent, self.fixture):
            self.assertEqual(self.process()[0]["text"], "Synthetic ASR placeholder")
        self.assertEqual(len(self.fetches), 2)
        self.assertEqual(len(self.decode_calls), 2)
        self.assertEqual(len(self.asr_calls), 1)
        self.assertEqual(len(self.partial_wavs), 1)

    def test_douyin_all_candidates_without_audio_never_reach_asr(self):
        self.generate("-f", "lavfi", "-i", "color=c=black:size=16x16:rate=10:duration=0.1",
                      "-c:v", "mpeg4", "-an", "-f", "mp4")
        with self.douyin_candidates(self.fixture, self.fixture), self.assertRaises(api.ServiceError) as error:
            self.process()
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(len(self.fetches), 2)
        self.assertEqual(len(self.decode_calls), 2)
        self.assertEqual(self.asr_calls, [])

    def callback_candidates(self):
        self.fixture.write_bytes(b"synthetic callback fixture; decoding is tested separately")
        urls = ["https://v3-dy-o.zjcdn.com/first", "https://v3-dy-o.zjcdn.com/second"]
        self.fixtures = dict.fromkeys(urls, self.fixture)
        return urls

    def test_candidate_validation_receives_the_same_deadline_as_download(self):
        urls = self.callback_candidates()
        deadlines = []
        clock = [100.0]

        def validate(deadline):
            deadlines.append(deadline)
            if len(deadlines) == 1:
                clock[0] = 219.0
                raise api.ServiceError("invalid_media")

        with patch.object(api.time, "monotonic", side_effect=lambda: clock[0]):
            self.service.download_media(urls, {"platform": "douyin"}, self.root / "download.media",
                                        lambda: False, lambda value: None, validate=validate)
        self.assertEqual(deadlines, [220.0, 220.0])
        self.assertEqual(self.fetch_deadlines, deadlines)
        self.assertEqual(self.fetches, urls)

    def test_validation_cannot_restart_the_shared_budget_for_another_candidate(self):
        urls = self.callback_candidates()
        clock = [100.0]

        def validate(deadline):
            clock[0] = deadline + 0.1
            raise api.ServiceError("invalid_media")

        with patch.object(api.time, "monotonic", side_effect=lambda: clock[0]), self.assertRaises(api.ServiceError) as error:
            self.service.download_media(urls, {"platform": "douyin"}, self.root / "download.media",
                                        lambda: False, lambda value: None, validate=validate)
        self.assertEqual(error.exception.code, "timeout")
        self.assertEqual(self.fetches, urls[:1])

    def test_safety_and_limit_validation_errors_never_try_a_backup(self):
        urls = self.callback_candidates()
        for code in ("unsafe_url", "too_long", "too_large", "timeout", "source_rate_limited"):
            with self.subTest(code=code):
                self.fetches.clear()

                def validate(deadline):
                    raise api.ServiceError(code)

                with self.assertRaises(api.ServiceError) as error:
                    self.service.download_media(urls, {"platform": "douyin"}, self.root / "download.media",
                                                lambda: False, lambda value: None, validate=validate)
                self.assertEqual(error.exception.code, code)
                self.assertEqual(self.fetches, urls[:1])

    def test_cancelled_validation_never_tries_a_backup(self):
        urls = self.callback_candidates()

        def validate(deadline):
            raise api.Cancelled()

        with self.assertRaises(api.Cancelled):
            self.service.download_media(urls, {"platform": "douyin"}, self.root / "download.media",
                                        lambda: False, lambda value: None, validate=validate)
        self.assertEqual(self.fetches, urls[:1])

    def test_non_douyin_decode_failure_does_not_enable_candidate_fallback(self):
        urls = ["https://media.example.org/first", "https://media.example.org/second"]
        self.fixture.write_bytes(b"synthetic callback fixture")
        self.fixtures = dict.fromkeys(urls, self.fixture)

        def validate(deadline):
            raise api.ServiceError("invalid_media")

        with self.assertRaises(api.ServiceError) as error:
            self.service.download_media(urls, {"platform": "audio"}, self.root / "download.media",
                                        lambda: False, lambda value: None, validate=validate)
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(self.fetches, urls[:1])

    def test_decode_fallback_still_obeys_candidate_count_limit(self):
        self.fixture.write_bytes(b"synthetic callback fixture")
        urls = [f"https://v3-dy-o.zjcdn.com/candidate-{index}" for index in range(api.MAX_MEDIA_CANDIDATES + 1)]
        self.fixtures = dict.fromkeys(urls, self.fixture)

        def validate(deadline):
            raise api.ServiceError("invalid_media")

        with self.assertRaises(api.ServiceError) as error:
            self.service.download_media(urls, {"platform": "douyin"}, self.root / "download.media",
                                        lambda: False, lambda value: None, validate=validate)
        self.assertEqual(error.exception.code, "invalid_media")
        self.assertEqual(self.fetches, urls[:api.MAX_MEDIA_CANDIDATES])


if __name__ == "__main__":
    unittest.main()

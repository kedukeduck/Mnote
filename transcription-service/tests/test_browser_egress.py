"""Synthetic CONNECT/TLS fixtures only: never contact a public host or media CDN."""
import base64
import contextlib
import dataclasses
import io
import json
import os
from pathlib import Path
import queue
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import browser_egress as proxy

PASSWORD = "synthetic_proxy_password_not_a_real_secret_123"
HOST = "www.douyin.com"


def authorization(password=PASSWORD):
    return "Basic " + base64.b64encode(("mnote:" + password).encode()).decode()


def request(host=HOST, headers=None, method="CONNECT", target=None):
    target = target or host + ":443"
    fields = [("Host", target), ("Proxy-Authorization", authorization())] if headers is None else headers
    return (f"{method} {target} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in fields) + "\r\n").encode()


def client_hello(host=HOST, extra=b"", fragment=False):
    name = host.encode("ascii")
    names = b"\x00" + len(name).to_bytes(2, "big") + name
    value = len(names).to_bytes(2, "big") + names
    extensions = b"\x00\x00" + len(value).to_bytes(2, "big") + value + extra
    body = b"\x03\x03" + b"r" * 32 + b"\x00\x00\x02\x13\x01\x01\x00" + len(extensions).to_bytes(2, "big") + extensions
    handshake = b"\x01" + len(body).to_bytes(3, "big") + body
    chunks = [handshake[:17], handshake[17:]] if fragment else [handshake]
    return b"".join(b"\x16\x03\x01" + len(chunk).to_bytes(2, "big") + chunk for chunk in chunks)


def receive_exact(connection, size):
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            break
        result.extend(chunk)
    return bytes(result)


def receive_header(connection):
    result = bytearray()
    while b"\r\n\r\n" not in result:
        chunk = connection.recv(1)
        if not chunk:
            break
        result.extend(chunk)
    return bytes(result)


class ConfigTests(unittest.TestCase):
    def test_exact_default_hosts_and_operator_additions(self):
        self.assertIn(HOST, proxy.BROWSER_HOSTS)
        self.assertNotIn("douyin.com", proxy.BROWSER_HOSTS)
        self.assertNotIn("sub." + HOST, proxy.BROWSER_HOSTS)
        with patch.dict(os.environ, {"MNOTE_BROWSER_PROXY_PASSWORD": PASSWORD,
                                     "MNOTE_BROWSER_EXTRA_HOSTS": "specific.example.org,other.example.org"}, clear=True):
            config = proxy.EgressConfig.from_env()
        self.assertEqual(config.allowed_hosts, proxy.BROWSER_HOSTS | {"specific.example.org", "other.example.org"})
        self.assertNotIn(PASSWORD, repr(config))

    def test_credentials_and_bad_allowlist_fail_closed(self):
        for password in ("", "short", "s" * 129, "a" * 32 + "\n", "a" * 32 + ":"):
            with self.subTest(password_length=len(password)), self.assertRaises(ValueError):
                proxy.EgressConfig(password)
        for host in ("*.douyin.com", "www.douyin.com:443", "localhost", "127.0.0.1", "::1",
                     "www.douyin.com.", "https://www.douyin.com", "user@www.douyin.com", "-bad.example", "a..org"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                proxy.EgressConfig(PASSWORD, frozenset({host}))

    def test_all_public_answers_are_checked_not_only_the_selected_one(self):
        blocked = ["127.0.0.1", "10.1.2.3", "169.254.169.254", "100.64.0.1", "224.0.0.1",
                   "192.168.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "ff02::1",
                   "::ffff:8.8.8.8", "2002:0808:0808::1", "2001::1", "8.8.8.8%zone"]
        for address in blocked:
            with self.subTest(address=address), self.assertRaises(proxy.EgressError):
                proxy.checked_addresses(["8.8.8.8", address])
        self.assertEqual(proxy.checked_addresses(["8.8.8.8", "8.8.8.8", "2606:4700:4700::1111"]),
                         ["8.8.8.8", "2606:4700:4700::1111"])

    def test_public_resolver_validates_every_dns_record(self):
        answers = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("8.8.8.8", 443)),
                   (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443))]
        with patch.object(socket, "getaddrinfo", return_value=answers), self.assertRaises(proxy.EgressError):
            proxy.public_addresses(HOST)

    def test_dns_subprocess_has_clean_environment_and_bounded_time(self):
        response = subprocess.CompletedProcess([], 0, json.dumps(["8.8.8.8"]).encode())
        with patch.dict(os.environ, {"MNOTE_TRANSCRIPT_TOKEN": "MUST_NOT_LEAK", "PYTHONPATH": "/untrusted"}), \
                patch.object(proxy.subprocess, "run", return_value=response) as run:
            self.assertEqual(proxy.bounded_addresses(HOST, 0.5), ["8.8.8.8"])
        args, kwargs = run.call_args
        self.assertEqual(args[0][1:3], ["-I", "-S"])
        self.assertEqual(args[0][-2:], ["--resolve", HOST])
        self.assertEqual(kwargs["env"], {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"})
        self.assertEqual(kwargs["timeout"], 0.5)
        with patch.object(proxy.subprocess, "run", side_effect=subprocess.TimeoutExpired("synthetic", 0.1)), \
                self.assertRaises(proxy.EgressError) as error:
            proxy.bounded_addresses(HOST, 0.1)
        self.assertEqual(error.exception.status, 504)
        for output in (b"not json", b'["127.0.0.1"]', b"[]", b"x" * 8193):
            with self.subTest(output_length=len(output)), patch.object(proxy.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output)), \
                    self.assertRaises(proxy.EgressError):
                proxy.bounded_addresses(HOST, 0.1)

    def test_pinned_connector_never_resolves_the_hostname_again(self):
        class Connection:
            def settimeout(self, timeout):
                self.timeout = timeout
            def connect(self, address):
                self.address = address
            def close(self):
                self.closed = True
        connection = Connection()
        with patch.object(socket, "socket", return_value=connection) as create, \
                patch.object(socket, "getaddrinfo", side_effect=AssertionError("No second DNS lookup")):
            self.assertIs(proxy.connect_pinned("8.8.8.8", 0.2), connection)
        create.assert_called_once_with(socket.AF_INET, socket.SOCK_STREAM)
        self.assertEqual(connection.address, ("8.8.8.8", 443))
        self.assertEqual(connection.timeout, 0.2)

    def test_real_dns_child_rejects_ip_without_resolving_or_needing_service_env(self):
        result = subprocess.run([sys.executable, "-I", "-S", str(Path(proxy.__file__).resolve()),
                                 "--resolve", "127.0.0.1"], env={"LANG": "C.UTF-8"},
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=2, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout + result.stderr, b"")

    def test_clienthello_malformed_lengths_and_missing_sni_are_rejected(self):
        correct = client_hello()[9:]
        for payload in (b"", correct[:20], correct[:-1], b"\x03\x01" + correct[2:],
                        correct[:34] + b"\xff" + correct[35:],
                        correct[:43] + b"\x00\x00", correct + b"extra"):
            with self.subTest(length=len(payload)), self.assertRaises(proxy.EgressError):
                proxy._sni_from_hello(payload)


class EgressTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "proxy.sock"
        self.resolutions, self.connections = [], []
        self.peers = queue.Queue()
        self.opened, self.servers = [], []
        self.config = proxy.EgressConfig(PASSWORD)

    def tearDown(self):
        for connection in self.opened:
            connection.close()
        for server, thread in self.servers:
            server.shutdown()
            server.server_close()
            thread.join(2)
        self.temp.cleanup()

    def start(self, **changes):
        config = dataclasses.replace(self.config, **changes)
        def resolve(host, timeout):
            self.resolutions.append((host, timeout))
            return ["8.8.8.8"]
        def connect(address, timeout):
            self.connections.append((address, timeout))
            upstream, peer = socket.socketpair()
            peer.settimeout(2)
            self.opened.append(peer)
            self.peers.put(peer)
            return upstream
        server = proxy.EgressServer(config, self.path, resolve, connect)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        self.servers.append((server, thread))
        return server

    def connect(self):
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(2)
        connection.connect(str(self.path))
        self.opened.append(connection)
        return connection

    def exchange(self, raw):
        connection = self.connect()
        connection.sendall(raw)
        return connection, receive_header(connection)

    def test_authentication_required_before_dns_or_connect(self):
        self.start()
        for headers in ([("Host", HOST + ":443")],
                        [("Host", HOST + ":443"), ("Proxy-Authorization", "Basic invalid")]):
            with self.subTest(headers=headers):
                _, response = self.exchange(request(headers=headers))
                self.assertTrue(response.startswith(b"HTTP/1.1 407"))
                self.assertIn(b'Proxy-Authenticate: Basic realm="mnote-browser"', response)
        self.assertEqual(self.resolutions, [])
        self.assertEqual(self.connections, [])

    def test_forbidden_target_scheme_port_suffix_and_credentials(self):
        self.start()
        for target in ("evil.org:443", "sub.www.douyin.com:443", "www.douyin.com.evil.org:443",
                       "www.douyin.com:80", "www.douyin.com:444", "127.0.0.1:443", "[::1]:443",
                       "https://www.douyin.com:443", "user:pass@www.douyin.com:443", "www.douyin.com.:443"):
            with self.subTest(target=target):
                _, response = self.exchange(request(target=target))
                self.assertTrue(response.startswith(b"HTTP/1.1 403"))
        _, response = self.exchange(request(method="GET", target="https://www.douyin.com/"))
        self.assertTrue(response.startswith(b"HTTP/1.1 405"))
        self.assertEqual(self.resolutions, [])

    def test_duplicate_headers_bodies_and_credentials_are_rejected(self):
        self.start()
        base = [("Host", HOST + ":443"), ("Proxy-Authorization", authorization())]
        for extra in ([('Host', HOST + ':443')], [("Proxy-Authorization", authorization())],
                      [("Content-Length", "1")], [("Transfer-Encoding", "chunked")],
                      [("Expect", "100-continue")], [("Cookie", "private=secret")],
                      [("Authorization", "Bearer secret")], [(" Bad", "value")]):
            with self.subTest(extra=extra):
                _, response = self.exchange(request(headers=base + extra))
                self.assertTrue(response.startswith(b"HTTP/1.1 400"))
        self.assertEqual(self.resolutions, [])

    def test_header_length_and_header_wait_are_bounded(self):
        self.start(max_header_bytes=1024, header_seconds=0.1)
        _, response = self.exchange(request(headers=[("Host", HOST + ":443"), ("X-Padding", "x" * 1200)]))
        self.assertTrue(response.startswith(b"HTTP/1.1 431"))
        started = time.monotonic()
        _, response = self.exchange(b"CONNECT ")
        self.assertLess(time.monotonic() - started, 0.5)
        self.assertTrue(response.startswith(b"HTTP/1.1 502"))
        self.assertEqual(self.resolutions, [])

    def test_mixed_public_private_dns_blocks_connection(self):
        server = self.start()
        server.resolver = lambda host, timeout: ["8.8.8.8", "127.0.0.1"]
        _, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 403"))
        self.assertEqual(self.connections, [])

    def test_connect_failure_is_terminal_and_does_not_try_other_ips(self):
        server = self.start()
        server.resolver = lambda host, timeout: ["8.8.8.8", "1.1.1.1"]
        calls = []
        def refuse(address, timeout):
            calls.append(address)
            raise OSError("synthetic connection rejection")
        server.connector = refuse
        _, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 502"))
        self.assertEqual(calls, ["8.8.8.8"])

    def test_expired_dns_budget_never_starts_connection(self):
        server = self.start(tunnel_seconds=0.05)
        def delayed(host, timeout):
            time.sleep(0.07)
            return ["8.8.8.8"]
        server.resolver = delayed
        _, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 504"))
        self.assertEqual(self.connections, [])

    def test_approved_connection_is_ip_pinned_and_relays_only_tls(self):
        self.start()
        connection, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        upstream = self.peers.get(timeout=1)
        hello = client_hello(fragment=True)
        connection.sendall(hello + b"synthetic-encrypted-request")
        self.assertEqual(receive_exact(upstream, len(hello) + 27), hello + b"synthetic-encrypted-request")
        upstream.sendall(b"synthetic-encrypted-response")
        upstream.shutdown(socket.SHUT_WR)
        self.assertEqual(receive_exact(connection, 28), b"synthetic-encrypted-response")
        self.assertEqual(connection.recv(1), b"")
        self.assertEqual(self.resolutions[0][0], HOST)
        self.assertEqual(self.connections[0][0], "8.8.8.8")

    def test_wrong_sni_plaintext_and_malformed_extensions_are_not_forwarded(self):
        self.start()
        for payload in (client_hello("evil.org"), b"GET / HTTP/1.1\r\n\r\n", client_hello(extra=b"\xfe\x0d\x00\x02\x00"),
                        client_hello("evil.org", extra=b"\xfe\x0d\x00\x01\x00")):
            with self.subTest(payload_length=len(payload)):
                connection, response = self.exchange(request())
                self.assertTrue(response.startswith(b"HTTP/1.1 200"))
                upstream = self.peers.get(timeout=1)
                connection.sendall(payload)
                self.assertEqual(upstream.recv(1), b"")
                with contextlib.suppress(ConnectionResetError):
                    self.assertEqual(connection.recv(1), b"")

    def test_grease_extension_does_not_bypass_visible_sni_or_change_wire_bytes(self):
        self.start()
        connection, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        upstream = self.peers.get(timeout=1)
        hello = client_hello(extra=b"\xfe\x0d\x00\x01\x00")
        connection.sendall(hello)
        self.assertEqual(receive_exact(upstream, len(hello)), hello)
        self.assertEqual(self.connections[0][0], "8.8.8.8")

    def test_real_tls_clienthello_is_accepted_without_disabling_verification(self):
        self.start()
        context = ssl.create_default_context()
        incoming, outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        tls = context.wrap_bio(incoming, outgoing, server_side=False, server_hostname=HOST)
        with self.assertRaises(ssl.SSLWantReadError):
            tls.do_handshake()
        hello = outgoing.read()
        connection, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        upstream = self.peers.get(timeout=1)
        connection.sendall(hello)
        self.assertEqual(receive_exact(upstream, len(hello)), hello)
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)

    def test_clienthello_pipelined_with_connect_is_preserved(self):
        self.start()
        hello = client_hello()
        _, response = self.exchange(request() + hello)
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        upstream = self.peers.get(timeout=1)
        self.assertEqual(receive_exact(upstream, len(hello)), hello)

    def test_half_close_drains_large_buffer_before_eof(self):
        self.start()
        connection, _ = self.exchange(request())
        upstream = self.peers.get(timeout=1)
        hello = client_hello()
        connection.sendall(hello)
        self.assertEqual(receive_exact(upstream, len(hello)), hello)
        payload = b"synthetic TLS ciphertext" * 16384
        sent = threading.Event()
        def send():
            upstream.sendall(payload)
            upstream.shutdown(socket.SHUT_WR)
            sent.set()
        thread = threading.Thread(target=send, daemon=True)
        thread.start()
        try:
            result = bytearray()
            while True:
                chunk = connection.recv(333)
                if not chunk:
                    break
                result.extend(chunk)
            self.assertEqual(bytes(result), payload)
            self.assertTrue(sent.wait(1))
        finally:
            thread.join(2)

    def test_hello_size_and_time_limits(self):
        self.start(max_hello_bytes=1024, hello_seconds=0.1)
        connection, _ = self.exchange(request())
        upstream = self.peers.get(timeout=1)
        connection.sendall(b"\x16\x03\x01\x08\x00")
        self.assertEqual(upstream.recv(1), b"")
        connection, _ = self.exchange(request())
        upstream = self.peers.get(timeout=1)
        started = time.monotonic()
        self.assertEqual(upstream.recv(1), b"")
        self.assertLess(time.monotonic() - started, 0.5)

    def test_concurrency_is_bounded_before_spawning_new_handler(self):
        server = self.start(max_connections=1)
        first, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        second = self.connect()
        self.assertTrue(receive_header(second).startswith(b"HTTP/1.1 503"))
        self.assertEqual(len(self.connections), 1)
        self.assertLessEqual(len(server.active), 2)

    def test_tunnel_absolute_deadline_cannot_be_extended_by_traffic(self):
        self.start(tunnel_seconds=0.2)
        started = time.monotonic()
        connection, _ = self.exchange(request())
        upstream = self.peers.get(timeout=1)
        hello = client_hello()
        connection.sendall(hello)
        self.assertEqual(receive_exact(upstream, len(hello)), hello)
        for _ in range(3):
            upstream.sendall(b"x")
            self.assertEqual(connection.recv(1), b"x")
            time.sleep(0.03)
        self.assertEqual(connection.recv(1), b"")
        self.assertLess(time.monotonic() - started, 0.6)

    def test_tunnel_byte_cap_stops_upload_and_download(self):
        self.start(max_tunnel_bytes=1024)
        for upload in (True, False):
            with self.subTest(upload=upload):
                connection, _ = self.exchange(request())
                upstream = self.peers.get(timeout=1)
                hello = client_hello()
                connection.sendall(hello)
                self.assertEqual(receive_exact(upstream, len(hello)), hello)
                sender, receiver = (connection, upstream) if upload else (upstream, connection)
                sender.sendall(b"x" * 1025)
                received = bytearray()
                with contextlib.suppress(ConnectionResetError):
                    while True:
                        chunk = receiver.recv(4096)
                        if not chunk:
                            break
                        received.extend(chunk)
                self.assertLessEqual(len(received), 1024)

    def test_shutdown_closes_active_tunnels_and_removes_only_own_socket(self):
        server = self.start()
        connection, _ = self.exchange(request())
        upstream = self.peers.get(timeout=1)
        server.shutdown()
        server.server_close()
        self.assertFalse(self.path.exists())
        self.assertEqual(upstream.recv(1), b"")
        self.assertEqual(connection.recv(1), b"")
        self.path.write_text("replacement fixture", encoding="ascii")
        server.server_close()
        self.assertEqual(self.path.read_text(), "replacement fixture")

    def test_existing_regular_file_or_symlink_is_never_removed(self):
        self.path.write_text("existing", encoding="ascii")
        with self.assertRaises(ValueError):
            proxy.EgressServer(self.config, self.path)
        self.assertEqual(self.path.read_text(), "existing")
        link = Path(self.temp.name) / "linked.sock"
        link.symlink_to(self.path)
        with self.assertRaises(ValueError):
            proxy.EgressServer(self.config, link)
        self.assertTrue(link.is_symlink())

    def test_stale_owned_socket_is_replaced_but_active_server_is_not(self):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stale:
            stale.bind(str(self.path))
        self.start()
        with self.assertRaises(ValueError):
            proxy.EgressServer(self.config, self.path)
        _, response = self.exchange(request())
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))

    def test_socket_mode_and_no_access_log_leaks(self):
        server = self.start()
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o666)
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            _, response = self.exchange(request(target="user:SECRET@evil.org:443"))
            server.handle_error(None, "synthetic")
        self.assertTrue(response.startswith(b"HTTP/1.1 403"))
        self.assertEqual(output.getvalue() + errors.getvalue(), "")


if __name__ == "__main__":
    unittest.main()

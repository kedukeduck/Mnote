#!/usr/bin/env python3
"""Authenticated, bounded HTTPS CONNECT egress for the isolated browser helper.

Production listens only on a Unix socket. The browser's private network namespace
bridges localhost:8794 to this socket; it must have no direct Internet route.
TLS stays end-to-end: Chromium must keep certificate verification enabled. The
proxy verifies the initial cleartext ClientHello SNI, not encrypted HTTP paths;
the helper must separately enforce its request/response and resource-type policy.
No request, destination, credential, or exception is written to access logs.
"""
from __future__ import annotations

import base64
import dataclasses
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import signal
import socket
import socketserver
import stat
import subprocess
import sys
import threading
import time

SOCKET_PATH = "/run/mnote-transcription-egress/proxy.sock"
PROXY_USERNAME = "mnote"
BROWSER_HOSTS = frozenset({
    "www.douyin.com", "lf-security.bytegoofy.com", "lf-douyin-pc-web.douyinstatic.com",
    "lf-cdn-tos.bytescm.com", "sf1-cdn-tos.douyinstatic.com", "lf9-sec.bytetos.com",
    "lf-c-flwb.bytetos.com", "lf-rc1.yhgfb-cn-static.com", "lf-ucenter-web.yhgfb-cn-static.com",
    "lf-static.applogcdn.com", "lf3-short.ibytedapm.com", "lf3-webcast.bytetos.com",
    "p-pc-weboff.byteimg.com", "fonts.bytedance.com",
})
HOST_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
HEADER_NAME = re.compile(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+\Z")


class EgressError(Exception):
    def __init__(self, status=403, reason=None):
        self.status = status
        self.reason = reason
        super().__init__("Browser egress rejected")


def valid_host(host: str) -> bool:
    if not isinstance(host, str) or len(host) > 253 or "." not in host:
        return False
    if any(not HOST_LABEL.fullmatch(label) for label in host.split(".")):
        return False
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return True
    return False


@dataclasses.dataclass(frozen=True)
class EgressConfig:
    password: str = dataclasses.field(repr=False)
    allowed_hosts: frozenset[str] = BROWSER_HOSTS
    max_connections: int = 12
    max_header_bytes: int = 8192
    max_hello_bytes: int = 65536
    max_tunnel_bytes: int = 32 * 1024 * 1024
    header_seconds: float = 5
    hello_seconds: float = 5
    dns_seconds: float = 5
    connect_seconds: float = 10
    tunnel_seconds: float = 60

    def __post_init__(self):
        if not isinstance(self.password, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", self.password):
            raise ValueError("Browser proxy password is missing or invalid")
        if not self.allowed_hosts or len(self.allowed_hosts) > 64 or any(not valid_host(h) for h in self.allowed_hosts):
            raise ValueError("Browser proxy hosts must be exact DNS names")
        object.__setattr__(self, "allowed_hosts", frozenset(self.allowed_hosts))
        if not 1 <= self.max_connections <= 32:
            raise ValueError("Invalid browser proxy connection limit")
        if not 1024 <= self.max_header_bytes <= 16384 or not 1024 <= self.max_hello_bytes <= 131072:
            raise ValueError("Invalid browser proxy input limit")
        if not 1024 <= self.max_tunnel_bytes <= 128 * 1024 * 1024:
            raise ValueError("Invalid browser proxy byte limit")
        for value in (self.header_seconds, self.hello_seconds, self.dns_seconds,
                      self.connect_seconds, self.tunnel_seconds):
            if not isinstance(value, (int, float)) or not 0 < value <= 120:
                raise ValueError("Invalid browser proxy timeout")

    @classmethod
    def from_env(cls):
        extra = os.environ.get("MNOTE_BROWSER_EXTRA_HOSTS", "")
        if len(extra) > 8192:
            raise ValueError("Invalid browser proxy hosts")
        hosts = frozenset(x.strip() for x in extra.split(",") if x.strip())
        return cls(os.environ.get("MNOTE_BROWSER_PROXY_PASSWORD", ""), BROWSER_HOSTS | hosts)

    def authorized(self, value: bytes) -> bool:
        expected = b"Basic " + base64.b64encode((PROXY_USERNAME + ":" + self.password).encode("ascii"))
        return hmac.compare_digest(value, expected)


def checked_addresses(values) -> list[str]:
    if not isinstance(values, list) or not 1 <= len(values) <= 64:
        raise EgressError()
    result = []
    for value in values:
        if not isinstance(value, str) or "%" in value:
            raise EgressError()
        try:
            address = ipaddress.ip_address(value)
        except ValueError:
            raise EgressError() from None
        if (not address.is_global or address.is_multicast or address.is_reserved
                or address.is_loopback or address.is_link_local or address.is_unspecified
                or getattr(address, "ipv4_mapped", None) or getattr(address, "sixtofour", None)
                or getattr(address, "teredo", None)):
            raise EgressError()
        normalized = str(address)
        if normalized not in result:
            result.append(normalized)
    return result


def public_addresses(host: str) -> list[str]:
    if not valid_host(host):
        raise EgressError()
    try:
        answers = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError:
        raise EgressError(502) from None
    return checked_addresses(list(dict.fromkeys(answer[4][0] for answer in answers)))


def bounded_addresses(host: str, timeout: float) -> list[str]:
    # Run only this standalone module, without inherited service secrets, Python
    # startup hooks, proxy settings, or a second resolver inside the connector.
    try:
        result = subprocess.run(
            [sys.executable, "-I", "-S", str(Path(__file__).resolve()), "--resolve", host],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"}, close_fds=True,
            timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired:
        raise EgressError(504) from None
    except OSError:
        raise EgressError(502) from None
    if result.returncode:
        raise EgressError(403 if result.returncode == 2 else 502)
    if len(result.stdout) > 8192:
        raise EgressError()
    try:
        return checked_addresses(json.loads(result.stdout))
    except (ValueError, UnicodeError):
        raise EgressError() from None


def connect_pinned(address: str, timeout: float) -> socket.socket:
    address = checked_addresses([address])[0]
    connection = socket.socket(socket.AF_INET6 if ":" in address else socket.AF_INET, socket.SOCK_STREAM)
    try:
        connection.settimeout(timeout)
        connection.connect((address, 443))
        return connection
    except BaseException:
        connection.close()
        raise


def _remaining(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise EgressError(504)
    return remaining


def read_connect(client: socket.socket, config: EgressConfig, deadline: float) -> tuple[str, bytes]:
    raw = bytearray()
    deadline = min(deadline, time.monotonic() + config.header_seconds)
    while b"\r\n\r\n" not in raw:
        client.settimeout(_remaining(deadline))
        chunk = client.recv(min(4096, config.max_header_bytes + 1 - len(raw)))
        if not chunk:
            raise EgressError(400)
        raw.extend(chunk)
        if len(raw) > config.max_header_bytes and b"\r\n\r\n" not in raw:
            raise EgressError(431)
    header, extra = bytes(raw).split(b"\r\n\r\n", 1)
    if len(header) + 4 > config.max_header_bytes:
        raise EgressError(431)
    lines = header.split(b"\r\n")
    if len(lines) > 41:
        raise EgressError(431)
    parts = lines[0].split(b" ")
    if len(parts) != 3 or parts[0] != b"CONNECT" or parts[2] not in (b"HTTP/1.0", b"HTTP/1.1"):
        raise EgressError(405)
    try:
        authority = parts[1].decode("ascii")
    except UnicodeError:
        raise EgressError(400) from None
    if not authority.endswith(":443") or authority[:-4] not in config.allowed_hosts:
        raise EgressError()
    headers = {}
    for line in lines[1:]:
        if b":" not in line:
            raise EgressError(400)
        name, value = line.split(b":", 1)
        name = name.lower()
        if (not HEADER_NAME.fullmatch(name) or name in headers
                or any(byte < 32 or byte == 127 for byte in value)):
            raise EgressError(400)
        headers[name] = value.strip(b" ")
    if headers.get(b"host") != parts[1]:
        raise EgressError(400)
    if (headers.get(b"content-length", b"0") != b"0" or b"transfer-encoding" in headers
            or b"expect" in headers or b"cookie" in headers or b"authorization" in headers):
        raise EgressError(400)
    if not config.authorized(headers.get(b"proxy-authorization", b"")):
        raise EgressError(407)
    return authority[:-4], extra


def _sni_from_hello(body: bytes) -> str:
    try:
        # ClientHello: legacy_version, random, session_id, ciphers, compression,
        # then length-delimited extensions. Every read is explicitly bounded.
        offset = 34
        if len(body) < 35 or body[:2] != b"\x03\x03":
            raise ValueError()
        session_length = body[offset]
        offset += 1 + session_length
        if session_length > 32 or offset + 2 > len(body):
            raise ValueError()
        cipher_length = int.from_bytes(body[offset:offset + 2], "big")
        offset += 2 + cipher_length
        if cipher_length < 2 or cipher_length % 2 or offset >= len(body):
            raise ValueError()
        compression_length = body[offset]
        offset += 1 + compression_length
        if compression_length != 1 or body[offset - 1] != 0 or offset + 2 > len(body):
            raise ValueError()
        extension_length = int.from_bytes(body[offset:offset + 2], "big")
        offset += 2
        if offset + extension_length != len(body):
            raise ValueError()
        seen, server_name = set(), None
        while offset < len(body):
            if offset + 4 > len(body):
                raise ValueError()
            kind = int.from_bytes(body[offset:offset + 2], "big")
            size = int.from_bytes(body[offset + 2:offset + 4], "big")
            offset += 4
            # Chromium also sends a GREASE ECH extension without an ECH config.
            # Keep opaque extensions unchanged; require the visible SNI below.
            # Browser URL checks and certificate validation cover the real origin,
            # while the proxy independently pins only allowlisted public endpoints.
            if offset + size > len(body) or kind in seen:
                raise ValueError()
            seen.add(kind)
            value = body[offset:offset + size]
            offset += size
            if kind == 0:
                if len(value) < 6 or int.from_bytes(value[:2], "big") != len(value) - 2 or value[2] != 0:
                    raise ValueError()
                if int.from_bytes(value[3:5], "big") != len(value) - 5:
                    raise ValueError()
                server_name = value[5:].decode("ascii")
        if not server_name or not valid_host(server_name):
            raise ValueError()
        return server_name
    except (ValueError, IndexError, UnicodeError):
        raise EgressError() from None


def read_client_hello(client: socket.socket, prefix: bytes, expected: str,
                      config: EgressConfig, deadline: float) -> bytes:
    deadline = min(deadline, time.monotonic() + config.hello_seconds)
    buffered, wire, handshake = bytearray(prefix), bytearray(), bytearray()
    def take(size):
        while len(buffered) < size:
            client.settimeout(_remaining(deadline))
            chunk = client.recv(size - len(buffered))
            if not chunk:
                raise EgressError()
            buffered.extend(chunk)
        value = bytes(buffered[:size])
        del buffered[:size]
        return value
    while True:
        record = take(5)
        size = int.from_bytes(record[3:], "big")
        if record[0] != 22 or record[1:3] not in (b"\x03\x01", b"\x03\x02", b"\x03\x03") or not 1 <= size <= 16384:
            raise EgressError()
        if len(wire) + 5 + size > config.max_hello_bytes:
            raise EgressError()
        payload = take(size)
        wire.extend(record + payload)
        handshake.extend(payload)
        if len(handshake) < 4:
            continue
        size = int.from_bytes(handshake[1:4], "big")
        if handshake[0] != 1 or size + 4 > config.max_hello_bytes:
            raise EgressError()
        if len(handshake) >= size + 4:
            if _sni_from_hello(bytes(handshake[4:size + 4])) != expected:
                raise EgressError()
            # Pipelined TLS bytes received with CONNECT stay within the same tunnel.
            return bytes(wire + buffered)


def relay(client: socket.socket, upstream: socket.socket, initial: bytes,
          config: EgressConfig, deadline: float, stopping: threading.Event):
    if len(initial) > config.max_tunnel_bytes:
        raise EgressError()
    client.setblocking(False)
    upstream.setblocking(False)
    pending = {client: bytearray(), upstream: bytearray(initial)}
    peers = {client: upstream, upstream: client}
    counts = {client: len(initial), upstream: 0}
    ended, write_closed = set(), set()
    while not stopping.is_set():
        remaining = _remaining(deadline)
        for current in peers:
            if peers[current] in ended and not pending[current] and current not in write_closed:
                current.shutdown(socket.SHUT_WR)
                write_closed.add(current)
        if len(ended) == 2 and not any(pending.values()):
            return
        reading = [s for s in peers if s not in ended and len(pending[peers[s]]) < 65536]
        writing = [s for s in peers if pending[s]]
        readable, writable, _ = select.select(reading, writing, [], min(remaining, 0.1))
        for current in writable:
            try:
                written = current.send(pending[current])
            except BlockingIOError:
                continue
            if not written:
                return
            del pending[current][:written]
        for current in readable:
            try:
                chunk = current.recv(min(16384, config.max_tunnel_bytes - counts[current] + 1))
            except BlockingIOError:
                continue
            if not chunk:
                ended.add(current)
                continue
            counts[current] += len(chunk)
            if counts[current] > config.max_tunnel_bytes:
                raise EgressError()
            pending[peers[current]].extend(chunk)


def _respond(client: socket.socket, status: int):
    reasons = {200: "Connection Established", 400: "Bad Request", 403: "Forbidden",
               405: "Method Not Allowed", 407: "Proxy Authentication Required",
               431: "Request Header Fields Too Large", 502: "Bad Gateway",
               503: "Service Unavailable", 504: "Gateway Timeout"}
    response = f"HTTP/1.1 {status} {reasons[status]}\r\n"
    if status == 407:
        response += 'Proxy-Authenticate: Basic realm="mnote-browser"\r\n'
    if status != 200:
        response += "Content-Length: 0\r\nConnection: close\r\n"
    client.sendall((response + "\r\n").encode("ascii"))


class _Handler(socketserver.BaseRequestHandler):
    def handle(self):
        server, client, established, upstream = self.server, self.request, False, None
        deadline = time.monotonic() + server.config.tunnel_seconds
        stage = "connect_headers"
        try:
            host, prefix = read_connect(client, server.config, deadline)
            stage = "dns"
            addresses = checked_addresses(server.resolver(host, min(server.config.dns_seconds, _remaining(deadline))))
            stage = "connect_pinned"
            upstream = server.connector(addresses[0], min(server.config.connect_seconds, _remaining(deadline)))
            server.track(upstream)
            client.settimeout(min(server.config.header_seconds, _remaining(deadline)))
            _respond(client, 200)
            established = True
            stage = "client_hello"
            hello = read_client_hello(client, prefix, host, server.config, deadline)
            stage = "relay"
            relay(client, upstream, hello, server.config, deadline, server.stopping)
        except (EgressError, OSError) as error:
            if os.environ.get("MNOTE_BROWSER_DIAGNOSTICS") == "1":
                try:
                    # Operator-only counters: never include destination, headers or bytes.
                    with server.lock:
                        counters = getattr(server, "diagnostic_counters", {})
                        key = "/".join(str(value) for value in (stage, type(error).__name__, getattr(error, "status", "io"), getattr(error, "reason", None)))
                        counters[key] = counters.get(key, 0) + 1
                        server.diagnostic_counters = counters
                        Path("/run/mnote-transcription-egress/diagnostic.json").write_text(json.dumps(counters))
                except OSError:
                    pass
            if not established:
                try:
                    client.settimeout(min(server.config.header_seconds, 1))
                    _respond(client, error.status if isinstance(error, EgressError) else 502)
                except OSError:
                    pass
        finally:
            if upstream:
                server.untrack(upstream)
                upstream.close()


class EgressServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 16

    def __init__(self, config: EgressConfig, socket_path=SOCKET_PATH,
                 resolver=bounded_addresses, connector=connect_pinned):
        self.config, self.resolver, self.connector = config, resolver, connector
        self.stopping = threading.Event()
        self.slots = threading.BoundedSemaphore(config.max_connections)
        self.active, self.lock = set(), threading.Lock()
        self.path = Path(socket_path)
        self.socket_identity = None
        if self.path.exists() or self.path.is_symlink():
            info = self.path.lstat()
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                raise ValueError("Unsafe browser proxy socket path")
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.settimeout(0.2)
                try:
                    probe.connect(str(self.path))
                except ConnectionRefusedError:
                    self.path.unlink()
                else:
                    raise ValueError("Browser proxy already running")
        super().__init__(str(self.path), _Handler)
        info = self.path.lstat()
        self.socket_identity = (info.st_dev, info.st_ino)
        os.chmod(self.path, 0o666)

    def track(self, connection):
        with self.lock:
            if self.stopping.is_set():
                connection.close()
                raise EgressError(503)
            self.active.add(connection)

    def untrack(self, connection):
        with self.lock:
            self.active.discard(connection)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.settimeout(0.1)
                _respond(request, 503)
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            self.track(request)
            super().process_request(request, client_address)
        except BaseException:
            self.untrack(request)
            self.slots.release()
            self.shutdown_request(request)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.untrack(request)
            self.slots.release()

    def handle_error(self, request, client_address):
        # Avoid socketserver's default traceback, which may expose request data.
        pass

    def server_close(self):
        self.stopping.set()
        with self.lock:
            active = list(self.active)
        for connection in active:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        super().server_close()
        try:
            info = self.path.lstat()
            if self.socket_identity == (info.st_dev, info.st_ino):
                self.path.unlink()
        except FileNotFoundError:
            pass


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--resolve":
        try:
            print(json.dumps(public_addresses(sys.argv[2])))
            return 0
        except EgressError as error:
            return 2 if error.status == 403 else 1
    if len(sys.argv) != 1:
        return 2
    try:
        config = EgressConfig.from_env()
        with EgressServer(config) as server:
            server.timeout = 0.2
            signal.signal(signal.SIGTERM, lambda *_: server.stopping.set())
            signal.signal(signal.SIGINT, lambda *_: server.stopping.set())
            while not server.stopping.is_set():
                server.handle_request()
    except (OSError, ValueError):
        # Intentionally generic: never echo environment, hostnames or credentials.
        print("Browser egress could not start", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

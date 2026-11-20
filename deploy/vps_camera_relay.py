#!/usr/bin/env python3
"""Single-upstream MJPEG fan-out relay & status micro-cache for HESTA VPS.

Why this exists:
Orange Pi reaches the VPS over one reverse SSH tunnel (Pi:8001 -> VPS:18001).
Opening N direct browser/modal streams multiplies WAN traffic by N, saturates
the SSH tunnel, builds megabytes of stale-frame backlog in nginx/sshd, and
starves /camera/api/status polls (HTTP 499 / timeouts).

This relay opens AT MOST ONE upstream MJPEG stream when >= 1 viewer is
connected, keeps only the latest complete JPEG frame per subscriber (0-frame
backlog), disconnects upstream shortly after the last viewer leaves, and
serves /camera/api/status through a single-flight micro-cache.
"""

import asyncio
import http.client
import json
import logging
import os
import threading
import time
from dataclasses import dataclass
from typing import Optional
from urllib.parse import urlsplit

logging.basicConfig(
    level=os.environ.get("RELAY_LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s [camera-relay] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("camera-relay")

JPEG_SOI = b"\xff\xd8"
JPEG_EOI = b"\xff\xd9"

RELAY_HOST = os.environ.get("RELAY_HOST", "127.0.0.1")
RELAY_PORT = int(os.environ.get("RELAY_PORT", "18002"))
UPSTREAM_HOST = os.environ.get("UPSTREAM_HOST", "127.0.0.1")
UPSTREAM_PORT = int(os.environ.get("UPSTREAM_PORT", "18001"))
STATUS_CACHE_TTL = float(os.environ.get("STATUS_CACHE_TTL", "1.5"))
STATUS_STALE_TTL = float(os.environ.get("STATUS_STALE_TTL", "15.0"))
ERROR_CACHE_TTL = float(os.environ.get("ERROR_CACHE_TTL", "1.0"))
IDLE_DISCONNECT_SEC = float(os.environ.get("IDLE_DISCONNECT_SEC", "3.0"))
UPSTREAM_STALL_SEC = float(os.environ.get("UPSTREAM_STALL_SEC", "6.0"))
STATUS_TIMEOUT_SEC = float(os.environ.get("STATUS_TIMEOUT_SEC", "3.5"))
ALLOWED_ORIGINS = {
    origin.strip()
    for origin in os.environ.get(
        "ALLOWED_ORIGINS",
        "https://hesta.homes,http://localhost:5173,http://localhost:3000",
    ).split(",")
    if origin.strip()
}


def extract_jpeg_frames(buf: bytearray, max_buffer_bytes: int = 2097152) -> list[bytes]:
    """Extract all complete JPEG frames (SOI..EOI) from a mutable stream buffer."""
    frames: list[bytes] = []
    while True:
        soi = buf.find(JPEG_SOI)
        if soi == -1:
            # Keep trailing 0xFF in case SOI straddles a chunk boundary.
            if buf.endswith(b"\xff"):
                del buf[:-1]
            else:
                buf.clear()
            break

        if soi > 0:
            del buf[:soi]

        eoi = buf.find(JPEG_EOI, 2)
        if eoi == -1:
            if len(buf) > max_buffer_bytes:
                # Corrupt/unterminated JPEG: skip past this SOI to recover.
                del buf[:2]
                continue
            break

        frame_end = eoi + 2
        if frame_end > max_buffer_bytes:
            del buf[:2]
            continue

        frames.append(bytes(buf[:frame_end]))
        del buf[:frame_end]

    return frames


def build_cors_headers(origin: str) -> list[tuple[str, str]]:
    headers: list[tuple[str, str]] = [("Vary", "Origin")]
    if origin and origin in ALLOWED_ORIGINS:
        headers.extend(
            [
                ("Access-Control-Allow-Origin", origin),
                ("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
                ("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key"),
                ("Access-Control-Allow-Credentials", "true"),
            ]
        )
    return headers


class MjpegHub:
    """Single-upstream MJPEG reader fanning out latest frames to N asyncio viewers."""

    def __init__(
        self,
        upstream_host: str = UPSTREAM_HOST,
        upstream_port: int = UPSTREAM_PORT,
        idle_disconnect_sec: float = IDLE_DISCONNECT_SEC,
        upstream_stall_sec: float = UPSTREAM_STALL_SEC,
    ):
        self.upstream_host = upstream_host
        self.upstream_port = upstream_port
        self.idle_disconnect_sec = idle_disconnect_sec
        self.upstream_stall_sec = upstream_stall_sec

        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._subscribers: set[asyncio.Event] = set()
        self._sub_lock = threading.Lock()
        self._frame_lock = threading.Lock()
        self._reader_thread: Optional[threading.Thread] = None
        self._stop_requested = False

        self.latest_frame: Optional[bytes] = None
        self.frame_seq: int = 0
        self.upstream_connected: bool = False
        self.frames_received: int = 0
        self.upstream_fps: float = 0.0
        self._last_subscriber_seen_mono: float = 0.0

    def bind_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop

    def subscriber_count(self) -> int:
        with self._sub_lock:
            return len(self._subscribers)

    def subscribe(self) -> asyncio.Event:
        ev = asyncio.Event()
        start_thread = False
        with self._sub_lock:
            self._subscribers.add(ev)
            self._last_subscriber_seen_mono = time.monotonic()
            if self.latest_frame is not None:
                ev.set()
            if self._reader_thread is None or not self._reader_thread.is_alive():
                self._stop_requested = False
                start_thread = True
        if start_thread:
            self._reader_thread = threading.Thread(
                target=self._upstream_reader_loop,
                name="UpstreamMjpegReader",
                daemon=True,
            )
            self._reader_thread.start()
        return ev

    def unsubscribe(self, ev: asyncio.Event) -> None:
        with self._sub_lock:
            self._subscribers.discard(ev)
            if self._subscribers:
                self._last_subscriber_seen_mono = time.monotonic()

    def get_frame(self) -> tuple[int, Optional[bytes]]:
        with self._frame_lock:
            return self.frame_seq, self.latest_frame

    def _notify_subscribers_on_loop(self) -> None:
        with self._sub_lock:
            subs = list(self._subscribers)
        for ev in subs:
            ev.set()

    def _publish_frame(self, frame_bytes: bytes, now_mono: float, prev_frame_ts: float) -> float:
        with self._frame_lock:
            self.latest_frame = frame_bytes
            self.frame_seq += 1
            self.frames_received += 1
            if prev_frame_ts > 0.0:
                dt = max(now_mono - prev_frame_ts, 0.001)
                inst_fps = 1.0 / dt
                self.upstream_fps = inst_fps if self.upstream_fps <= 0.0 else (0.85 * self.upstream_fps + 0.15 * inst_fps)
        if self._loop is not None and not self._loop.is_closed():
            self._loop.call_soon_threadsafe(self._notify_subscribers_on_loop)
        return now_mono

    def _has_active_demand(self) -> bool:
        with self._sub_lock:
            if self._stop_requested:
                return False
            if self._subscribers:
                self._last_subscriber_seen_mono = time.monotonic()
                return True
            return (time.monotonic() - self._last_subscriber_seen_mono) < self.idle_disconnect_sec

    def _upstream_reader_loop(self) -> None:
        backoff = 0.5
        prev_frame_ts = 0.0
        logger.info("Upstream MJPEG reader started (%s:%d)", self.upstream_host, self.upstream_port)

        while self._has_active_demand():
            conn: Optional[http.client.HTTPConnection] = None
            try:
                conn = http.client.HTTPConnection(
                    self.upstream_host,
                    self.upstream_port,
                    timeout=self.upstream_stall_sec,
                )
                conn.request("GET", "/camera/video_feed", headers={"Connection": "close"})
                resp = conn.getresponse()
                if resp.status != 200:
                    logger.warning("Upstream /camera/video_feed returned HTTP %d", resp.status)
                    raise RuntimeError(f"upstream HTTP {resp.status}")

                self.upstream_connected = True
                backoff = 0.5
                buf = bytearray()

                while self._has_active_demand():
                    # read1() returns immediately when a chunk arrives rather than waiting to fill amt.
                    chunk = resp.read1(16384)
                    if not chunk:
                        break
                    buf.extend(chunk)
                    frames = extract_jpeg_frames(buf)
                    if frames:
                        now_mono = time.monotonic()
                        # Keep only the newest complete frame extracted from this chunk batch.
                        prev_frame_ts = self._publish_frame(frames[-1], now_mono, prev_frame_ts)
            except Exception as exc:
                logger.warning("Upstream MJPEG reader interrupted: %s", exc)
            finally:
                self.upstream_connected = False
                if conn is not None:
                    try:
                        conn.close()
                    except Exception:
                        pass

            if not self._has_active_demand():
                break
            time.sleep(backoff)
            backoff = min(backoff * 1.5, 2.0)

        logger.info("Upstream MJPEG reader stopped (idle)")


@dataclass
class CachedHttpResponse:
    status: int
    content_type: str
    body: bytes
    fetched_mono: float


class StatusCache:
    """Single-flight micro-cache with stale-while-revalidate and negative error caching."""

    def __init__(
        self,
        upstream_host: str = UPSTREAM_HOST,
        upstream_port: int = UPSTREAM_PORT,
        cache_ttl: float = STATUS_CACHE_TTL,
        stale_ttl: float = STATUS_STALE_TTL,
        error_cache_ttl: float = ERROR_CACHE_TTL,
        timeout_sec: float = STATUS_TIMEOUT_SEC,
    ):
        self.upstream_host = upstream_host
        self.upstream_port = upstream_port
        self.cache_ttl = cache_ttl
        self.stale_ttl = stale_ttl
        self.error_cache_ttl = error_cache_ttl
        self.timeout_sec = timeout_sec
        self._cached: Optional[CachedHttpResponse] = None
        self._last_error_mono: float = 0.0
        self._inflight: Optional[asyncio.Task[tuple[int, str, bytes]]] = None

    def invalidate(self) -> None:
        self._cached = None
        self._last_error_mono = 0.0

    def age_ms(self) -> Optional[int]:
        if self._cached is None:
            return None
        return int((time.monotonic() - self._cached.fetched_mono) * 1000)

    def _sync_request(
        self,
        method: str,
        path: str,
        body: bytes = b"",
        content_type: str = "",
    ) -> tuple[int, str, bytes]:
        conn = http.client.HTTPConnection(
            self.upstream_host,
            self.upstream_port,
            timeout=self.timeout_sec,
        )
        headers = {"Connection": "close"}
        if body:
            headers["Content-Length"] = str(len(body))
        if content_type:
            headers["Content-Type"] = content_type
        try:
            conn.request(method, path, body=body if body else None, headers=headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            resp_ct = resp.getheader("Content-Type", "application/json")
            return resp.status, resp_ct, resp_body
        finally:
            conn.close()

    def _start_refresh_task(self) -> asyncio.Task[tuple[int, str, bytes]]:
        async def _run() -> tuple[int, str, bytes]:
            try:
                status, ct, body = await asyncio.to_thread(self._sync_request, "GET", "/camera/api/status")
                if 200 <= status < 500:
                    self._cached = CachedHttpResponse(
                        status=status,
                        content_type=ct,
                        body=body,
                        fetched_mono=time.monotonic(),
                    )
                    self._last_error_mono = 0.0
                    return status, ct, body
                self._last_error_mono = time.monotonic()
                return 503, "application/json", b'{"error": "upstream_unavailable"}'
            except Exception as exc:
                logger.warning("Upstream /camera/api/status error: %s", exc)
                self._last_error_mono = time.monotonic()
                return 503, "application/json", b'{"error": "upstream_unavailable"}'
            finally:
                self._inflight = None

        self._inflight = asyncio.create_task(_run())
        return self._inflight

    async def get_status(self) -> tuple[int, str, bytes, str]:
        now = time.monotonic()
        if self._cached is not None and (now - self._cached.fetched_mono) < self.cache_ttl:
            return self._cached.status, self._cached.content_type, self._cached.body, "hit"

        if self._cached is not None and (now - self._cached.fetched_mono) < self.stale_ttl:
            if self._inflight is None and (
                self._last_error_mono <= 0.0 or (now - self._last_error_mono) >= self.error_cache_ttl
            ):
                self._start_refresh_task()
            return self._cached.status, self._cached.content_type, self._cached.body, "stale"

        if self._last_error_mono > 0.0 and (now - self._last_error_mono) < self.error_cache_ttl:
            return 503, "application/json", b'{"error": "upstream_unavailable"}', "error"

        task = self._inflight or self._start_refresh_task()
        status, ct, body = await asyncio.shield(task)
        state = "miss" if 200 <= status < 500 else "error"
        return status, ct, body, state

    async def forward_post(self, path: str, body: bytes, content_type: str) -> tuple[int, str, bytes]:
        self.invalidate()
        try:
            return await asyncio.to_thread(self._sync_request, "POST", path, body, content_type)
        except Exception as exc:
            logger.warning("Upstream %s POST error: %s", path, exc)
            return 503, "application/json", json.dumps({"error": "upstream_unavailable"}).encode()


async def _write_http_response(
    writer: asyncio.StreamWriter,
    status: int,
    reason: str,
    headers: list[tuple[str, str]],
    body: bytes = b"",
) -> None:
    lines = [f"HTTP/1.1 {status} {reason}"]
    for k, v in headers:
        lines.append(f"{k}: {v}")
    lines.append("")
    lines.append("")
    payload = ("\r\n".join(lines)).encode("latin-1") + body
    writer.write(payload)
    await asyncio.wait_for(writer.drain(), timeout=5.0)


async def handle_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    hub: MjpegHub,
    status_cache: StatusCache,
) -> None:
    sub_event: Optional[asyncio.Event] = None
    try:
        raw_head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5.0)
    except Exception:
        writer.close()
        return

    try:
        head_text = raw_head.decode("latin-1")
        req_lines = head_text.split("\r\n")
        parts = req_lines[0].split(" ", 2)
        if len(parts) < 2:
            await _write_http_response(writer, 400, "Bad Request", [("Connection", "close")], b"Bad Request")
            return
        method = parts[0].upper()
        target = parts[1]
        path = urlsplit(target).path

        req_headers: dict[str, str] = {}
        for line in req_lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                req_headers[k.strip().lower()] = v.strip()

        origin = req_headers.get("origin", "")
        cors_headers = build_cors_headers(origin)

        content_length = min(int(req_headers.get("content-length", "0") or "0"), 1048576)
        req_body = b""
        if content_length > 0:
            req_body = await asyncio.wait_for(reader.readexactly(content_length), timeout=5.0)

        if method == "OPTIONS":
            await _write_http_response(
                writer,
                204,
                "No Content",
                cors_headers + [("Content-Length", "0"), ("Connection", "close")],
            )
            return

        if path == "/relay/health" and method == "GET":
            health = {
                "status": "ok",
                "subscribers": hub.subscriber_count(),
                "upstream_connected": hub.upstream_connected,
                "frames_received": hub.frames_received,
                "upstream_fps": round(hub.upstream_fps, 1),
                "frame_seq": hub.frame_seq,
                "status_cache_age_ms": status_cache.age_ms(),
            }
            payload = json.dumps(health).encode("utf-8")
            await _write_http_response(
                writer,
                200,
                "OK",
                [
                    ("Content-Type", "application/json"),
                    ("Content-Length", str(len(payload))),
                    ("Cache-Control", "no-store"),
                    ("Connection", "close"),
                ]
                + cors_headers,
                payload,
            )
            return

        if path == "/camera/api/status" and method == "GET":
            st_code, st_ct, st_body, cache_state = await status_cache.get_status()
            reason = "OK" if st_code == 200 else ("Service Unavailable" if st_code == 503 else "Status")
            await _write_http_response(
                writer,
                st_code,
                reason,
                [
                    ("Content-Type", st_ct),
                    ("Content-Length", str(len(st_body))),
                    ("Cache-Control", "no-store"),
                    ("X-Relay-Cache", cache_state),
                    ("Connection", "close"),
                ]
                + cors_headers,
                st_body,
            )
            return

        if path in ("/camera/api/dismiss", "/camera/api/trigger_demo_fall") and method == "POST":
            st_code, st_ct, st_body = await status_cache.forward_post(
                path,
                req_body,
                req_headers.get("content-type", "application/json"),
            )
            reason = "OK" if st_code == 200 else ("Service Unavailable" if st_code == 503 else "Status")
            await _write_http_response(
                writer,
                st_code,
                reason,
                [
                    ("Content-Type", st_ct),
                    ("Content-Length", str(len(st_body))),
                    ("Cache-Control", "no-store"),
                    ("Connection", "close"),
                ]
                + cors_headers,
                st_body,
            )
            return

        if path == "/camera/video_feed" and method == "GET":
            stream_headers = [
                ("Content-Type", "multipart/x-mixed-replace; boundary=frame"),
                ("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0"),
                ("Pragma", "no-cache"),
                ("X-Accel-Buffering", "no"),
                ("Connection", "close"),
            ] + cors_headers
            await _write_http_response(writer, 200, "OK", stream_headers)

            sub_event = hub.subscribe()
            last_sent_seq = -1
            while True:
                try:
                    await asyncio.wait_for(sub_event.wait(), timeout=10.0)
                except asyncio.TimeoutError:
                    if writer.is_closing():
                        break
                    continue

                sub_event.clear()
                seq, frame = hub.get_frame()
                if frame is None or seq == last_sent_seq:
                    continue
                last_sent_seq = seq

                part_header = (
                    f"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame)}\r\n\r\n"
                ).encode("ascii")
                writer.write(part_header + frame + b"\r\n")
                await asyncio.wait_for(writer.drain(), timeout=5.0)
            return

        payload = json.dumps({"error": "not_found", "path": path}).encode("utf-8")
        await _write_http_response(
            writer,
            404,
            "Not Found",
            [("Content-Type", "application/json"), ("Content-Length", str(len(payload))), ("Connection", "close")]
            + cors_headers,
            payload,
        )
    except (ConnectionError, asyncio.CancelledError, asyncio.TimeoutError):
        pass
    except Exception as exc:
        logger.debug("Client handler exception: %s", exc)
    finally:
        if sub_event is not None:
            hub.unsubscribe(sub_event)
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


async def start_relay_server(
    host: str = RELAY_HOST,
    port: int = RELAY_PORT,
    upstream_host: str = UPSTREAM_HOST,
    upstream_port: int = UPSTREAM_PORT,
    idle_disconnect_sec: float = IDLE_DISCONNECT_SEC,
) -> tuple[asyncio.AbstractServer, MjpegHub, StatusCache]:
    loop = asyncio.get_running_loop()
    hub = MjpegHub(
        upstream_host=upstream_host,
        upstream_port=upstream_port,
        idle_disconnect_sec=idle_disconnect_sec,
    )
    hub.bind_loop(loop)
    status_cache = StatusCache(upstream_host=upstream_host, upstream_port=upstream_port)

    server = await asyncio.start_server(
        lambda r, w: handle_client(r, w, hub, status_cache),
        host,
        port,
        reuse_address=True,
    )
    return server, hub, status_cache


async def serve() -> None:
    server, _, _ = await start_relay_server()
    logger.info(
        "HESTA Camera Relay listening on http://%s:%d -> upstream %s:%d",
        RELAY_HOST,
        RELAY_PORT,
        UPSTREAM_HOST,
        UPSTREAM_PORT,
    )
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(serve())
    except KeyboardInterrupt:
        pass

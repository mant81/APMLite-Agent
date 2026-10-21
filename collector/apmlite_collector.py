#!/usr/bin/env python3
"""Small reference Collector for APMLite Agent batches.

The Collector owns persistence. The application JVM only sends bounded batches;
SQLite stores time-bucket rollups and a capped recent-event sample.
"""
import json
import os
import queue
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DB_PATH = os.environ.get("APMLITE_COLLECTOR_DB", "apmlite-collector.db")
PORT = int(os.environ.get("APMLITE_COLLECTOR_PORT", "9000"))
QUEUE_SIZE = int(os.environ.get("APMLITE_COLLECTOR_QUEUE", "5000"))
EVENT_QUEUE = queue.Queue(maxsize=max(100, QUEUE_SIZE))
COUNTERS = {"accepted": 0, "dropped": 0, "stored": 0}
LOCK = threading.Lock()


def db_connect():
    conn = sqlite3.connect(DB_PATH, timeout=2)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("""CREATE TABLE IF NOT EXISTS event_rollups (
        bucket INTEGER NOT NULL, service TEXT NOT NULL, type TEXT NOT NULL,
        event_count INTEGER NOT NULL, error_count INTEGER NOT NULL,
        total_duration_ms REAL NOT NULL, max_duration_ms REAL NOT NULL,
        PRIMARY KEY (bucket, service, type))""")
    conn.execute("""CREATE TABLE IF NOT EXISTS recent_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, received_at INTEGER NOT NULL,
        service TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL)""")
    conn.commit()
    return conn


def store_batch(events):
    if not events:
        return
    conn = db_connect()
    try:
        for event in events:
            service = str(event.get("service", "unknown"))[:100]
            kind = str(event.get("type", "unknown"))[:20]
            bucket = int(int(event.get("timestamp", int(time.time() * 1000))) / 60000) * 60000
            duration = float(event.get("durationMs", 0) or 0)
            is_error = 1 if str(event.get("status", "")).upper() == "ERROR" else 0
            conn.execute("""INSERT INTO event_rollups
                (bucket, service, type, event_count, error_count, total_duration_ms, max_duration_ms)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(bucket, service, type) DO UPDATE SET
                event_count=event_count+1, error_count=error_count+excluded.error_count,
                total_duration_ms=total_duration_ms+excluded.total_duration_ms,
                max_duration_ms=MAX(max_duration_ms, excluded.max_duration_ms)""",
                         (bucket, service, kind, is_error, duration, duration))
            conn.execute("INSERT INTO recent_events(received_at, service, type, payload) VALUES (?, ?, ?, ?)",
                         (int(time.time() * 1000), service, kind, json.dumps(event, ensure_ascii=False)))
        conn.execute("DELETE FROM recent_events WHERE id NOT IN (SELECT id FROM recent_events ORDER BY id DESC LIMIT 1000)")
        conn.commit()
        with LOCK:
            COUNTERS["stored"] += len(events)
    finally:
        conn.close()


def worker():
    while True:
        batch = [EVENT_QUEUE.get()]
        deadline = time.monotonic() + 0.2
        while len(batch) < 100 and time.monotonic() < deadline:
            try:
                batch.append(EVENT_QUEUE.get_nowait())
            except queue.Empty:
                time.sleep(0.01)
        try:
            store_batch(batch)
        except Exception:
            with LOCK:
                COUNTERS["dropped"] += len(batch)
        finally:
            for _ in batch:
                EVENT_QUEUE.task_done()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send_json(self, status, body):
        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"status": "UP", "queue": EVENT_QUEUE.qsize(), "counters": COUNTERS})
            return
        if self.path == "/summary":
            conn = db_connect()
            rows = conn.execute("SELECT bucket, service, type, event_count, error_count, total_duration_ms, max_duration_ms FROM event_rollups ORDER BY bucket DESC LIMIT 200").fetchall()
            conn.close()
            self.send_json(200, {"items": [dict(zip(("bucket", "service", "type", "count", "errors", "totalDurationMs", "maxDurationMs"), row)) for row in rows]})
            return
        self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/events":
            self.send_json(404, {"error": "not_found"})
            return
        try:
            length = min(int(self.headers.get("Content-Length", "0")), 2 * 1024 * 1024)
            payload = json.loads(self.rfile.read(length))
            events = payload if isinstance(payload, list) else [payload]
            accepted = 0
            for event in events[:500]:
                if isinstance(event, dict):
                    try:
                        EVENT_QUEUE.put_nowait(event)
                        accepted += 1
                    except queue.Full:
                        with LOCK:
                            COUNTERS["dropped"] += 1
            with LOCK:
                COUNTERS["accepted"] += accepted
            self.send_json(202, {"accepted": accepted, "dropped": len(events) - accepted})
        except Exception:
            self.send_json(400, {"error": "invalid_batch"})


if __name__ == "__main__":
    db_connect().close()
    threading.Thread(target=worker, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("APMLite Collector listening on http://127.0.0.1:%d" % PORT, flush=True)
    server.serve_forever()

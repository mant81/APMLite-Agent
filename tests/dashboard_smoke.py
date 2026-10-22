"""Run against a packaged agent: python tests/dashboard_smoke.py <agent.jar>."""
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]


def main():
    jar = Path(sys.argv[1]).resolve()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    work = ROOT / "target" / "dashboard-smoke"
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work) as temporary:
        folder = Path(temporary)
        source = folder / "DashboardSmoke.java"
        source.write_text(
            "public class DashboardSmoke { public static void main(String[] args) "
            "throws Exception { Thread.sleep(60000); } }", encoding="utf-8"
        )
        subprocess.run(["javac", "-d", str(folder), str(source)], check=True)
        with (folder / "agent.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen([
                "java", f"-javaagent:{jar}=use_db=false;use_log=false;http_port={port};sample_ms=200",
                "-cp", str(folder), "DashboardSmoke",
            ], stdout=log, stderr=log)
            try:
                base = f"http://127.0.0.1:{port}"

                def get(path):
                    with urlopen(base + path, timeout=3) as response:
                        assert response.status == 200, path
                        return response.read().decode("utf-8")

                deadline = time.monotonic() + 20
                while True:
                    try:
                        health = json.loads(get("/api/health"))
                        if health["status"] == "UP":
                            break
                    except (OSError, ValueError):
                        pass
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise AssertionError("Agent did not become healthy")
                    time.sleep(0.1)
                first = json.loads(get("/api/overview"))["timestamp"]
                time.sleep(0.6)
                assert json.loads(get("/api/overview"))["timestamp"] > first
                assert len(json.loads(get("/api/timeline"))["points"]) >= 2
                for path in ("/api/sql/top", "/api/sql/slow", "/api/sql/timeline",
                             "/api/traffic", "/api/traffic/timeline", "/api/traffic/errors",
                             "/api/alerts", "/api/collector/status", "/api/collector/summary"):
                    json.loads(get(path))
                html = get("/")
                assert get("/monitor") == html
                page = folder / "dashboard.html"
                page.write_text(html, encoding="utf-8")
                subprocess.run(["node", str(ROOT / "tests" / "dashboard-script.test.cjs"),
                                str(page)], check=True)
                print("PASS: real JVM agent, dashboard routes, health, advancing samples and all dashboard APIs")
            finally:
                process.terminate()
                process.wait(timeout=10)


if __name__ == "__main__":
    main()

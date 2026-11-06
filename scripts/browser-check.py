from pathlib import Path
import os
import json
import secrets
import shutil
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / "build/browser-check"
SCRATCH.mkdir(parents=True, exist_ok=True)
environment = os.environ.copy()
desktop_socket = Path.home() / ".docker/run/docker.sock"
if not environment.get("DOCKER_HOST") and desktop_socket.exists():
    environment["DOCKER_HOST"] = f"unix://{desktop_socket}"


def docker(*arguments):
    result = subprocess.run(["docker", *arguments], env=environment, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"Docker {arguments[0]} failed: {result.stderr}")
    return result.stdout.strip()


def available_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


def wait_for_app(process, origin):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Backend exited; see {SCRATCH / 'backend.log'}")
        try:
            with urllib.request.urlopen(f"{origin}/api/v1/auth/csrf", timeout=1) as response:
                if response.status == 200:
                    return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError("Backend did not start within 60 seconds")


def browser_tests(containers, app_port):
    docker("build", "-t", "secureshare-browser-check:1.64.1-node26", "-f", str(ROOT / "frontend/e2e/Dockerfile"), str(ROOT / "frontend/e2e"))
    command = (
        "cp /source/package.json /source/package-lock.json . && "
        "mkdir -p frontend docs/screenshots && "
        "cp /source/frontend/package.json /source/frontend/playwright.config.ts frontend/ && "
        "cp -R /source/frontend/e2e frontend/e2e && "
        "npm ci --no-audit --no-fund && npm run test:e2e --workspace frontend"
    )
    browser = docker(
        "create", "--add-host", "host.docker.internal:host-gateway",
        "-v", f"{ROOT}:/source:ro",
        "-e", f"E2E_BASE_URL=http://host.docker.internal:{app_port}",
        "secureshare-browser-check:1.64.1-node26", "sh", "-c", command,
    )
    containers.append(browser)
    result = subprocess.run(["docker", "start", "--attach", browser], env=environment)
    for source, destination in [("docs/screenshots", ROOT / "docs/screenshots"), ("frontend/test-results", ROOT / "frontend/test-results")]:
        destination.mkdir(parents=True, exist_ok=True)
        copy = subprocess.run(["docker", "cp", f"{browser}:/workspace/{source}/.", str(destination)], env=environment, capture_output=True)
        if result.returncode == 0 and copy.returncode != 0:
            raise RuntimeError("Cannot collect browser test artifacts")
    return result


def main():
    containers = []
    application = None
    log = None
    try:
        db_port, s3_port, app_port = (available_port() for _ in range(3))
        password = secrets.token_hex(20)
        access = "e2e" + secrets.token_hex(8)
        secret = secrets.token_hex(24)
        containers.append(docker(
            "run", "-d", "--rm", "-p", f"127.0.0.1:{db_port}:5432",
            "-e", "POSTGRES_DB=secureshare", "-e", "POSTGRES_USER=secureshare",
            "-e", f"POSTGRES_PASSWORD={password}", "postgres:17.6-alpine",
        ))
        containers.append(docker(
            "run", "-d", "--rm", "-p", f"127.0.0.1:{s3_port}:8333",
            "-e", f"AWS_ACCESS_KEY_ID={access}", "-e", f"AWS_SECRET_ACCESS_KEY={secret}",
            "-e", "S3_BUCKET=secureshare", "chrislusf/seaweedfs:4.52", "mini", "-dir=/data",
        ))
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            ready = subprocess.run(
                ["docker", "exec", containers[0], "pg_isready", "-U", "secureshare"],
                env=environment, capture_output=True,
            )
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("PostgreSQL did not start within 30 seconds")

        application_environment = environment | {
            "JDBC_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/secureshare",
            "DB_USER": "secureshare", "DB_PASSWORD": password,
            "AWS_ACCESS_KEY_ID": access, "AWS_SECRET_ACCESS_KEY": secret,
            "S3_BUCKET": "secureshare", "S3_ENDPOINT": f"http://127.0.0.1:{s3_port}",
            "SPRING_PROFILES_ACTIVE": "local", "SERVER_PORT": str(app_port),
            "SERVER_ADDRESS": "0.0.0.0", "LOGGING_STRUCTURED_FORMAT_CONSOLE": "ecs",
        }
        # Отдельная копия не зависит от параллельной пересборки исходного jar.
        jar = SCRATCH / "application.jar"
        shutil.copyfile(ROOT / "backend/build/libs/backend-0.1.0.jar", jar)
        log = (SCRATCH / "backend.log").open("w")
        application = subprocess.Popen(
            ["java", "-jar", str(jar)], env=application_environment,
            stdout=log, stderr=subprocess.STDOUT,
        )
        wait_for_app(application, f"http://127.0.0.1:{app_port}")
        result = browser_tests(containers, app_port)
        if result.returncode == 0:
            content = (SCRATCH / "backend.log").read_text()
            entries = [json.loads(line) for line in content.splitlines() if line.startswith("{")]
            events = [entry for entry in entries if "event" in entry]
            assert events, "No structured application events"
            assert all("requestId" in entry for entry in events), "Missing request correlation"
            for expected in ["auth.login.succeeded", "auth.logout.succeeded", "file.upload.succeeded", "file.download.succeeded", "security.request.rejected"]:
                assert any(entry["event"] == expected for entry in events), expected
            for private in [password, access, secret, "Browser correct passphrase", "План проекта.txt", "Заметки по встрече.md", "requirements.json", "jdbc:postgresql"]:
                assert private not in content, "Private data appeared in backend logs"
            print(f"Structured logs: {len(events)} correlated events, sensitive content absent")
        return result.returncode
    finally:
        if application:
            application.terminate()
            try:
                application.wait(timeout=15)
            except subprocess.TimeoutExpired:
                application.kill()
                application.wait()
        if log:
            log.close()
        for container in containers:
            subprocess.run(["docker", "rm", "-f", container], env=environment, capture_output=True)


if __name__ == "__main__":
    raise SystemExit(main())

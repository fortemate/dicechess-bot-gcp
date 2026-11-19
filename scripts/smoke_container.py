"""Build-independent checks for an already assembled runtime image."""
import hashlib
import hmac
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid

FIXTURE_KEY = "authored-container-smoke-fixture"
SMOKE_IMAGE = "dicechess-bot-gcp:ci"
HANDSHAKE = {"type": "verification", "nonce": "container-smoke"}


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def request(url, payload, *, method="POST", headers=None):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    query = urllib.request.Request(
        url, data=body if method == "POST" else None, method=method,
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    try:
        response = urllib.request.urlopen(query, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read()


def wait_ready(url, container):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        try:
            status, body = request(url, HANDSHAKE)
            assert status == 200 and json.loads(body) == {"nonce": "container-smoke"}
            return
        except OSError:
            assert docker("inspect", "--format={{.State.Running}}", container) == "true"
            time.sleep(0.1)
    raise TimeoutError("Container did not serve a valid handshake within 45 seconds")


def check_contracts(url):
    status, body = request(url, HANDSHAKE)
    assert status == 200 and json.loads(body) == {"nonce": "container-smoke"}
    for payload, method in [(b"{", "POST"), ({}, "POST"),
                            ({"type": "authored-unsupported"}, "POST"), ({}, "GET")]:
        assert request(url, payload, method=method)[0] == 400
    turn = {
        "type": "yourTurn", "gameId": "container-smoke", "seat": "White",
        "state": {
            "version": 1, "activeSeat": "White", "dicePending": True,
            "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
            "clocks": {"white": 800, "black": 800},
            "timeControl": {"Fischer": {"initialSeconds": 300, "incrementSeconds": 3}},
        },
    }
    raw = json.dumps(turn, separators=(",", ":")).encode()
    assert request(url, raw)[0] == 401
    timestamp = str(int(time.time()))
    signature = hmac.new(FIXTURE_KEY.encode(), timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest()
    status, body = request(url, raw, headers={
        "x-dicechess-timestamp": timestamp, "x-dicechess-signature": signature,
    })
    moves = json.loads(body).get("moves")
    assert status == 200 and isinstance(moves, list) and moves
    assert all(isinstance(move, str) for move in moves)


def main():
    name = "gcp-container-smoke-" + uuid.uuid4().hex[:12]
    container = None
    try:
        container = docker(
            "run", "--detach", "--name", name, "--cpus=2", "--memory=512m", "--read-only",
            "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m", "--env", "PORT=8080",
            "--env", "DICECHESS_WEBHOOK_SECRET=" + FIXTURE_KEY,
            "--publish", "127.0.0.1::8080", "--", SMOKE_IMAGE,
        )
        assert docker("exec", container, "id", "-u") == "10001"
        assert docker("exec", container, "id", "-g") == "10001"
        port = docker("port", container, "8080/tcp").rsplit(":", 1)[1]
        url = "http://127.0.0.1:" + port + "/api/webhook"
        wait_ready(url, container)
        check_contracts(url)
        print("Non-root image: handshake, malformed requests and signed clocked turn passed")
    finally:
        if container:
            try:
                print(docker("logs", container))
            finally:
                docker("rm", "--force", container)


if __name__ == "__main__":
    main()

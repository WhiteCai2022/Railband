"""Check deployed archive through an SSH tunnel without printing secrets."""
import http.client
import json
import pathlib
import re
import socket
import ssl
import sys
import urllib.parse
import uuid

IP = "62.234.140.204"


class TunnelHTTPS(http.client.HTTPSConnection):
    def __init__(self, port):
        super().__init__(IP, 443, timeout=20, context=ssl.create_default_context())
        self.local_port = port

    def connect(self):
        if self.local_port == 0:
            return super().connect()
        sock = socket.create_connection(("127.0.0.1", self.local_port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=IP)


def main():
    values = {}
    for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        if "：" in line:
            key, value = line.split("：", 1)
            values[key] = value
    port = int(sys.argv[2])
    payload = (pathlib.Path(__file__).resolve().parent / "fixtures/emulator-v05.jsonl").read_bytes()
    device = str(uuid.uuid4())

    def call(method, path, body=None, headers=None):
        conn = TunnelHTTPS(port)
        conn.request(method, path, body, headers or {})
        response = conn.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), data
        conn.close()
        return result

    token = values["APP 上传密钥"]
    status, _, body = call("POST", "/api/upload", payload, {
        "Authorization": "Bearer " + token,
        "X-Railband-Device": device,
        "Content-Type": "application/x-ndjson",
    })
    assert status == 201, (status, body[:200])
    item_id = json.loads(body)["id"]
    cookie = None
    try:
        status, headers, _ = call("POST", "/login", urllib.parse.urlencode({"password": values["网页管理密码"]}), {
            "Content-Type": "application/x-www-form-urlencoded",
        })
        assert status == 302, status
        cookie = headers["Set-Cookie"].split(";", 1)[0]
        status, _, html = call("GET", "/", headers={"Cookie": cookie})
        assert status == 200 and f"#{item_id}".encode() in html, status
        csrf = re.search(rb'name="csrf" value="([^"]+)"', html).group(1).decode()
        status, _, downloaded = call("GET", f"/download/{item_id}", headers={"Cookie": cookie})
        assert status == 200 and downloaded == payload, status
        status, _, _ = call("GET", f"/delete/{item_id}", headers={"Cookie": cookie})
        assert status == 200, status
        status, _, _ = call("POST", f"/delete/{item_id}", urllib.parse.urlencode({"csrf": csrf, "confirm": "delete"}), {
            "Content-Type": "application/x-www-form-urlencoded", "Cookie": cookie,
        })
        assert status == 302, status
        status, _, _ = call("GET", f"/download/{item_id}", headers={"Cookie": cookie})
        assert status == 404, status
        print("PASS: trusted HTTPS, authenticated upload, list, exact JSONL download, confirmed deletion")
    except Exception:
        print(f"Remote smoke failed; test measurement #{item_id} may need deletion")
        raise


if __name__ == "__main__":
    main()

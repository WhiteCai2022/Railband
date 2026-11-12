"""Create one-time credentials outside the Git checkout; do not print secrets."""
import hashlib
import pathlib
import secrets
import sys

target = pathlib.Path(sys.argv[1])
target.mkdir(parents=True, exist_ok=True)
password = secrets.token_urlsafe(20)
token = secrets.token_urlsafe(32)
session_key = secrets.token_hex(32)


def hashed(value):
    salt = secrets.token_bytes(16)
    return salt.hex() + ":" + hashlib.pbkdf2_hmac("sha256", value.encode(), salt, 310000).hex()


(target / "railband.env").write_text(
    "RAILBAND_SECRET_KEY=" + session_key + "\n"
    "RAILBAND_ADMIN_HASH=" + hashed(password) + "\n"
    "RAILBAND_UPLOAD_HASH=" + hashed(token) + "\n"
    "RAILBAND_DB=/var/lib/railband/archive.sqlite3\n",
    encoding="utf-8",
)
(target / "credentials.txt").write_text(
    "轨途测速数据管理\n"
    "管理网页：https://62.234.140.204/\n"
    "APP 上传地址：https://62.234.140.204/api/upload\n"
    "网页管理密码：" + password + "\n"
    "APP 上传密钥：" + token + "\n"
    "请妥善保存此文件；服务器只保存密钥哈希。\n",
    encoding="utf-8",
)
print(str(target / "credentials.txt"))

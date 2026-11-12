"""Authenticated archive for one-session RailBand JSONL exports."""
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
from datetime import datetime, timezone
from functools import wraps
from contextlib import contextmanager

from flask import Flask, Response, abort, flash, redirect, render_template, request, session, url_for

MAX_UPLOAD = 128 * 1024 * 1024
ALLOWED_TYPES = {"metadata", "sessions", "labels", "locations", "location_events", "radio_observations", "samples", "events"}


def hash_secret(value):
    salt = secrets.token_bytes(16)
    return salt.hex() + ":" + hashlib.pbkdf2_hmac("sha256", value.encode(), salt, 310000).hex()


def verify_secret(value, stored):
    try:
        salt, digest = stored.split(":", 1)
        actual = hashlib.pbkdf2_hmac("sha256", value.encode(), bytes.fromhex(salt), 310000)
        return hmac.compare_digest(actual, bytes.fromhex(digest))
    except (ValueError, AttributeError):
        return False


def parse_upload(data):
    if not data or len(data) > MAX_UPLOAD:
        raise ValueError("文件为空或超过 128 MB")
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise ValueError("文件不是 UTF-8") from exc
    if len(lines) > 250000:
        raise ValueError("记录数量过多")
    metadata = measurement = None
    sample_count = 0
    labels = set()
    referenced_sessions = []
    for index, line in enumerate(lines):
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"第 {index + 1} 行不是有效 JSON") from exc
        if not isinstance(row, dict) or row.get("type") not in ALLOWED_TYPES:
            raise ValueError(f"第 {index + 1} 行类型无效")
        kind = row["type"]
        if index == 0:
            if kind != "metadata" or row.get("schema_version") not in (1, 2, 3, 4):
                raise ValueError("缺少支持的元数据")
            metadata = row
            continue
        if kind == "metadata":
            raise ValueError("只能包含一次元数据")
        if kind == "sessions":
            if measurement is not None:
                raise ValueError("一次上传只能包含一次测量")
            measurement = row
        else:
            referenced_sessions.append(row.get("session_id"))
        if kind == "samples":
            sample_count += 1
            if row.get("label_at_receive") is not None:
                labels.add(str(row["label_at_receive"]))
        elif kind == "labels" and row.get("label") is not None:
            labels.add(str(row["label"]))
    if measurement is None or type(measurement.get("id")) is not int or measurement["id"] < 1:
        raise ValueError("缺少有效的测量记录")
    if metadata.get("export_session_id") != measurement["id"]:
        raise ValueError("只能上传单次测量导出")
    if measurement.get("status") == "running":
        raise ValueError("测量尚未结束")
    for key in ("started_utc_ms", "ended_utc_ms"):
        value = measurement.get(key)
        if value is not None and (type(value) is not int or value < 0):
            raise ValueError("测量时间无效")
    if any(source_id != measurement["id"] for source_id in referenced_sessions):
        raise ValueError("记录中包含其他测量的数据")
    config = measurement.get("config")
    try:
        config = json.loads(config) if isinstance(config, str) else config
    except json.JSONDecodeError:
        config = {}
    if not isinstance(config, dict):
        config = {}
    return {
        "source_session_id": measurement["id"],
        "started_utc_ms": measurement.get("started_utc_ms"),
        "ended_utc_ms": measurement.get("ended_utc_ms"),
        "status": str(measurement.get("status") or "unknown")[:32],
        "route": str(config.get("route") or "")[:200],
        "device": str(config.get("device") or "")[:100],
        "sample_count": sample_count,
        "labels": ", ".join(sorted(labels))[:500],
    }


def create_app(config=None):
    app = Flask(__name__, template_folder="templates")
    app.config.update(
        SECRET_KEY=os.environ.get("RAILBAND_SECRET_KEY"),
        ADMIN_HASH=os.environ.get("RAILBAND_ADMIN_HASH"),
        UPLOAD_HASH=os.environ.get("RAILBAND_UPLOAD_HASH"),
        DATABASE=os.environ.get("RAILBAND_DB", "/var/lib/railband/archive.sqlite3"),
        MAX_CONTENT_LENGTH=MAX_UPLOAD,
        SESSION_COOKIE_SECURE=True,
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
    )
    if config:
        app.config.update(config)
    if not all(app.config.get(key) for key in ("SECRET_KEY", "ADMIN_HASH", "UPLOAD_HASH")):
        raise RuntimeError("Missing server secrets")

    @contextmanager
    def connect():
        db = sqlite3.connect(app.config["DATABASE"], timeout=20)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=20000")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    with connect() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("""CREATE TABLE IF NOT EXISTS measurements (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device_id TEXT NOT NULL,
            source_session_id INTEGER NOT NULL,
            uploaded_utc_ms INTEGER NOT NULL,
            started_utc_ms INTEGER,
            ended_utc_ms INTEGER,
            status TEXT NOT NULL,
            route TEXT NOT NULL,
            device TEXT NOT NULL,
            sample_count INTEGER NOT NULL,
            labels TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            jsonl BLOB NOT NULL,
            UNIQUE(device_id, source_session_id))""")
        db.execute("CREATE INDEX IF NOT EXISTS measurements_date ON measurements(started_utc_ms DESC)")

    attempts = {}

    def admin_required(fn):
        @wraps(fn)
        def wrapped(*args, **kwargs):
            if not session.get("admin"):
                return redirect(url_for("login"))
            return fn(*args, **kwargs)
        return wrapped

    def csrf_ok():
        return bool(session.get("csrf")) and hmac.compare_digest(session["csrf"], request.form.get("csrf", ""))

    @app.after_request
    def headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        map_page = request.endpoint in {"measurement_detail", "journey_detail"}
        response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin" if map_page else "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; style-src-attr 'unsafe-inline'; "
            "img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; "
            "form-action 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"
            if map_page else "default-src 'self'; style-src 'self'; form-action 'self'; frame-ancestors 'none'")
        return response

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if request.method == "POST":
            ip = request.remote_addr or "unknown"
            now = time.monotonic()
            recent = [item for item in attempts.get(ip, []) if now - item < 900]
            if len(recent) >= 8:
                abort(429)
            if verify_secret(request.form.get("password", ""), app.config["ADMIN_HASH"]):
                attempts.pop(ip, None)
                session.clear()
                session["admin"] = True
                session["csrf"] = secrets.token_urlsafe(24)
                return redirect(url_for("index"))
            recent.append(now)
            attempts[ip] = recent
            flash("密码不正确")
        return render_template("login.html")

    @app.post("/logout")
    @admin_required
    def logout():
        if not csrf_ok():
            abort(403)
        session.clear()
        return redirect(url_for("login"))

    @app.get("/")
    @admin_required
    def index():
        page = max(1, min(request.args.get("page", 1, type=int), 1000000))
        with connect() as db:
            total = db.execute("SELECT COUNT(*) FROM measurements").fetchone()[0]
            rows = db.execute("SELECT id,device_id,source_session_id,uploaded_utc_ms,started_utc_ms,status,route,device,sample_count,labels FROM measurements ORDER BY id DESC LIMIT 30 OFFSET ?", ((page - 1) * 30,)).fetchall()
        return render_template("index.html", rows=rows, total=total, page=page,
                               csrf=session["csrf"], datetime=datetime, timezone=timezone)

    @app.get("/download/<int:item_id>")
    @admin_required
    def download(item_id):
        with connect() as db:
            row = db.execute("SELECT source_session_id,jsonl FROM measurements WHERE id=?", (item_id,)).fetchone()
        if row is None:
            abort(404)
        name = f"railband-{item_id}-session-{row['source_session_id']}.jsonl"
        return Response(row["jsonl"], content_type="application/x-ndjson; charset=utf-8",
                        headers={"Content-Disposition": f'attachment; filename="{name}"'})

    @app.get("/delete/<int:item_id>")
    @admin_required
    def confirm_delete(item_id):
        with connect() as db:
            row = db.execute("SELECT id,route,started_utc_ms FROM measurements WHERE id=?", (item_id,)).fetchone()
        if row is None:
            abort(404)
        return render_template("delete.html", row=row, csrf=session["csrf"])

    @app.post("/delete/<int:item_id>")
    @admin_required
    def delete(item_id):
        if not csrf_ok() or request.form.get("confirm") != "delete":
            abort(403)
        with connect() as db:
            count = db.execute("DELETE FROM measurements WHERE id=?", (item_id,)).rowcount
        flash("已删除记录" if count else "记录不存在")
        return redirect(url_for("index"))

    @app.post("/api/upload")
    def upload():
        authorization = request.headers.get("Authorization", "")
        if not authorization.startswith("Bearer ") or not verify_secret(authorization[7:], app.config["UPLOAD_HASH"]):
            abort(401)
        device_id = request.headers.get("X-Railband-Device", "")
        if len(device_id) != 36 or any(c not in "0123456789abcdef-" for c in device_id):
            abort(400)
        try:
            data = request.get_data(cache=False)
            meta = parse_upload(data)
        except ValueError as exc:
            return {"error": str(exc)}, 400
        digest = hashlib.sha256(data).hexdigest()
        try:
            with connect() as db:
                old = db.execute("SELECT id,sha256 FROM measurements WHERE device_id=? AND source_session_id=?",
                                 (device_id, meta["source_session_id"])).fetchone()
                if old:
                    if old["sha256"] != digest:
                        return {"error": "此设备测量编号已上传其他内容"}, 409
                    return {"id": old["id"], "status": "already_saved"}, 200
                cursor = db.execute("""INSERT INTO measurements
                    (device_id,source_session_id,uploaded_utc_ms,started_utc_ms,ended_utc_ms,status,route,device,sample_count,labels,sha256,jsonl)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (device_id, meta["source_session_id"], int(time.time() * 1000),
                     meta["started_utc_ms"], meta["ended_utc_ms"], meta["status"],
                     meta["route"], meta["device"], meta["sample_count"], meta["labels"], digest, data))
            return {"id": cursor.lastrowid, "status": "saved"}, 201
        except sqlite3.IntegrityError:
            # A concurrent retry may have inserted the same session after our lookup.
            with connect() as db:
                old = db.execute("SELECT id,sha256 FROM measurements WHERE device_id=? AND source_session_id=?",
                                 (device_id, meta["source_session_id"])).fetchone()
            if old and old["sha256"] == digest:
                return {"id": old["id"], "status": "already_saved"}, 200
            return {"error": "此设备测量编号已上传其他内容"}, 409

    from journeys import install
    install(app, connect, admin_required, csrf_ok, verify_secret)
    from detail_pages import install as install_details
    install_details(app, connect, admin_required)
    return app

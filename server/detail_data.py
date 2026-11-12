"""Small, defensive map projections of stored RailBand JSONL.

Only normalized fixes and measurements are spooled to a temporary SQLite file.
Raw iperf/events never reach the browser. Input is a one-pass iterable of byte
blocks; blocks may be chunks, entire exports, or arbitrary file read buffers.
Positions stay WGS84. Carries identify an existing fix, regardless of age; they
do not create another point. Route segments stop at missing chunk indices,
actual fix gaps over 120 seconds, or travel above 600 km/h plus accuracy margin.
Weighted direction averages use measured bytes/time, excluding offline nulls.
"""
import hashlib
import json
import math
import sqlite3
import tempfile

MAX_POINTS = 5000
MAX_LINE = 2 * 1024 * 1024
MAX_GAP_MS = 120000
MAX_SPEED_MPS = 600 / 3.6


def _number(value, minimum=None, maximum=None):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        value = float(value)
    except (ValueError, OverflowError):
        return None
    if not math.isfinite(value):
        return None
    if minimum is not None and value < minimum or maximum is not None and value > maximum:
        return None
    return value


def _time(value):
    value = _number(value, 0, 253402300799000)
    return int(value) if value is not None else None


def _identity(value):
    return str(value) if type(value) is int and 0 < value <= 9223372036854775807 else None


def _text(value, limit=200):
    return value[:limit] if isinstance(value, str) else ""


def _object(value):
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except (ValueError, RecursionError):
            pass
    return {}


def _rows(payloads, diagnostics):
    """No splitlines/list: at most one bounded JSON line is buffered."""
    pending = bytearray()
    discarding = False
    for block in payloads:
        if not isinstance(block, (bytes, bytearray, memoryview)):
            diagnostics["malformed_rows"] += 1
            continue
        if isinstance(block, memoryview):
            block = block.tobytes()
        offset = 0
        while offset < len(block):
            newline = block.find(b"\n", offset)
            end = len(block) if newline == -1 else newline
            size = end - offset
            if not discarding:
                if len(pending) + size > MAX_LINE:
                    pending.clear()
                    discarding = True
                    diagnostics["malformed_rows"] += 1
                else:
                    pending.extend(block[offset:end])
            offset = end + 1
            if newline == -1:
                break
            if not discarding and pending.strip():
                yield from _parse_line(bytes(pending), diagnostics)
            pending.clear()
            discarding = False
    if not discarding and pending.strip():
        yield from _parse_line(bytes(pending), diagnostics)


def _parse_line(line, diagnostics):
    try:
        row = json.loads(line)
        if isinstance(row, dict):
            yield row, hashlib.sha256(line).hexdigest()
        else:
            diagnostics["malformed_rows"] += 1
    except (ValueError, UnicodeError, RecursionError):
        diagnostics["malformed_rows"] += 1


def _network(row):
    value = row.get("network_type")
    if value not in ("cellular", "wifi", "vpn", "none", "other", "unknown"):
        return "unknown"
    return value


def _distance(a, b):
    lat1, lat2 = math.radians(a["lat"]), math.radians(b["lat"])
    delta_lat = lat2 - lat1
    delta_lon = math.radians(b["lon"] - a["lon"])
    v = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 6371008.8 * 2 * math.asin(math.sqrt(min(1.0, max(0.0, v))))


def _schema(db):
    db.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA cache_size=-2048;
        CREATE TABLE seen(kind TEXT, session TEXT, identity TEXT, PRIMARY KEY(kind,session,identity));
        CREATE TABLE samples(direction TEXT, bytes REAL, seconds REAL, bps REAL);
        CREATE TABLE intervals(identity TEXT PRIMARY KEY);
        CREATE TABLE labels(session TEXT, utc INTEGER, label TEXT);
        CREATE INDEX label_time ON labels(session,utc);
        CREATE TABLE fixes(identity TEXT PRIMARY KEY, session TEXT, grp INTEGER,
          fix_utc INTEGER, fix_elapsed INTEGER, observed_utc INTEGER,
          lat REAL, lon REAL, accuracy REAL, speed REAL, provider TEXT, mock INTEGER,
          priority INTEGER, ordinal INTEGER);
        CREATE TABLE contexts(fix TEXT, direction TEXT, utc INTEGER, age REAL,
          label TEXT, network TEXT, bps REAL, PRIMARY KEY(fix,direction));
        CREATE TABLE route(ordinal INTEGER PRIMARY KEY, segment INTEGER, pinned INTEGER, point TEXT);
    """)


def _fix(db, location, session, group, ordinal, priority, diagnostics, observed=None):
    lat = _number(location.get("latitude"), -90, 90)
    lon = _number(location.get("longitude"), -180, 180)
    if lat is None or lon is None:
        # Missing coordinates are normal before GPS is available.
        if location.get("latitude") is not None or location.get("longitude") is not None:
            diagnostics["invalid_coordinates"] += 1
            return None
        # Older schema-2/3 exports keep only location_id in the sample row.
        # The actual fix is in the separate locations table (possibly later).
        identifier = _identity(location.get("id"))
        return session + ":id:" + identifier if identifier and priority < 3 else None
    fix_utc = _time(location.get("fix_utc_ms"))
    fix_elapsed = _time(location.get("fix_elapsed_ms"))
    identifier = _identity(location.get("id"))
    if identifier:
        key = session + ":id:" + identifier
    else:
        key = session + ":fix:" + json.dumps([fix_elapsed, fix_utc, lat, lon, _text(location.get("provider"), 32)])
    accuracy = _number(location.get("accuracy_m"), 0, 1000000)
    speed = _number(location.get("speed_mps"), 0, 10000)
    mock = location.get("is_mock") is True or type(location.get("is_mock")) is int and location["is_mock"] == 1
    values = (key, session, group, fix_utc, fix_elapsed,
              _time(location.get("received_utc_ms")) or observed, lat, lon,
              accuracy, speed, _text(location.get("provider"), 32), int(mock), priority, ordinal)
    db.execute("""INSERT INTO fixes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(identity) DO UPDATE SET fix_utc=excluded.fix_utc,fix_elapsed=excluded.fix_elapsed,
        observed_utc=excluded.observed_utc,lat=excluded.lat,lon=excluded.lon,
        accuracy=excluded.accuracy,speed=excluded.speed,provider=excluded.provider,
        mock=excluded.mock,priority=excluded.priority
      WHERE excluded.priority > fixes.priority""", values)
    return key


def _flat_location(row):
    return {"id": row.get("location_id"), "latitude": row.get("latitude"),
            "longitude": row.get("longitude"), "fix_utc_ms": row.get("location_fix_utc_ms"),
            "fix_elapsed_ms": row.get("location_fix_elapsed_ms"),
            "accuracy_m": row.get("location_accuracy_m"), "provider": row.get("location_provider"),
            "is_mock": row.get("location_is_mock")}


def _context(db, key, direction, row, rate=None):
    if key is None:
        return
    observed = _time(row.get("received_utc_ms")) or _time(row.get("utc_ms"))
    age = _number(row.get("location_age_ms"), 0, 253402300799000)
    label = _text(row.get("label_at_receive") or row.get("label"))
    network = _network(row)
    db.execute("""INSERT INTO contexts VALUES(?,?,?,?,?,?,?)
      ON CONFLICT(fix,direction) DO UPDATE SET utc=excluded.utc,age=excluded.age,
        label=excluded.label,network=excluded.network,bps=excluded.bps
      WHERE COALESCE(excluded.age,1e30) < COALESCE(contexts.age,1e30)
        OR contexts.utc IS NULL""", (key, direction, observed, age, label, network, rate))


def _point(db, fix):
    contexts = list(db.execute("SELECT * FROM contexts WHERE fix=? ORDER BY COALESCE(age,1e30),utc", (fix["identity"],)))
    context = contexts[0] if contexts else None
    observed = context["utc"] if context else fix["observed_utc"]
    label = context["label"] if context else ""
    if not label:
        event = db.execute("SELECT label FROM labels WHERE session=? AND utc<=? ORDER BY utc DESC LIMIT 1",
                           (fix["session"], fix["fix_utc"] or observed)).fetchone()
        label = event[0] if event else ""
    rates = {row["direction"]: row["bps"] for row in contexts}
    return {"lat": fix["lat"], "lon": fix["lon"], "fix_utc_ms": fix["fix_utc"],
            "observed_utc_ms": observed, "label": label,
            "up_bps": rates.get("phone_upload"), "down_bps": rates.get("phone_download"),
            "network": context["network"] if context else "unknown",
            "accuracy_m": fix["accuracy"], "location_age_ms": context["age"] if context else None,
            "speed_mps": fix["speed"], "provider": fix["provider"], "is_mock": bool(fix["mock"])}


def _route(db, max_points):
    previous = previous_fix = None
    ordinal = segment = gap_count = 0
    distance = 0.0
    for fix in db.execute("SELECT * FROM fixes ORDER BY grp,session,COALESCE(fix_elapsed,fix_utc,ordinal),ordinal"):
        point = _point(db, fix)
        boundary = previous is None
        leg = 0.0
        if previous is not None:
            before = previous_fix["fix_elapsed"] if previous_fix["fix_elapsed"] is not None and fix["fix_elapsed"] is not None else previous_fix["fix_utc"]
            after = fix["fix_elapsed"] if previous_fix["fix_elapsed"] is not None and fix["fix_elapsed"] is not None else fix["fix_utc"]
            dt = after - before if before is not None and after is not None else None
            leg = _distance(previous, point)
            allowance = 200 + (previous["accuracy_m"] or 0) + (point["accuracy_m"] or 0)
            boundary = (fix["grp"] != previous_fix["grp"] or fix["session"] != previous_fix["session"]
                        or dt is not None and (dt > MAX_GAP_MS or dt < 0
                                               or leg > MAX_SPEED_MPS * dt / 1000 + allowance))
        if boundary:
            if ordinal:
                db.execute("UPDATE route SET pinned=1 WHERE ordinal=?", (ordinal,))
                gap_count += 1
            segment += 1
        else:
            distance += leg
        ordinal += 1
        changed_label = previous is not None and point["label"] != previous["label"]
        if changed_label:
            db.execute("UPDATE route SET pinned=1 WHERE ordinal=?", (ordinal - 1,))
        db.execute("INSERT INTO route VALUES(?,?,?,?)", (ordinal, segment, int(boundary or changed_label), json.dumps(point, allow_nan=False)))
        previous, previous_fix = point, fix
    if ordinal:
        db.execute("UPDATE route SET pinned=1 WHERE ordinal=?", (ordinal,))
    pins = db.execute("SELECT COUNT(*) FROM route WHERE pinned=1").fetchone()[0]
    budget = max_points - pins
    stride = max(1, math.ceil((ordinal - pins) / budget)) if budget > 0 else None
    # Extreme label/segment counts can exceed the entire display budget. Keep
    # global first/last fixes explicitly so the UI endpoints remain truthful.
    inner_budget = max(0, max_points - 2)
    pin_stride = max(1, math.ceil(max(0, pins - 2) / inner_budget)) if inner_budget else None
    segments, current, previous_segment = [], [], None
    nonpin_seen = pin_seen = kept = 0
    for row in db.execute("SELECT * FROM route ORDER BY ordinal"):
        if row["pinned"]:
            if pins <= max_points or row["ordinal"] == 1:
                use = True
            elif row["ordinal"] == ordinal:
                use = max_points >= 2
            else:
                pin_seen += 1
                use = pin_stride is not None and (pin_seen - 1) % pin_stride == 0
        else:
            nonpin_seen += 1
            use = stride is not None and (nonpin_seen - 1) % stride == 0
        if not use or kept >= max_points:
            continue
        if previous_segment != row["segment"]:
            if current:
                segments.append(current)
            current = []
            previous_segment = row["segment"]
        current.append(json.loads(row["point"]))
        kept += 1
    if current:
        segments.append(current)
    extent = db.execute("SELECT MIN(lat),MIN(lon),MAX(lat),MAX(lon),SUM(mock) FROM fixes").fetchone()
    return {"coordinate_system": "WGS84", "segments": segments, "fix_count": ordinal,
            "display_point_count": kept, "reduced": kept < ordinal,
            "bounds": list(extent[:4]) if ordinal else None, "gap_count": gap_count,
            "distance_m": distance, "mock_fix_count": extent[4] or 0,
            "preserved_all_boundaries_and_labels": pins <= max_points}


def build_detail(payloads, *, complete=True, max_points=MAX_POINTS):
    """Return summary/track/warnings; never serialize the original JSON rows.

    Input journey chunks must be in chunk-index order. Repeated ``sessions``
    snapshots use the last snapshot; rows dedupe by (type,session,id). Direction
    statistics also dedupe interval identity, so retry copies cannot inflate
    measured averages. Coordinate values must be explicit, finite numbers.
    """
    max_points = max(1, min(MAX_POINTS, int(max_points)))
    diagnostics = {"malformed_rows": 0, "invalid_coordinates": 0}
    measurement, metadata = {}, {}
    labels, networks = set(), set()
    group = ordinal = chunk_gaps = 0
    last_chunk = None
    with tempfile.TemporaryDirectory(prefix="railband-detail-") as temporary:
        db = sqlite3.connect(temporary + "/projection.sqlite3")
        db.row_factory = sqlite3.Row
        try:
            _schema(db)
            for row, digest in _rows(payloads, diagnostics):
                kind = row.get("type")
                if kind == "metadata":
                    metadata = row
                    index = row.get("chunk_index")
                    if type(index) is int and index >= 0:
                        if (last_chunk is None and index != 0) or (last_chunk is not None and index != last_chunk + 1):
                            group += 1
                            chunk_gaps += 1
                        last_chunk = index
                    continue
                if kind == "sessions":
                    measurement = row
                    continue
                if kind not in ("samples", "telemetry", "locations", "labels"):
                    continue
                session = _identity(row.get("session_id")) or "unknown"
                identifier = _identity(row.get("id")) or digest
                inserted = db.execute("INSERT OR IGNORE INTO seen VALUES(?,?,?)", (kind, session, identifier)).rowcount
                if not inserted:
                    continue
                ordinal += 1
                if kind == "labels":
                    label = _text(row.get("label"))
                    if label:
                        if len(labels) < 1000:
                            labels.add(label)
                        db.execute("INSERT INTO labels VALUES(?,?,?)", (session, _time(row.get("utc_ms")), label))
                    continue
                if kind == "locations":
                    _fix(db, row, session, group, ordinal, 3, diagnostics)
                    continue
                if kind == "telemetry":
                    raw = _object(row.get("raw"))
                    network = _object(raw.get("network"))
                    networks.add(_network(network))
                    location = _object(raw.get("location"))
                    if not location:
                        location = _flat_location(raw)
                    context = dict(raw, network_type=_network(network))
                    key = _fix(db, location, session, group, ordinal, 1, diagnostics, _time(raw.get("utc_ms")))
                    _context(db, key, "context", context)
                    continue
                networks.add(_network(row))
                label = _text(row.get("label_at_receive"))
                if label and len(labels) < 1000:
                    labels.add(label)
                direction = row.get("direction")
                if direction not in ("phone_upload", "phone_download"):
                    sender = row.get("sender")
                    direction = "phone_upload" if sender is True or type(sender) is int and sender == 1 else "phone_download" if sender is False or type(sender) is int and sender == 0 else "unknown"
                seconds = _number(row.get("seconds"), 0, 86400000)
                count_bytes = _number(row.get("bytes"), 0, 1e18)
                rate = _number(row.get("bits_per_second"), 0, 1e15)
                # Actual schema supplies seq and attempt. Fall back to native interval range.
                seq = _identity(row.get("seq"))
                interval = [session, _identity(row.get("attempt_id")), seq,
                            _number(row.get("start_s")), _number(row.get("end_s"))]
                if seq is None and interval[-2:] == [None, None]:
                    interval.append(identifier)
                interval_key = json.dumps(interval)
                if db.execute("INSERT OR IGNORE INTO intervals VALUES(?)", (interval_key + ":" + direction,)).rowcount:
                    db.execute("INSERT INTO samples VALUES(?,?,?,?)", (direction, count_bytes, seconds, rate))
                key = _fix(db, _flat_location(row), session, group, ordinal, 2, diagnostics, _time(row.get("received_utc_ms")))
                _context(db, key, direction, row, rate)
            config = _object(measurement.get("config"))
            started = _time(measurement.get("started_utc_ms"))
            ended = _time(measurement.get("ended_utc_ms"))
            duration = (ended - started) / 1000 if started is not None and ended is not None and ended >= started else None
            counts = dict(db.execute("SELECT kind,COUNT(*) FROM seen GROUP BY kind"))
            summary = {"route": _text(config.get("route")), "device": _text(config.get("device"), 100),
                       "status": _text(measurement.get("status"), 32) or "unknown",
                       "started_utc_ms": started, "ended_utc_ms": ended, "duration_s": duration,
                       "sample_count": counts.get("samples", 0), "telemetry_count": counts.get("telemetry", 0),
                       "interval_count": db.execute("SELECT COUNT(DISTINCT substr(identity,1,length(identity)-length(CASE WHEN identity LIKE '%:phone_upload' THEN ':phone_upload' WHEN identity LIKE '%:phone_download' THEN ':phone_download' ELSE ':unknown' END))) FROM intervals").fetchone()[0],
                       "labels": sorted(labels), "networks": sorted(networks),
                       "direction": _text(config.get("direction"), 32),
                       "sample_interval_s": _number(config.get("sample_interval_s"), 0, 86400),
                       "rate_cap_bps": _number(config.get("rate_cap_bps"), 0, 1e15),
                       "app_version": _text(config.get("app_version"), 32), "complete": bool(complete)}
            for direction, name in (("phone_upload", "upload"), ("phone_download", "download")):
                stats = db.execute("SELECT SUM(bytes),SUM(seconds),MAX(bps),COUNT(*) FROM samples WHERE direction=? AND bytes IS NOT NULL AND seconds>0", (direction,)).fetchone()
                byte_sum, seconds_sum = stats[0] or 0, stats[1] or 0
                summary[name] = {"bytes": byte_sum, "seconds": seconds_sum,
                                 "average_bps": 8 * byte_sum / seconds_sum if seconds_sum else None,
                                 "peak_bps": stats[2], "rows": stats[3]}
            track = _route(db, max_points)
            warnings = []
            if not complete:
                warnings.append("行程数据尚未完整接收，地图仅显示已上传的数据。")
            if chunk_gaps:
                warnings.append("数据块存在缺口，缺口两侧的轨迹已分段显示。")
            if track["gap_count"]:
                warnings.append("实际定位间隔超过 120 秒或位置跳变时，轨迹分段显示，连线不能代表真实铁路路线。")
            if not track["fix_count"]:
                warnings.append("这次测量没有可用的定位坐标，仍可下载原始数据。")
            if track["mock_fix_count"]:
                warnings.append("包含手机标记为模拟位置的记录，请勿将其作为真实行程定位。")
            if track["reduced"]:
                warnings.append("为保证长途行程浏览流畅，地图已抽稀；下载文件保留原始序列。")
            if not track["preserved_all_boundaries_and_labels"]:
                warnings.append("定位分段或标签过多，地图只显示部分端点与标签，请下载完整数据。")
            if diagnostics["malformed_rows"] or diagnostics["invalid_coordinates"]:
                warnings.append("部分记录或定位坐标无效，已跳过对应地图内容。")
            track.update(diagnostics)
            return {"summary": summary, "track": track, "warnings": warnings}
        finally:
            db.close()

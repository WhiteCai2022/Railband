"""Bounded, immutable journey chunks. Legacy measurement API remains supported."""
import hashlib
import json
import re
import time
from flask import Response, abort, flash, redirect, render_template, request, session, stream_with_context, url_for

MAX_CHUNK = 2 * 1024 * 1024
UUID = re.compile(r"^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$")
KINDS = {"sessions", "labels", "locations", "location_events", "radio_observations", "samples", "events", "telemetry", "attempt_events"}
TERMINAL = {"completed", "cancelled", "failed", "timeout", "interrupted"}


def parse_chunk(data):
    if not data or len(data) > MAX_CHUNK:
        raise ValueError("数据块为空或超过 2 MiB")
    try:
        rows = [json.loads(line) for line in data.decode("utf-8").splitlines()]
    except (ValueError, UnicodeError) as exc:
        raise ValueError("数据块不是有效 UTF-8 JSONL") from exc
    if len(rows) < 2 or len(rows) > 10000 or any(not isinstance(row, dict) for row in rows):
        raise ValueError("数据块行数或格式无效")
    meta, measurement = rows[:2]
    if meta.get("type") != "metadata" or meta.get("schema_version") != 5 or meta.get("format") != "railband_journey_chunk":
        raise ValueError("数据块版本无效")
    if not UUID.fullmatch(str(meta.get("journey_id", ""))):
        raise ValueError("行程编号无效")
    if type(meta.get("chunk_index")) is not int or not 0 <= meta["chunk_index"] <= 1000000:
        raise ValueError("数据块编号无效")
    if type(meta.get("is_final")) is not bool:
        raise ValueError("缺少最终块标记")
    if measurement.get("type") != "sessions" or type(measurement.get("id")) is not int or measurement["id"] < 1:
        raise ValueError("缺少行程记录")
    if measurement.get("journey_id") != meta["journey_id"] or measurement.get("status") not in TERMINAL | {"running"}:
        raise ValueError("行程状态无效")
    if meta["is_final"] and measurement["status"] not in TERMINAL:
        raise ValueError("最终块必须已结束")
    for key in ("started_utc_ms", "ended_utc_ms"):
        value = measurement.get(key)
        if value is not None and (type(value) is not int or not 0 <= value <= 253402300799000):
            raise ValueError("时间无效")
    keys = set()
    for row in rows[2:]:
        kind = row.get("type")
        if kind not in KINDS - {"sessions"} or row.get("session_id") != measurement["id"]:
            raise ValueError("包含其他行程或无效记录")
        if type(row.get("id")) is not int or row["id"] < 1 or (kind, row["id"]) in keys:
            raise ValueError("记录编号无效或重复")
        keys.add((kind, row["id"]))
    try:
        config = json.loads(measurement["config"])
    except (KeyError, TypeError, ValueError) as exc:
        raise ValueError("行程配置无效") from exc
    if not isinstance(config, dict) or config.get("journey_id") != meta["journey_id"]:
        raise ValueError("行程配置编号不一致")
    return meta, measurement, config, sum(row.get("type") == "samples" for row in rows)


def install(app, connect, admin_required, csrf_ok, verify_secret):
    with connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS journeys (
          id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, journey_id TEXT NOT NULL,
          source_session_id INTEGER NOT NULL, started_utc_ms INTEGER, uploaded_utc_ms INTEGER NOT NULL,
          route TEXT NOT NULL, device TEXT NOT NULL, config TEXT NOT NULL,
          final_index INTEGER, final_status TEXT, deleted INTEGER NOT NULL DEFAULT 0,
          UNIQUE(device_id,journey_id));
        CREATE TABLE IF NOT EXISTS journey_chunks (
          journey INTEGER NOT NULL, chunk_index INTEGER NOT NULL, sha256 TEXT NOT NULL,
          sample_count INTEGER NOT NULL, jsonl BLOB NOT NULL,
          PRIMARY KEY(journey,chunk_index));
        """)

    @app.post('/api/journeys/chunks')
    def upload_chunk():
        authorization = request.headers.get('Authorization', '')
        if not authorization.startswith('Bearer ') or not verify_secret(authorization[7:], app.config['UPLOAD_HASH']):
            abort(401)
        device = request.headers.get('X-Railband-Device', '')
        if not UUID.fullmatch(device):
            abort(400)
        if request.content_length is not None and request.content_length > MAX_CHUNK:
            abort(413)
        data = request.stream.read(MAX_CHUNK + 1)
        if len(data) > MAX_CHUNK:
            abort(413)
        try:
            meta, measurement, config, sample_count = parse_chunk(data)
        except ValueError as exc:
            return {'error': str(exc)}, 400
        digest = hashlib.sha256(data).hexdigest()
        index = meta['chunk_index']
        config_text = json.dumps(config, ensure_ascii=False, sort_keys=True)
        with connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('''INSERT OR IGNORE INTO journeys
                (device_id,journey_id,source_session_id,started_utc_ms,uploaded_utc_ms,route,device,config)
                VALUES(?,?,?,?,?,?,?,?)''', (device, meta['journey_id'], measurement['id'], measurement.get('started_utc_ms'),
                int(time.time()*1000), str(config.get('route') or '')[:200], str(config.get('device') or '')[:100], config_text))
            trip = db.execute('SELECT * FROM journeys WHERE device_id=? AND journey_id=?', (device, meta['journey_id'])).fetchone()
            if trip['deleted']:
                return {'error': '行程已在网页删除，停止补传'}, 410
            if trip['config'] != config_text or trip['source_session_id'] != measurement['id'] or trip['started_utc_ms'] != measurement.get('started_utc_ms'):
                return {'error': '行程配置冲突'}, 409
            old = db.execute('SELECT sha256 FROM journey_chunks WHERE journey=? AND chunk_index=?', (trip['id'], index)).fetchone()
            if old:
                if old['sha256'] != digest:
                    return {'error': '同一数据块包含不同内容'}, 409
                return {'id': trip['id'], 'chunk_index': index, 'sha256': digest, 'status': 'already_saved'}, 200
            if trip['final_index'] is not None and (index > trip['final_index'] or meta['is_final'] and index != trip['final_index']):
                return {'error': '最终块编号冲突'}, 409
            if meta['is_final']:
                largest = db.execute('SELECT MAX(chunk_index) FROM journey_chunks WHERE journey=?', (trip['id'],)).fetchone()[0]
                if largest is not None and largest > index:
                    return {'error': '最终块早于已有数据块'}, 409
                db.execute('UPDATE journeys SET final_index=?,final_status=? WHERE id=?', (index, measurement['status'], trip['id']))
            db.execute('INSERT INTO journey_chunks VALUES(?,?,?,?,?)', (trip['id'], index, digest, sample_count, data))
        return {'id': trip['id'], 'chunk_index': index, 'sha256': digest, 'status': 'saved'}, 201

    @app.get('/journeys')
    @admin_required
    def journey_index():
        page = max(1, min(request.args.get('page', 1, type=int), 1000000))
        with connect() as db:
            total = db.execute('SELECT COUNT(*) FROM journeys WHERE deleted=0').fetchone()[0]
            rows = db.execute('''SELECT j.*, COUNT(c.chunk_index) AS chunks, COALESCE(SUM(c.sample_count),0) AS samples
                FROM journeys j LEFT JOIN journey_chunks c ON c.journey=j.id WHERE j.deleted=0
                GROUP BY j.id ORDER BY j.id DESC LIMIT 30 OFFSET ?''', ((page-1)*30,)).fetchall()
        return render_template('journeys.html', rows=rows, page=page, total=total, csrf=session['csrf'])

    @app.get('/journeys/<int:item_id>/download')
    @admin_required
    def journey_download(item_id):
        # Freeze the list for this download. Later chunks appear on the next download.
        with connect() as db:
            trip = db.execute('SELECT * FROM journeys WHERE id=? AND deleted=0', (item_id,)).fetchone()
            if trip is None:
                abort(404)
            blocks = db.execute('SELECT chunk_index,sha256 FROM journey_chunks WHERE journey=? ORDER BY chunk_index', (item_id,)).fetchall()
        indices = [row['chunk_index'] for row in blocks]
        complete = trip['final_index'] is not None and len(indices) == trip['final_index']+1 and indices == list(range(len(indices)))
        manifest = {'type': 'metadata', 'schema_version': 5, 'format': 'railband_journey_download',
                    'journey_id': trip['journey_id'], 'complete': complete, 'final_index': trip['final_index'],
                    'chunks': [dict(row) for row in blocks],
                    'semantics': 'Following blocks retain their own metadata and sessions snapshots. Other rows are identified by (type,id). Gaps are missing data, not zero throughput.'}
        def generate():
            yield (json.dumps(manifest, ensure_ascii=False)+'\n').encode()
            for block in blocks:
                with connect() as db:
                    row = db.execute('SELECT jsonl FROM journey_chunks WHERE journey=? AND chunk_index=?', (item_id, block['chunk_index'])).fetchone()
                if row is None:
                    # Never silently claim a complete file after a concurrent deletion.
                    raise RuntimeError('Journey deleted during download; retry from the list')
                yield row['jsonl']
        return Response(stream_with_context(generate()), content_type='application/x-ndjson; charset=utf-8',
                        headers={'Content-Disposition': f'attachment; filename="railband-journey-{item_id}.jsonl"'})

    @app.route('/journeys/<int:item_id>/delete', methods=['GET', 'POST'])
    @admin_required
    def journey_delete(item_id):
        with connect() as db:
            row = db.execute('SELECT id,route FROM journeys WHERE id=? AND deleted=0', (item_id,)).fetchone()
            if row is None:
                abort(404)
            if request.method == 'POST':
                if not csrf_ok() or request.form.get('confirm') != 'delete':
                    abort(403)
                db.execute('DELETE FROM journey_chunks WHERE journey=?', (item_id,))
                # Keep only an opaque tombstone so an offline phone cannot resurrect deleted data.
                db.execute("UPDATE journeys SET deleted=1,route='',device='',config='',started_utc_ms=NULL WHERE id=?", (item_id,))
                flash('行程数据已删除，手机后续补传也会停止')
                return redirect(url_for('journey_index'))
        return render_template('delete.html', row=row, csrf=session['csrf'], journey=True)

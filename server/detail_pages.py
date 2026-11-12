"""Authenticated detail pages for both legacy sessions and chunked journeys."""
from flask import abort, jsonify, render_template, url_for

from detail_data import build_detail


def install(app, connect, admin_required):
    @app.get('/measurements/<int:item_id>')
    @admin_required
    def measurement_detail(item_id):
        with connect() as db:
            row = db.execute('SELECT id,route,device FROM measurements WHERE id=?', (item_id,)).fetchone()
        if row is None:
            abort(404)
        return render_template('detail.html', row=row, journey=False,
            data_url=url_for('measurement_detail_data', item_id=item_id),
            download_url=url_for('download', item_id=item_id),
            delete_url=url_for('confirm_delete', item_id=item_id))

    @app.get('/measurements/<int:item_id>/data')
    @admin_required
    def measurement_detail_data(item_id):
        with connect() as db:
            row = db.execute('SELECT id,route,device,jsonl FROM measurements WHERE id=?', (item_id,)).fetchone()
            if row is None:
                abort(404)
            detail = build_detail([row['jsonl']])
            detail['item'] = {'id': item_id, 'kind': 'measurement', 'route': row['route'],
                'device': row['device'], 'complete': True, 'received_chunks': None, 'expected_chunks': None,
                'download_url': url_for('download', item_id=item_id),
                'delete_url': url_for('confirm_delete', item_id=item_id)}
        return jsonify(detail)

    @app.get('/journeys/<int:item_id>')
    @admin_required
    def journey_detail(item_id):
        with connect() as db:
            row = db.execute('SELECT id,route,device FROM journeys WHERE id=? AND deleted=0', (item_id,)).fetchone()
        if row is None:
            abort(404)
        return render_template('detail.html', row=row, journey=True,
            data_url=url_for('journey_detail_data', item_id=item_id),
            download_url=url_for('journey_download', item_id=item_id),
            delete_url=url_for('journey_delete', item_id=item_id))

    @app.get('/journeys/<int:item_id>/data')
    @admin_required
    def journey_detail_data(item_id):
        with connect() as db:
            # One read snapshot keeps summary/completeness consistent during uploads or deletion.
            db.execute('BEGIN')
            row = db.execute('SELECT * FROM journeys WHERE id=? AND deleted=0', (item_id,)).fetchone()
            if row is None:
                abort(404)
            blocks = db.execute('SELECT chunk_index FROM journey_chunks WHERE journey=? ORDER BY chunk_index', (item_id,)).fetchall()
            indices = [block['chunk_index'] for block in blocks]
            expected = row['final_index'] + 1 if row['final_index'] is not None else None
            complete = expected is not None and len(indices) == expected and indices == list(range(expected))
            payloads = (block['jsonl'] for block in db.execute(
                'SELECT jsonl FROM journey_chunks WHERE journey=? ORDER BY chunk_index', (item_id,)))
            detail = build_detail(payloads, complete=complete)
            detail['item'] = {'id': item_id, 'kind': 'journey', 'route': row['route'],
                'device': row['device'], 'complete': complete, 'received_chunks': len(indices), 'expected_chunks': expected,
                'download_url': url_for('journey_download', item_id=item_id),
                'delete_url': url_for('journey_delete', item_id=item_id)}
        return jsonify(detail)

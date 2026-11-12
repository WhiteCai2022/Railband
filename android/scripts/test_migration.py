"""Check v1->v6 migration and the actual sample export join against user data."""
import json, pathlib, re, sqlite3, sys
root = pathlib.Path(__file__).resolve().parents[2]
source = (root / 'android/app/src/main/java/cn/railband/mvp/Store.java').read_text(encoding='utf-8-sig')
old = source.split('public void onCreate(SQLiteDatabase d) {',1)[1].split('private void addLocationSchema',1)[0]
upgrade = source.split('private void addLocationSchema(SQLiteDatabase d) {',1)[1].split('public void onUpgrade',1)[0]
sql = lambda text: re.findall(r'd\.execSQL\("([^"\n]+)"\)', text)
db = sqlite3.connect(':memory:')
for statement in sql(old): db.execute(statement)
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig').splitlines()]
for row in rows:
    kind = row['type']
    if kind not in ('sessions','samples','labels','events'): continue
    fields = {key:value for key,value in row.items() if key != 'type'}
    db.execute(f"INSERT INTO {kind} ({','.join(fields)}) VALUES ({','.join('?' for _ in fields)})", list(fields.values()))
before = {table:db.execute(f'SELECT * FROM {table} ORDER BY id').fetchall() for table in ('sessions','samples','labels','events')}
with db:
    for statement in sql(upgrade): db.execute(statement)
    journey = (root / "android/app/src/main/java/cn/railband/mvp/JourneyChunks.java").read_text(encoding="utf-8").split("static void schema",1)[1].split("static JSONObject row",1)[0]
    for statement in sql(journey): db.execute(statement)
for table, original in before.items():
    current = db.execute(f'SELECT * FROM {table} ORDER BY id').fetchall()
    if table == 'samples':
        width = len(original[0])
        assert [row[:width] for row in current] == original
        cols = [column[1] for column in db.execute('PRAGMA table_info(samples)')]
        for row in current:
            values = dict(zip(cols,row))
            assert values['location_id'] is None and values['location_age_ms'] is None
            assert values['direction'] == {1:'phone_upload',0:'phone_download'}.get(values['sender'])
            assert all(values[name] is None for name in ('radio_observation_id','radio_age_ms','network_type','vpn_active','transport_wifi','transport_cellular','underlying_hint'))
    else:
        width = len(original[0]) if original else 0
        assert [row[:width] for row in current] == original
        assert all(all(value is None for value in row[width:]) for row in current)
assert db.execute('SELECT count(*) FROM locations').fetchone()[0] == 0
assert db.execute('SELECT count(*) FROM location_events').fetchone()[0] == 0
assert db.execute('SELECT count(*) FROM radio_observations').fetchone()[0] == 0
assert db.execute('SELECT count(*) FROM uploaded_sessions').fetchone()[0] == 0
join = re.search(r'SAMPLE_JOIN="([^"]+)"', source).group(1) + ' ORDER BY samples.id ASC'
cursor = db.execute(join)
names = [column[0] for column in cursor.description]
assert all(row[names.index('latitude')] is None for row in cursor.fetchall())
db.execute("INSERT INTO locations(session_id,fix_utc_ms,fix_elapsed_ms,provider,latitude,longitude,accuracy_m,is_mock) VALUES (3,100000,5000,'gps',40.0,116.0,8.0,1)")
location_id = db.execute('SELECT last_insert_rowid()').fetchone()[0]
sample_id = db.execute('SELECT id FROM samples WHERE session_id=3 ORDER BY id LIMIT 1').fetchone()[0]
db.execute('UPDATE samples SET location_id=?, location_age_ms=1000 WHERE id=?',(location_id,sample_id))
exported = [dict(zip(names,row)) for row in db.execute(join)]
located = next(row for row in exported if row['id'] == sample_id)
assert located['latitude'] == 40.0 and located['longitude'] == 116.0
assert located['location_accuracy_m'] == 8.0 and located['location_is_mock'] == 1
assert sum(row['latitude'] is not None for row in exported) == 1
assert all(row['nr_ss_sinr_db'] is None and row['nr_pci'] is None and row['network_type'] is None for row in exported)

db.execute("INSERT INTO radio_observations(session_id,subscription_id,radio_rat,modem_elapsed_ms,nr_ss_sinr_db,nr_ss_rsrp_dbm,nr_pci,nr_signal_source,lte_rsrp_dbm,lte_pci) VALUES (3,1,'NR',7000,18,-92,317,'cell_info',-103,70)")
radio_id = db.execute('SELECT last_insert_rowid()').fetchone()[0]
db.execute("UPDATE samples SET radio_observation_id=?,radio_age_ms=1200,network_type='vpn',vpn_active=1,transport_wifi=1,transport_cellular=0,underlying_hint='wifi' WHERE id=?",(radio_id,sample_id))
exported = [dict(zip(names,row)) for row in db.execute(join)]
attached = next(row for row in exported if row['id'] == sample_id)
assert (attached['radio_rat'],attached['nr_ss_sinr_db'],attached['nr_ss_rsrp_dbm'],attached['nr_pci']) == ('NR',18,-92,317)
assert (attached['network_type'],attached['vpn_active'],attached['underlying_hint'],attached['radio_age_ms']) == ('vpn',1,'wifi',1200)
assert sum(row['nr_pci'] is not None for row in exported) == 1
db.execute('UPDATE radio_observations SET session_id=2 WHERE id=?',(radio_id,))
assert next(row for row in (dict(zip(names,row)) for row in db.execute(join)) if row['id']==sample_id)['nr_pci'] is None
print('PASS: v1->v6 preserves sessions and samples; upload state starts empty')
print('PASS: exported sample coordinates/accuracy/provider join to the correct independent fix; missing positions remain NULL')
print('PASS: NR SINR/RSRP/PCI and VPN labels join only to the correct session')

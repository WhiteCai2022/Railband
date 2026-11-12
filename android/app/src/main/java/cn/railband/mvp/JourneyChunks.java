package cn.railband.mvp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable bounded snapshots; only the short cursor/queue commit holds a write transaction. */
final class JourneyChunks {
    static final String[] TABLES={"labels","locations","location_events","radio_observations","samples","events","telemetry","attempt_events"};
    private static final int TARGET=512*1024;
    private static final Object SEAL_LOCK=new Object();
    static void schema(SQLiteDatabase d){
        d.execSQL("ALTER TABLE sessions ADD COLUMN journey_id TEXT");
        d.execSQL("ALTER TABLE samples ADD COLUMN attempt_id INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN journey_start_s REAL");
        d.execSQL("ALTER TABLE samples ADD COLUMN journey_end_s REAL");
        d.execSQL("ALTER TABLE events ADD COLUMN attempt_id INTEGER");
        d.execSQL("CREATE TABLE telemetry(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,raw TEXT NOT NULL)");
        d.execSQL("CREATE INDEX telemetry_session ON telemetry(session_id,id)");
        d.execSQL("CREATE TABLE attempt_events(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,raw TEXT NOT NULL)");
        d.execSQL("CREATE INDEX attempt_session ON attempt_events(session_id,id)");
        d.execSQL("CREATE INDEX event_session ON events(session_id,id)");
        d.execSQL("CREATE INDEX label_session ON labels(session_id,id)");
        d.execSQL("CREATE INDEX location_event_session ON location_events(session_id,id)");
        d.execSQL("CREATE TABLE journey_chunks(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,chunk_index INTEGER NOT NULL,created_utc_ms INTEGER NOT NULL,is_final INTEGER NOT NULL,payload BLOB NOT NULL,uploaded INTEGER NOT NULL DEFAULT 0,error TEXT,UNIQUE(session_id,chunk_index))");
        d.execSQL("CREATE TABLE journey_cursors(session_id INTEGER NOT NULL,table_name TEXT NOT NULL,last_id INTEGER NOT NULL,PRIMARY KEY(session_id,table_name))");
        d.execSQL("CREATE TABLE upload_failures(session_id INTEGER PRIMARY KEY,error TEXT NOT NULL)");
    }
    static JSONObject row(Cursor c)throws Exception{
        JSONObject row=new JSONObject();
        for(int i=0;i<c.getColumnCount();i++){
            Object value;switch(c.getType(i)){case Cursor.FIELD_TYPE_NULL:value=JSONObject.NULL;break;case Cursor.FIELD_TYPE_INTEGER:value=c.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:value=c.getDouble(i);break;default:value=c.getString(i);}
            row.put(c.getColumnName(i),value);
        }return row;
    }
    static int sealReady(Store store)throws Exception{
        synchronized(SEAL_LOCK){
            SQLiteDatabase db=store.getWritableDatabase();List<Long> ids=new ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE journey_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM journey_chunks WHERE session_id=sessions.id AND is_final=1) ORDER BY id",null)){while(c.moveToNext())ids.add(c.getLong(0));}
            int sealed=0;
            for(long id:ids){int limit=0;while(limit++<16&&sealOne(store,id)){sealed++;}}
            return sealed;
        }
    }
    private static boolean sealOne(Store store,long session)throws Exception{
        SQLiteDatabase db=store.getWritableDatabase();String[] args={Long.toString(session)};
        JSONObject snapshot;try(Cursor c=db.rawQuery("SELECT * FROM sessions WHERE id=?",args)){if(!c.moveToFirst())return false;snapshot=row(c);snapshot.put("type","sessions");}
        long lastSeal=0;int index=0;
        try(Cursor c=db.rawQuery("SELECT chunk_index,created_utc_ms,is_final FROM journey_chunks WHERE session_id=? ORDER BY chunk_index DESC LIMIT 1",args)){
            if(c.moveToFirst()){if(c.getInt(2)!=0)return false;index=c.getInt(0)+1;lastSeal=c.getLong(1);}
        }
        boolean ended=!"running".equals(snapshot.getString("status"));
        Map<String,Long> cursors=new LinkedHashMap<>();
        try(Cursor c=db.rawQuery("SELECT table_name,last_id FROM journey_cursors WHERE session_id=?",args)){while(c.moveToNext())cursors.put(c.getString(0),c.getLong(1));}
        ByteArrayOutputStream records=new ByteArrayOutputStream();boolean all=true;
        for(String table:TABLES){
            long after=cursors.containsKey(table)?cursors.get(table):0;
            int read=0;
            String sql=table.equals("samples")?Store.SAMPLE_JOIN+" WHERE samples.session_id=? AND samples.id>? ORDER BY samples.id LIMIT 1001":"SELECT * FROM "+table+" WHERE session_id=? AND id>? ORDER BY id LIMIT 1001";
            try(Cursor c=db.rawQuery(sql,new String[]{Long.toString(session),Long.toString(after)})){
                while(c.moveToNext()){
                    JSONObject record=row(c);record.put("type",table);byte[] line=(record.toString()+"\n").getBytes(StandardCharsets.UTF_8);
                    if(line.length>1024*1024)throw new IllegalStateException("单条记录超过 1 MiB，请导出本地日志");
                    if(records.size()>0&&records.size()+line.length>TARGET){all=false;break;}
                    records.write(line);cursors.put(table,record.getLong("id"));read++;
                }
            }
            if(!all||read==1001){all=false;break;}
        }
        long now=System.currentTimeMillis();
        if(!ended&&all&&(records.size()==0||now-(lastSeal==0?snapshot.getLong("started_utc_ms"):lastSeal)<60000))return false;
        JSONObject meta=new JSONObject();meta.put("type","metadata");meta.put("schema_version",5);meta.put("format","railband_journey_chunk");meta.put("journey_id",snapshot.getString("journey_id"));meta.put("chunk_index",index);meta.put("is_final",ended&&all);
        meta.put("semantics","Session rows are snapshots; all other rows are append-only with (type,id) identity. Sample interval times are per attempt; journey_start_s/end_s align reconnects. Telemetry is an independent clock, missing throughput is null; carried positions retain original fix time.");
        ByteArrayOutputStream payload=new ByteArrayOutputStream();payload.write((meta.toString()+"\n"+snapshot.toString()+"\n").getBytes(StandardCharsets.UTF_8));records.writeTo(payload);
        db.beginTransaction();
        try{
            try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE id=?",args)){if(!c.moveToFirst())return false;}
            ContentValues chunk=new ContentValues();chunk.put("session_id",session);chunk.put("chunk_index",index);chunk.put("created_utc_ms",now);chunk.put("is_final",ended&&all?1:0);chunk.put("payload",payload.toByteArray());db.insertOrThrow("journey_chunks",null,chunk);
            for(Map.Entry<String,Long> entry:cursors.entrySet()){ContentValues v=new ContentValues();v.put("session_id",session);v.put("table_name",entry.getKey());v.put("last_id",entry.getValue());db.insertWithOnConflict("journey_cursors",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return true;
    }
}

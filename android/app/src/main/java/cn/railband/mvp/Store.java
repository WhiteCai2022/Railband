package cn.railband.mvp;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.SystemClock;
import android.location.Location;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Raw events and label timestamps are retained for later re-labelling. */
public final class Store extends SQLiteOpenHelper {
    static final String SAMPLE_JOIN="SELECT samples.*, locations.latitude, locations.longitude, locations.accuracy_m AS location_accuracy_m, locations.fix_utc_ms AS location_fix_utc_ms, locations.fix_elapsed_ms AS location_fix_elapsed_ms, locations.provider AS location_provider, locations.is_mock AS location_is_mock, radio_observations.radio_rat, radio_observations.subscription_id AS radio_subscription_id, radio_observations.modem_elapsed_ms AS radio_modem_elapsed_ms, radio_observations.nr_ss_sinr_db, radio_observations.nr_ss_rsrp_dbm, radio_observations.nr_csi_sinr_db, radio_observations.nr_csi_rsrp_dbm, radio_observations.nr_pci, radio_observations.nr_registered, radio_observations.nr_modem_elapsed_ms, radio_observations.nr_signal_source, radio_observations.lte_rsrp_dbm, radio_observations.lte_rssnr_db, radio_observations.lte_pci, radio_observations.lte_registered, radio_observations.lte_modem_elapsed_ms, radio_observations.lte_signal_source FROM samples LEFT JOIN locations ON locations.id=samples.location_id AND locations.session_id=samples.session_id LEFT JOIN radio_observations ON radio_observations.id=samples.radio_observation_id AND radio_observations.session_id=samples.session_id";
    private static Store instance;
    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }
    private long currentSessionId,lastLocationId,lastLocationFixMs,journeyStarted,attemptStarted;
    private int currentAttempt;
    private Store(Context c) { super(c, "railband.db", null, 6); setWriteAheadLoggingEnabled(true); }
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, started_utc_ms INTEGER, started_elapsed_ms INTEGER, ended_utc_ms INTEGER, status TEXT, config TEXT, error TEXT)");
        d.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, received_utc_ms INTEGER, received_elapsed_ms INTEGER, raw TEXT)");
        d.execSQL("CREATE TABLE labels(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, utc_ms INTEGER, elapsed_ms INTEGER, label TEXT)");
        d.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, seq INTEGER, start_s REAL, end_s REAL, seconds REAL, bytes INTEGER, bits_per_second REAL, sender INTEGER, received_utc_ms INTEGER, received_elapsed_ms INTEGER, label_at_receive TEXT, raw TEXT)");
        d.execSQL("CREATE INDEX sample_session ON samples(session_id)");
        addLocationSchema(d);
        addDirectionSchema(d);
        addRadioSchema(d);
        addUploadSchema(d);
        JourneyChunks.schema(d);
    }
    private void addLocationSchema(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE locations(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, fix_utc_ms INTEGER, fix_elapsed_ms INTEGER, received_utc_ms INTEGER, received_elapsed_ms INTEGER, provider TEXT, latitude REAL, longitude REAL, accuracy_m REAL, altitude_m REAL, speed_mps REAL, bearing_deg REAL, is_mock INTEGER)");
        d.execSQL("CREATE INDEX location_session ON locations(session_id)");
        d.execSQL("CREATE TABLE location_events(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, utc_ms INTEGER, elapsed_ms INTEGER, status TEXT)");
        d.execSQL("ALTER TABLE samples ADD COLUMN location_id INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN location_age_ms INTEGER");
    }
    private void addDirectionSchema(SQLiteDatabase d){
        d.execSQL("ALTER TABLE samples ADD COLUMN direction TEXT");
        d.execSQL("UPDATE samples SET direction=CASE sender WHEN 1 THEN 'phone_upload' WHEN 0 THEN 'phone_download' ELSE NULL END");
    }
    private void addRadioSchema(SQLiteDatabase d){
        d.execSQL("CREATE TABLE radio_observations(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER, subscription_id INTEGER, radio_rat TEXT, modem_elapsed_ms INTEGER, received_utc_ms INTEGER, received_elapsed_ms INTEGER, nr_ss_sinr_db INTEGER, nr_ss_rsrp_dbm INTEGER, nr_csi_sinr_db INTEGER, nr_csi_rsrp_dbm INTEGER, nr_pci INTEGER, nr_registered INTEGER, nr_connection_status INTEGER, nr_modem_elapsed_ms INTEGER, nr_signal_source TEXT, lte_rsrp_dbm INTEGER, lte_rssnr_db INTEGER, lte_pci INTEGER, lte_registered INTEGER, lte_connection_status INTEGER, lte_modem_elapsed_ms INTEGER, lte_signal_source TEXT, source TEXT, raw TEXT)");
        d.execSQL("CREATE INDEX radio_session ON radio_observations(session_id)");
        d.execSQL("ALTER TABLE samples ADD COLUMN radio_observation_id INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN radio_age_ms INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN network_type TEXT");
        d.execSQL("ALTER TABLE samples ADD COLUMN vpn_active INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN transport_wifi INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN transport_cellular INTEGER");
        d.execSQL("ALTER TABLE samples ADD COLUMN underlying_hint TEXT");
    }
    private void addUploadSchema(SQLiteDatabase d){d.execSQL("CREATE TABLE uploaded_sessions(session_id INTEGER PRIMARY KEY, uploaded_utc_ms INTEGER NOT NULL, server_id INTEGER)");}
    public void onUpgrade(SQLiteDatabase d,int a,int b) {if(b!=6||a<1||a>5)throw new IllegalStateException("Unsupported database upgrade");if(a<2)addLocationSchema(d);if(a<3)addDirectionSchema(d);if(a<4)addRadioSchema(d);if(a<5)addUploadSchema(d);if(a<6)JourneyChunks.schema(d);}
    public synchronized long radio(long session,JSONObject observation){
        ContentValues v=new ContentValues();v.put("session_id",session);v.put("received_utc_ms",System.currentTimeMillis());v.put("received_elapsed_ms",SystemClock.elapsedRealtime());
        for(String key:new String[]{"subscription_id","radio_rat","modem_elapsed_ms","nr_ss_sinr_db","nr_ss_rsrp_dbm","nr_csi_sinr_db","nr_csi_rsrp_dbm","nr_pci","nr_registered","nr_connection_status","nr_modem_elapsed_ms","nr_signal_source","lte_rsrp_dbm","lte_rssnr_db","lte_pci","lte_registered","lte_connection_status","lte_modem_elapsed_ms","lte_signal_source","source"}){
            if(!observation.has(key)||observation.isNull(key))continue;
            Object value=observation.opt(key);if(value instanceof Boolean)v.put(key,(Boolean)value?1:0);else if(value instanceof Number)v.put(key,((Number)value).longValue());else v.put(key,value.toString());
        }
        v.put("raw",observation.toString());return getWritableDatabase().insertOrThrow("radio_observations",null,v);
    }
    public synchronized void locationStatus(long session,String status){ContentValues v=new ContentValues();v.put("session_id",session);v.put("utc_ms",System.currentTimeMillis());v.put("elapsed_ms",SystemClock.elapsedRealtime());v.put("status",status);getWritableDatabase().insertOrThrow("location_events",null,v);}
    public synchronized void location(long session,Location fix){
        long fixMs=fix.getElapsedRealtimeNanos()/1000000;
        long receivedMs=SystemClock.elapsedRealtime();
        if(!TestOptions.usableLocation(receivedMs,fixMs)||!Double.isFinite(fix.getLatitude())||!Double.isFinite(fix.getLongitude())||Math.abs(fix.getLatitude())>90||Math.abs(fix.getLongitude())>180)return;
        ContentValues v=new ContentValues();v.put("session_id",session);v.put("fix_utc_ms",fix.getTime());v.put("fix_elapsed_ms",fixMs);v.put("received_utc_ms",System.currentTimeMillis());v.put("received_elapsed_ms",receivedMs);v.put("provider",fix.getProvider());v.put("latitude",fix.getLatitude());v.put("longitude",fix.getLongitude());
        if(fix.hasAccuracy())v.put("accuracy_m",fix.getAccuracy());if(fix.hasAltitude())v.put("altitude_m",fix.getAltitude());if(fix.hasSpeed())v.put("speed_mps",fix.getSpeed());if(fix.hasBearing())v.put("bearing_deg",fix.getBearing());v.put("is_mock",fix.isFromMockProvider()?1:0);
        long id=getWritableDatabase().insertOrThrow("locations",null,v);if(session==currentSessionId&&fixMs>=lastLocationFixMs){lastLocationId=id;lastLocationFixMs=fixMs;}
    }
    public synchronized void recover() {
        ContentValues v=new ContentValues(); v.put("status","interrupted"); v.put("ended_utc_ms",System.currentTimeMillis()); v.put("error","应用进程中断；已有记录保留，缺失时间不补零");
        getWritableDatabase().update("sessions",v,"status = ?",new String[]{"running"});
    }
    public synchronized long start(JSONObject config,String label) {
        lastLocationId=lastLocationFixMs=0;currentAttempt=0;journeyStarted=SystemClock.elapsedRealtime();attemptStarted=journeyStarted;
        ContentValues v=new ContentValues(); v.put("started_utc_ms",System.currentTimeMillis()); v.put("started_elapsed_ms",SystemClock.elapsedRealtime()); v.put("status","running");v.put("config",config.toString());if(config.has("journey_id"))v.put("journey_id",config.optString("journey_id"));
        long id=getWritableDatabase().insertOrThrow("sessions",null,v);currentSessionId=id;label(id,label);return id;
    }
    public synchronized void label(long id,String label) {
        ContentValues v=new ContentValues();v.put("session_id",id);v.put("utc_ms",System.currentTimeMillis());v.put("elapsed_ms",SystemClock.elapsedRealtime());v.put("label",label); getWritableDatabase().insertOrThrow("labels",null,v);
    }
    public synchronized void event(long id,String raw) {
        ContentValues v=new ContentValues();v.put("session_id",id);v.put("received_utc_ms",System.currentTimeMillis());v.put("received_elapsed_ms",SystemClock.elapsedRealtime());v.put("raw",raw);v.put("attempt_id",currentAttempt); getWritableDatabase().insertOrThrow("events",null,v);
    }
    public synchronized double sample(long id,int seq,JSONObject data,String label) throws JSONException {
        return sample(id,seq,data,"sum",label);
    }
    public synchronized double sample(long id,int seq,JSONObject data,String sumKey,String label) throws JSONException {
        return sample(id,seq,data,sumKey,label,null,0,-1);
    }
    public synchronized double sample(long id,int seq,JSONObject data,String sumKey,String label,JSONObject network,long radioId,long radioAgeMs) throws JSONException {
        JSONObject s=data.getJSONObject(sumKey);
        long now=SystemClock.elapsedRealtime();ContentValues v=new ContentValues();v.put("session_id",id);v.put("seq",seq);v.put("start_s",s.getDouble("start"));v.put("end_s",s.getDouble("end"));v.put("seconds",s.getDouble("seconds"));v.put("bytes",s.getLong("bytes"));v.put("bits_per_second",s.getDouble("bits_per_second"));v.put("sender",s.getBoolean("sender")?1:0);v.put("direction",s.getBoolean("sender")?"phone_upload":"phone_download");v.put("received_utc_ms",System.currentTimeMillis());v.put("received_elapsed_ms",now);v.put("label_at_receive",label);v.put("raw",data.toString());if(id==currentSessionId&&lastLocationId>0&&TestOptions.usableLocation(now,lastLocationFixMs)){v.put("location_id",lastLocationId);v.put("location_age_ms",now-lastLocationFixMs);}
        if(radioId>0){v.put("radio_observation_id",radioId);if(radioAgeMs>=0)v.put("radio_age_ms",radioAgeMs);}
        if(network!=null){v.put("network_type",network.optString("network_type","unknown"));if(!network.isNull("vpn_active")&&network.has("vpn_active"))v.put("vpn_active",network.optBoolean("vpn_active")?1:0);if(!network.isNull("transport_wifi")&&network.has("transport_wifi"))v.put("transport_wifi",network.optBoolean("transport_wifi")?1:0);if(!network.isNull("transport_cellular")&&network.has("transport_cellular"))v.put("transport_cellular",network.optBoolean("transport_cellular")?1:0);v.put("underlying_hint",network.optString("underlying_hint","unknown"));}
        if(currentAttempt>0){v.put("attempt_id",currentAttempt);v.put("journey_start_s",(attemptStarted-journeyStarted)/1000.0+s.getDouble("start"));v.put("journey_end_s",(attemptStarted-journeyStarted)/1000.0+s.getDouble("end"));}
        getWritableDatabase().insertOrThrow("samples",null,v);return s.getDouble("bits_per_second")/1000000.0;
    }
    public synchronized void finish(long id,String status,String error) {
        ContentValues v=new ContentValues();v.put("ended_utc_ms",System.currentTimeMillis());v.put("status",status);v.put("error",error);getWritableDatabase().update("sessions",v,"id=?",new String[]{Long.toString(id)});
    }
    public synchronized void attempt(int attempt,long anchor){currentAttempt=attempt;attemptStarted=anchor;}
    public synchronized void journeyEvent(long id,int attempt,String state,String detail){
        try{JSONObject row=new JSONObject();row.put("attempt_id",attempt);row.put("state",state);row.put("detail",detail);row.put("utc_ms",System.currentTimeMillis());row.put("elapsed_ms",SystemClock.elapsedRealtime());appendRaw("attempt_events",id,row);}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    private void appendRaw(String table,long id,JSONObject row){ContentValues v=new ContentValues();v.put("session_id",id);v.put("raw",row.toString());getWritableDatabase().insertOrThrow(table,null,v);}
    public synchronized void telemetry(long id,int attempt,String state,JSONObject network,long radioId,long radioAge,String label)throws Exception{
        long now=SystemClock.elapsedRealtime();JSONObject row=new JSONObject();row.put("utc_ms",System.currentTimeMillis());row.put("elapsed_ms",now);row.put("journey_elapsed_s",(now-journeyStarted)/1000.0);row.put("attempt_id",attempt);row.put("measurement_state",state);row.put("label",label);row.put("network",network);row.put("throughput_bps",JSONObject.NULL);
        row.put("location",JSONObject.NULL);row.put("latitude",JSONObject.NULL);row.put("longitude",JSONObject.NULL);row.put("location_age_ms",JSONObject.NULL);row.put("location_state","unavailable");
        if(id==currentSessionId&&lastLocationId>0&&TestOptions.usableLocation(now,lastLocationFixMs)){
            try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM locations WHERE id=? AND session_id=?",new String[]{Long.toString(lastLocationId),Long.toString(id)})){
                if(c.moveToFirst()){JSONObject fix=JourneyChunks.row(c);row.put("location",fix);row.put("latitude",fix.get("latitude"));row.put("longitude",fix.get("longitude"));row.put("location_age_ms",now-lastLocationFixMs);row.put("location_state",now-lastLocationFixMs<=10000?"fresh":"carried");}
            }
        }
        row.put("radio",JSONObject.NULL);row.put("radio_age_ms",radioAge<0?JSONObject.NULL:radioAge);
        if(radioId>0)try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM radio_observations WHERE id=? AND session_id=?",new String[]{Long.toString(radioId),Long.toString(id)})){if(c.moveToFirst())row.put("radio",JourneyChunks.row(c));}
        appendRaw("telemetry",id,row);
    }
    private boolean hasJourney(Long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM sessions WHERE journey_id IS NOT NULL"+(id==null?"":" AND id=?")+" LIMIT 1",id==null?null:new String[]{id.toString()})){return c.moveToFirst();}}
    public long[] pendingChunks(){java.util.ArrayList<Long> ids=new java.util.ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM journey_chunks WHERE uploaded=0 AND error IS NULL ORDER BY id LIMIT 50",null)){while(c.moveToNext())ids.add(c.getLong(0));}long[] result=new long[ids.size()];for(int i=0;i<result.length;i++)result[i]=ids.get(i);return result;}
    public byte[] chunk(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM journey_chunks WHERE id=?",new String[]{Long.toString(id)})){return c.moveToFirst()?c.getBlob(0):null;}}
    public void chunkDone(long id){ContentValues v=new ContentValues();v.put("uploaded",1);v.putNull("error");getWritableDatabase().update("journey_chunks",v,"id=?",new String[]{Long.toString(id)});}
    public void chunkFailed(long id,String error){ContentValues v=new ContentValues();v.put("error",error);getWritableDatabase().update("journey_chunks",v,"id=?",new String[]{Long.toString(id)});}
    public void legacyFailed(long id,String error){ContentValues v=new ContentValues();v.put("session_id",id);v.put("error",error);getWritableDatabase().insertWithOnConflict("upload_failures",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public void retryFailures(){getWritableDatabase().execSQL("UPDATE journey_chunks SET error=NULL WHERE uploaded=0");getWritableDatabase().delete("upload_failures",null,null);}
    public int chunkCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM journey_chunks WHERE uploaded=0 AND error IS NULL",null)){c.moveToFirst();return c.getInt(0);}}
    public int uploadErrorCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT (SELECT COUNT(*) FROM journey_chunks WHERE error IS NOT NULL)+(SELECT COUNT(*) FROM upload_failures)",null)){c.moveToFirst();return c.getInt(0);}}
    public synchronized String history() {
        StringBuilder s=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,status,(SELECT count(*) FROM samples WHERE session_id=sessions.id),error FROM sessions ORDER BY id DESC LIMIT 8",null)) {
            while(c.moveToNext()) s.append("#").append(c.getLong(0)).append("  ").append(statusLabel(c.getString(1))).append(" · ").append(c.getInt(2)).append(" 条\n").append(c.isNull(3)||c.getString(3).isEmpty()?"":c.getString(3)+"\n");
        }return s.length()==0?"暂无记录，开始第一次测量吧。":s.toString();
    }
    private String statusLabel(String status) {
        switch(status) {case "running":return "测量中";case "completed":return "已完成";case "cancelled":return "已停止";case "timeout":return "超时";case "interrupted":return "进程中断";default:return "失败";}
    }
    public void export(OutputStream output) throws Exception {
        export(output,null);
    }
    public synchronized long latestSessionId(){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM sessions ORDER BY id DESC LIMIT 1",null)){return c.moveToFirst()?c.getLong(0):0;}}
    public synchronized long[] pendingUploads(){
        java.util.ArrayList<Long> ids=new java.util.ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT sessions.id FROM sessions LEFT JOIN uploaded_sessions ON uploaded_sessions.session_id=sessions.id WHERE sessions.status!='running' AND sessions.journey_id IS NULL AND uploaded_sessions.session_id IS NULL AND NOT EXISTS(SELECT 1 FROM upload_failures WHERE session_id=sessions.id) ORDER BY sessions.id ASC LIMIT 25",null)){while(c.moveToNext())ids.add(c.getLong(0));}
        long[] result=new long[ids.size()];for(int i=0;i<ids.size();i++)result[i]=ids.get(i);return result;
    }
    public synchronized int pendingUploadCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM sessions LEFT JOIN uploaded_sessions ON uploaded_sessions.session_id=sessions.id WHERE sessions.status!='running' AND sessions.journey_id IS NULL AND uploaded_sessions.session_id IS NULL AND NOT EXISTS(SELECT 1 FROM upload_failures WHERE session_id=sessions.id)",null)){return c.moveToFirst()?c.getInt(0):0;}}
    public synchronized void markUploaded(long sessionId,long serverId){ContentValues v=new ContentValues();v.put("session_id",sessionId);v.put("uploaded_utc_ms",System.currentTimeMillis());v.put("server_id",serverId);getWritableDatabase().insertWithOnConflict("uploaded_sessions",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public synchronized int deleteLogs(Long sessionId){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            try(Cursor c=db.rawQuery("SELECT 1 FROM sessions WHERE status='running' LIMIT 1",null)){if(c.moveToFirst())throw new IllegalStateException("请先停止测量，再删除日志");}
            String[] args=sessionId==null?null:new String[]{Long.toString(sessionId)};
            for(String table:new String[]{"samples","events","labels","locations","location_events","radio_observations","uploaded_sessions","telemetry","attempt_events","journey_chunks","journey_cursors","upload_failures"})db.delete(table,sessionId==null?null:"session_id=?",args);
            int deleted=db.delete("sessions",sessionId==null?null:"id=?",args);db.setTransactionSuccessful();return deleted;
        }finally{db.endTransaction();}
    }
    public void export(OutputStream output,Long sessionId) throws Exception {
        // Only finished sessions are exported by upload; UI disables manual export during measurement.
        try(Writer w=new OutputStreamWriter(output,StandardCharsets.UTF_8)) {
            JSONObject metadata=new JSONObject("{\"type\":\"metadata\",\"schema_version\":4,\"rate_unit\":\"bits/s\",\"clock\":\"UTC epoch ms and Android elapsedRealtime ms\",\"label_semantics\":\"label_at_receive is only a receive-time snapshot; use labels event timeline for interval annotation\",\"missing_intervals\":\"not zero-filled\",\"mode\":\"TCP; rate_cap_bps=0 means unlimited, duration_s=0 means until stopped; bidirectional rate cap applies to each direction\",\"sample_semantics\":\"One row per direction per interval; direction explicitly identifies phone_upload or phone_download; sender is the phone role\",\"location_semantics\":\"WGS84; from app 0.4 onward each sample carries the latest valid fix received in the same measurement with no age cutoff; original fix timestamps and location_age_ms are retained; null until a valid fix is received, never filled from another session or a future fix; older records keep their original associations; not interval-wide ground truth\",\"radio_semantics\":\"NR SS-SINR and SS-RSRP are separate from LTE RSSNR and RSRP; serving cell observations are asynchronous and may be stale; radio_age_ms is sample time minus modem timestamp; null means unavailable; Wi-Fi or VPN speed is not attributed to the cellular radio\",\"network_semantics\":\"network_type is the Android default network observed when iperf reported the interval; vpn takes precedence; underlying_hint is not a confirmed VPN route\"}");
            boolean journey=hasJourney(sessionId);if(journey){metadata.put("schema_version",5);metadata.put("format","railband_local_journey");}
            metadata.put("export_session_id",sessionId==null?JSONObject.NULL:sessionId);w.write(metadata.toString());w.write('\n');
            for(String table:journey?new String[]{"sessions","labels","locations","location_events","radio_observations","samples","events","telemetry","attempt_events"}:new String[]{"sessions","labels","locations","location_events","radio_observations","samples","events"}) {
                String sampleJoin=SAMPLE_JOIN+" ORDER BY samples.id ASC";
                String[] args=sessionId==null?null:new String[]{Long.toString(sessionId)};
                if(sessionId!=null)sampleJoin=sampleJoin.replace(" ORDER BY", " WHERE samples.session_id=? ORDER BY");
                String selection=sessionId==null?null:table.equals("sessions")?"id=?":"session_id=?";
                try(Cursor c=table.equals("samples")?getReadableDatabase().rawQuery(sampleJoin,args):getReadableDatabase().query(table,null,selection,args,null,null,"id ASC")) {
                    while(c.moveToNext()) {
                        JSONObject row=new JSONObject();row.put("type",table);
                        for(int i=0;i<c.getColumnCount();i++) {
                            Object val;
                            switch(c.getType(i)) {case Cursor.FIELD_TYPE_NULL:val=JSONObject.NULL;break;case Cursor.FIELD_TYPE_INTEGER:val=c.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:val=c.getDouble(i);break;default:val=c.getString(i);}
                            row.put(c.getColumnName(i),val);
                        }w.write(row.toString());w.write('\n');
                    }
                }
            }
        }
    }
}

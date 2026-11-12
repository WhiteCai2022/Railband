package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.location.Location;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.util.*;

/** Isolated real native loopback outage test; removes only its own generated session after export. */
public class JourneyTest extends Instrumentation {
    private Process server;
    private long id;
    private Store store;
    private Context context;
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void until(java.util.concurrent.Callable<Boolean> condition,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!condition.call()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("timeout: "+MeasureService.status);Thread.sleep(100);}}
    private long rows(String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE session_id=?",new String[]{Long.toString(id)})){c.moveToFirst();return c.getLong(0);}}
    private void serve()throws Exception{
        server=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libiperf_exec.so").getAbsolutePath(),"-s","-p","55203","-B","127.0.0.1").redirectErrorStream(true).start();final Process p=server;
        new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(Exception ignored){}}).start();Thread.sleep(600);check(server.isAlive(),"native loopback server started");
    }
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();String token="";boolean successful=false;
        try{
            context=getTargetContext();store=Store.get(context);SharedPreferences prefs=context.getSharedPreferences("upload",0);token=prefs.getString("token","");prefs.edit().putString("token","").commit();
            startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));serve();
            Intent intent=new Intent(context,MeasureService.class).putExtra("host","127.0.0.1").putExtra("port",55203).putExtra("journey",true).putExtra("bidirectional",true).putExtra("seconds",0).putExtra("interval_s",0.1).putExtra("rate_bps",1000000L).putExtra("location",true).putExtra("route","synthetic journey integration").putExtra("scene","测试正常连接");
            runOnMainSync(()->context.startForegroundService(intent));until(()->MeasureService.running&&MeasureService.session>0&&MeasureService.count>=12,20000);id=MeasureService.session;
            Location fix=new Location("test");fix.setLatitude(40);fix.setLongitude(116);fix.setTime(System.currentTimeMillis());fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());fix.setAccuracy(8);store.location(id,fix);
            server.destroyForcibly();server.waitFor();server=null;
            long before=rows("telemetry");Thread.sleep(10000);long during=rows("telemetry");check(during-before>=30,"independent telemetry continues during server outage");
            long attempts=rows("attempt_events");check(attempts>=5,"reconnect attempts recorded");store.label(id,"测试断线区间");
            int samplesBefore=MeasureService.count;serve();until(()->MeasureService.count>samplesBefore+8,40000);
            // Accumulate enough real samples to exercise both time/size sealing without stopping.
            until(()->{JourneyChunks.sealReady(store);return rows("journey_chunks")>0;},70000);
            long nonFinal=rows("journey_chunks");check(nonFinal>0,"data sealed during active journey");
            runOnMainSync(()->context.startService(new Intent(context,MeasureService.class).setAction("STOP")));until(()->!MeasureService.running,15000);
            JourneyChunks.sealReady(store);
            File directory=new File(context.getExternalFilesDir(null),"journey-test");directory.mkdirs();Set<String> seen=new HashSet<>();Map<String,Integer> counts=new HashMap<>();int index=0;boolean finalSeen=false;
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT payload FROM journey_chunks WHERE session_id=? ORDER BY chunk_index",new String[]{Long.toString(id)})){
                while(c.moveToNext()){
                    byte[] data=c.getBlob(0);check(data.length<2*1024*1024,"bounded chunks");String[] lines=new String(data,"UTF-8").split("\n");JSONObject meta=new JSONObject(lines[0]);check(meta.getInt("chunk_index")==index,"contiguous chunk indices");check(!finalSeen,"nothing after final chunk");finalSeen=meta.getBoolean("is_final");
                    for(int n=2;n<lines.length;n++){JSONObject row=new JSONObject(lines[n]);String kind=row.getString("type"),key=kind+":"+row.getLong("id");check(seen.add(key),"no repeated records across chunks");counts.put(kind,counts.containsKey(kind)?counts.get(kind)+1:1);if(kind.equals("samples"))check(row.getInt("attempt_id")>0&&row.getDouble("journey_end_s")>=row.getDouble("end_s"),"attempt and journey clocks");}
                    try(FileOutputStream out=new FileOutputStream(new File(directory,"chunk-"+(index++)+".jsonl"))){out.write(data);}
                }
            }
            check(finalSeen,"final manifest sealed");for(String table:JourneyChunks.TABLES)check((counts.containsKey(table)?counts.get(table):0)==rows(table),"all "+table+" rows included");
            try(FileOutputStream out=new FileOutputStream(new File(directory,"local-export.jsonl"))){store.export(out,id);}
            JSONObject summary=new JSONObject();summary.put("session_id",id);summary.put("telemetry_during_outage",during-before);summary.put("chunks",index);summary.put("sample_count",rows("samples"));summary.put("attempt_events",rows("attempt_events"));summary.put("status","PASS");
            try(FileOutputStream out=new FileOutputStream(new File(directory,"result.json"))){out.write(summary.toString().getBytes("UTF-8"));}
            result.putString("result",summary.toString());successful=true;
        }catch(Throwable e){result.putString("failure",e.toString());}
        finally{
            if(server!=null)server.destroyForcibly();
            if(context!=null){try{if(MeasureService.running){context.startService(new Intent(context,MeasureService.class).setAction("STOP"));until(()->!MeasureService.running,15000);}if(id>0&&!MeasureService.running)store.deleteLogs(id);}catch(Exception ignored){}context.getSharedPreferences("upload",0).edit().putString("token",token).commit();}
        }
        finish(successful?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}

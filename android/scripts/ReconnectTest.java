package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.net.*;
import java.util.*;

/** Run only on the dedicated emulator: exercises real Android default-network callbacks. */
public class ReconnectTest extends Instrumentation {
    private Context context;
    private Store store;
    private long id;
    private final List<Long> sessions=new ArrayList<>();
    private Bundle args;
    private Process server;
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void until(java.util.concurrent.Callable<Boolean> condition,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(!condition.call()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("timeout: "+MeasureService.status);Thread.sleep(50);}}
    private String shell(String command)throws Exception{try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[1024];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return out.toString("UTF-8").trim();}}
    private long rows(String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE session_id=?",new String[]{Long.toString(id)})){c.moveToFirst();return c.getLong(0);}}
    private List<JSONObject> events()throws Exception{List<JSONObject> out=new ArrayList<>();try(Cursor c=store.getReadableDatabase().rawQuery("SELECT raw FROM attempt_events WHERE session_id=? ORDER BY id",new String[]{Long.toString(id)})){while(c.moveToNext())out.add(new JSONObject(c.getString(0)));}return out;}
    private JSONObject lastEvent(String state,long after)throws Exception{JSONObject result=null;for(JSONObject event:events())if(state.equals(event.optString("state"))&&event.getLong("elapsed_ms")>=after)result=event;return result;}
    private JSONObject firstEvent(String state,long after)throws Exception{for(JSONObject event:events())if(state.equals(event.optString("state"))&&event.getLong("elapsed_ms")>=after)return event;return null;}
    private void begin(String host,int port,double interval,boolean both,int seconds)throws Exception{
        long previous=MeasureService.session;
        Intent intent=new Intent(context,MeasureService.class).putExtra("host",host).putExtra("port",port).putExtra("journey",true).putExtra("bidirectional",both).putExtra("seconds",seconds).putExtra("interval_s",interval).putExtra("rate_bps",1000000L).putExtra("location",false).putExtra("route","synthetic reconnect verification").putExtra("scene","自动化断网测试");
        runOnMainSync(()->context.startForegroundService(intent));until(()->MeasureService.running&&MeasureService.session>0&&MeasureService.session!=previous,10000);id=MeasureService.session;sessions.add(id);
    }
    private void end()throws Exception{if(MeasureService.running){runOnMainSync(()->context.startService(new Intent(context,MeasureService.class).setAction("STOP")));until(()->!MeasureService.running,15000);}}
    private void export(String name)throws Exception{File dir=new File(context.getExternalFilesDir(null),"reconnect-test");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".jsonl"))){store.export(out,id);}}
    private void serve()throws Exception{
        server=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libiperf_exec.so").getAbsolutePath(),"-s","-p","55208","-B","127.0.0.1","--rcv-timeout","8000","--snd-timeout","8000").redirectErrorStream(true).start();final Process active=server;
        new Thread(()->{try(InputStream in=active.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(Exception ignored){}}).start();Thread.sleep(500);check(server.isAlive(),"local test server");
    }
    @Override public void onCreate(Bundle args){this.args=args;super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false;String token=null;int wifi=-1,data=-1,airplane=-1;
        try{
            check("ranchu".equals(Build.HARDWARE),"dedicated emulator required");
            context=getTargetContext();store=Store.get(context);check(!MeasureService.running,"do not interrupt an existing measurement");
            token=context.getSharedPreferences("upload",0).getString("token","");context.getSharedPreferences("upload",0).edit().putString("token","").commit();
            wifi=Settings.Global.getInt(context.getContentResolver(),"wifi_on",0);data=Settings.Global.getInt(context.getContentResolver(),"mobile_data",1);airplane=Settings.Global.getInt(context.getContentResolver(),"airplane_mode_on",0);
            startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            shell("cmd connectivity airplane-mode disable");shell("svc wifi disable");shell("svc data enable");until(()->"cellular".equals(NetworkRecorder.snapshot(context).optString("network_type")),20000);
            JSONObject summary=new JSONObject();JSONArray timings=new JSONArray();
            begin(args.getString("host","62.234.140.204"),5201,1,true,0);until(()->MeasureService.count>=6,30000);
            for(String mode:new String[]{"mobile_data","airplane","mobile_data"}){
                long lostStart=SystemClock.elapsedRealtime();long before=rows("telemetry");
                shell(mode.equals("airplane")?"cmd connectivity airplane-mode enable":"svc data disable");until(()->"none".equals(NetworkRecorder.snapshot(context).optString("network_type")),15000);until(()->lastEvent("network_lost",lostStart)!=null,5000);
                Thread.sleep(4000);check(rows("telemetry")-before>=3,"telemetry continues while offline");
                long enabled=SystemClock.elapsedRealtime();shell(mode.equals("airplane")?"cmd connectivity airplane-mode disable":"svc data enable");
                until(()->lastEvent("network_available",enabled)!=null,20000);JSONObject available=lastEvent("network_available",enabled);long ready=available.getLong("elapsed_ms");
                until(()->lastEvent("connected",ready)!=null&&MeasureService.lastSampleElapsed>ready,20000);
                long first;try(Cursor c=store.getReadableDatabase().rawQuery("SELECT MIN(received_elapsed_ms) FROM samples WHERE session_id=? AND attempt_id>? AND received_elapsed_ms>=?",new String[]{Long.toString(id),Integer.toString(available.getInt("attempt_id")),Long.toString(ready)})){c.moveToFirst();check(!c.isNull(0),"new attempt produced data");first=c.getLong(0);}
                JSONObject timing=new JSONObject();timing.put("mode",mode);timing.put("enable_to_network_ms",ready-enabled);timing.put("network_to_first_sample_ms",first-ready);timing.put("network_to_attempt_ms",firstEvent("connecting",ready).getLong("elapsed_ms")-ready);timings.put(timing);
                check(first-ready<15000,"network-to-sample must recover within 15s in lab");Thread.sleep(1500);
            }
            end();export("network-toggles");summary.put("network_toggles",timings);
            // A socket accepting TCP but never speaking iperf must not inherit the 60s report delay.
            try(ServerSocket silent=new ServerSocket(55209,8,InetAddress.getByName("127.0.0.1"))){
                List<Socket> accepted=Collections.synchronizedList(new ArrayList<Socket>());
                Thread acceptor=new Thread(()->{try{while(!silent.isClosed())accepted.add(silent.accept());}catch(IOException ignored){}});acceptor.start();
                try{
                    begin("127.0.0.1",55209,60,true,0);until(()->{int n=0;for(JSONObject e:events())if("connecting".equals(e.optString("state")))n++;return n>=2;},14000);
                    List<Long> starts=new ArrayList<>();for(JSONObject e:events())if("connecting".equals(e.optString("state")))starts.add(e.getLong("elapsed_ms"));summary.put("silent_handshake_retry_ms",starts.get(1)-starts.get(0));check(starts.get(1)-starts.get(0)<12000,"handshake timeout independent of reporting");end();export("silent-handshake");
                }finally{silent.close();acceptor.join(1000);for(Socket s:accepted)s.close();}
            }
            serve();begin("127.0.0.1",55208,1,false,5);until(()->!MeasureService.running,15000);check(rows("samples")>=2,"native sender-only mode works");summary.put("upload_only_samples",rows("samples"));export("upload-only");
            begin("127.0.0.1",55208,60,true,65);until(()->!MeasureService.running,75000);check(rows("samples")>=2,"slow interval yields real data");int attempts=0;for(JSONObject e:events())if("connecting".equals(e.optString("state")))attempts++;check(attempts==1,"60-second interval must not trigger false reconnect");summary.put("slow_interval_attempts",attempts);summary.put("slow_interval_samples",rows("samples"));export("slow-interval");
            summary.put("status","PASS");File dir=new File(context.getExternalFilesDir(null),"reconnect-test");try(FileOutputStream out=new FileOutputStream(new File(dir,"result.json"))){out.write(summary.toString(2).getBytes("UTF-8"));}result.putString("result",summary.toString());success=true;
        }catch(Throwable e){result.putString("failure",e.toString());try{if(id>0)export("failure");}catch(Exception ignored){}}
        finally{
            try{end();if(server!=null)server.destroyForcibly();if(airplane>=0)shell("cmd connectivity airplane-mode "+(airplane==1?"enable":"disable"));if(data>=0)shell("svc data "+(data==1?"enable":"disable"));if(wifi>=0)shell("svc wifi "+(wifi==1||wifi==2?"enable":"disable"));for(Long own:sessions)store.deleteLogs(own);}catch(Exception e){result.putString("cleanup_failure",e.toString());success=false;}
            if(context!=null&&token!=null)context.getSharedPreferences("upload",0).edit().putString("token",token).commit();
        }
        finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}

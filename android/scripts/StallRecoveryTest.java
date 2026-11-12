package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.lang.Process;
import java.net.*;
import java.util.*;

/** Real data-socket failure with the control socket intact, only on the emulator. */
public final class StallRecoveryTest extends Instrumentation {
    private Context context;private Store store;private Process server;private long session;
    private String token;private final JSONObject result=new JSONObject();
    private Proxy proxy;
    private static final class Proxy implements Closeable {
        private static final class Pair {
            final Socket client,server;volatile boolean fault;
            Pair(Socket c,Socket s){client=c;server=s;}
            void close(){try{client.close();}catch(IOException ignored){}try{server.close();}catch(IOException ignored){}}
        }
        final List<Pair> pairs=Collections.synchronizedList(new ArrayList<>());
        final ServerSocket listener=new ServerSocket(55219,16,InetAddress.getByName("127.0.0.1"));
        Proxy()throws IOException{
            new Thread(()->{try{while(!listener.isClosed()){
                Socket client=listener.accept(),server=new Socket("127.0.0.1",55218);
                Pair pair=new Pair(client,server);pairs.add(pair);pump(pair,client,server);pump(pair,server,client);
            }}catch(IOException ignored){}},"stall-test-proxy").start();
        }
        private void pump(Pair pair,Socket from,Socket to){new Thread(()->{
            try{byte[] b=new byte[32768];int n;InputStream in=from.getInputStream();OutputStream out=to.getOutputStream();while((n=in.read(b))!=-1)out.write(b,0,n);}
            catch(IOException ignored){}finally{if(!pair.fault)pair.close();}
        },"stall-test-pump").start();}
        void failData()throws IOException{
            checkStatic(pairs.size()>=2,"control and data connections exist");Pair data=pairs.get(1);
            data.fault=true;data.client.setSoLinger(true,0);data.client.close();
            // Keep the server-facing data socket and control pair open. The old
            // client's sender worker fails, while its reporting timer keeps ticking.
        }
        private static void checkStatic(boolean ok,String why){if(!ok)throw new AssertionError(why);}
        public void close(){try{listener.close();}catch(IOException ignored){}synchronized(pairs){for(Pair p:pairs)p.close();}}
    }
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private void until(java.util.concurrent.Callable<Boolean> condition,long timeout,String why)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        while(!condition.call()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("timeout "+why+": "+MeasureService.status);Thread.sleep(50);}
    }
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private long count(String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE session_id=?",new String[]{Long.toString(session)})){c.moveToFirst();return c.getLong(0);}}
    private int attempt()throws Exception{return (Integer)field(fieldService(),"attempt");}
    private Object fieldService()throws Exception{Field f=MeasureService.class.getDeclaredField("journey");f.setAccessible(true);return f.get(service());}
    private MeasureService service()throws Exception{
        // Emulator instrumentation observes the real service without production hooks.
        return serviceInstance;
    }
    private MeasureService serviceInstance;
    private void findService()throws Exception{
        Class<?> at=Class.forName("android.app.ActivityThread");Method current=at.getDeclaredMethod("currentActivityThread");current.setAccessible(true);
        Object thread=current.invoke(null);Field services=at.getDeclaredField("mServices");services.setAccessible(true);
        Object map=services.get(thread);
        for(Object value:((Map<?,?>)map).values())if(value instanceof MeasureService)serviceInstance=(MeasureService)value;
        check(serviceInstance!=null,"production measurement service found");
    }
    private void serve(String timeout)throws Exception{
        String engine=new File(context.getApplicationInfo().nativeLibraryDir,"libiperf_exec.so").getAbsolutePath();
        server=new ProcessBuilder(engine,"-s","-p","55218","-B","127.0.0.1","--rcv-timeout",timeout).redirectErrorStream(true).start();
        final Process local=server;new Thread(()->{try(InputStream in=local.getInputStream()){byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(Exception ignored){}}).start();
        Thread.sleep(500);check(server.isAlive(),"loopback server alive");
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){boolean ok=false;
        try{
            check("ranchu".equals(Build.HARDWARE),"dedicated emulator only");context=getTargetContext();store=Store.get(context);
            check(!MeasureService.running,"do not interrupt existing measurement");
            token=context.getSharedPreferences("upload",0).getString("token","");context.getSharedPreferences("upload",0).edit().putString("token","").commit();
            startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            serve("120000");proxy=new Proxy();
            Intent options=new Intent(context,MeasureService.class).putExtra("host","127.0.0.1").putExtra("port",55219).putExtra("journey",true)
                .putExtra("seconds",0).putExtra("interval_s",1.0).putExtra("rate_bps",1000000L).putExtra("location",false)
                .putExtra("route","synthetic stall recovery regression");
            runOnMainSync(()->context.startForegroundService(options));
            until(()->MeasureService.running&&MeasureService.count>=3,15000,"initial throughput");session=MeasureService.session;
            runOnMainSync(()->{try{findService();}catch(Exception e){throw new RuntimeException(e);}});
            int before=attempt();long countBefore=count("telemetry");
            proxy.failData();long injected=SystemClock.elapsedRealtime();
            until(()->attempt()>before&&MeasureService.lastSampleElapsed>injected,30000,"automatic stalled connection rebuild");
            check(MeasureService.running&&MeasureService.session==session,"same journey survives zero progress");
            long recovery=SystemClock.elapsedRealtime()-injected;
            check(count("telemetry")>countBefore,"independent telemetry continues");
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT raw FROM attempt_events WHERE session_id=?",new String[]{Long.toString(session)})){
                boolean found=false;while(c.moveToNext()){JSONObject event=new JSONObject(c.getString(0));if("data_stalled".equals(event.optString("state"))){JSONObject detail=new JSONObject(event.getString("detail"));check(detail.getString("reason").equals("no_byte_progress"),"explicit recorded cause");found=true;}}
                check(found,"stall recovery event persisted");
            }
            check(recovery>=14000,"real zero reports persisted until byte watchdog fired");
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM samples WHERE session_id=? AND attempt_id=? AND bytes=0",new String[]{Long.toString(session),Integer.toString(before)})){
                c.moveToFirst();check(c.getInt(0)>=10,"native engine actually emitted zero intervals");result.put("real_zero_intervals",c.getInt(0));
            }
            result.put("data_socket_failure_to_new_sample_ms",recovery);
            // The long server timeout above intentionally kept the dead data leg
            // around. Restore the usual short stale-client cleanup for the next,
            // independent fault; otherwise that fixture would occupy the server.
            int beforeServerReset=attempt();long restarted=SystemClock.elapsedRealtime();
            server.destroyForcibly();server.waitFor();serve("8000");
            until(()->attempt()>beforeServerReset&&MeasureService.lastSampleElapsed>restarted,20000,"server reset recovery");
            // A local process pipe closed during a read used to fail the whole journey.
            Object current=fieldService();int attemptBeforeClose=attempt();Process process=(Process)field(current,"process");
            long closed=SystemClock.elapsedRealtime();process.getInputStream().close();
            until(()->attempt()>attemptBeforeClose&&MeasureService.lastSampleElapsed>closed,25000,"closed stream retries instead of failing journey");
            check(MeasureService.running&&MeasureService.session==session,"same journey survives stream closure");
            result.put("stream_close_to_new_sample_ms",SystemClock.elapsedRealtime()-closed);
            runOnMainSync(()->context.startService(new Intent(context,MeasureService.class).setAction("STOP")));
            until(()->!MeasureService.running,10000,"manual stop");
            Set<String> intervals=new HashSet<>();
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT attempt_id,direction,start_s,end_s FROM samples WHERE session_id=?",new String[]{Long.toString(session)})){
                while(c.moveToNext())check(intervals.add(c.getInt(0)+":"+c.getString(1)+":"+c.getDouble(2)+":"+c.getDouble(3)),"no repeated native intervals saved");
            }
            result.put("unique_intervals",intervals.size());result.put("telemetry_records",count("telemetry"));result.put("attempt_events",count("attempt_events"));
            File dir=new File(context.getExternalFilesDir(null),"stall-test");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"local-export.jsonl"))){store.export(out,session);}
            result.put("status","PASS");try(FileOutputStream out=new FileOutputStream(new File(dir,"result.json"))){out.write(result.toString(2).getBytes("UTF-8"));}ok=true;
        }catch(Throwable e){try{result.put("failure",e.toString());}catch(Exception ignored){}}
        finally{
            try{if(context!=null&&MeasureService.running){runOnMainSync(()->context.startService(new Intent(context,MeasureService.class).setAction("STOP")));until(()->!MeasureService.running,10000,"cleanup");}
                if(proxy!=null)proxy.close();if(server!=null)server.destroyForcibly();if(session>0&&!MeasureService.running)store.deleteLogs(session);
            }catch(Exception e){ok=false;}
            if(context!=null&&token!=null)context.getSharedPreferences("upload",0).edit().putString("token",token).commit();
        }
        Bundle bundle=new Bundle();bundle.putString("result",result.toString());finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,bundle);
    }
}

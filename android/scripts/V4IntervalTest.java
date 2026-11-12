package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Real UI/iperf collection-interval verification. Adds one named session; never deletes existing logs. */
public class V4IntervalTest extends Instrumentation {
    private MainActivity activity;
    private Map<String,?> savedPreferences;
    private String testHost="64.81.112.204",intervalText="0.5";
    private int testPort=5201,durationSeconds=3;
    private double intervalSeconds,stopAfterSeconds,measuredStopSeconds;
    private long testSession;
    private int savedWindowFlags;
    private boolean windowPrepared;

    public void onCreate(Bundle args){
        super.onCreate(args);
        if(args!=null){
            testHost=args.getString("host",testHost);
            intervalText=args.getString("interval",intervalText);
            if(args.containsKey("port"))testPort=Integer.parseInt(String.valueOf(args.get("port")));
            if(args.containsKey("duration"))durationSeconds=Integer.parseInt(String.valueOf(args.get("duration")));
            if(args.containsKey("stop_after"))stopAfterSeconds=Double.parseDouble(String.valueOf(args.get("stop_after")));
        }
        start();
    }
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    /** Keep failed UI assertions on the instrumentation thread so cleanup/result reporting still run. */
    private void onMainChecked(Runnable action){
        final Throwable[] failure={null};
        runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0] instanceof Error)throw (Error)failure[0];
        if(failure[0]!=null)throw new RuntimeException(failure[0]);
    }
    private void until(java.util.concurrent.Callable<Boolean> predicate,long duration)throws Exception{
        long stop=SystemClock.elapsedRealtime()+duration;
        while(!predicate.call()){if(SystemClock.elapsedRealtime()>stop)throw new AssertionError("timeout: "+MeasureService.status);Thread.sleep(50);}
    }
    private long countSessions(Store store){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM sessions",null)){c.moveToFirst();return c.getLong(0);}}
    @SuppressWarnings("unchecked")
    private void restorePreferences(){
        if(activity==null||savedPreferences==null)return;
        SharedPreferences.Editor e=activity.getPreferences(0).edit().clear();
        for(Map.Entry<String,?> item:savedPreferences.entrySet()){
            Object value=item.getValue();String key=item.getKey();
            if(value instanceof String)e.putString(key,(String)value);
            else if(value instanceof Integer)e.putInt(key,(Integer)value);
            else if(value instanceof Boolean)e.putBoolean(key,(Boolean)value);
            else if(value instanceof Long)e.putLong(key,(Long)value);
            else if(value instanceof Float)e.putFloat(key,(Float)value);
            else if(value instanceof Set)e.putStringSet(key,new HashSet<>((Set<String>)value));
        }
        e.commit();
    }
    private void checkDisplayedLatest()throws Exception{
        onMainChecked(()->{try{
            check(activity.hasWindowFocus(),"measurement activity is not foreground; wake/unlock emulator before this UI test");
            for(String name:new String[]{"upLatest","downLatest"}){
                String text=((TextView)field(name)).getText().toString();
                check(text.contains("最近一次"),"latest label does not describe selected interval: "+text);
                check(!text.contains("—"),"last valid rate vanished before next scheduled interval: "+text);
            }
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void checkIntervals(List<JSONObject> samples,String direction)throws Exception{
        double expectedSeconds=stopAfterSeconds>0?measuredStopSeconds:durationSeconds;
        int expected=(int)Math.ceil(expectedSeconds/intervalSeconds);
        check(samples.size()>=Math.max(1,expected-1)&&samples.size()<=expected+1,"unexpected "+direction+" sample count: "+samples.size()+", expected near "+expected);
        if(stopAfterSeconds>0&&measuredStopSeconds+2<intervalSeconds)check(samples.size()==1,"stopping before first interval should retain exactly one real partial "+direction+" sample");
        double sum=0,previousEnd=0,tolerance=Math.max(.12,intervalSeconds*.05);
        int previousSequence=0;
        for(int index=0;index<samples.size();index++){
            JSONObject row=samples.get(index);
            double start=row.getDouble("start_s"),end=row.getDouble("end_s"),seconds=row.getDouble("seconds");
            check(seconds>0&&end>start,"invalid interval duration");
            check(Math.abs(start-previousEnd)<=tolerance,"non-contiguous "+direction+" interval");
            check(Math.abs(end-start-seconds)<.01,"interval bounds disagree with actual duration");
            check(row.getInt("seq")==previousSequence+1,"missing "+direction+" sequence");
            if(index<samples.size()-1)check(Math.abs(seconds-intervalSeconds)<=tolerance,"engine ignored selected "+intervalSeconds+" s interval: "+seconds);
            // iperf includes its short shutdown/flush delay in the last real interval.
            else check(seconds<=intervalSeconds+Math.max(.35,intervalSeconds*.05),"last partial interval too long: "+seconds);
            sum+=seconds;previousEnd=end;previousSequence=row.getInt("seq");
        }
        // The configured duration is a nominal traffic target; the final real receiver/report
        // interval also includes cross-border shutdown/flush time. Keep every non-final interval
        // strict above while allowing the measured overall duration a bounded 1.5-second tail.
        double durationTolerance=1.5;
        check(Math.abs(sum-expectedSeconds)<=durationTolerance,"measured "+direction+" duration changed: "+sum+", expected near "+expectedSeconds);
        if(stopAfterSeconds>0&&measuredStopSeconds+2<intervalSeconds){
            check(sum<intervalSeconds,"stopped measurement invented a full configured interval");
            check(samples.get(0).getLong("bytes")>0,"stopped partial interval contains no transferred bytes");
        }
    }
    public void onStart(){
        Bundle result=new Bundle();int resultCode=Activity.RESULT_CANCELED;
        try{
            intervalSeconds=TestOptions.interval(intervalText);
            check(durationSeconds>=1&&durationSeconds<=180,"test duration must be 1–180 seconds");
            check(Double.isFinite(stopAfterSeconds)&&stopAfterSeconds>=0&&stopAfterSeconds<durationSeconds,"stop_after must be zero or a positive time before configured duration");
            check(!MeasureService.running,"do not interrupt an existing measurement");
            Context context=getTargetContext();
            activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            savedPreferences=new HashMap<>(activity.getPreferences(0).getAll());
            onMainChecked(()->{
                savedWindowFlags=activity.getWindow().getAttributes().flags;windowPrepared=true;
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if(Build.VERSION.SDK_INT>=27){activity.setTurnScreenOn(true);activity.setShowWhenLocked(true);}
            });
            until(()->{final boolean[] focused={false};onMainChecked(()->focused[0]=activity.hasWindowFocus());return focused[0];},5000);
            Store store=Store.get(context);long oldCount=countSessions(store);
            onMainChecked(()->{try{
                ((CheckBox)field("useLocation")).setChecked(false);
                ((EditText)field("host")).setText(testHost);
                ((EditText)field("port")).setText(Integer.toString(testPort));
                ((EditText)field("route")).setText("模拟器0.4间隔验证 "+intervalText+"秒"+(stopAfterSeconds>0?" / 手动停止尾段":""));
                ((Spinner)field("direction")).setSelection(2);
                ((Spinner)field("rate")).setSelection(6);
                ((EditText)field("customRate")).setText("1");
                ((Spinner)field("duration")).setSelection(6);
                ((EditText)field("customDuration")).setText(Integer.toString(durationSeconds));
                ((Spinner)field("interval")).setSelection(8);
                ((EditText)field("customInterval")).setText(intervalText);
                ((Button)field("start")).performClick();
            }catch(Exception e){throw new RuntimeException(e);}});
            until(()->MeasureService.running&&MeasureService.session>0,8000);
            testSession=MeasureService.session;
            check(Math.abs(MeasureService.sampleIntervalSeconds-intervalSeconds)<.000001,"service interval differs from UI selection");
            SharedPreferences prefs=activity.getPreferences(0);
            check(prefs.getInt("interval_choice",-1)==8&&prefs.getString("custom_interval","").equals(intervalText),"interval preference not saved");
            boolean slowDisplayChecked=false;
            if(stopAfterSeconds>0){
                until(()->MeasureService.testStartedElapsed>0,15000);
                long stopAt=MeasureService.testStartedElapsed+(long)Math.ceil(stopAfterSeconds*1000);
                until(()->SystemClock.elapsedRealtime()>=stopAt,(long)Math.ceil(stopAfterSeconds*1000)+3000);
                check(MeasureService.running,"measurement ended before requested manual stop");
                onMainChecked(()->{try{
                    Button stop=(Button)field("stop");check(stop.isEnabled(),"UI stop action unavailable");
                    measuredStopSeconds=(SystemClock.elapsedRealtime()-MeasureService.testStartedElapsed)/1000.0;
                    check(stop.performClick(),"UI stop click not handled");
                }catch(Exception e){throw new RuntimeException(e);}});
            }else if(intervalSeconds>=5&&durationSeconds>=2*intervalSeconds+1){
                until(()->{LiveRates.Snapshot s=MeasureService.rates.snapshot();return s.up.samples>0&&s.down.samples>0;},(long)(intervalSeconds*1000+15000));
                LiveRates.Snapshot first=MeasureService.rates.snapshot();
                long displayAt=Math.max(first.up.receivedMs,first.down.receivedMs)+3500;
                until(()->SystemClock.elapsedRealtime()>=displayAt,5000);
                check(MeasureService.running,"slow interval test ended before display check");
                LiveRates.Snapshot later=MeasureService.rates.snapshot();
                check(later.up.samples==first.up.samples&&later.down.samples==first.down.samples,"next sample arrived before stale-display check");
                checkDisplayedLatest();slowDisplayChecked=true;
            }
            until(()->!MeasureService.running,stopAfterSeconds>0?15000:durationSeconds*1000L+45000);waitForIdleSync();
            check(MeasureService.completed==(stopAfterSeconds==0),"unexpected completion state: "+MeasureService.status);
            LiveRates.Snapshot stats=MeasureService.rates.snapshot();
            check(stats.up.samples>0&&stats.down.samples>0,"missing measured direction");
            check(!Double.isNaN(stats.up.average)&&!Double.isNaN(stats.down.average),"missing weighted averages");
            check(countSessions(store)==oldCount+1,"earlier logs were not preserved");
            long latest=store.latestSessionId();ByteArrayOutputStream bytes=new ByteArrayOutputStream();store.export(bytes,latest);
            ArrayList<JSONObject> up=new ArrayList<>(),down=new ArrayList<>();int sessions=0;
            for(String line:bytes.toString("UTF-8").split("\n")){
                JSONObject row=new JSONObject(line);String type=row.getString("type");
                if(type.equals("sessions")){
                    sessions++;check(row.getLong("id")==latest,"exported wrong session");
                    check(row.getString("status").equals(stopAfterSeconds>0?"cancelled":"completed"),"wrong stored completion status: "+row.getString("status"));
                    JSONObject config=new JSONObject(row.getString("config"));
                    check(Math.abs(config.getDouble("sample_interval_s")-intervalSeconds)<.000001,"export does not retain configured interval");
                    check(config.getInt("duration_s")==durationSeconds,"manual stop or partial interval changed configured duration");
                }else if(type.equals("samples")){
                    check(row.getLong("session_id")==latest,"export leaked previous measurement");
                    String direction=row.getString("direction");
                    if(direction.equals("phone_upload"))up.add(row);
                    else if(direction.equals("phone_download"))down.add(row);
                    else throw new AssertionError("unknown sample direction "+direction);
                }
            }
            check(sessions==1,"latest export has incorrect session count");
            checkIntervals(up,"upload");checkIntervals(down,"download");
            check(up.size()==down.size(),"bidirectional intervals unpaired");
            for(int i=0;i<up.size();i++)check(up.get(i).getInt("seq")==down.get(i).getInt("seq"),"bidirectional sequence mismatch");
            try(FileOutputStream out=context.openFileOutput("v04-test.jsonl",Context.MODE_PRIVATE)){out.write(bytes.toByteArray());}
            Thread.sleep(700);waitForIdleSync();checkDisplayedLatest();
            android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();
            check(screen!=null,"screenshot unavailable");
            try(FileOutputStream out=context.openFileOutput("v04-screen.png",Context.MODE_PRIVATE)){check(screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"screenshot write failed");}finally{screen.recycle();}
            result.putString("result","PASS: real Android bidirectional custom interval="+intervalSeconds+" s, duration="+durationSeconds+" s, stop_after="+stopAfterSeconds+" s, actual UI stop="+measuredStopSeconds+" s, upload="+up.size()+", download="+down.size()+", latest labels/averages, exact session config/export, earlier sessions preserved="+oldCount+", slow interval display="+(slowDisplayChecked?"checked":"not applicable"));
            resultCode=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("failure",android.util.Log.getStackTraceString(e));}
        finally{
            if(testSession>0&&MeasureService.running&&MeasureService.session==testSession){
                try{getTargetContext().startService(new Intent(getTargetContext(),MeasureService.class).setAction("STOP"));until(()->!MeasureService.running,10000);}
                catch(Exception ignored){}
            }
            restorePreferences();
            if(windowPrepared&&activity!=null)try{onMainChecked(()->{
                activity.getWindow().setFlags(savedWindowFlags,WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if(Build.VERSION.SDK_INT>=27){activity.setTurnScreenOn(false);activity.setShowWhenLocked(false);}
            });}catch(Throwable ignored){}
        }
        finish(resultCode,result);
    }
}

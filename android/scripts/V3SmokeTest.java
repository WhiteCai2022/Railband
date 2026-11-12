package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.location.Location;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Real Android UI/bidirectional integration; destructive checks use an isolated cache DB. */
public class V3SmokeTest extends Instrumentation {
    private MainActivity activity;
    private Map<String,?> savedPreferences;
    private String testHost="64.81.112.204";
    private int testPort=5201;
    public void onCreate(Bundle args){super.onCreate(args);if(args!=null){testHost=args.getString("host",testHost);testPort=args.getInt("port",testPort);}start();}
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void until(java.util.concurrent.Callable<Boolean> predicate,long duration)throws Exception{long stop=SystemClock.elapsedRealtime()+duration;while(!predicate.call()){if(SystemClock.elapsedRealtime()>stop)throw new AssertionError("timeout: "+MeasureService.status);Thread.sleep(100);}}
    private List<JSONObject> readExport(Store store,Long id)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();store.export(bytes,id);List<JSONObject> rows=new ArrayList<>();for(String line:bytes.toString("UTF-8").split("\n"))rows.add(new JSONObject(line));return rows;}
    private long count(Store store,String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}}
    private void restorePreferences(){if(activity==null||savedPreferences==null)return;SharedPreferences.Editor e=activity.getPreferences(0).edit().clear();for(Map.Entry<String,?> item:savedPreferences.entrySet()){Object v=item.getValue();String k=item.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}e.commit();}
    private void isolatedLogChecks(JSONObject interval)throws Exception{
        final File file=new File(getTargetContext().getCacheDir(),"isolated-v3-"+System.nanoTime()+".db");
        Context isolated=new ContextWrapper(getTargetContext()){
            public File getDatabasePath(String name){return file;}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(file,factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openDatabase(file.getAbsolutePath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
        };
        Constructor<Store> constructor=Store.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);Store store=constructor.newInstance(isolated);
        try{
            long first=store.start(new JSONObject().put("purpose","isolated test"),"first");store.sample(first,1,interval,"sum","first");store.finish(first,"completed","");
            long last=store.start(new JSONObject().put("purpose","isolated test"),"last");
            Location loc=new Location("test_fixture");loc.setLatitude(40);loc.setLongitude(116);loc.setAccuracy(8);loc.setTime(System.currentTimeMillis());loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());store.location(last,loc);store.locationStatus(last,"test fixture");
            store.sample(last,1,interval,"sum","last");store.sample(last,1,interval,"sum_bidir_reverse","last");store.event(last,"test-only raw event");store.finish(last,"cancelled","");
            check(store.latestSessionId()==last,"latest cancelled session must be included");
            int samples=0,sessions=0;for(JSONObject row:readExport(store,last)){
                String type=row.getString("type");if(type.equals("metadata")){check(row.getInt("schema_version")==3,"schema version");continue;}
                if(type.equals("sessions")){sessions++;check(row.getLong("id")==last,"wrong session exported");}else check(row.getLong("session_id")==last,"export leaked another session");
                if(type.equals("samples")){samples++;check(row.getDouble("latitude")==40,"position not joined");check(row.has("direction"),"missing direction");}
            }
            check(sessions==1&&samples==2,"last export row counts");
            check(store.deleteLogs(last)==1,"delete last count");check(store.latestSessionId()==first&&count(store,"samples")==1,"delete last affected earlier log");
            for(String table:new String[]{"locations","location_events","events"})check(count(store,table)==0,"orphan after delete: "+table);
            long running=store.start(new JSONObject(),"running");boolean rejected=false;try{store.deleteLogs(null);}catch(IllegalStateException expected){rejected=true;}check(rejected&&count(store,"sessions")==2,"running guard/rollback");store.finish(running,"cancelled","");
            check(store.deleteLogs(null)==2,"delete all count");for(String table:new String[]{"sessions","samples","labels","events","locations","location_events"})check(count(store,table)==0,"delete all incomplete: "+table);
            check(store.latestSessionId()==0,"empty latest");
        }finally{store.close();SQLiteDatabase.deleteDatabase(file);}
    }
    public void onStart(){Bundle result=new Bundle();int resultCode=Activity.RESULT_CANCELED;try{
        Context context=getTargetContext();check(!MeasureService.running,"do not interrupt an existing measurement");
        activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));savedPreferences=new HashMap<>(activity.getPreferences(0).getAll());
        Store store=Store.get(context);long oldCount=count(store,"sessions");
        runOnMainSync(()->{try{
            ((CheckBox)field("useLocation")).setChecked(false);((EditText)field("host")).setText(testHost);((EditText)field("port")).setText(Integer.toString(testPort));((EditText)field("route")).setText("模拟器0.3自动验证");((Spinner)field("direction")).setSelection(2);((Spinner)field("rate")).setSelection(6);((EditText)field("customRate")).setText("1");((Spinner)field("duration")).setSelection(6);((EditText)field("customDuration")).setText("5");((Button)field("start")).performClick();
        }catch(Exception e){throw new RuntimeException(e);}});
        until(()->MeasureService.running,8000);until(()->!MeasureService.running,60000);waitForIdleSync();
        check(MeasureService.completed,"bidir failed: "+MeasureService.status);LiveRates.Snapshot stats=MeasureService.rates.snapshot();check(stats.up.samples>=3&&stats.down.samples>=3,"missing a direction");check(!Double.isNaN(stats.up.average)&&!Double.isNaN(stats.down.average),"missing averages");
        long latest=store.latestSessionId();check(count(store,"sessions")==oldCount+1,"old user logs not preserved");int up=0,down=0;JSONObject interval=null;
        for(JSONObject row:readExport(store,latest)){if(row.getString("type").equals("samples")){if(row.getString("direction").equals("phone_upload"))up++;else if(row.getString("direction").equals("phone_download"))down++;interval=new JSONObject(row.getString("raw"));}}
        check(up>=3&&down>=3,"export lacks bidirectional samples");check(interval!=null,"missing interval fixture");isolatedLogChecks(interval);
        Thread.sleep(700);waitForIdleSync();android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();if(screen!=null)try(FileOutputStream out=context.openFileOutput("v03-screen.png",Context.MODE_PRIVATE)){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);screen.recycle();}
        result.putString("result","PASS: real Android16 bidirectional upload/download, curve data, both averages/latest, scoped export, isolated deletion last/all, running-delete rejection; up="+up+", down="+down+", earlier sessions preserved="+oldCount);
        resultCode=Activity.RESULT_OK;
    }catch(Throwable e){result.putString("failure",android.util.Log.getStackTraceString(e));}finally{restorePreferences();}finish(resultCode,result);}
}

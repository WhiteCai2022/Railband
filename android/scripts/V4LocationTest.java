package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.location.Location;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Location association regression checks; uses an isolated cache database, never user logs. */
public class V4LocationTest extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private List<JSONObject> rows(Store store,long id,String type)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();store.export(output,id);
        List<JSONObject> selected=new ArrayList<>();
        for(String line:output.toString("UTF-8").split("\n")){JSONObject row=new JSONObject(line);if(row.getString("type").equals(type))selected.add(row);}
        return selected;
    }
    private Location fix(long elapsedMs,double latitude,double longitude){
        Location location=new Location("isolated_fixture");location.setLatitude(latitude);location.setLongitude(longitude);location.setAccuracy(7);
        location.setElapsedRealtimeNanos(elapsedMs*1000000);location.setTime(System.currentTimeMillis()-(SystemClock.elapsedRealtime()-elapsedMs));return location;
    }
    private JSONObject interval(boolean sender)throws Exception{
        return new JSONObject().put("sum",new JSONObject().put("start",0).put("end",1).put("seconds",1).put("bytes",125000).put("bits_per_second",1000000).put("sender",sender));
    }
    private void emptyLocation(JSONObject row,String message)throws Exception{
        for(String column:new String[]{"location_id","location_age_ms","latitude","longitude","location_fix_utc_ms","location_fix_elapsed_ms"})check(row.isNull(column),message+": "+column);
    }
    private void attachedLocation(JSONObject row,Location expected,String message)throws Exception{
        check(!row.isNull("location_id"),message+": missing id");
        check(row.getDouble("latitude")==expected.getLatitude()&&row.getDouble("longitude")==expected.getLongitude(),message+": wrong coordinates");
        check(row.getLong("location_fix_utc_ms")==expected.getTime(),message+": fix UTC changed");
        long elapsedMs=expected.getElapsedRealtimeNanos()/1000000;
        check(row.getLong("location_fix_elapsed_ms")==elapsedMs,message+": fix monotonic time changed");
        check(row.getLong("location_age_ms")==row.getLong("received_elapsed_ms")-elapsedMs,message+": incorrect fix age");
        check(row.getLong("location_age_ms")>=0,message+": future fix attached");
    }
    private void runChecks(Store store)throws Exception{
        long now=SystemClock.elapsedRealtime();check(now>120001,"Emulator must have at least 2 minutes uptime for the stale-location regression fixture");
        JSONObject up=interval(true),down=interval(false);
        long old=store.start(new JSONObject().put("app_version","0.3.0"),"existing record");
        store.sample(old,1,up,"existing record");store.finish(old,"completed","");
        String oldSample=rows(store,old,"samples").get(0).toString();

        long current=store.start(new JSONObject().put("app_version","0.4.0"),"current");
        store.sample(current,1,up,"current");
        emptyLocation(rows(store,current,"samples").get(0),"No fix yet must remain explicit null");
        Location lateOld=fix(SystemClock.elapsedRealtime()-1000,39,115);store.location(old,lateOld);
        store.sample(current,2,down,"current");
        emptyLocation(rows(store,current,"samples").get(1),"Late previous-session callback must not seed the new session");

        Location stale=fix(SystemClock.elapsedRealtime()-120000,40,116);store.location(current,stale);
        store.sample(current,3,up,"current");store.sample(current,3,down,"current");
        Thread.sleep(35);store.sample(current,4,up,"current");
        List<JSONObject> samples=rows(store,current,"samples");
        for(int i=2;i<5;i++){attachedLocation(samples.get(i),stale,"Stale fix must appear in every direction and interval");check(samples.get(i).getLong("location_age_ms")>=120000,"Two-minute stale age lost");}
        check(samples.get(4).getLong("location_age_ms")>samples.get(2).getLong("location_age_ms"),"Carried fix age must increase without a fresh timestamp");
        check(rows(store,current,"locations").size()==1,"Reusing a location must not create synthetic fixes");

        Location fresh=fix(SystemClock.elapsedRealtime()-500,41,117);store.location(current,fresh);store.sample(current,5,down,"current");
        attachedLocation(rows(store,current,"samples").get(5),fresh,"A newer valid fix must replace the retained fix");
        store.location(current,fix(SystemClock.elapsedRealtime()+60000,42,118));
        store.location(current,fix(0,43,119));
        Location invalid=fix(SystemClock.elapsedRealtime()-100,Double.NaN,120);store.location(current,invalid);
        store.location(current,stale);store.sample(current,6,up,"current");
        attachedLocation(rows(store,current,"samples").get(6),fresh,"Future, zero-time, invalid and older fixes must not replace the valid reference");
        check(rows(store,current,"locations").size()==3,"Invalid/future fixes must not be persisted");
        check(rows(store,current,"metadata").get(0).getString("location_semantics").contains("no age cutoff"),"Export metadata describes an obsolete expiry policy");
        store.finish(current,"completed","");

        long next=store.start(new JSONObject(),"next");
        store.location(current,fix(SystemClock.elapsedRealtime()-50,44,120));store.sample(next,1,up,"next");
        emptyLocation(rows(store,next,"samples").get(0),"A new session must not inherit coordinates");
        store.sample(current,7,up,"late interval");
        emptyLocation(rows(store,current,"samples").get(7),"An old session cannot use the current session cache");
        store.finish(next,"completed","");
        check(rows(store,old,"samples").get(0).toString().equals(oldSample),"Existing sample was rewritten");
        check(rows(store,current,"samples").size()==8,"Earlier current-session rows lost");

        Location saved=LocationRecorder.latest;String savedState=LocationRecorder.state;
        try{
            LocationRecorder.latest=stale;LocationRecorder.state="已收到定位";String display=LocationRecorder.display();
            check(display.contains("40.000000, 116.000000")&&display.contains("沿用最近位置")&&!display.contains("坐标留空"),"Retained-location UI must show coordinates and age");
            LocationRecorder.latest=null;LocationRecorder.state="等待定位";check(LocationRecorder.display().equals("等待定位"),"No-fix UI invented coordinates");
        }finally{LocationRecorder.latest=saved;LocationRecorder.state=savedState;}
    }
    public void onStart(){Bundle result=new Bundle();int resultCode=Activity.RESULT_CANCELED;Store store=null;
        final File file=new File(getTargetContext().getCacheDir(),"isolated-v4-location-"+System.nanoTime()+".db");
        try{
            check(!MeasureService.running,"Do not run instrumentation during an existing measurement");
            Context isolated=new ContextWrapper(getTargetContext()){
                public File getDatabasePath(String name){return file;}
                public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(file,factory);}
                public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openDatabase(file.getAbsolutePath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);}
            };
            Constructor<Store> constructor=Store.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);store=constructor.newInstance(isolated);runChecks(store);
            result.putString("result","PASS: location carried beyond 2 minutes into every upload/download row; original fix times and exact age exported; newer fix replaces old; absent, future, invalid and cross-session fixes excluded; retained coordinates displayed; existing samples preserved; only isolated cache DB used");resultCode=Activity.RESULT_OK;
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{if(store!=null)store.close();SQLiteDatabase.deleteDatabase(file);}
        finish(resultCode,result);
    }
}

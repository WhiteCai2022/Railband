package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;

/** Real Android integration test. Run only on a disposable emulator. */
public class SmokeTest extends Instrumentation {
    private MainActivity activity;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private Object field(String name) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void until(java.util.concurrent.Callable<Boolean> condition,long ms) throws Exception {long end=SystemClock.elapsedRealtime()+ms;while(!condition.call()){if(SystemClock.elapsedRealtime()>end)throw new AssertionError("timeout");Thread.sleep(100);}}
    private void launch(boolean reverse,int duration,String host,String port) throws Exception {
        runOnMainSync(()->{try{((CheckBox)field("useLocation")).setChecked(false);((Spinner)field("rate")).setSelection(0);((EditText)field("host")).setText(host);((EditText)field("port")).setText(port);((EditText)field("route")).setText("模拟器集成测试");((Spinner)field("direction")).setSelection(reverse?1:0);((Spinner)field("duration")).setSelection(duration);((Button)field("start")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
        until(()->MeasureService.running,5000);
    }
    private void completed() throws Exception {until(()->!MeasureService.running,60000);waitForIdleSync();Thread.sleep(500);}
    private String status(long id){try(Cursor c=Store.get(getTargetContext()).getReadableDatabase().rawQuery("SELECT status FROM sessions WHERE id=?",new String[]{Long.toString(id)})){check(c.moveToFirst(),"missing session");return c.getString(0);}}
    private void label(String text){getTargetContext().startService(new Intent(getTargetContext(),MeasureService.class).setAction("LABEL").putExtra("scene",text));}
    public void onStart(){Bundle result=new Bundle();try {
        Context c=getTargetContext();
        activity=(MainActivity)startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        launch(false,0,"10.0.2.2","5202");until(()->MeasureService.count>=2,25000);label("隧道");completed();long upload=MeasureService.session;check("completed".equals(status(upload)),"upload: "+MeasureService.status);check(MeasureService.count>=8,"upload intervals");
        launch(true,0,"10.0.2.2","5202");until(()->MeasureService.count>=2,25000);label("山区");completed();long download=MeasureService.session;check("completed".equals(status(download)),"download: "+MeasureService.status);
        launch(false,2,"10.0.2.2","5202");until(()->MeasureService.count>=2,25000);getTargetContext().startService(new Intent(c,MeasureService.class).setAction("STOP"));completed();check("cancelled".equals(status(MeasureService.session)),"cancel status");check(MeasureService.count>=2,"cancel lost records");
        // Connection refusal can finish before running is observed; invoke and inspect new session.
        long previous=MeasureService.session;
        runOnMainSync(()->{try{((EditText)field("host")).setText("127.0.0.1");((EditText)field("port")).setText("1");((Button)field("start")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
        until(()->MeasureService.session>previous&&!MeasureService.running,20000);check("failed".equals(status(MeasureService.session)),"failure status");check(MeasureService.count==0,"failure invented samples");
        ByteArrayOutputStream output=new ByteArrayOutputStream();Store.get(c).export(output);String jsonl=output.toString("UTF-8");int samples=0,labels=0,sessions=0;
        for(String line:jsonl.split("\n")){JSONObject row=new JSONObject(line);String type=row.getString("type");if(type.equals("samples")){samples++;check(row.getDouble("seconds")>0,"interval duration");check(row.getDouble("bits_per_second")>=0,"rate");long id=row.getLong("session_id");if(id==upload)check(row.getInt("sender")==1,"upload sender");if(id==download)check(row.getInt("sender")==0,"download receiver");}if(type.equals("labels"))labels++;if(type.equals("sessions"))sessions++;}
        check(samples>=20,"export samples");check(labels>=6,"label timeline");check(sessions>=4,"sessions");
        try(FileOutputStream f=c.openFileOutput("smoke-export.jsonl",Context.MODE_PRIVATE)){f.write(output.toByteArray());}
        // Reset visible settings for a clean default endpoint screen after testing.
        runOnMainSync(()->{try{((EditText)field("host")).setText("64.81.112.204");((EditText)field("port")).setText("5201");}catch(Exception e){throw new RuntimeException(e);}});
        result.putString("result","PASS: upload, download, per-interval SQLite persistence, live labels, cancellation, refusal, JSONL export; samples="+samples+", labels="+labels);
        finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("failure",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}

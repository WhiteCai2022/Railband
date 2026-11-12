package cn.railband.mvp;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real JobScheduler regression tests. All HTTPS is intercepted in this test process. */
public class UploadSchedulingTest extends Instrumentation {
    private static final int JOB_ID=22016;
    private static final String ENDPOINT="https://upload-test.invalid/api/upload";
    private static final String TOKEN="isolated-upload-regression-token";
    private Context context;
    private Store store;
    private JobScheduler scheduler;
    private SharedPreferences prefs;
    private Map<String,?> savedPrefs;
    private final List<Long> sessions=new ArrayList<>();
    private final List<Long> chunkIds=new ArrayList<>();
    private final Map<String,byte[]> expectedBodies=new LinkedHashMap<>();
    private final FakeTransport fake=new FakeTransport();
    private final JSONObject summary=new JSONObject();
    private final List<String> checks=new ArrayList<>();

    public void onCreate(Bundle args){super.onCreate(args);start();}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void until(Callable<Boolean> condition,long timeout,String what)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(!condition.call()){
            if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("timeout: "+what+"; status="+prefs.getString("status",""));
            Thread.sleep(50);
        }
    }
    private String shell(String command)throws Exception{
        try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] bytes=new byte[1024];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);
            return output.toString("UTF-8");
        }
    }
    private void forceJob()throws Exception{
        if(scheduler.getPendingJob(JOB_ID)!=null)shell("cmd jobscheduler run -f "+context.getPackageName()+" "+JOB_ID);
    }
    private void noJob()throws Exception{
        until(()->scheduler.getPendingJob(JOB_ID)==null,10000,"job must finish without backoff");
    }
    private long sqlCount(String sql,String...args){
        try(Cursor cursor=store.getReadableDatabase().rawQuery(sql,args)){cursor.moveToFirst();return cursor.getLong(0);}
    }
    private long fixture(int count)throws Exception{
        String journey=UUID.randomUUID().toString();
        long session=store.start(new JSONObject().put("journey_id",journey).put("route","synthetic upload scheduling regression"),"fixture only");
        sessions.add(session);store.finish(session,"completed",null);
        JSONObject snapshot;
        try(Cursor cursor=store.getReadableDatabase().rawQuery("SELECT * FROM sessions WHERE id=?",new String[]{Long.toString(session)})){
            check(cursor.moveToFirst(),"fixture session exists");snapshot=JourneyChunks.row(cursor);snapshot.put("type","sessions");
        }
        for(int index=0;index<count;index++){
            JSONObject metadata=new JSONObject().put("type","metadata").put("schema_version",5).put("format","railband_journey_chunk")
                .put("journey_id",journey).put("chunk_index",index).put("is_final",index==count-1);
            JSONObject row=new JSONObject().put("type","events").put("id",index+1).put("session_id",session)
                .put("raw","test payload "+index+"; UTF-8 完整性与去重校验 "+UUID.randomUUID());
            byte[] body=(metadata.toString()+"\n"+snapshot.toString()+"\n"+row.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            ContentValues values=new ContentValues();values.put("session_id",session);values.put("chunk_index",index);
            values.put("created_utc_ms",System.currentTimeMillis());values.put("is_final",index==count-1?1:0);values.put("payload",body);
            chunkIds.add(store.getWritableDatabase().insertOrThrow("journey_chunks",null,values));
            expectedBodies.put(journey+":"+index,body);
        }
        return session;
    }
    private void legacyFixtures(int count)throws Exception{
        for(int index=0;index<count;index++){
            long id=store.start(new JSONObject().put("route","synthetic legacy upload regression "+index),"fixture only");
            sessions.add(id);store.event(id,"legacy UTF-8 payload "+index);store.finish(id,"completed",null);
        }
    }
    private void assertPendingUnmodified(long session,int count){
        String id=Long.toString(session);
        check(sqlCount("SELECT COUNT(*) FROM journey_chunks WHERE session_id=? AND uploaded=0 AND error IS NULL",id)==count,"paused/retry chunks remain pending without permanent error");
    }
    private void manualSchedule(long delay){
        scheduler.cancel(JOB_ID);
        int scheduled=scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,UploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setPersisted(true)
            .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        check(scheduled==JobScheduler.RESULT_SUCCESS,"fixture job scheduled");
    }
    private void measurementPause()throws Exception{
        long id=fixture(2);MeasureService.running=true;MeasureService.journeyMode=true;MeasureService.engineActive=true;
        prefs.edit().putBoolean("during_measurement",false).commit();
        UploadJob.schedule(context);UploadJob.scheduleNow(context);
        Thread.sleep(200);
        check(scheduler.getPendingJob(JOB_ID)==null,"measurement pause must not schedule a network job");
        // Also exercise an already-pending job that Android starts during a new measurement.
        manualSchedule(3600000);forceJob();noJob();
        check(fake.requests()==0,"measurement pause must not send a request");assertPendingUnmodified(id,2);
        check(prefs.getString("status","").contains("测速"),"pause status explains measurement wait");
        MeasureService.running=false;MeasureService.journeyMode=false;
        UploadJob.scheduleNow(context);forceJob();until(()->store.chunkCount()==0,10000,"finish resumes paused chunks");noJob();
        checks.add("measurement pause creates no retry job or permanent failure; finishing resumes immediately");
    }
    private void resetBackoffAndDrain()throws Exception{
        fixture(55);legacyFixtures(27);int before=fake.requests();
        manualSchedule(3600000);check(scheduler.getPendingJob(JOB_ID).getMinLatencyMillis()==3600000,"long delayed job fixture");
        fake.holdNext();UploadJob.scheduleNow(context);forceJob();
        check(fake.awaitHeld(),"scheduleNow starts without stale backoff");
        JobInfo current=scheduler.getPendingJob(JOB_ID);
        check(current!=null&&current.getMinLatencyMillis()==0,"manual/finish request removes one-hour minimum latency");
        summary.put("immediate_job_expedited",Build.VERSION.SDK_INT>=31&&current.isExpedited());
        fake.release();until(()->store.chunkCount()==0&&store.pendingUploadCount()==0,15000,"all 55 chunks and 27 legacy logs drain in one job");noJob();
        check(fake.requests()-before==82,"one request per chunk/legacy log, across both batch limits");
        check(fake.maximumSuccessGap()<5000,"no retry gap between successful batches");
        summary.put("largest_success_gap_ms",fake.maximumSuccessGap());
        checks.add("manual/finish schedule clears long wait; 55 chunks and 27 legacy logs drain continuously");
    }
    private void activeManualRequests()throws Exception{
        fixture(1);int before=fake.requests();fake.holdNext();UploadJob.scheduleNow(context);forceJob();
        check(fake.awaitHeld(),"active upload held for concurrency test");
        fixture(55);
        for(int n=0;n<8;n++)UploadJob.scheduleNow(context);
        check(!fake.heldDisconnected,"manual/finish request must not cancel an active connection");
        fake.release();until(()->store.chunkCount()==0,15000,"new chunks arriving during active upload drain");noJob();
        check(fake.requests()-before==56,"active upload handles new chunks without duplicate requests");
        checks.add("repeated manual/finish requests preserve the active connection and drain newly queued data");
    }
    private void networkRetry()throws Exception{
        long id=fixture(1);int before=fake.requests();fake.mode="network";
        fake.holdNext();UploadJob.scheduleNow(context);forceJob();check(fake.awaitHeld(),"network failure held while background schedules arrive");
        JobInfo original=scheduler.getPendingJob(JOB_ID);check(original!=null,"active network job registered");
        long generation=original.getExtras().getLong("upload_generation");
        for(int n=0;n<8;n++)UploadJob.schedule(context);
        fake.release();until(()->fake.requests()>before,10000,"network failure attempted");
        until(()->prefs.getString("status","").contains("IOException"),10000,"network error status");
        Thread.sleep(250);assertPendingUnmodified(id,1);
        JobInfo retried=scheduler.getPendingJob(JOB_ID);check(retried!=null,"genuine network failure retains a retry job");
        check(retried.getExtras().getLong("upload_generation")==generation,"background schedule must not reset genuine network backoff");
        check(fake.requests()-before==1,"background schedule must not immediately retry network failure");
        fake.mode="ok";UploadJob.scheduleNow(context);forceJob();until(()->store.chunkCount()==0,10000,"manual retry recovers failed connection");noJob();
        check(fake.requests()-before==2,"network recovery sends only one retry");
        checks.add("real transport failure retains pending data/backoff despite background scheduling; explicit retry wakes it immediately");
    }
    private void checksumFailure()throws Exception{
        long id=fixture(1);int before=fake.requests();fake.mode="bad-hash";
        UploadJob.scheduleNow(context);forceJob();until(()->fake.requests()>before,10000,"bad acknowledgement attempted");noJob();
        check(sqlCount("SELECT COUNT(*) FROM journey_chunks WHERE session_id=? AND uploaded=0 AND error IS NOT NULL",Long.toString(id))==1,"bad SHA-256 must never mark chunk uploaded");
        fake.mode="ok";store.retryFailures();UploadJob.scheduleNow(context);forceJob();until(()->store.chunkCount()==0,10000,"correct checksum retry accepted");noJob();
        check(fake.requests()-before==2,"bad checksum retry count");checks.add("SHA-256 mismatch never marks uploaded; matching acknowledgement permits explicit retry");
    }
    private void integrity()throws Exception{
        for(Map.Entry<String,byte[]> entry:expectedBodies.entrySet()){
            byte[] received=fake.accepted.get(entry.getKey());
            check(received!=null&&Arrays.equals(entry.getValue(),received),"uploaded body exactly matches immutable chunk: "+entry.getKey());
        }
        for(long id:chunkIds)check(sqlCount("SELECT COUNT(*) FROM journey_chunks WHERE id=? AND uploaded=1 AND error IS NULL",Long.toString(id))==1,"all own chunks acknowledged, no data removed");
        check(fake.failure==null,"fake transport validation: "+fake.failure);
        summary.put("verified_chunks",expectedBodies.size());summary.put("http_requests",fake.requests());
        checks.add("all immutable UTF-8 payloads and SHA-256 acknowledgements verified byte for byte");
    }
    public void onStart(){
        Bundle result=new Bundle();boolean successful=false;
        try{
            check(Build.FINGERPRINT.contains("generic")||Build.FINGERPRINT.contains("emulator")||Build.MODEL.contains("sdk_gphone")||Build.MODEL.contains("Android SDK"),"test is emulator-only");
            context=getTargetContext();store=Store.get(context);scheduler=context.getSystemService(JobScheduler.class);prefs=context.getSharedPreferences("upload",0);
            check(!MeasureService.running,"measurement must be stopped before test");
            check(store.chunkCount()==0&&store.pendingUploadCount()==0,"refusing to touch existing pending logs; use isolated emulator data");
            savedPrefs=new HashMap<>(prefs.getAll());scheduler.cancel(JOB_ID);
            prefs.edit().putString("token",TOKEN).putString("url",ENDPOINT).putString("device_id","upload-scheduling-emulator").putBoolean("during_measurement",false).commit();
            URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
                protected URLConnection openConnection(URL url)throws IOException{
                    if(!"upload-test.invalid".equals(url.getHost()))throw new IOException("Test blocks external HTTPS: "+url.getHost());
                    return fake.connection(url);
                }
            }:null);
            measurementPause();resetBackoffAndDrain();activeManualRequests();networkRetry();checksumFailure();integrity();
            summary.put("status","PASS");summary.put("checks",new org.json.JSONArray(checks));successful=true;result.putString("result",summary.toString());
        }catch(Throwable error){
            try{summary.put("status","FAIL").put("failure",error.toString()).put("checks",new org.json.JSONArray(checks));}catch(Exception ignored){}
            result.putString("failure",summary.toString());
        }finally{
            fake.release();if(savedPrefs!=null){MeasureService.running=false;MeasureService.journeyMode=false;MeasureService.engineActive=false;}
            if(context!=null){
                if(scheduler!=null)scheduler.cancel(JOB_ID);
                try{Thread.sleep(300);if(store!=null)for(long id:sessions)store.deleteLogs(id);}catch(Exception error){result.putString("cleanup_warning",error.toString());}
                if(savedPrefs!=null){SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,?> entry:savedPrefs.entrySet()){
                    Object value=entry.getValue();if(value instanceof String)editor.putString(entry.getKey(),(String)value);else if(value instanceof Boolean)editor.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Integer)editor.putInt(entry.getKey(),(Integer)value);else if(value instanceof Long)editor.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)editor.putFloat(entry.getKey(),(Float)value);
                }editor.commit();}
                try{File directory=new File(context.getExternalFilesDir(null),"upload-scheduling-test");directory.mkdirs();try(FileOutputStream output=new FileOutputStream(new File(directory,"result.json"))){output.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}
            }
        }
        finish(successful?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private static String digest(byte[] body)throws Exception{
        StringBuilder text=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(body))text.append(String.format(java.util.Locale.US,"%02x",value&255));return text.toString();
    }
    private static final class FakeTransport {
        volatile String mode="ok",failure;
        volatile boolean heldDisconnected;
        private volatile CountDownLatch held,release;
        private volatile boolean holdNext;
        private int requests;
        private final List<Long> successTimes=new ArrayList<>();
        final Map<String,byte[]> accepted=new java.util.concurrent.ConcurrentHashMap<>();
        synchronized int requests(){return requests;}
        synchronized void count(){requests++;}
        synchronized void success(){successTimes.add(SystemClock.elapsedRealtime());}
        synchronized long maximumSuccessGap(){long maximum=0;for(int n=1;n<successTimes.size();n++)maximum=Math.max(maximum,successTimes.get(n)-successTimes.get(n-1));return maximum;}
        void holdNext(){held=new CountDownLatch(1);release=new CountDownLatch(1);heldDisconnected=false;holdNext=true;}
        boolean awaitHeld()throws Exception{return held.await(10,TimeUnit.SECONDS);}
        void release(){CountDownLatch latch=release;if(latch!=null)latch.countDown();}
        HttpURLConnection connection(URL url){return new HttpURLConnection(url){
            private final ByteArrayOutputStream sent=new ByteArrayOutputStream();
            private final Map<String,String> headers=new HashMap<>();
            private byte[] response;
            private boolean blocked;
            public void connect(){}
            public boolean usingProxy(){return false;}
            public void disconnect(){if(blocked)heldDisconnected=true;}
            public void setRequestProperty(String key,String value){headers.put(key,value);}
            public String getRequestProperty(String key){return headers.get(key);}
            public OutputStream getOutputStream(){return sent;}
            public int getResponseCode()throws IOException{
                count();
                try{
                    check("POST".equals(getRequestMethod()),"transport method");
                    check(("Bearer "+TOKEN).equals(headers.get("Authorization")),"transport token");
                    check("upload-scheduling-emulator".equals(headers.get("X-Railband-Device")),"transport device identity");
                    byte[] body=sent.toByteArray();
                    if(holdNext){holdNext=false;blocked=true;held.countDown();check(release.await(10,TimeUnit.SECONDS),"release held upload");blocked=false;}
                    String currentMode=mode;if("network".equals(currentMode))throw new IOException("synthetic network disconnect");
                    JSONObject reply=new JSONObject();
                    if(url.getPath().endsWith("/api/journeys/chunks")){
                        JSONObject meta=new JSONObject(new String(body,StandardCharsets.UTF_8).split("\n",2)[0]);
                        reply.put("sha256","bad-hash".equals(currentMode)?"incorrect":digest(body)).put("chunk_index",meta.getInt("chunk_index")).put("status","saved");
                        if(!"bad-hash".equals(currentMode))accepted.put(meta.getString("journey_id")+":"+meta.getInt("chunk_index"),body);
                    }else{
                        check(url.getPath().endsWith("/api/upload"),"expected isolated upload endpoint");
                        check(body.length>100,"legacy JSONL exported");reply.put("id",900000+requests());
                    }
                    response=reply.toString().getBytes(StandardCharsets.UTF_8);success();return 201;
                }catch(IOException error){throw error;}catch(Throwable error){failure=error.toString();throw new IOException(error);}
            }
            public InputStream getInputStream(){return new ByteArrayInputStream(response);}
        };}
    }
}

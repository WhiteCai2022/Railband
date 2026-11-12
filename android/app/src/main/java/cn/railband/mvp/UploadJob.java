package cn.railband.mvp;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/** Network-constrained, persisted retry job for completed measurement exports. */
public final class UploadJob extends JobService {
    private static final int JOB_ID=22016;
    private static final String GENERATION="upload_generation";
    private static final Object DRAIN_LOCK=new Object();
    private static UploadRun inFlight;
    private volatile UploadRun active;
    private static final class UploadRun {
        final JobParameters params;
        volatile boolean stopped;
        volatile HttpURLConnection connection;
        volatile boolean wakeRequested;
        String item;
        long lastProgress;
        UploadRun(JobParameters params){this.params=params;}
        void stop(){stopped=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    }
    public static void schedule(Context context){schedule(context,false);}
    /** Finish/manual/open-app triggers must replace an old retry delay. */
    public static void scheduleNow(Context context){schedule(context,true);}
    private static boolean paused(Context context){
        return MeasureService.running&&(!MeasureService.journeyMode||MeasureService.engineActive)
            &&!context.getSharedPreferences("upload",MODE_PRIVATE).getBoolean("during_measurement",false);
    }
    private static synchronized void schedule(Context context,boolean immediate){
        SharedPreferences prefs=context.getSharedPreferences("upload",MODE_PRIVATE);
        if(prefs.getString("token","").isEmpty()||prefs.getString("url","").isEmpty())return;
        try{
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);
            if(paused(context)){
                if(inFlight==null)scheduler.cancel(JOB_ID);
                prefs.edit().putString("status","测速中暂停上传，结束后自动同步").apply();return;
            }
            if(inFlight!=null&&!inFlight.stopped){if(immediate)inFlight.wakeRequested=true;return;}
            if(!immediate&&scheduler.getPendingJob(JOB_ID)!=null)return;
            if(immediate)scheduler.cancel(JOB_ID);
            // An expedited foreground job can start before schedule() returns.
            prefs.edit().putString("status","已安排上传，等待可用网络和系统启动").apply();
            PersistableBundle extras=new PersistableBundle();extras.putLong(GENERATION,SystemClock.elapsedRealtimeNanos());
            JobInfo.Builder builder=new JobInfo.Builder(JOB_ID,new ComponentName(context,UploadJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setExtras(extras)
                .setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
            int scheduled;
            if(immediate&&Build.VERSION.SDK_INT>=31){
                scheduled=scheduler.schedule(builder.setExpedited(true).build());
                if(scheduled<=0)scheduled=scheduler.schedule(builder.setExpedited(false).build());
            }else{
                if(!immediate)builder.setMinimumLatency(1000);
                else builder.setImportantWhileForeground(true);
                scheduled=scheduler.schedule(builder.build());
            }
            if(scheduled<=0)prefs.edit().putString("status","系统暂未安排上传，请稍后重试").apply();
        }catch(Exception e){prefs.edit().putString("status","无法安排上传："+e.getClass().getSimpleName()).apply();}
    }
    public boolean onStartJob(JobParameters params){
        final UploadRun run=new UploadRun(params);
        synchronized(UploadJob.class){if(inFlight!=null)inFlight.stop();active=run;inFlight=run;}
        new Thread(()->{
            // A cancelled worker may still be leaving an HTTP call; never send concurrently.
            synchronized(DRAIN_LOCK){drain(run);}
        },"railband-upload").start();
        return true;
    }
    private void drain(UploadRun run){
            boolean retry=false,halted=false;
            Store store=Store.get(this);SharedPreferences prefs=getSharedPreferences("upload",MODE_PRIVATE);
            try{
                String endpoint=prefs.getString("url","");String token=prefs.getString("token","");
                if(endpoint.isEmpty()||token.isEmpty())return;
                String device;
                synchronized(UploadJob.class){device=prefs.getString("device_id","");if(device.isEmpty()){device=UUID.randomUUID().toString();prefs.edit().putString("device_id",device).commit();}}
                // Batch size bounds each SQL query, not the number uploaded before a retry delay.
                for(;;){
                    checkAllowed(run);JourneyChunks.sealReady(store);
                    long[] batch=store.pendingChunks();if(batch.length==0)break;
                    for(long id:batch){
                        try{
                            checkAllowed(run);byte[] payload=store.chunk(id);if(payload==null)continue;
                            run.item="数据块 #"+id;run.lastProgress=0;
                            prefs.edit().putString("status","正在连接服务器，上传"+run.item).apply();
                            String chunkUrl=endpoint.substring(0,endpoint.length()-"/api/upload".length())+"/api/journeys/chunks";
                            JSONObject response=send(chunkUrl,token,device,new java.io.ByteArrayInputStream(payload),payload.length,run);
                            String digest=hex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
                            JSONObject meta=new JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8).split("\n",2)[0]);
                            if(!digest.equals(response.optString("sha256"))||response.optInt("chunk_index",-1)!=meta.getInt("chunk_index"))throw new UploadFailure("服务器校验不一致",false);
                            if(run.stopped)return;
                            store.chunkDone(id);prefs.edit().putString("status","已同步"+run.item).apply();
                        }catch(UploadFailure e){
                            if(e.retry||e.global||e.paused)throw e;
                            prefs.edit().putString("status",e.getMessage()).apply();store.chunkFailed(id,e.getMessage());
                        }
                    }
                }
                while(!MeasureService.running){
                    long[] batch=store.pendingUploads();if(batch.length==0)break;
                    for(long id:batch){
                        try{checkAllowed(run);run.item="测量 #"+id;run.lastProgress=0;long serverId=uploadOne(endpoint,token,device,id,run);if(run.stopped)return;store.markUploaded(id,serverId);prefs.edit().putString("status","已同步"+run.item).apply();}
                        catch(UploadFailure e){if(e.retry||e.global||e.paused)throw e;prefs.edit().putString("status",e.getMessage()).apply();store.legacyFailed(id,e.getMessage());}
                    }
                }
                if(!run.stopped)prefs.edit().putString("status",store.chunkCount()>0||store.pendingUploadCount()>0?"剩余记录等待测速结束后同步":store.uploadErrorCount()>0?"可上传记录已同步，仍有失败记录需处理":"本机记录已全部同步").apply();
            }catch(UploadFailure e){if(!run.stopped)prefs.edit().putString("status",e.getMessage()).apply();retry=e.retry;halted=e.global;}
            catch(Exception e){if(!run.stopped)prefs.edit().putString("status","上传暂不可用："+e.getClass().getSimpleName()).apply();retry=true;}
            finally{
                synchronized(UploadJob.class){
                    if(inFlight==run)inFlight=null;
                    if(active==run)active=null;
                    if(!run.stopped){
                        jobFinished(run.params,retry);
                        // Only explicit finish/manual requests may bypass a real failure's backoff.
                        // Newly queued work after a successful drain is another immediate pass.
                        if((run.wakeRequested||!retry&&!halted)&&!paused(this)
                            &&(store.chunkCount()>0||!MeasureService.running&&store.pendingUploadCount()>0))scheduleNow(this);
                    }
                }
            }
    }
    public boolean onStopJob(JobParameters params){
        UploadRun run=active;
        if(run!=null&&run.params.getExtras().getLong(GENERATION)==params.getExtras().getLong(GENERATION)){
            run.stop();synchronized(UploadJob.class){if(inFlight==run)inFlight=null;}
        }
        return !paused(this);
    }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.US,"%02x",b&255));return s.toString();}
    private void checkAllowed(UploadRun run)throws UploadFailure{
        if(run.stopped)throw new UploadFailure("网络变化，等待补传",true);
        if(paused(this))throw new UploadFailure("测速中暂停上传，结束后自动同步",false,false,true);
    }
    private void progress(UploadRun run,long sent,long total){
        if(run.item==null||run.stopped)return;
        long now=SystemClock.elapsedRealtime();if(sent<total&&now-run.lastProgress<500)return;run.lastProgress=now;
        getSharedPreferences("upload",MODE_PRIVATE).edit().putString("status",sent>=total?run.item+"已发送，等待服务器确认":"正在上传"+run.item+" · "+(total>0?sent*100/total:0)+"%").apply();
    }
    private long uploadOne(String endpoint,String token,String device,long id,UploadRun run)throws UploadFailure{
        File file=null;
        try{file=File.createTempFile("upload-",".jsonl",getCacheDir());try(OutputStream out=new FileOutputStream(file)){Store.get(this).export(out,id);}
            if(file.length()>128L*1024*1024)throw new UploadFailure("旧日志超过 128 MiB，请单独导出",false);
            return send(endpoint,token,device,new FileInputStream(file),file.length(),run).getLong("id");
        }catch(UploadFailure e){throw e;}catch(Exception e){throw new UploadFailure("日志导出失败："+e.getClass().getSimpleName(),false);}
        finally{if(file!=null)file.delete();}
    }
    private JSONObject send(String endpoint,String token,String device,InputStream input,long length,UploadRun run)throws UploadFailure{
        HttpURLConnection connection=null;
        try(InputStream in=input){
            checkAllowed(run);URL url=new URL(endpoint);
            if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null)throw new UploadFailure("必须使用 HTTPS 地址",false,true);
            connection=(HttpURLConnection)url.openConnection();run.connection=connection;
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(15000);connection.setReadTimeout(60000);
            connection.setDoOutput(true);connection.setFixedLengthStreamingMode(length);connection.setRequestProperty("Content-Type","application/x-ndjson; charset=utf-8");connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("X-Railband-Device",device);
            try(OutputStream out=connection.getOutputStream()){byte[] buffer=new byte[16384];int n;long sent=0;while((n=in.read(buffer))!=-1){checkAllowed(run);out.write(buffer,0,n);sent+=n;progress(run,sent,length);}}
            int code=connection.getResponseCode();
            if(code==200||code==201){try(InputStream bodyIn=connection.getInputStream()){java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;while((n=bodyIn.read(bytes))!=-1){if(body.size()+n>4096)throw new UploadFailure("服务器响应过大",false,true);body.write(bytes,0,n);}return new JSONObject(body.toString("UTF-8"));}}
            if(code==401)throw new UploadFailure("上传密钥无效，请检查设置",false,true);
            if(code==404)throw new UploadFailure("服务器尚未支持行程上传或地址错误",false,true);
            if(code==410)throw new UploadFailure("行程已在网页删除，停止补传该块",false);
            if(code==400||code==409||code==413)throw new UploadFailure("数据块被拒绝（HTTP "+code+"），其他记录继续补传",false);
            throw new UploadFailure("服务器响应 HTTP "+code+"，稍后重试",true);
        }catch(UploadFailure e){throw e;}catch(Exception e){throw new UploadFailure("网络或证书错误："+e.getClass().getSimpleName(),true);}
        finally{run.connection=null;if(connection!=null)connection.disconnect();}
    }
    private static final class UploadFailure extends Exception{
        final boolean retry,global,paused;
        UploadFailure(String message,boolean retry){this(message,retry,false,false);}
        UploadFailure(String message,boolean retry,boolean global){this(message,retry,global,false);}
        UploadFailure(String message,boolean retry,boolean global,boolean paused){super(message);this.retry=retry;this.global=global;this.paused=paused;}
    }
}

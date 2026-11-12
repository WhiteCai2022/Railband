package cn.railband.mvp;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.util.*;
import java.util.concurrent.*;

/** One user-started journey, independent sensor clock and replaceable TCP attempts. */
final class JourneyRun {
    private final MeasureService service;
    private final Intent options;
    private final boolean locationFg;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService chunks=Executors.newSingleThreadScheduledExecutor();
    private final Object lifecycle=new Object();
    private volatile boolean stop,finished;
    private volatile String result="cancelled",error="",state="connecting",attemptError="";
    private volatile Process process;
    private volatile File pidFile;
    private volatile long lastOutput,anchor;
    private volatile int attempt;
    private long id,started;
    private int sequence;
    private LocationRecorder location;
    private RadioRecorder radio;
    private PowerManager.WakeLock wake;
    private double interval;
    private int duration;
    private TransferWatchdog progress;
    // All network generations and process/pid pairs are guarded by lifecycle.
    private String networkHandle="";
    private long networkEpoch,attemptNetworkEpoch;
    private volatile boolean connected,aborting;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    JourneyRun(MeasureService service,Intent options,boolean locationFg){this.service=service;this.options=new Intent(options);this.locationFg=locationFg;}
    void start(){new Thread(this::run,"railband-journey").start();}
    void stop(String status,String detail){synchronized(lifecycle){if(finished||stop)return;result=status;error=detail;stop=true;state="stopping";lifecycle.notifyAll();}stopEngine();}
    private boolean waitFor(long ms,long expectedEpoch){long until=SystemClock.elapsedRealtime()+ms;synchronized(lifecycle){while(!stop&&networkEpoch==expectedEpoch){long remaining=until-SystemClock.elapsedRealtime();if(remaining<=0)break;try{lifecycle.wait(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}return !stop;}}
    private void stopEngine(){
        synchronized(lifecycle){stopEngineLocked(3000);}
    }
    private void stopEngineLocked(long graceMs){
        final Process active=process;if(active==null||!active.isAlive())return;
        try{File file=pidFile;if(file==null)throw new IOException();int pid;try(BufferedReader in=new BufferedReader(new FileReader(file))){pid=Integer.parseInt(in.readLine().trim());}if(pid<=0||pid==android.os.Process.myPid())throw new IOException();if(active.isAlive())android.system.Os.kill(pid,android.system.OsConstants.SIGINT);}
        catch(Exception e){active.destroy();}
        main.postDelayed(()->{if(active.isAlive())active.destroyForcibly();},graceMs);
    }
    private void networkChanged(String handle,String lostHandle){
        synchronized(lifecycle){
            if(stop||(lostHandle!=null&&!lostHandle.equals(networkHandle))||handle.equals(networkHandle))return;
            networkHandle=handle;networkEpoch++;
            Store.get(service).journeyEvent(id,attempt,handle.isEmpty()?"network_lost":"network_available",handle);
            if(process!=null&&process.isAlive()){
                attemptError=handle.isEmpty()?"默认网络断开":"默认网络恢复或切换，立即重连";
                aborting=true;state=handle.isEmpty()?"no_network":"retry_wait";stopEngineLocked(250);
            }
            lifecycle.notifyAll();
        }
    }
    private void queueNetworkChange(String handle,String lostHandle){
        try{clock.execute(()->{try{networkChanged(handle,lostHandle);}catch(Exception e){stop("failed","网络状态保存失败："+e.getClass().getSimpleName());}});}catch(RejectedExecutionException ignored){}
    }
    private void watchNetwork(){
        synchronized(lifecycle){networkHandle=NetworkRecorder.snapshot(service).optString("network_handle","");}
        connectivity=(ConnectivityManager)service.getSystemService(service.CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){queueNetworkChange(Long.toString(network.getNetworkHandle()),null);}
            @Override public void onLost(Network network){queueNetworkChange("",Long.toString(network.getNetworkHandle()));}
        };
        try{connectivity.registerDefaultNetworkCallback(networkCallback,main);}
        catch(RuntimeException e){networkCallback=null;Store.get(service).journeyEvent(id,0,"network_callback_unavailable","改用每秒检查网络");}
    }
    private void monitor(){
        if(stop)return;
        if(wake!=null)wake.acquire(120000);
        if(networkCallback==null)networkChanged(NetworkRecorder.snapshot(service).optString("network_handle",""),null);
        long now=SystemClock.elapsedRealtime();MeasureService.measuredSeconds=(now-started)/1000.0;
        if(duration>0&&now-started>=duration*1000L){stop("completed","");return;}
        synchronized(lifecycle){
            if(process!=null&&process.isAlive()&&!aborting){
                boolean timedOut=connected?now-lastOutput>TestOptions.outputTimeoutMs(interval):now-anchor>8000;
                int stalled=connected&&progress!=null?progress.stalled(now):0;
                if(stalled!=0){
                    String direction=stalled==3?"上下行":stalled==1?"上行":"下行";
                    attemptError=direction+"持续无数据进展，重建测速连接";
                    try{
                        JSONObject detail=new JSONObject();detail.put("reason","no_byte_progress");detail.put("direction_mask",stalled);
                        detail.put("timeout_ms",progress.timeoutMs());detail.put("upload_age_ms",progress.ageMs(true,now));detail.put("download_age_ms",progress.ageMs(false,now));
                        Store.get(service).journeyEvent(id,attempt,"data_stalled",detail.toString());
                    }catch(JSONException e){throw new IllegalStateException(e);}
                    aborting=true;state="retry_wait";MeasureService.status=attemptError+"；位置和信号继续记录";stopEngineLocked(250);
                }else if(timedOut){attemptError=connected?"引擎输出超时，重新连接":"连接握手超时，重新连接";aborting=true;state="retry_wait";stopEngineLocked(250);}
            }
        }
    }
    private void sampleTelemetry(){
        if(stop)return;
        try{long[] observed=radio==null?new long[]{0,-1}:radio.observation(SystemClock.elapsedRealtime());Store.get(service).telemetry(id,attempt,state,NetworkRecorder.snapshot(service),observed[0],observed[1],MeasureService.scene);}
        catch(Exception e){stop("failed","独立采集保存失败："+e.getClass().getSimpleName());}
    }
    private void run(){
        Store store=Store.get(service);
        try{
            interval=options.getDoubleExtra("interval_s",1);duration=options.getIntExtra("seconds",0);
            boolean both=options.getBooleanExtra("bidirectional",false),reverse=options.getBooleanExtra("reverse",false);
            progress=new TransferWatchdog(both||!reverse,both||reverse,interval,options.getLongExtra("rate_bps",5000000));
            JSONObject config=new JSONObject();String uuid=UUID.randomUUID().toString();
            config.put("journey_id",uuid);config.put("journey_mode",true);config.put("host",options.getStringExtra("host"));config.put("port",options.getIntExtra("port",5201));config.put("duration_s",duration);config.put("duration_semantics","wall time including outages");config.put("sample_interval_s",interval);config.put("rate_cap_bps",options.getLongExtra("rate_bps",5000000));config.put("rate_cap_scope","per_direction");config.put("protocol","TCP");config.put("direction",both?"bidirectional":reverse?"phone_download":"phone_upload");config.put("route",options.getStringExtra("route"));config.put("device",Build.MANUFACTURER+" "+Build.MODEL);config.put("android",Build.VERSION.RELEASE);config.put("app_version","0.7.3");config.put("zero_progress_timeout_ms",progress.timeoutMs());config.put("location_requested",options.getBooleanExtra("location",true));config.put("location_permission",LocationRecorder.permitted(service));config.put("radio_permission",RadioRecorder.permitted(service));config.put("location_association_policy","latest_valid_fix_in_session_no_age_limit");config.put("upload_during_measurement",service.getSharedPreferences("upload",0).getBoolean("during_measurement",false));config.put("iperf_commit","c9b74229d0d9bfec6d2307b66b43c29a7665ad0b");
            id=store.start(config,MeasureService.scene);MeasureService.session=id;started=SystemClock.elapsedRealtime();MeasureService.testStartedElapsed=started;
            CountDownLatch ready=new CountDownLatch(1);
            main.post(()->{try{if(!stop){location=new LocationRecorder(service,id);location.start(options.getBooleanExtra("location",true),locationFg);radio=new RadioRecorder(service,id);radio.start();wake=((PowerManager)service.getSystemService(service.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"railband:journey");wake.setReferenceCounted(false);wake.acquire(120000);}}finally{ready.countDown();}});
            ready.await();
            store.journeyEvent(id,0,"journey_started",uuid);
            watchNetwork();
            clock.scheduleWithFixedDelay(()->{try{monitor();}catch(Exception e){stop("failed","行程监控失败："+e.getClass().getSimpleName());}},0,1,TimeUnit.SECONDS);
            clock.scheduleWithFixedDelay(this::sampleTelemetry,0,Math.max(100,(long)(interval*1000)),TimeUnit.MILLISECONDS);
            chunks.scheduleWithFixedDelay(()->{try{if(JourneyChunks.sealReady(store)>0)UploadJob.schedule(service);}catch(Exception e){stop("failed","数据分块失败："+e.getClass().getSimpleName());}},5,5,TimeUnit.SECONDS);
            File engine=new File(service.getApplicationInfo().nativeLibraryDir,"libiperf_exec.so");if(!engine.canExecute())throw new IOException("缺少 iperf 引擎");
            int failures=0;boolean offline=false;long failureEpoch=-1;
            while(!stop){
                long epoch;boolean hasNetwork;
                synchronized(lifecycle){epoch=networkEpoch;hasNetwork=!networkHandle.isEmpty();}
                if(epoch!=failureEpoch){failures=0;failureEpoch=epoch;}
                if(!hasNetwork){
                    state="no_network";MeasureService.engineActive=false;MeasureService.status="行程采集中 · 无网络，位置和信号继续记录";
                    if(!offline){store.journeyEvent(id,attempt,"no_network","等待网络恢复");offline=true;}if(!waitFor(1000,epoch))break;continue;
                }
                offline=false;attempt++;synchronized(lifecycle){anchor=SystemClock.elapsedRealtime();attemptNetworkEpoch=epoch;connected=false;aborting=false;state="connecting";attemptError="";}store.attempt(attempt,anchor);MeasureService.engineActive=true;
                MeasureService.status="行程采集中 · 第 "+attempt+" 次连接";store.journeyEvent(id,attempt,"connecting",NetworkRecorder.snapshot(service).toString());
                int before=MeasureService.count;String detail=runAttempt(store,engine);
                MeasureService.engineActive=false;if(stop)break;
                state="retry_wait";failures=MeasureService.count>before?0:Math.min(failures+1,4);long delay=Math.min(5000,1000L<<Math.max(0,failures-1));
                synchronized(lifecycle){if(networkEpoch!=epoch)delay=0;}
                store.journeyEvent(id,attempt,"retry_wait",detail+"；等待 "+delay+" ms");
                MeasureService.status="行程采集中 · 连接中断，"+(delay/1000)+" 秒后重试\n位置和信号继续记录";
                UploadJob.schedule(service);waitFor(delay,epoch);
            }
        }catch(Exception e){result="failed";error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();stop=true;}
        finally{
            stop=true;state="stopping";MeasureService.engineActive=false;service.journeyFinishing();stopEngine();
            if(networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}
            clock.shutdown();chunks.shutdown();
            try{clock.awaitTermination(5,TimeUnit.SECONDS);chunks.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            CountDownLatch stopped=new CountDownLatch(1);main.post(()->{try{if(location!=null)location.stop();if(radio!=null)radio.stop();if(wake!=null&&wake.isHeld())wake.release();}finally{stopped.countDown();}});
            try{stopped.await();if(id>0){store.journeyEvent(id,attempt,"journey_finished",result+": "+error);store.finish(id,result,error);JourneyChunks.sealReady(store);}}
            catch(Exception e){result="failed";error="行程收尾失败，已有数据保留："+e.getClass().getSimpleName();}
            finished=true;service.journeyFinished(result,error);
        }
    }
    private String runAttempt(Store store,File engine)throws Exception{
        File file=new File(service.getCacheDir(),"journey-"+id+"-"+attempt+".pid");
        List<String> command=TestOptions.journeyCommand(engine.getAbsolutePath(),options.getStringExtra("host"),options.getIntExtra("port",5201),options.getLongExtra("rate_bps",5000000),options.getBooleanExtra("reverse",false),options.getBooleanExtra("bidirectional",false),interval);command.add("--pidfile");command.add(file.getAbsolutePath());
        Process active;
        synchronized(lifecycle){
            if(stop||networkEpoch!=attemptNetworkEpoch)return "启动前网络已变化";
            lastOutput=SystemClock.elapsedRealtime();pidFile=file;active=new ProcessBuilder(command).redirectErrorStream(true).start();process=active;
        }
        String detail="连接已结束";
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(active.getInputStream()))){
            String line;while((line=reader.readLine())!=null){
                lastOutput=SystemClock.elapsedRealtime();store.event(id,line);JSONObject event;
                try{event=new JSONObject(line);}catch(JSONException e){detail=line;continue;}
                String type=event.optString("event");
                if("start".equals(type)){synchronized(lifecycle){anchor=SystemClock.elapsedRealtime();store.attempt(attempt,anchor);progress.reset(anchor);connected=true;if(!aborting)state="testing";}store.journeyEvent(id,attempt,"connected",Long.toString(anchor));}
                else if("error".equals(type))detail=event.optString("data","iperf 错误");
                else if("interval".equals(type)){
                    JSONObject data=event.getJSONObject("data");boolean both=options.getBooleanExtra("bidirectional",false);
                    if(both&&!data.has("sum_bidir_reverse"))throw new JSONException("双向区间缺少下行，已停止行程");
                    String[] keys=both?new String[]{"sum","sum_bidir_reverse"}:new String[]{"sum"};
                    boolean[] accepted=new boolean[keys.length];boolean any=false;
                    for(int i=0;i<keys.length;i++){
                        JSONObject sum=data.getJSONObject(keys[i]);accepted[i]=progress.record(sum.getBoolean("sender"),sum.getLong("bytes"),sum.getDouble("start"),sum.getDouble("end"),SystemClock.elapsedRealtime());any|=accepted[i];
                    }
                    if(!any)continue;
                    sequence++;
                    JSONObject network=NetworkRecorder.snapshot(service);long[] observation=radio==null?new long[]{0,-1}:radio.observation(SystemClock.elapsedRealtime());
                    for(int i=0;i<keys.length;i++){
                        if(!accepted[i])continue;String key=keys[i];
                        JSONObject sum=data.getJSONObject(key);MeasureService.mbps=store.sample(id,sequence,data,key,MeasureService.scene,network,observation[0],observation[1]);
                        MeasureService.rates.add(sum.getBoolean("sender"),sum.getLong("bytes"),sum.getDouble("seconds"),(anchor-started)/1000.0+sum.getDouble("end"),sum.getDouble("bits_per_second"),SystemClock.elapsedRealtime());MeasureService.lastSampleElapsed=SystemClock.elapsedRealtime();MeasureService.count++;
                    }
                    if(!aborting)MeasureService.status="行程采集中 · 第 "+attempt+" 次连接 · "+MeasureService.count+" 条速率记录";
                }
            }
            int exit=active.waitFor();detail=(attemptError.isEmpty()?detail:attemptError)+"（退出 "+exit+"）";
        }catch(IOException e){
            // Socket/process pipe closure ends this attempt, not the entire journey.
            detail=attemptError.isEmpty()?"测速连接读写中断："+e.getClass().getSimpleName():attemptError;
        }finally{synchronized(lifecycle){if(active.isAlive())active.destroyForcibly();process=null;file.delete();pidFile=null;}store.journeyEvent(id,attempt,"disconnected",detail);}
        return detail;
    }
}

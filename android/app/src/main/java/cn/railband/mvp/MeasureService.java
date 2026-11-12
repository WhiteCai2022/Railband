package cn.railband.mvp;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.util.concurrent.*;

public final class MeasureService extends Service {
    public static volatile boolean running,completed;
    public static volatile boolean journeyMode,engineActive;
    private JourneyRun journey;
    public static volatile String status="准备就绪",scene="未标注",summary="";
    public static volatile long session,testStartedElapsed,lastSampleElapsed;
    public static volatile int count,plannedSeconds;
    public static volatile double mbps,measuredSeconds;
    public static volatile double sampleIntervalSeconds=1;
    public static final LiveRates rates=new LiveRates();
    public static void clearResults(){if(running)return;rates.clear();session=0;count=0;mbps=0;measuredSeconds=0;testStartedElapsed=lastSampleElapsed=0;completed=false;summary="";status="准备就绪";LocationRecorder.latest=null;LocationRecorder.state="定位未开始";RadioRecorder.latestValues=null;RadioRecorder.state="无线信号未开始";}
    private final Object lock=new Object();
    private volatile boolean cancelled,timedOut,finishing,destroyed;
    private volatile String timeoutMessage="输出超时，已保留已有记录";
    private volatile long lastOutput;
    private volatile Process process;
    private volatile File processPidFile;
    private Process stoppingProcess;
    private PowerManager.WakeLock wake;
    private LocationRecorder location;
    private RadioRecorder radio;
    private ExecutorService worker;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable deadline=()->{timedOut=true;timeoutMessage="测量超时，已保留已有记录";stopProcess();};
    private final Runnable heartbeat=new Runnable(){public void run(){
        if(!running||finishing)return;
        if(SystemClock.elapsedRealtime()-lastOutput>TestOptions.outputTimeoutMs(sampleIntervalSeconds)){timedOut=true;stopProcess();return;}
        if(wake!=null)wake.acquire(120000);handler.postDelayed(this,10000);
    }};
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();worker=Executors.newSingleThreadExecutor();Store.get(this).recover();}
    private Notification notification(){
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("measure","测速进行中",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MeasureService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"measure").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("轨途测速 · 正在记录").setContentText("按所选间隔保存测速和最近位置；点击停止保留记录").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    public int onStartCommand(Intent i,int flags,int startId){
        if(i==null){stopSelf();return START_NOT_STICKY;}
        if("STOP".equals(i.getAction())){if(running&&!finishing){cancelled=true;status="正在停止…";if(journey!=null)journey.stop("cancelled","");else stopProcess();}else if(!running)stopSelf();return START_NOT_STICKY;}
        if("LABEL".equals(i.getAction())){synchronized(lock){if(running&&!finishing&&session>0){scene=i.getStringExtra("scene");Store.get(this).label(session,scene);}}if(!running)stopSelf();return START_NOT_STICKY;}
        if(running||finishing)return START_NOT_STICKY;
        final String host=i.getStringExtra("host"),route=i.getStringExtra("route");
        final int port=i.getIntExtra("port",5201),seconds=i.getIntExtra("seconds",10);
        final long rate=i.getLongExtra("rate_bps",5000000L);
        final double interval=i.getDoubleExtra("interval_s",1);
        final boolean reverse=i.getBooleanExtra("reverse",false),wantLocation=i.getBooleanExtra("location",true);
        final boolean bidirectional=i.getBooleanExtra("bidirectional",false);
        final boolean locationFg=(wantLocation&&LocationRecorder.permitted(this)||RadioRecorder.permitted(this))&&LocationRecorder.enabled(this);
        try{
            TestOptions.command("iperf",host,port,seconds,rate,reverse,bidirectional,interval);
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC|(locationFg?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0);
            if(Build.VERSION.SDK_INT>=29)startForeground(11,notification(),type);else startForeground(11,notification());
        }catch(Exception e){status="无法开始："+e.getMessage();stopSelf();return START_NOT_STICKY;}
        sampleIntervalSeconds=interval;running=true;rates.clear();completed=false;cancelled=false;timedOut=false;session=0;count=0;mbps=0;measuredSeconds=0;testStartedElapsed=lastSampleElapsed=0;plannedSeconds=seconds;scene=i.getStringExtra("scene");summary="";status="正在连接服务器…";LocationRecorder.latest=null;LocationRecorder.state="正在准备定位";RadioRecorder.latestValues=null;RadioRecorder.state="正在准备无线信号";
        journeyMode=i.getBooleanExtra("journey",false);engineActive=!journeyMode;
        if(journeyMode){journey=new JourneyRun(this,i,locationFg);journey.start();return START_NOT_STICKY;}
        wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"railband:measure");wake.setReferenceCounted(false);wake.acquire(120000);
        lastOutput=SystemClock.elapsedRealtime();handler.postDelayed(heartbeat,10000);
        worker.execute(()->measure(host,port,seconds,rate,reverse,bidirectional,interval,route,wantLocation,locationFg));
        return START_NOT_STICKY;
    }
    private synchronized void stopProcess(){
        Process p=process;if(p==null||!p.isAlive()||stoppingProcess==p)return;stoppingProcess=p;
        // Keep stdout open so iperf can flush its real final partial interval.
        signalStop(p,processPidFile,0);
        handler.postDelayed(()->{if(p.isAlive())p.destroyForcibly();},3000);
    }
    private void signalStop(Process p,File pidFile,int attempt){
        if(!p.isAlive())return;
        try{
            if(pidFile==null)throw new IOException("PID file not ready");
            int pid;try(BufferedReader reader=new BufferedReader(new FileReader(pidFile))){pid=Integer.parseInt(reader.readLine().trim());}
            if(pid<=0||pid==android.os.Process.myPid())throw new IOException("Invalid engine PID");
            if(p.isAlive())android.system.Os.kill(pid,android.system.OsConstants.SIGINT);
        }catch(Exception e){
            if(attempt<10)handler.postDelayed(()->signalStop(p,pidFile,attempt+1),100);
            else if(p.isAlive())p.destroy();
        }
    }
    private void measure(String host,int port,int seconds,long rate,boolean reverse,boolean bidirectional,double interval,String route,boolean wantLocation,boolean locationFg){
        String result="failed",error="";boolean ended=false;Thread stderrThread=null;
        int intervalSequence=0;
        try {
            JSONObject config=new JSONObject();config.put("host",host);config.put("port",port);config.put("duration_s",seconds);config.put("sample_interval_s",interval);config.put("location_association_policy","latest_valid_fix_in_session_no_age_limit");config.put("direction",bidirectional?"bidirectional":reverse?"phone_download":"phone_upload");config.put("protocol","TCP");config.put("rate_cap_bps",rate);config.put("rate_cap_scope","per_direction");config.put("route",route);config.put("location_requested",wantLocation);config.put("location_permission",LocationRecorder.permitted(this));config.put("radio_requested",true);config.put("radio_permission",RadioRecorder.permitted(this));config.put("network_observation","Android default network at interval receipt");config.put("device",Build.MANUFACTURER+" "+Build.MODEL);config.put("android",Build.VERSION.RELEASE);config.put("app_version","0.7.3");config.put("iperf_commit","c9b74229d0d9bfec6d2307b66b43c29a7665ad0b");
            synchronized(lock){session=Store.get(this).start(config,scene);}
            final long id=session;handler.post(()->{if(!finishing&&!destroyed){location=new LocationRecorder(this,id);location.start(wantLocation,locationFg);radio=new RadioRecorder(this,id);radio.start();}});
            File nativeExe=new File(getApplicationInfo().nativeLibraryDir,"libiperf_exec.so");
            if(!nativeExe.canExecute())throw new IOException("缺少可执行的 iperf3 引擎，请重新安装 APK");
            if(cancelled||destroyed)throw new InterruptedException("测量已停止");
            processPidFile=new File(getCacheDir(),"iperf-"+session+"-"+System.nanoTime()+".pid");
            java.util.List<String> command=TestOptions.command(nativeExe.getAbsolutePath(),host,port,seconds,rate,reverse,bidirectional,interval);
            command.add("--pidfile");command.add(processPidFile.getAbsolutePath());
            process=new ProcessBuilder(command).start();
            if(cancelled||timedOut||destroyed)stopProcess();
            final Process active=process;
            stderrThread=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(active.getErrorStream()))){String line;while((line=r.readLine())!=null)Store.get(this).event(id,"stderr: "+line);}catch(Exception ignored){}},"iperf-stderr");stderrThread.start();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(active.getInputStream()))){
                String line;while((line=r.readLine())!=null){
                    lastOutput=SystemClock.elapsedRealtime();Store.get(this).event(session,line);
                    try{
                        JSONObject event=new JSONObject(line);String type=event.optString("event");
                        if("start".equals(type)){
                            testStartedElapsed=SystemClock.elapsedRealtime();status="已连接，等待首个采样区间（"+TestOptions.intervalText(interval)+" 秒）…";
                            if(seconds>0)handler.post(()->{if(!finishing)handler.postDelayed(deadline,(seconds+30L)*1000);});
                        }else if("interval".equals(type)){
                            synchronized(lock){
                                JSONObject data=event.getJSONObject("data");intervalSequence++;
                                if(bidirectional&&!data.has("sum_bidir_reverse"))throw new JSONException("双向测量缺少下行区间");
                                JSONObject network=NetworkRecorder.snapshot(this);
                                long[] observation=radio==null?new long[]{0,-1}:radio.observation(SystemClock.elapsedRealtime());
                                for(String key:bidirectional?new String[]{"sum","sum_bidir_reverse"}:new String[]{"sum"}){
                                    JSONObject s=data.getJSONObject(key);boolean upload=s.getBoolean("sender");
                                    mbps=Store.get(this).sample(session,intervalSequence,data,key,scene,network,observation[0],observation[1]);
                                    rates.add(upload,s.getLong("bytes"),s.getDouble("seconds"),s.getDouble("end"),s.getDouble("bits_per_second"),SystemClock.elapsedRealtime());
                                    measuredSeconds=Math.max(measuredSeconds,s.getDouble("end"));lastSampleElapsed=SystemClock.elapsedRealtime();count++;
                                }
                            }
                            status="正在测量 · 已保存 "+count+" 条";
                        }else if("error".equals(type)){error=event.optString("data","iperf3 error");}
                        else if("end".equals(type)){
                            ended=true;JSONObject data=event.optJSONObject("data");
                            if(data!=null&&data.has("sum_sent")&&data.has("sum_received")){
                                summary=String.format(java.util.Locale.US,(reverse?"下行":"上行")+"结束汇总：发送 %.2f / 接收 %.2f Mbps",data.getJSONObject("sum_sent").getDouble("bits_per_second")/1e6,data.getJSONObject("sum_received").getDouble("bits_per_second")/1e6);
                                if(bidirectional)summary+=String.format(java.util.Locale.US,"\n下行结束汇总：发送 %.2f / 接收 %.2f Mbps",data.getJSONObject("sum_sent_bidir_reverse").getDouble("bits_per_second")/1e6,data.getJSONObject("sum_received_bidir_reverse").getDouble("bits_per_second")/1e6);
                            }
                        }
                    }catch(JSONException e){error="引擎输出解析失败："+e.getMessage();}
                }
            }
            int code=active.waitFor();
            if(cancelled){result="cancelled";error="";}else if(timedOut){result="timeout";error=timeoutMessage;}else if(destroyed){result="interrupted";error="系统结束了测量服务";}else if(code==0&&ended&&count>0&&error.isEmpty())result="completed";else if(error.isEmpty())error="测量未完成（引擎退出 "+code+"），请检查服务器、端口或网络";
        }catch(Exception e){result=cancelled?"cancelled":timedOut?"timeout":destroyed?"interrupted":"failed";error=cancelled?"":timedOut?timeoutMessage:e.getMessage()==null?e.toString():e.getMessage();}
        finally {
            finishing=true;
            if(process!=null&&process.isAlive())process.destroyForcibly();
            if(processPidFile!=null)processPidFile.delete();
            if(stderrThread!=null)try{stderrThread.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            try{synchronized(lock){if(session>0)Store.get(this).finish(session,result,error);}}catch(Exception e){result="failed";error="保存结束状态失败："+e.getMessage();}
            if(!"completed".equals(result)&&count>0)summary="已保存实际采样区间；未取得完整的双方结束汇总";
            final String finalResult=result,finalError=error;
            handler.post(()->{handler.removeCallbacks(deadline);handler.removeCallbacks(heartbeat);if(location!=null)location.stop();if(radio!=null)radio.stop();completed="completed".equals(finalResult);status=(completed?"测量完成":"cancelled".equals(finalResult)?"已停止，数据已保存":"测量未完成")+" · "+count+" 条"+(finalError.isEmpty()?"":"\n"+finalError);if(wake!=null&&wake.isHeld())wake.release();running=false;UploadJob.scheduleNow(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
        }
    }
    void journeyFinishing(){finishing=true;}
    void journeyFinished(String result,String error){handler.post(()->{completed="completed".equals(result);running=false;engineActive=false;status=(completed?"行程已结束":"cancelled".equals(result)?"行程已停止":"行程已中断")+" · "+count+" 条速率记录"+(error.isEmpty()?"":"\n"+error);summary="位置、无线信号、连接事件和速率已保存；数据块将按上传设置补传";UploadJob.scheduleNow(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});}
    @Override public void onTimeout(int startId,int fgsType){timedOut=true;timeoutMessage="Android 后台运行时限已到，记录已保留";if(journey!=null)journey.stop("timeout",timeoutMessage);else stopProcess();stopSelf();}
    public void onDestroy(){destroyed=true;if(journey!=null)journey.stop("interrupted","系统结束了采集服务");if(location!=null)location.stop();if(radio!=null)radio.stop();stopProcess();handler.removeCallbacks(deadline);handler.removeCallbacks(heartbeat);if(wake!=null&&wake.isHeld())wake.release();worker.shutdown();super.onDestroy();}
}

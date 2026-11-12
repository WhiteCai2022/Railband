package cn.railband.mvp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private EditText host,port,route,customRate,customDuration,customInterval,uploadUrl,uploadToken;
    private Spinner direction,duration,rate,interval;
    private CheckBox useLocation,journeyMode,uploadDuring;
    private TextView upLatest,downLatest,upAverage,downAverage,state,currentLabel,history,progressText,positionText,networkText,radioText,estimate,speedHint,resultText,uploadStatus;
    private RateChart chart;
    private ProgressBar progress;
    private Button start,stop,export,exportLast,delete;
    private static volatile boolean logsBusy;
    private long exportSession=-2;
    private String label="未标注";
    private Intent pendingStart;
    private boolean starting;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private long historyRefresh;
    private final Runnable tick=new Runnable(){public void run(){refresh();ui.postDelayed(this,MeasureService.running?(long)Math.max(100,Math.min(500,MeasureService.sampleIntervalSeconds*1000)):500);}};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private EditText field(LinearLayout root,String title,String value,int type){root.addView(text(title,13,Color.DKGRAY));EditText e=new EditText(this);e.setText(value);e.setSingleLine();e.setTextSize(16);e.setInputType(type);root.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;}
    private Spinner spinner(LinearLayout root,String title,String[] items){root.addView(text(title,13,Color.DKGRAY));Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items));root.addView(s,new LinearLayout.LayoutParams(-1,dp(48)));return s;}
    private Button button(String title){Button b=new Button(this);b.setText(title);b.setAllCaps(false);return b;}
    private TextView[] rateColumn(LinearLayout row,String title,int color){
        LinearLayout col=new LinearLayout(this);col.setOrientation(1);col.setPadding(dp(10),dp(8),dp(6),dp(8));col.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.setMargins(dp(3),dp(8),dp(3),dp(5));row.addView(col,params);
        col.addView(text(title,17,color));TextView latest=text("最近一次 —",21,color),average=text("平均 — Mbps",13,Color.DKGRAY);col.addView(latest);col.addView(average);return new TextView[]{latest,average};
    }
    public void onCreate(Bundle saved){super.onCreate(saved);
        if(saved!=null){exportSession=saved.getLong("export_session",-2);if(exportSession!=-2)logsBusy=true;}
        if(!MeasureService.running)Store.get(this).recover();
        UploadJob.scheduleNow(this);
        label=getPreferences(0).getString("label","未标注");
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(16),dp(22),dp(30));root.setBackgroundColor(Color.rgb(246,248,252));scroll.addView(root);setContentView(scroll);
        if(Build.VERSION.SDK_INT>=30)scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(b.left,b.top,b.right,b.bottom);return insets;});
        root.addView(text("轨途测速",28,Color.rgb(20,43,69)));root.addView(text("行程采集与停滞恢复 · 0.7.3",14,Color.DKGRAY));
        chart=new RateChart(this);root.addView(chart,new LinearLayout.LayoutParams(-1,dp(175)));
        LinearLayout rateRow=new LinearLayout(this);TextView[] up=rateColumn(rateRow,"↑ 上行",RateChart.UP),down=rateColumn(rateRow,"↓ 下行",RateChart.DOWN);upLatest=up[0];upAverage=up[1];downLatest=down[0];downAverage=down[1];root.addView(rateRow);
        speedHint=text("单位 Mbps · 平均按实际区间时长计算",12,Color.DKGRAY);root.addView(speedHint);
        state=text("准备就绪",15,Color.DKGRAY);root.addView(state);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(10)));
        progressText=text("准备开始",13,Color.DKGRAY);root.addView(progressText);resultText=text("",13,Color.DKGRAY);root.addView(resultText);
        LinearLayout actions=new LinearLayout(this);start=button("一键测速");stop=button("停止并保存");actions.addView(start,new LinearLayout.LayoutParams(0,dp(56),1));actions.addView(stop,new LinearLayout.LayoutParams(0,dp(56),1));root.addView(actions);
        root.addView(text("当前位置",17,Color.rgb(20,43,69)));positionText=text("测量开始后获取位置",13,Color.DKGRAY);root.addView(positionText);
        root.addView(text("测速网络与蜂窝信号",17,Color.rgb(20,43,69)));
        networkText=text("正在识别网络",13,Color.DKGRAY);root.addView(networkText);
        radioText=text("测量开始后获取 5G / 4G 信号",13,Color.DKGRAY);root.addView(radioText);
        root.addView(text("Wi-Fi 和 VPN 下也可记录手机蜂窝信号，但不能把测速结果当成蜂窝带宽。无线指标由手机决定刷新频率，旧观测保留时间差。",12,Color.DKGRAY));
        currentLabel=text("当前场景："+label,17,Color.rgb(20,43,69));root.addView(currentLabel);
        String[][] labels={{"未标注","城区","开阔地"},{"山区","隧道","车站"}};
        for(String[] row:labels){LinearLayout line=new LinearLayout(this);for(String value:row){Button b=button(value);b.setTextSize(13);line.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->changeLabel(value));}root.addView(line);}
        Button custom=button("自定义场景标签");root.addView(custom);custom.setOnClickListener(v->{EditText e=new EditText(this);e.setHint("例如：隧道入口 / 车厢靠窗");new AlertDialog.Builder(this).setTitle("手动标注").setView(e).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()&&s.length()<=80)changeLabel(s);else toast("请输入 1–80 个字符");}).show();});
        root.addView(text("测量设置",19,Color.rgb(20,43,69)));
        journeyMode=new CheckBox(this);journeyMode.setText("行程模式：断网自动重连，位置和信号持续记录");journeyMode.setChecked(getPreferences(0).getBoolean("journey",true));root.addView(journeyMode);
        direction=spinner(root,"测量方向",new String[]{"仅测上行 → 服务器","仅测下行 ← 服务器","同时测上下行 ↕"});direction.setSelection(getPreferences(0).getInt("direction_v3",2));
        rate=spinner(root,"速率上限",new String[]{"5 Mbps","10 Mbps","20 Mbps","50 Mbps","100 Mbps","不限速","自定义 Mbps"});rate.setSelection(getPreferences(0).getInt("rate_choice",0));
        customRate=field(root,"自定义限速（Mbps，仅自定义时可编辑）",getPreferences(0).getString("custom_rate","5"),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        duration=spinner(root,"测量时长",new String[]{"10 秒","30 秒","60 秒","5 分钟","10 分钟","持续测量，直到停止","自定义秒数"});duration.setSelection(getPreferences(0).getInt("duration",0));
        customDuration=field(root,"自定义时长（秒，1–86400）",getPreferences(0).getString("custom_duration","120"),InputType.TYPE_CLASS_NUMBER);
        interval=spinner(root,"采样间隔",new String[]{"0.1 秒","0.5 秒","1 秒","2 秒","5 秒","10 秒","30 秒","60 秒","自定义秒数"});interval.setSelection(getPreferences(0).getInt("interval_choice",2));
        customInterval=field(root,"自定义采样间隔（秒，0.1–60）",getPreferences(0).getString("custom_interval","1"),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(text("每个间隔保存一组真实测量结果，结束时可能有不足一个间隔的记录。定位独立请求，未刷新时每条记录沿用本次最近位置并保留位置时间；首次定位前坐标为空。",12,Color.DKGRAY));
        estimate=text("",13,Color.DKGRAY);root.addView(estimate);
        root.addView(text("不限速会尽量占用可用带宽。行程模式按总经过时间计时，包含断网等待；持续测量请手动停止。Android 后台时限或进程结束仍可能中断行程。",12,Color.DKGRAY));
        useLocation=new CheckBox(this);useLocation.setText("测量时记录地理位置");useLocation.setChecked(getPreferences(0).getBoolean("location",true));root.addView(useLocation);
        root.addView(text("首次请选择“使用期间允许”和精确位置。定位约每秒请求一次，实际频率由系统决定；未刷新时沿用本次最近位置，并记录位置时间与距采样的时差。配置下方上传后，位置会随记录加密传到服务器。",12,Color.DKGRAY));
        root.addView(text("读取 SINR、RSRP、PCI 需要开启系统定位并授予精确位置权限；即使不记录 GPS 坐标，也需要这项权限。拒绝后测速仍可继续，信号字段为空。",12,Color.DKGRAY));
        host=field(root,"服务器地址",getPreferences(0).getString("host","62.234.140.204"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        port=field(root,"端口",getPreferences(0).getString("port","5201"),InputType.TYPE_CLASS_NUMBER);
        route=field(root,"路线 / 行程备注",getPreferences(0).getString("route",""),InputType.TYPE_CLASS_TEXT);
        root.addView(text("测速结果也受服务器、路径和 VPN 影响。上行实时显示手机发送端速率，结束后查看双方汇总。",12,Color.DKGRAY));
        root.addView(text("服务器数据同步",19,Color.rgb(20,43,69)));
        android.content.SharedPreferences uploadPrefs=getSharedPreferences("upload",MODE_PRIVATE);
        uploadUrl=field(root,"上传地址（HTTPS）",uploadPrefs.getString("url","https://62.234.140.204/api/upload"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        uploadToken=field(root,"上传密钥（从服务器管理信息复制）",uploadPrefs.getString("token",""),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        uploadDuring=new CheckBox(this);uploadDuring.setText("测速期间同步数据块（会占用网络，可能影响结果）");uploadDuring.setChecked(uploadPrefs.getBoolean("during_measurement",false));root.addView(uploadDuring);
        Button sync=button("保存设置并上传待同步记录");root.addView(sync);
        uploadStatus=text("",13,Color.DKGRAY);root.addView(uploadStatus);
        root.addView(text("仅在填写上传密钥后自动同步。行程约每分钟或每 512 KiB 封存数据块，默认在测速间隙或结束后补传。勾选上方选项并保存可在测速期间补传。断网先保存在手机，网页按行程下载和删除。普通模式的旧日志仍有 128 MiB 限制。",12,Color.DKGRAY));
        sync.setOnClickListener(v->{String address=uploadUrl.getText().toString().trim(),key=uploadToken.getText().toString().trim();if(!address.startsWith("https://")||!address.endsWith("/api/upload")||key.length()<16){toast("请填写 HTTPS 上传地址和有效密钥");return;}uploadPrefs.edit().putString("url",address).putString("token",key).putBoolean("during_measurement",uploadDuring.isChecked()).putString("status","正在安排上传").apply();Store.get(this).retryFailures();UploadJob.scheduleNow(this);refresh();toast("上传设置已保存，已请求立即同步");});
        root.addView(text("日志管理",19,Color.rgb(20,43,69)));
        exportLast=button("导出最后一次日志");root.addView(exportLast);exportLast.setOnClickListener(v->pickExport(true));
        export=button("导出全部日志（含位置、信号 JSONL）");root.addView(export);export.setOnClickListener(v->pickExport(false));
        delete=button("删除日志");root.addView(delete);delete.setOnClickListener(v->deleteMenu());
        root.addView(text("最近的测量",19,Color.rgb(20,43,69)));history=text(Store.get(this).history(),13,Color.DKGRAY);root.addView(history);
        Button about=button("记录说明与开源许可");root.addView(about);about.setOnClickListener(v->{String license="";try(InputStream in=getAssets().open("IPERF-LICENSE.txt")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);license=out.toString("UTF-8");}catch(Exception ignored){}new AlertDialog.Builder(this).setTitle("数据与许可").setMessage("数据、位置和无线观测先保存在本机；配置上传后，行程数据分块补传到服务器数据库。卸载 APP 会删除手机本地记录，请先导出。\n速率、位置和基站观测各保留时间戳。NR SS-SINR 与 LTE RSSNR 是不同指标；没有上报的信号字段为空，不补零。VPN 底层网络仅作为提示，不据此确认测速路径。\n持续测量不设置应用时长上限，但受网络、系统运行限制；Android 15+ 的 dataSync 后台运行通常有24小时内累计6小时配额。\n\n基于 ESnet iperf3，commit c9b7422。\n\n"+license).setPositiveButton("知道了",null).show();});
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){updateOptions();}public void onNothingSelected(AdapterView<?> p){}};
        rate.setOnItemSelectedListener(listener);duration.setOnItemSelectedListener(listener);direction.setOnItemSelectedListener(listener);interval.setOnItemSelectedListener(listener);
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateOptions();}public void afterTextChanged(Editable e){}};
        customRate.addTextChangedListener(watcher);customDuration.addTextChangedListener(watcher);customInterval.addTextChangedListener(watcher);
        start.setOnClickListener(v->begin());stop.setOnClickListener(v->{if(MeasureService.running)startService(new Intent(this,MeasureService.class).setAction("STOP"));});refresh();
    }
    private long chosenRate(){int p=rate.getSelectedItemPosition();return TestOptions.rate(p==6?customRate.getText().toString():new String[]{"5","10","20","50","100","0","0"}[p],p==5);}
    private int chosenDuration(){int p=duration.getSelectedItemPosition();return TestOptions.duration(p==6?customDuration.getText().toString():new String[]{"10","30","60","300","600","0","0"}[p],p==5);}
    private double chosenInterval(){int p=interval.getSelectedItemPosition();return TestOptions.interval(p==8?customInterval.getText().toString():new String[]{"0.1","0.5","1","2","5","10","30","60","1"}[p]);}
    private void updateOptions(){
        if(estimate==null)return;boolean enabled=!MeasureService.running&&!starting&&!logsBusy;
        customRate.setEnabled(enabled&&rate.getSelectedItemPosition()==6);customDuration.setEnabled(enabled&&duration.getSelectedItemPosition()==6);customInterval.setEnabled(enabled&&interval.getSelectedItemPosition()==8);
        try{chosenInterval();long bps=chosenRate();int seconds=chosenDuration();boolean both=direction.getSelectedItemPosition()==2;double multiplier=both?2:1;estimate.setText((both?"上下行各应用所选限速，互相可能影响。\n":"")+(bps==0?"不限速 · 流量取决于实际网络速度":seconds==0?String.format(Locale.US,"合计约 %.1f MB/分钟（不含协议开销）",bps*60.0/8e6*multiplier):String.format(Locale.US,"预计合计约 %.1f MB（不含协议开销）",bps*seconds/8e6*multiplier)));}catch(IllegalArgumentException e){estimate.setText(e.getMessage());}
    }
    private void changeLabel(String value){label=value;getPreferences(0).edit().putString("label",label).apply();if(MeasureService.running)startService(new Intent(this,MeasureService.class).setAction("LABEL").putExtra("scene",label));currentLabel.setText("当前场景："+label);}
    private void begin(){
        if(logsBusy){toast("请等待日志操作完成");return;}
        String h=host.getText().toString().trim();int p;long bps;int seconds;double intervalSeconds;
        try{p=Integer.parseInt(port.getText().toString());bps=chosenRate();seconds=chosenDuration();intervalSeconds=chosenInterval();}catch(IllegalArgumentException e){toast(e.getMessage());return;}
        if(h.isEmpty()||h.length()>253||!h.matches("[A-Za-z0-9.:%_-]+")||p<1||p>65535){toast("请检查服务器地址和端口（无需 http://）");return;}
        getPreferences(0).edit().putString("host",h).putString("port",Integer.toString(p)).putString("route",route.getText().toString()).putInt("direction_v3",direction.getSelectedItemPosition()).putInt("duration",duration.getSelectedItemPosition()).putInt("rate_choice",rate.getSelectedItemPosition()).putString("custom_rate",customRate.getText().toString()).putString("custom_duration",customDuration.getText().toString()).putInt("interval_choice",interval.getSelectedItemPosition()).putString("custom_interval",customInterval.getText().toString()).putBoolean("location",useLocation.isChecked()).putBoolean("journey",journeyMode.isChecked()).apply();
        pendingStart=new Intent(this,MeasureService.class).putExtra("journey",journeyMode.isChecked()).putExtra("host",h).putExtra("port",p).putExtra("route",route.getText().toString()).putExtra("reverse",direction.getSelectedItemPosition()==1).putExtra("bidirectional",direction.getSelectedItemPosition()==2).putExtra("seconds",seconds).putExtra("interval_s",intervalSeconds).putExtra("rate_bps",bps).putExtra("location",useLocation.isChecked()).putExtra("scene",label);
        starting=true;refresh();ArrayList<String> permissions=new ArrayList<>();
        if(!RadioRecorder.permitted(this)){permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!permissions.isEmpty())requestPermissions(permissions.toArray(new String[0]),9);else launchPending();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==9)launchPending();}
    private void launchPending(){
        if(pendingStart==null){starting=false;return;}
        if(pendingStart.getBooleanExtra("location",false)&&(!LocationRecorder.permitted(this)||!LocationRecorder.enabled(this)))toast("定位权限或系统定位未开启；本次测速继续，坐标留空");
        if(!RadioRecorder.permitted(this)||!LocationRecorder.enabled(this))toast("精确位置权限或系统定位未开启；测速继续，无线信号留空");
        try{startForegroundService(pendingStart);}catch(Exception e){toast("无法启动："+e.getMessage());}pendingStart=null;ui.postDelayed(()->{starting=false;refresh();},1000);
    }
    private String time(double value){long s=(long)Math.max(0,value);return String.format(Locale.US,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
    private void refresh(){
        boolean r=MeasureService.running;boolean available=!r&&!starting&&!logsBusy;start.setEnabled(available);stop.setEnabled(r);export.setEnabled(available);exportLast.setEnabled(available);delete.setEnabled(available);for(View v:new View[]{host,port,route,direction,duration,rate,interval,useLocation,journeyMode})v.setEnabled(available);updateOptions();
        state.setText(starting&&!r?"正在准备…":MeasureService.status);long now=SystemClock.elapsedRealtime();long age=MeasureService.lastSampleElapsed==0?0:now-MeasureService.lastSampleElapsed;
        boolean stale=r&&MeasureService.count>0&&age>TestOptions.sampleStaleMs(MeasureService.sampleIntervalSeconds);
        LiveRates.Snapshot snapshot=MeasureService.rates.snapshot();chart.show(snapshot,MeasureService.sampleIntervalSeconds);showRate(upLatest,upAverage,snapshot.up,r,now);showRate(downLatest,downAverage,snapshot.down,r,now);
        speedHint.setText(stale?"已 "+age/1000+" 秒无新速率；曲线不补零":"单位 Mbps · 最近一次=最新采样区间（设置 "+TestOptions.intervalText(MeasureService.sampleIntervalSeconds)+" 秒）\n平均=累计字节 / 实际区间时长；上行取手机发送端，下行取手机接收端");
        resultText.setText(MeasureService.summary);
        double elapsed=MeasureService.measuredSeconds;if(r&&MeasureService.testStartedElapsed>0)elapsed=Math.max(elapsed,(now-MeasureService.testStartedElapsed)/1000.0);
        int total=MeasureService.plannedSeconds;progress.setIndeterminate(r&&(total==0||MeasureService.testStartedElapsed==0));progress.setProgress(TestOptions.progress(elapsed,total,MeasureService.completed));
        progressText.setText(MeasureService.session==0?"准备开始":total==0?(r?"持续测量 · 已运行 ":"已记录 ")+time(elapsed):time(Math.min(elapsed,total))+" / "+time(total)+(r&&elapsed>=total?" · 正在收尾":MeasureService.completed?" · 100%":""));
        positionText.setText(LocationRecorder.display());
        networkText.setText(NetworkRecorder.display(this));radioText.setText(RadioRecorder.display());
        if(r){label=MeasureService.scene;currentLabel.setText("当前场景："+label);}if(!logsBusy&&now-historyRefresh>2000){history.setText(Store.get(this).history());historyRefresh=now;}
        if(uploadStatus!=null){android.content.SharedPreferences prefs=getSharedPreferences("upload",MODE_PRIVATE);uploadStatus.setText("待同步 "+Store.get(this).chunkCount()+" 块 / "+Store.get(this).pendingUploadCount()+" 次旧测量 · 需处理 "+Store.get(this).uploadErrorCount()+" 项 · "+(prefs.getString("token","").isEmpty()?"未配置上传密钥":prefs.getString("status","等待测速完成")));}
    }
    protected void onResume(){super.onResume();ui.post(tick);}protected void onPause(){ui.removeCallbacks(tick);super.onPause();}
    protected void onSaveInstanceState(Bundle out){out.putLong("export_session",exportSession);super.onSaveInstanceState(out);}
    private void showRate(TextView latest,TextView average,LiveRates.Direction data,boolean running,long now){
        boolean absent=data.samples==0||running&&now-data.receivedMs>TestOptions.sampleStaleMs(MeasureService.sampleIntervalSeconds);
        latest.setText("最近一次\n"+(absent?"—":String.format(Locale.US,"%.2f",data.latest)));
        average.setText("平均 "+(data.samples==0?"—":String.format(Locale.US,"%.2f",data.average))+" Mbps");
    }
    private void pickExport(boolean lastOnly){
        if(MeasureService.running||starting||logsBusy){toast("请先停止测量并等待日志操作完成");return;}
        long id=Store.get(this).latestSessionId();if(id==0){toast("暂无日志可导出");return;}
        exportSession=lastOnly?id:-1;logsBusy=true;refresh();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"railband-"+(lastOnly?"last-"+id+"-":"all-")+System.currentTimeMillis()+".jsonl");
        try{startActivityForResult(i,101);}catch(Exception e){exportSession=-2;logsBusy=false;toast("无法打开保存位置："+e.getMessage());}
    }
    private void deleteMenu(){
        if(MeasureService.running||starting||logsBusy){toast("请先停止测量");return;}
        long latest=Store.get(this).latestSessionId();if(latest==0){toast("暂无日志可删除");return;}
        new AlertDialog.Builder(this).setTitle("删除哪些日志？").setItems(new String[]{"最后一次日志 #"+latest,"全部日志"},(dialog,which)->{
            final Long id=which==0?Long.valueOf(latest):null;
            new AlertDialog.Builder(this).setTitle("确认删除"+(id==null?"全部日志":"日志 #"+id)).setMessage("将删除手机上的测速、无线信号、位置和场景标签，无法撤销。服务器上已上传的记录需在网页单独删除。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{
                if(MeasureService.running||starting||logsBusy){toast("当前有操作进行中，未删除");return;}
                logsBusy=true;refresh();new Thread(()->{
                    try{int count=Store.get(this).deleteLogs(id);runOnUiThread(()->{if(id==null||id==MeasureService.session)MeasureService.clearResults();toast("已删除 "+count+" 次测量日志");});}
                    catch(Exception e){runOnUiThread(()->toast("删除失败："+e.getMessage()));}
                    finally{runOnUiThread(()->{logsBusy=false;historyRefresh=0;refresh();});}
                },"delete-logs").start();
            }).show();
        }).setNegativeButton("取消",null).show();
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=101)return;
        final long selected=exportSession;exportSession=-2;
        if(result!=RESULT_OK||data==null||data.getData()==null||selected==-2){logsBusy=false;return;}
        logsBusy=true;new Thread(()->{
            try{OutputStream out=getContentResolver().openOutputStream(data.getData());if(out==null)throw new IOException("无法打开保存位置");Store.get(this).export(out,selected>0?Long.valueOf(selected):null);runOnUiThread(()->toast(selected>0?"最后一次日志已导出（含位置、信号）":"全部日志已导出（含位置、信号）"));}
            catch(Exception e){runOnUiThread(()->toast("导出失败："+e.getMessage()));}
            finally{runOnUiThread(()->{logsBusy=false;refresh();});}
        },"export-logs").start();
    }
}

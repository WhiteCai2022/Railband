package cn.railband.mvp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.*;
import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

/** Stores modem observations independently of iperf's much faster interval clock. */
final class RadioRecorder {
    static volatile String state="无线信号未开始";
    static volatile JSONObject latestValues;
    static volatile long latestModemMs;
    private final Context context;
    private final long session;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TelephonyManager base;
    private volatile boolean active;
    private volatile int subscriptionId=SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile long latestId;
    private String lastFingerprint="";
    private final Runnable poll=new Runnable(){public void run(){if(!active)return;refresh();handler.postDelayed(this,5000);}};

    RadioRecorder(Context context,long session){this.context=context;this.session=session;base=(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);}
    static boolean permitted(Context context){return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    void start(){clear("正在等待 5G / 4G 小区信息");active=true;handler.post(poll);}
    void stop(){active=false;handler.removeCallbacks(poll);}
    private synchronized void clear(String why){latestId=0;latestValues=null;latestModemMs=0;lastFingerprint="";state=why;}
    private void refresh(){
        if(!permitted(context)){clear("未获精确位置权限，SINR / RSRP / PCI 留空");return;}
        if(!LocationRecorder.enabled(context)){clear("系统定位关闭，小区信息不可用");return;}
        if(base==null||!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)){clear("此设备无蜂窝无线接口");return;}
        int sub=SubscriptionManager.getDefaultDataSubscriptionId();
        if(sub!=subscriptionId){clear("数据 SIM 已切换，等待新小区信息");subscriptionId=sub;}
        if(sub==SubscriptionManager.INVALID_SUBSCRIPTION_ID){clear("无默认移动数据 SIM");return;}
        final int requestedSub=sub;
        try {
            TelephonyManager manager=base.createForSubscriptionId(sub);
            List<CellInfo> cached=manager.getAllCellInfo();
            if(cached!=null)accept(cached,manager,requestedSub,"cache");
            if(Build.VERSION.SDK_INT>=29)manager.requestCellInfoUpdate(command->handler.post(command),new TelephonyManager.CellInfoCallback(){
                @Override public void onCellInfo(List<CellInfo> cells){if(active)accept(cells,manager,requestedSub,"update");}
                @Override public void onError(int errorCode,Throwable detail){if(active&&requestedSub==subscriptionId)state="小区刷新失败（代码 "+errorCode+"），保留已有观测";}
            });
        }catch(SecurityException e){clear("无权读取小区信息，请允许精确位置");}
        catch(UnsupportedOperationException e){clear("设备不支持小区信息更新");}
        catch(RuntimeException e){state="小区信息暂不可用："+e.getClass().getSimpleName();}
    }
    private static boolean serving(CellInfo c){int status=c.getCellConnectionStatus();return c.isRegistered()||status==CellInfo.CONNECTION_PRIMARY_SERVING||status==CellInfo.CONNECTION_SECONDARY_SERVING;}
    private static long modemTime(CellInfo c){return Build.VERSION.SDK_INT>=30?c.getTimestampMillis():c.getTimeStamp()/1000000;}
    private static Object value(int n){return n==Integer.MAX_VALUE?JSONObject.NULL:n;}
    private static void put(JSONObject o,String key,int n)throws Exception{o.put(key,value(n));}
    private void accept(List<CellInfo> cells,TelephonyManager manager,int sub,String source){
        if(!active||sub!=subscriptionId||cells==null)return;
        try {
            CellInfoNr nr=null;CellInfoLte lte=null;
            for(CellInfo c:cells){if(c==null||!serving(c))continue;if(Build.VERSION.SDK_INT>=29&&nr==null&&c instanceof CellInfoNr)nr=(CellInfoNr)c;else if(lte==null&&c instanceof CellInfoLte)lte=(CellInfoLte)c;}
            SignalStrength strength=Build.VERSION.SDK_INT>=29?manager.getSignalStrength():null;
            CellSignalStrengthNr nrFallback=null;CellSignalStrengthLte lteFallback=null;
            long strengthTime=0;
            if(strength!=null){
                strengthTime=Build.VERSION.SDK_INT>=30?strength.getTimestampMillis():0;
                if(nr==null){List<CellSignalStrengthNr> values=strength.getCellSignalStrengths(CellSignalStrengthNr.class);if(!values.isEmpty())nrFallback=values.get(0);}
                if(lte==null){List<CellSignalStrengthLte> values=strength.getCellSignalStrengths(CellSignalStrengthLte.class);if(!values.isEmpty())lteFallback=values.get(0);}
            }
            if(nr==null&&lte==null&&nrFallback==null&&lteFallback==null){clear("未上报服务中的 5G / 4G 小区");return;}
            JSONObject row=new JSONObject();row.put("subscription_id",sub);row.put("source",source);
            row.put("radio_rat",nr!=null||nrFallback!=null?"NR":"LTE");
            long modem=0;
            if(nr!=null||nrFallback!=null){
                CellSignalStrengthNr signal=nr!=null?(CellSignalStrengthNr)nr.getCellSignalStrength():nrFallback;
                put(row,"nr_ss_sinr_db",signal.getSsSinr());put(row,"nr_ss_rsrp_dbm",signal.getSsRsrp());put(row,"nr_csi_sinr_db",signal.getCsiSinr());put(row,"nr_csi_rsrp_dbm",signal.getCsiRsrp());
                if(nr!=null){CellIdentityNr identity=(CellIdentityNr)nr.getCellIdentity();put(row,"nr_pci",identity.getPci());row.put("nr_registered",nr.isRegistered());row.put("nr_connection_status",nr.getCellConnectionStatus());}
                row.put("nr_signal_source",nr!=null?"cell_info":"signal_strength_cache");
                long time=nr!=null?modemTime(nr):strengthTime;row.put("nr_modem_elapsed_ms",time);
            }
            if(lte!=null||lteFallback!=null){
                CellSignalStrengthLte signal=lte!=null?lte.getCellSignalStrength():lteFallback;
                put(row,"lte_rsrp_dbm",signal.getRsrp());put(row,"lte_rssnr_db",signal.getRssnr());
                if(lte!=null){CellIdentityLte identity=lte.getCellIdentity();put(row,"lte_pci",identity.getPci());row.put("lte_registered",lte.isRegistered());row.put("lte_connection_status",lte.getCellConnectionStatus());}
                row.put("lte_signal_source",lte!=null?"cell_info":"signal_strength_cache");
                long time=lte!=null?modemTime(lte):strengthTime;row.put("lte_modem_elapsed_ms",time);
            }
            modem="NR".equals(row.optString("radio_rat"))?row.optLong("nr_modem_elapsed_ms",0):row.optLong("lte_modem_elapsed_ms",0);
            long now=SystemClock.elapsedRealtime();if(modem<=0||modem>now)modem=0;
            row.put("modem_elapsed_ms",modem);
            String fingerprint=row.toString().replace("\"source\":\"cache\"","\"source\":\"update\"");
            if(fingerprint.equals(lastFingerprint))return;
            long id=Store.get(context).radio(session,row);
            if(!active||sub!=subscriptionId)return;
            synchronized(this){lastFingerprint=fingerprint;latestId=id;latestModemMs=modem;latestValues=row;}
            state=(nr!=null||nrFallback!=null?"5G NR":"4G LTE")+" 信号观测已保存";
        }catch(Exception e){state="无线观测保存失败："+e.getClass().getSimpleName();}
    }
    synchronized long[] observation(long now){
        if(!active||latestId<=0||SubscriptionManager.getDefaultDataSubscriptionId()!=subscriptionId)return new long[]{0,-1};
        return new long[]{latestId,latestModemMs>0&&now>=latestModemMs?now-latestModemMs:-1};
    }
    static String display(){
        JSONObject o=latestValues;if(o==null)return state;
        long age=latestModemMs>0?SystemClock.elapsedRealtime()-latestModemMs:-1;
        String time=age<0?"观测时间未知":String.format(Locale.US,"观测于 %.1f 秒前",age/1000.0);
        if("NR".equals(o.optString("radio_rat")))return "5G NR · SS-SINR "+shown(o,"nr_ss_sinr_db"," dB")+" · SS-RSRP "+shown(o,"nr_ss_rsrp_dbm"," dBm")+" · PCI "+shown(o,"nr_pci","")+"\n"+time+(o.has("lte_rsrp_dbm")?"；同时记录 4G LTE 信号":"");
        return "4G LTE · RSSNR "+shown(o,"lte_rssnr_db"," dB")+" · RSRP "+shown(o,"lte_rsrp_dbm"," dBm")+" · PCI "+shown(o,"lte_pci","")+"\n"+time;
    }
    private static String shown(JSONObject o,String key,String unit){return o.isNull(key)||!o.has(key)?"—":o.optString(key)+unit;}
}

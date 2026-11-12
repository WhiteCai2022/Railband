package cn.railband.mvp;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;

/** Registers only during measurement; samples may refer to an older fix without creating a new fix. */
final class LocationRecorder implements LocationListener {
    static volatile String state="定位未开始";
    static volatile Location latest;
    private final Context context;
    private final LocationManager manager;
    private final long session;
    private boolean active;
    LocationRecorder(Context c,long id){context=c;session=id;manager=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);}
    static boolean permitted(Context c){return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    static boolean enabled(Context c){LocationManager m=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);return m!=null&&m.isLocationEnabled();}
    void note(String value){state=value;try{Store.get(context).locationStatus(session,value);}catch(RuntimeException e){state="定位状态保存失败："+e.getMessage();}}
    void start(boolean requested,boolean foregroundAllowed){
        latest=null;active=true;
        if(!requested){note("本次未启用定位");return;}
        if(!permitted(context)){note("未获定位权限，坐标留空");return;}
        if(!foregroundAllowed||!enabled(context)){note("系统定位关闭或不可用，坐标留空；开启后重新测量");return;}
        int registered=0;boolean fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){
            if(provider.equals(LocationManager.GPS_PROVIDER)&&!fine)continue;
            try{if(manager.getAllProviders().contains(provider)){manager.requestLocationUpdates(provider,1000,0,this,Looper.getMainLooper());registered++;}}
            catch(SecurityException e){note("定位权限不可用："+provider);}catch(IllegalArgumentException e){note("定位来源不可用："+provider);}
        }
        note(registered==0?"无可用定位来源，坐标留空":fine?"等待定位（建议到室外或窗边）":"仅获大致位置权限，等待定位");
    }
    public void onLocationChanged(Location fix){
        if(!active)return;
        if(!Double.isFinite(fix.getLatitude())||!Double.isFinite(fix.getLongitude())||Math.abs(fix.getLatitude())>90||Math.abs(fix.getLongitude())>180||!TestOptions.usableLocation(SystemClock.elapsedRealtime(),fix.getElapsedRealtimeNanos()/1000000))return;
        try{Store.get(context).location(session,fix);}catch(RuntimeException e){state="定位保存失败："+e.getMessage();stop();return;}
        if(latest==null||fix.getElapsedRealtimeNanos()>=latest.getElapsedRealtimeNanos())latest=new Location(fix);
        state="已收到定位";
    }
    public void onProviderEnabled(String provider){if(active)note("定位来源已开启："+provider);}
    public void onProviderDisabled(String provider){if(active)note("定位来源已关闭："+provider);}
    @SuppressWarnings("deprecation") public void onStatusChanged(String provider,int status,Bundle extras){}
    void stop(){active=false;if(manager!=null)try{manager.removeUpdates(this);}catch(SecurityException ignored){}}
    static String display(){
        Location fix=latest;if(fix==null)return state;
        long age=SystemClock.elapsedRealtime()-fix.getElapsedRealtimeNanos()/1000000;
        if(age<0)return state;
        return String.format(Locale.US,"%.6f, %.6f\n精度 %s · %s · %s%d 秒前",fix.getLatitude(),fix.getLongitude(),fix.hasAccuracy()?String.format(Locale.US,"±%.0f m",fix.getAccuracy()):"未知",fix.getProvider(),age>10000?"沿用最近位置 · ":"",age/1000);
    }
}

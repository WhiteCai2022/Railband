package cn.railband.mvp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONObject;

/** The process uses Android's default network; classify that route at each iperf interval. */
final class NetworkRecorder {
    private NetworkRecorder() {}

    static JSONObject snapshot(Context context) {
        JSONObject result=new JSONObject();
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network=manager==null?null:manager.getActiveNetwork();
            NetworkCapabilities caps=network==null?null:manager.getNetworkCapabilities(network);
            boolean vpn=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            boolean wifi=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean cellular=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
            String type=caps==null?network==null?"none":"unknown":vpn?"vpn":wifi?"wifi":cellular?"cellular":"other";
            result.put("network_type",type);
            result.put("network_handle",network==null?"":Long.toString(network.getNetworkHandle()));
            result.put("vpn_active",caps==null&&network!=null?JSONObject.NULL:vpn);
            result.put("transport_wifi",caps==null&&network!=null?JSONObject.NULL:wifi);
            result.put("transport_cellular",caps==null&&network!=null?JSONObject.NULL:cellular);
            result.put("underlying_hint",vpn?(wifi&&cellular?"wifi+cellular":wifi?"wifi":cellular?"cellular":"unknown"):type);
        } catch(Exception e) {
            try {result.put("network_type","unknown");result.put("underlying_hint","unknown");}catch(Exception ignored){}
        }
        return result;
    }

    static String display(Context context) {
        JSONObject n=snapshot(context);String type=n.optString("network_type","unknown");
        if("vpn".equals(type))return "当前网络：VPN · 底层网络提示："+name(n.optString("underlying_hint","unknown"))+"（不能据此确认实际路径）";
        return "当前网络："+name(type);
    }

    private static String name(String type) {
        switch(type) {case "wifi":return "Wi-Fi";case "cellular":return "移动数据";case "none":return "无网络";case "unknown":return "未知";default:return type;}
    }
}

package cn.railband.mvp;

import java.math.BigDecimal;
import java.util.*;

/** Pure Java options shared by the UI, service and command tests. Zero means unlimited. */
public final class TestOptions {
    public static long rate(String text,boolean unlimited) {
        if(unlimited)return 0;
        try {
            long value=new BigDecimal(text.trim()).multiply(new BigDecimal("1000000")).longValueExact();
            if(value<1||value>1000000000000L)throw new NumberFormatException();
            return value;
        }catch(Exception e){throw new IllegalArgumentException("限速请输入大于 0、最多 1000000 的 Mbps 数值（支持小数）");}
    }
    public static int duration(String text,boolean unlimited) {
        if(unlimited)return 0;
        try{int seconds=Integer.parseInt(text.trim());if(seconds<1||seconds>86400)throw new NumberFormatException();return seconds;}
        catch(Exception e){throw new IllegalArgumentException("时长请输入 1–86400 秒，或选择持续测量");}
    }
    public static double interval(String text){
        try{double value=Double.parseDouble(text.trim());if(!Double.isFinite(value)||value<0.1||value>60)throw new NumberFormatException();return value;}
        catch(Exception e){throw new IllegalArgumentException("采样间隔请输入 0.1–60 秒（支持小数）");}
    }
    public static String intervalText(double seconds){return BigDecimal.valueOf(seconds).stripTrailingZeros().toPlainString();}
    public static long sampleStaleMs(double interval){return Math.max(3000,(long)Math.ceil(interval*2000+1000));}
    public static long outputTimeoutMs(double interval){return Math.max(30000,(long)Math.ceil(interval*1000+15000));}
    public static List<String> command(String engine,String host,int port,int seconds,long bps,boolean reverse){
        return command(engine,host,port,seconds,bps,reverse,false);
    }
    public static List<String> command(String engine,String host,int port,int seconds,long bps,boolean reverse,boolean bidirectional){
        return command(engine,host,port,seconds,bps,reverse,bidirectional,1);
    }
    public static List<String> command(String engine,String host,int port,int seconds,long bps,boolean reverse,boolean bidirectional,double intervalSeconds){
        interval(Double.toString(intervalSeconds));
        if(reverse&&bidirectional)throw new IllegalArgumentException("单向下载和双向模式不能同时选择");
        if(seconds<0||seconds>86400||bps<0||bps>1000000000000L||port<1||port>65535)throw new IllegalArgumentException("测量参数超出范围");
        List<String> args=new ArrayList<>(Arrays.asList(engine,"-c",host,"-p",Integer.toString(port),"-t",Integer.toString(seconds),"-i",intervalText(intervalSeconds),"-b",Long.toString(bps),"--json-stream","--forceflush","--connect-timeout","5000","--snd-timeout","15000"));
        // iperf rejects --rcv-timeout on a client that only sends.
        if(reverse||bidirectional)args.addAll(Arrays.asList("--rcv-timeout","15000"));
        if(reverse)args.add("-R");if(bidirectional)args.add("--bidir");return args;
    }
    public static List<String> journeyCommand(String engine,String host,int port,long bps,boolean reverse,boolean bidirectional,double intervalSeconds){
        List<String> args=command(engine,host,port,0,bps,reverse,bidirectional,intervalSeconds);
        args.set(args.indexOf("--connect-timeout")+1,"3000");
        args.set(args.indexOf("--snd-timeout")+1,"8000");
        if(args.contains("--rcv-timeout"))args.set(args.indexOf("--rcv-timeout")+1,"8000");
        return args;
    }
    public static boolean freshLocation(long receivedMs,long fixMs){return fixMs>0&&receivedMs>=fixMs&&receivedMs-fixMs<=10000;}
    public static boolean usableLocation(long receivedMs,long fixMs){return fixMs>0&&receivedMs>=fixMs;}
    public static int progress(double elapsed,int seconds,boolean complete){if(seconds<=0)return 0;return complete?1000:(int)Math.max(0,Math.min(999,elapsed*1000/seconds));}
}

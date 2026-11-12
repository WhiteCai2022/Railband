package cn.railband.mvp;
import java.io.*;

/** Pure Java boundaries and replay of real sample clocks, without modifying source data. */
public final class TransferWatchdogTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        TransferWatchdog w=new TransferWatchdog(true,false,1,0);w.reset(1000);
        w.record(true,0,0,1,2000);w.record(true,0,1,2,3000);
        check(w.stalled(15999)==0,"no early timeout");check(w.stalled(16000)==1,"zero reports do not keep dead TCP alive");
        w.record(true,1,2,3,16001);check(w.stalled(30000)==0,"any real byte progress resets stall");
        check(!w.record(true,1,2,3,99999),"duplicate native interval rejected");
        w.record(true,0,3,4,30001);check(w.stalled(40000)==0,"two distinct zero reports required");
        w.record(true,0,4,5,30002);check(w.stalled(40000)==1,"next zero report permits timeout");
        w.reset(50000);check(w.stalled(100000)==0,"new attempt and handshake without reports cannot trigger byte watchdog");
        w=new TransferWatchdog(true,true,1,0);w.reset(0);
        w.record(true,100,0,1,1000);w.record(false,0,0,1,1000);
        w.record(true,100,1,2,15000);w.record(false,0,1,2,15000);
        check(w.stalled(15000)==2,"healthy upload does not hide stalled download");
        w=new TransferWatchdog(false,true,1,0);w.reset(0);w.record(false,100,0,1,20000);
        check(w.stalled(30000)==0,"unmeasured upload not monitored");
        w=new TransferWatchdog(true,false,60,0);w.reset(0);
        check(w.stalled(75000)==0,"60s report cadence no false reconnect");
        w.record(true,0,0,60,60000);check(w.stalled(121000)==0,"one long zero report alone insufficient");
        w.record(true,0,60,120,120000);check(w.stalled(120999)==0&&w.stalled(121000)==1,"long intervals have explicit grace");
        w=new TransferWatchdog(true,false,1,1000);w.reset(0);w.record(true,0,0,1,1000);w.record(true,0,1,2,2000);
        check(w.stalled(15000)==0&&w.timeoutMs()>2000000,"low requested pacing not mistaken for outage");
        System.out.println("PASS: byte watchdog, reset, per direction, duplicates, long reports and low rate pacing");
        if(args.length>0)replay(args[0]);
    }
    private static void replay(String path)throws Exception{
        String previous="";TransferWatchdog w=null;int triggers=0;
        try(BufferedReader in=new BufferedReader(new FileReader(path))){String line;
            while((line=in.readLine())!=null){String[] a=line.split(",");String key=a[0]+":"+a[1];long now=Long.parseLong(a[2]);
                if(!key.equals(previous)){w=new TransferWatchdog(true,false,1,0);w.reset(now);previous=key;}
                w.record(true,Long.parseLong(a[5]),Double.parseDouble(a[3]),Double.parseDouble(a[4]),now);
                if(w.stalled(now)!=0){System.out.println("REPLAY_STALL session_attempt="+key+" at_elapsed_ms="+now);triggers++;w.reset(now);}
            }
        }
        check(triggers>0,"provided logs reproduce watchdog trigger");System.out.println("REPLAY_TRIGGERS="+triggers);
    }
}

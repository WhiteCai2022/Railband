import cn.railband.mvp.TestOptions;
import cn.railband.mvp.LiveRates;
import java.util.List;

public class OptionsTest {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid input accepted");}
    public static void main(String[] args){
        check(TestOptions.rate("2.5",false)==2500000L,"fractional Mbps");
        check(TestOptions.rate("",true)==0,"unlimited rate");
        check(TestOptions.duration("",true)==0,"unlimited duration");
        check(TestOptions.duration("1234",false)==1234,"custom duration");
        check(TestOptions.duration("86400",false)==86400,"duration boundary");
        check(TestOptions.interval("0.1")==.1,"minimum collection interval");
        check(TestOptions.interval("60")==60,"maximum collection interval");
        check(TestOptions.interval(" 2.5 ")==2.5,"fractional custom collection interval");
        for(String value:new String[]{"0","-1","NaN","Infinity","1000001","0.0000001"})rejects(()->TestOptions.rate(value,false));
        for(String value:new String[]{"0","-1","1.5","86401","99999999999"})rejects(()->TestOptions.duration(value,false));
        for(String value:new String[]{"","0","-1","0.099","60.01","NaN","Infinity","abc"})rejects(()->TestOptions.interval(value));
        List<String> infinite=TestOptions.command("engine","host",5201,0,0,true);
        check(infinite.get(infinite.indexOf("-t")+1).equals("0"),"iperf infinite time");
        check(infinite.get(infinite.indexOf("-b")+1).equals("0"),"iperf unlimited rate");
        check(infinite.contains("-R"),"download direction");
        List<String> custom=TestOptions.command("engine","host",5201,1234,2500000,false);
        check(custom.get(custom.indexOf("-t")+1).equals("1234"),"custom time argument");
        check(custom.get(custom.indexOf("-b")+1).equals("2500000"),"custom rate argument");
        check(Double.parseDouble(custom.get(custom.indexOf("-i")+1))==1,"legacy command keeps one-second interval");
        check(!custom.contains("-R"),"upload direction");
        check(!custom.contains("--rcv-timeout"),"sender-only iperf must not receive receiver-only option");
        check(infinite.contains("--rcv-timeout"),"download keeps receive timeout");
        List<String> both=TestOptions.command("engine","host",5201,10,5000000,false,true);
        check(both.contains("--bidir")&&!both.contains("-R"),"real bidirectional mode");
        List<String> journey=TestOptions.journeyCommand("engine","host",5201,0,false,true,60);
        check(journey.get(journey.indexOf("--connect-timeout")+1).equals("3000"),"journey fast connect timeout");
        check(journey.get(journey.indexOf("--rcv-timeout")+1).equals("8000"),"journey receive liveness timeout");
        check(journey.get(journey.indexOf("--snd-timeout")+1).equals("8000"),"journey TCP timeout");
        check(journey.get(journey.indexOf("-i")+1).equals("60"),"fast reconnect keeps slow report interval");
        rejects(()->TestOptions.command("engine","host",5201,10,5000000,true,true));
        for(double seconds:new double[]{.1,.5,2.5,60}){
            List<String> interval=TestOptions.command("engine","host",5201,120,1000000,false,true,seconds);
            check(Double.parseDouble(interval.get(interval.indexOf("-i")+1))==seconds,"selected interval reaches iperf without rounding");
            check(interval.contains("--bidir"),"interval preserves bidirectional mode");
        }
        for(double seconds:new double[]{0,.099,60.1,Double.NaN,Double.POSITIVE_INFINITY})rejects(()->TestOptions.command("engine","host",5201,10,5000000,false,true,seconds));
        check(TestOptions.sampleStaleMs(.1)==3000,"fast samples keep minimum UI grace");
        check(TestOptions.sampleStaleMs(5)==11000,"slow samples remain visible between records");
        check(TestOptions.sampleStaleMs(60)==121000,"longest sample UI grace");
        check(TestOptions.outputTimeoutMs(.1)==30000,"fast samples keep minimum engine timeout");
        check(TestOptions.outputTimeoutMs(30)==45000,"engine waits longer than slow collection interval");
        check(TestOptions.outputTimeoutMs(60)==75000,"longest interval engine timeout");
        LiveRates stats=new LiveRates();check(Double.isNaN(stats.snapshot().up.average)&&Double.isNaN(stats.snapshot().down.latest),"unmeasured directions are missing");
        stats.add(true,1000000,1,1,8000000,1000);stats.add(true,0,3,4,0,4000);stats.add(false,500000,.5,.5,8000000,1000);
        check(stats.snapshot().up.average==2&&stats.snapshot().up.latest==0,"time weighted average and real zero");
        check(stats.snapshot().down.average==8&&stats.snapshot().down.latest==8,"independent downstream statistics");
        for(int i=0;i<1400;i++)stats.add(i%2==0,100,.1,5+i*.05,8000,5000+i*50);
        check(stats.snapshot().points.size()==1300,"bounded graph retains one minute of fast bidirectional samples");stats.clear();check(stats.snapshot().points.isEmpty()&&stats.snapshot().up.samples==0,"clear stale display");
        check(TestOptions.progress(5,10,false)==500,"half progress");
        check(TestOptions.progress(20,10,false)==999,"not complete until end");
        check(TestOptions.progress(10,10,true)==1000,"completed progress");
        check(TestOptions.progress(600,0,false)==0,"indefinite progress");
        check(TestOptions.usableLocation(20000,10000),"recent position can be carried");
        check(TestOptions.usableLocation(20001,10000),"position older than ten seconds still carried");
        check(TestOptions.usableLocation(86400000,1),"position has no age expiry");
        check(TestOptions.usableLocation(20000,20000),"current position usable");
        check(!TestOptions.usableLocation(20000,20001),"future fix rejected");
        check(!TestOptions.usableLocation(20000,0),"missing fix rejected");
        check(!TestOptions.usableLocation(20000,-1),"invalid fix rejected");
        System.out.println("PASS: rate/duration/interval options, invalid inputs, exact iperf args, interval-aware display/timeouts, weighted rates, bounded graph, progress and carried position validity");
    }
}

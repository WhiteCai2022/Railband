package cn.railband.mvp;

/** Per-attempt byte progress, independent of the engine's still-running JSON clock. */
public final class TransferWatchdog {
    public static final int UPLOAD=1,DOWNLOAD=2;
    private final int required;
    private final long timeoutMs;
    private final long[] lastProgress=new long[2];
    private final int[] zeroReports=new int[2];
    private final double[] lastEnd={-1,-1};

    public TransferWatchdog(boolean upload,boolean download,double intervalSeconds,long rateCapBps){
        required=(upload?UPLOAD:0)|(download?DOWNLOAD:0);
        // Require time for at least two reports. Very low pacing rates may emit a
        // 128 KiB block infrequently; do not mistake that requested pacing for a stall.
        long paced=rateCapBps>0?(long)Math.ceil(2.0*131072*8*1000/rateCapBps):0;
        timeoutMs=Math.max(15000,Math.max((long)Math.ceil(intervalSeconds*2000+1000),paced));
    }
    public synchronized void reset(long now){
        for(int i=0;i<2;i++){lastProgress[i]=now;zeroReports[i]=0;lastEnd[i]=-1;}
    }
    /** False identifies a repeated native interval; raw events are still retained. */
    public synchronized boolean record(boolean upload,long bytes,double start,double end,long now){
        if(bytes<0||!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start)
            throw new IllegalArgumentException("Invalid progress interval");
        int i=upload?0:1;
        if(end<=lastEnd[i]+0.000001)return false;
        lastEnd[i]=end;
        if(bytes>0){lastProgress[i]=now;zeroReports[i]=0;}
        else zeroReports[i]++;
        return true;
    }
    public synchronized int stalled(long now){
        int mask=0;
        for(int i=0;i<2;i++)if((required&(1<<i))!=0&&zeroReports[i]>=2&&now-lastProgress[i]>=timeoutMs)mask|=1<<i;
        return mask;
    }
    public long timeoutMs(){return timeoutMs;}
    public synchronized long ageMs(boolean upload,long now){return Math.max(0,now-lastProgress[upload?0:1]);}
}

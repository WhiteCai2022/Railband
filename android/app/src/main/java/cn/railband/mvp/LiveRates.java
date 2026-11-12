package cn.railband.mvp;

import java.util.*;

/** Per-direction statistics. Average is total bytes / actual measured seconds. */
public final class LiveRates {
    public static final class Point {
        public final double end,mbps; public final boolean upload;
        Point(double t,double rate,boolean up){end=t;mbps=rate;upload=up;}
    }
    public static final class Direction {
        public final double latest,average;public final long receivedMs;public final int samples;
        Direction(double l,double a,long r,int n){latest=l;average=a;receivedMs=r;samples=n;}
    }
    public static final class Snapshot {
        public final Direction up,down;public final List<Point> points;
        Snapshot(Direction u,Direction d,List<Point> p){up=u;down=d;points=p;}
    }
    private final long[] bytes=new long[2],received=new long[2];
    private final double[] seconds=new double[2],latest={Double.NaN,Double.NaN};
    private final int[] counts=new int[2];
    private final ArrayDeque<Point> points=new ArrayDeque<>();
    public synchronized void clear(){Arrays.fill(bytes,0);Arrays.fill(received,0);Arrays.fill(seconds,0);Arrays.fill(counts,0);Arrays.fill(latest,Double.NaN);points.clear();}
    public synchronized void add(boolean upload,long transferred,double elapsed,double end,double bps,long receiveTime){
        if(transferred<0||elapsed<=0||!Double.isFinite(elapsed)||!Double.isFinite(end)||end<0||!Double.isFinite(bps)||bps<0)throw new IllegalArgumentException("Invalid measurement interval");
        int i=upload?0:1;bytes[i]+=transferred;seconds[i]+=elapsed;latest[i]=bps/1e6;received[i]=receiveTime;counts[i]++;
        points.addLast(new Point(end,latest[i],upload));while(points.size()>1300)points.removeFirst();
    }
    private Direction direction(int i){return new Direction(latest[i],counts[i]==0?Double.NaN:bytes[i]*8.0/seconds[i]/1e6,received[i],counts[i]);}
    public synchronized Snapshot snapshot(){return new Snapshot(direction(0),direction(1),new ArrayList<>(points));}
}

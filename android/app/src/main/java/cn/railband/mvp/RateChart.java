package cn.railband.mvp;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Recent real interval values; missing data remains a gap instead of becoming zero. */
public final class RateChart extends View {
    public static final int UP=Color.rgb(0,120,110),DOWN=Color.rgb(55,101,213);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<LiveRates.Point> points=Collections.emptyList();
    private double intervalSeconds=1;
    public RateChart(Context c){super(c);setContentDescription("最近60秒吞吐量曲线：绿色上行，蓝色下行");}
    public void show(LiveRates.Snapshot snapshot){points=snapshot.points;invalidate();}
    public void show(LiveRates.Snapshot snapshot,double interval){intervalSeconds=interval;show(snapshot);}
    private float dp(float n){return n*getResources().getDisplayMetrics().density;}
    protected void onDraw(Canvas canvas){super.onDraw(canvas);
        float left=dp(43),right=getWidth()-dp(10),top=dp(24),bottom=getHeight()-dp(24);
        if(right<=left||bottom<=top)return;
        double end=0;for(LiveRates.Point p:points)end=Math.max(end,p.end);
        double start=Math.max(0,end-60),finish=Math.max(10,end),peak=1;
        for(LiveRates.Point p:points)if(p.end>=start)peak=Math.max(peak,p.mbps);
        peak=Math.ceil(peak*1.1*10)/10;
        paint.setPathEffect(null);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);canvas.drawRoundRect(0,0,getWidth(),getHeight(),dp(10),dp(10),paint);
        paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));
        for(int i=0;i<=3;i++){float y=bottom-(bottom-top)*i/3;paint.setColor(Color.rgb(226,231,239));canvas.drawLine(left,y,right,y,paint);paint.setColor(Color.DKGRAY);canvas.drawText(String.format(Locale.US,"%.1f",peak*i/3),dp(4),y+dp(3),paint);}
        canvas.drawText("Mbps",dp(5),dp(13),paint);canvas.drawText(String.format(Locale.US,"%.0fs",start),left,bottom+dp(16),paint);canvas.drawText(String.format(Locale.US,"%.0fs",finish),right-dp(28),bottom+dp(16),paint);
        paint.setColor(UP);canvas.drawText("↑ 上行",left+dp(15),dp(13),paint);paint.setColor(DOWN);canvas.drawText("↓ 下行（虚线）",left+dp(85),dp(13),paint);
        for(boolean up:new boolean[]{true,false}){Path line=new Path();double previous=Double.NaN;paint.setColor(up?UP:DOWN);paint.setPathEffect(up?null:new DashPathEffect(new float[]{dp(6),dp(4)},0));
            for(LiveRates.Point p:points){if(p.upload!=up||p.end<start)continue;float x=left+(float)((p.end-start)/(finish-start))*(right-left),y=bottom-(float)(p.mbps/peak)*(bottom-top);
                if(Double.isNaN(previous)||p.end-previous>Math.max(2.5,intervalSeconds*1.5))line.moveTo(x,y);else line.lineTo(x,y);previous=p.end;paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(1.8f),paint);}
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawPath(line,paint);paint.setStyle(Paint.Style.FILL);
        }
        paint.setPathEffect(null);
        if(points.isEmpty()){paint.setColor(Color.GRAY);paint.setTextSize(dp(13));canvas.drawText("测量开始后显示真实曲线",left+dp(10),top+(bottom-top)/2,paint);}
    }
}

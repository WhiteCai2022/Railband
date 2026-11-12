package cn.railband.mvp;
import android.app.*;
import android.app.job.JobParameters;
import android.content.Context;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;

/** Exercise the production HTTPS transport with emulator chunks, without touching pending user logs. */
public class JourneyUploadTest extends Instrumentation {
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private byte[] read(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    public void onStart(){Bundle result=new Bundle();boolean ok=false;File configFile=new File(getTargetContext().getExternalFilesDir(null),"journey-test/upload-config.json");
        try{
            JSONObject config=new JSONObject(new String(read(configFile),StandardCharsets.UTF_8));configFile.delete();
            Class<?> runClass=Class.forName("cn.railband.mvp.UploadJob$UploadRun");Constructor<?> constructor=runClass.getDeclaredConstructor(JobParameters.class);constructor.setAccessible(true);Object run=constructor.newInstance((Object)null);
            Method send=UploadJob.class.getDeclaredMethod("send",String.class,String.class,String.class,InputStream.class,long.class,runClass);send.setAccessible(true);
            UploadJob job=new UploadJob();JSONObject last=null;
            for(int index:new int[]{1,0,0}){
                byte[] data=read(new File(configFile.getParentFile(),"chunk-"+index+".jsonl"));
                last=(JSONObject)send.invoke(job,config.getString("url"),config.getString("token"),config.getString("device"),new ByteArrayInputStream(data),(long)data.length,run);
                Method hex=UploadJob.class.getDeclaredMethod("hex",byte[].class);hex.setAccessible(true);String digest=(String)hex.invoke(null,(Object)java.security.MessageDigest.getInstance("SHA-256").digest(data));
                if(!digest.equals(last.getString("sha256"))||last.getInt("chunk_index")!=index)throw new AssertionError("response identity mismatch");
            }
            if(!"already_saved".equals(last.getString("status")))throw new AssertionError("retry not idempotent");
            result.putString("result","PASS: Android HTTPS transport, out-of-order chunks, SHA-256 acknowledgement and duplicate retry");
            try(FileOutputStream out=new FileOutputStream(new File(configFile.getParentFile(),"upload-result.json"))){out.write(last.toString().getBytes(StandardCharsets.UTF_8));}ok=true;
        }catch(Throwable e){result.putString("failure",e instanceof InvocationTargetException?String.valueOf(((InvocationTargetException)e).getCause()):e.toString());}
        finally{configFile.delete();}
        finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}

package dev.kiprian.kipautomate;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Explicitly armed, visible foreground microphone service.
 *
 * Safety invariants:
 * - Default: off; no persisted armed state; START_NOT_STICKY.
 * - Must be armed from a visible Activity with microphone permission.
 * - Face down for >= 2000 ms after a non-face-down sensor sample.
 * - Recording never stops on orientation changes; explicit STOP is required.
 * - Active foreground notification always exposes STOP / DISARM.
 * - Recording is stored in Music/KipAutomate using MediaStore on Android 10+.
 */
public final class FaceDownRecorderService extends Service implements SensorEventListener {
    public static final String ACTION_ARM = "dev.kiprian.kipautomate.recorder.ARM";
    public static final String ACTION_STOP = "dev.kiprian.kipautomate.recorder.STOP";
    public static final String ACTION_DISARM = "dev.kiprian.kipautomate.recorder.DISARM";
    private static final String CHANNEL = "kip_recording";
    private static final int NOTIFICATION_ID = 3202;
    private static final long FACE_DOWN_DELAY_MS = 2000;
    private static volatile FaceDownRecorderService current;

    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private boolean sensorRegistered=false;
    private boolean armed=false, recording=false, seenNotDown=false;
    private long faceDownSince=0, lastSensorLog=0, lastSensorSampleAt=0;
    private float lastX, lastY, lastZ;
    private MediaRecorder recorder;
    private Uri outputUri;
    private File outputFile;
    private ParcelFileDescriptor outputFd;

    public static boolean isArmed() {
        FaceDownRecorderService s=current;
        return s!=null && s.armed;
    }
    public static boolean isRecording() {
        FaceDownRecorderService s=current;
        return s!=null && s.recording;
    }
    public static String getStatus() {
        FaceDownRecorderService s=current;
        if(s==null || !s.armed)return "Предохранитель выключен";
        if(s.recording)return "Идёт запись аудио · остановка только вручную";
        if(!s.seenNotDown)return "Ожидание: подними телефон, затем положи экраном вниз";
        if(s.faceDownSince>0)return "Экраном вниз · подтверждаю положение (2 секунды)";
        return "Предохранитель включён · ожидаю положения экраном вниз";
    }
    public static boolean isFaceDown(float x,float y,float z) {
        float norm=(float)Math.sqrt(x*x+y*y+z*z);
        return z < -8.4f && Math.abs(x)<3.8f && Math.abs(y)<3.8f &&
            norm>=8.3f && norm<=11.3f;
    }
    public static void arm(Context context) {
        Intent intent=new Intent(context,FaceDownRecorderService.class);
        intent.setAction(ACTION_ARM);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
        else context.startService(intent);
    }
    public static void stop(Context context) {
        Intent intent=new Intent(context,FaceDownRecorderService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
    public static void disarm(Context context) {
        Intent intent=new Intent(context,FaceDownRecorderService.class);
        intent.setAction(ACTION_DISARM);
        context.startService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();
        current=this;
        sensorManager=(SensorManager)getSystemService(SENSOR_SERVICE);
        if(sensorManager!=null) {
            gravitySensor=sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if(gravitySensor==null)
                gravitySensor=sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null && Build.VERSION.SDK_INT>=26) {
            NotificationChannel nc=new NotificationChannel(CHANNEL,
                "KipAutomate: диктофон",NotificationManager.IMPORTANCE_LOW);
            nc.setDescription("Включённый предохранитель и активная запись аудио");
            nm.createNotificationChannel(nc);
        }
        LogBook.write(this,"REC SERVICE CREATED; default=DISARMED");
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)||ACTION_DISARM.equals(action)) {
            LogBook.write(this,ACTION_STOP.equals(action)?
                    "REC STOP requested manually":"REC DISARM requested manually");
            shutdownRecording();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!ACTION_ARM.equals(action)) {
            LogBook.write(this,"REC REJECT unexpected action");
            stopSelf();
            return START_NOT_STICKY;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            LogBook.write(this,"REC ARM FAILED: RECORD_AUDIO permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }
        if(armed) {
            LogBook.write(this,"REC ARM ignored: already armed");
            return START_NOT_STICKY;
        }
        try {
            Notification notification=notification(false);
            if(Build.VERSION.SDK_INT>=29)
                startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIFICATION_ID,notification);
        }catch(Exception e) {
            LogBook.write(this,"REC ARM FAILED foreground: "+e.getClass().getSimpleName()+": "+e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if(gravitySensor==null) {
            LogBook.write(this,"REC ARM FAILED: gravity and accelerometer sensors unavailable");
            stopForeground(true);stopSelf();return START_NOT_STICKY;
        }
        armed=true;seenNotDown=false;faceDownSince=0;
        lastSensorLog=0;lastSensorSampleAt=0;
        try {
            sensorRegistered=sensorManager.registerListener(this,gravitySensor,SensorManager.SENSOR_DELAY_GAME);
        } catch(Exception e) {
            LogBook.write(this,"REC ARM SENSOR ERROR: "+e);
            sensorRegistered=false;
        }
        if(!sensorRegistered) {
            armed=false;
            LogBook.write(this,"REC ARM FAILED: sensor listener not registered");
            stopForeground(true);stopSelf();
            return START_NOT_STICKY;
        }
        LogBook.write(this,"REC ARMED: sensor="+gravitySensor.getName()+
                " type="+gravitySensor.getType()+" delayMs="+FACE_DOWN_DELAY_MS+
                " trigger=screen_down stop=MANUAL only");
        return START_NOT_STICKY;
    }
    private Notification notification(boolean isRecording) {
        Intent open=new Intent(this,MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int f=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content=PendingIntent.getActivity(this,3,open,f);
        Intent action=new Intent(this,FaceDownRecorderService.class);
        action.setAction(isRecording?ACTION_STOP:ACTION_DISARM);
        PendingIntent stop=PendingIntent.getService(this,isRecording?8:7,action,f);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(isRecording?"KipAutomate · идёт запись":"KipAutomate · предохранитель включён")
                .setContentText(isRecording?"Микрофон записывает · остановка вручную":
                        "Для записи положи телефон экраном вниз на 2 секунды")
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        isRecording?"Остановить запись":"Выключить предохранитель",stop).build())
                .build();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(!armed||recording||event==null||event.values.length<3)return;
        lastX=event.values[0];lastY=event.values[1];lastZ=event.values[2];
        long now=SystemClock.elapsedRealtime();lastSensorSampleAt=now;
        boolean down=isFaceDown(lastX,lastY,lastZ);
        if(!down) {
            if(faceDownSince>0)LogBook.write(this,"REC SENSOR: screen-down candidate cancelled");
            faceDownSince=0;
            seenNotDown=true;
        } else if(seenNotDown) {
            if(faceDownSince==0) {
                faceDownSince=now;
                LogBook.write(this,"REC SENSOR FACE_DOWN candidate; starting 2000ms confirmation");
            }
            if(now-faceDownSince>=FACE_DOWN_DELAY_MS) {
                LogBook.write(this,"REC SENSOR FACE_DOWN stableMs="+(now-faceDownSince));
                beginRecording();
                return;
            }
        }
        if(now-lastSensorLog>=3500) {
            lastSensorLog=now;
            LogBook.write(this,String.format(Locale.ROOT,
                "REC SENSOR sample x=%.1f y=%.1f z=%.1f down=%s movedSinceArm=%s",
                lastX,lastY,lastZ,down,seenNotDown));
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}

    private void beginRecording() {
        if(!armed||recording)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            LogBook.write(this,"REC START FAILED: microphone permission lost");
            shutdownRecording();stopSelf();return;
        }
        try {
            String name="KipAutomate_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(new Date())+".m4a";
            if(Build.VERSION.SDK_INT>=29) {
                ContentValues values=new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME,name);
                values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/mp4");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH,Environment.DIRECTORY_MUSIC+"/KipAutomate");
                values.put(MediaStore.Audio.Media.IS_PENDING,1);
                outputUri=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
                if(outputUri==null)throw new IOException("MediaStore returned null URI");
                outputFd=getContentResolver().openFileDescriptor(outputUri,"w");
                if(outputFd==null)throw new IOException("Audio output descriptor unavailable");
            }else{
                File base=new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC),"KipAutomate");
                if(!base.exists()&&!base.mkdirs())throw new IOException("Cannot create private music directory");
                outputFile=new File(base,name);
            }
            recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(this):new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            if(outputFd!=null)recorder.setOutputFile(outputFd.getFileDescriptor());
            else recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording=true;
            if(sensorRegistered) {
                sensorManager.unregisterListener(this);
                sensorRegistered=false;
            }
            Notification n=notification(true);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if(nm!=null)nm.notify(NOTIFICATION_ID,n);
            LogBook.write(this,"REC RECORDING STARTED: format=M4A/AAC save=Music/KipAutomate");
        }catch(Exception e) {
            LogBook.write(this,"REC START FAILED "+e.getClass().getSimpleName()+": "+e.getMessage());
            shutdownRecording();
            stopForeground(true);
            stopSelf();
        }
    }
    private void shutdownRecording() {
        armed=false;
        faceDownSince=0;
        if(sensorRegistered && sensorManager!=null) {
            sensorManager.unregisterListener(this);sensorRegistered=false;
        }
        boolean saved=false;
        if(recorder!=null) {
            try {
                if(recording) {
                    recorder.stop();
                    saved=true;
                    LogBook.write(this,"REC STOP: encoder finalized");
                }
            }catch(RuntimeException e) {
                LogBook.write(this,"REC STOP FAILED: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }finally{
                try{recorder.reset();}catch(Exception ignored){}
                try{recorder.release();}catch(Exception ignored){}
                recorder=null;
            }
        }
        recording=false;
        if(outputFd!=null) {
            try{outputFd.close();}catch(IOException ignored){}
            outputFd=null;
        }
        if(outputUri!=null) {
            try {
                if(saved) {
                    ContentValues values=new ContentValues();
                    if(Build.VERSION.SDK_INT>=29)values.put(MediaStore.Audio.Media.IS_PENDING,0);
                    getContentResolver().update(outputUri,values,null,null);
                    LogBook.write(this,"REC SAVED Music/KipAutomate uri="+outputUri);
                }else{
                    getContentResolver().delete(outputUri,null,null);
                    LogBook.write(this,"REC INVALID FILE REMOVED");
                }
            }catch(Exception e) {
                LogBook.write(this,"REC STORE ERROR: "+e.getMessage());
            }finally{outputUri=null;}
        }
        if(outputFile!=null) {
            if(!saved)outputFile.delete();
            else LogBook.write(this,"REC SAVED "+outputFile.getAbsolutePath());
            outputFile=null;
        }
        LogBook.write(this,"REC DISARMED");
    }
    @Override public void onDestroy() {
        if(armed||recording||recorder!=null) {
            LogBook.write(this,"REC SERVICE DESTROYED: finalize active recording");
            shutdownRecording();
        }
        current=null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}

package com.hongqi.flighting.service;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import com.hongqi.flighting.integration.FeatureNavigation;
import com.hongqi.flighting.integration.InstrumentContent;
import com.hongqi.flighting.util.ActivationGuard;
import com.hongqi.flighting.util.ConfigManager;
import com.hongqi.flighting.util.InAppLogger;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Historical class name retained for sidebar/key bindings. Only DiAuto video is accepted. */
public class ScreenCaptureService extends Service implements DisplayManager.DisplayListener {
    public static final String ACTION_STOP = "com.hongqi.flighting.ACTION_CAST_STOP";
    public static final String ACTION_START = "com.hongqi.flighting.ACTION_CAST_START";
    public static final String ACTION_QUERY = "com.hongqi.flighting.ACTION_CAST_QUERY";
    public static final String EXTRA_AUTO_START = "com.hongqi.flighting.EXTRA_AUTO_START_CAST";
    private static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();
    private DisplayManager displays;
    private FlightPresentation presentation;
    private boolean starting;
    private int token;
    private boolean destroyed;
    public class LocalBinder extends Binder { public ScreenCaptureService getService() { return ScreenCaptureService.this; } }
    public static boolean isMirrorRunning() { return running; }
    public static void toggleCast(Context context) {
        if (!ActivationGuard.isSecureRunning(context)) return;
        if (running) context.startService(new Intent(context,ScreenCaptureService.class).setAction(ACTION_STOP));
        else context.startActivity(FeatureNavigation.castIntent(context));
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        displays = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        if (displays != null) displays.registerDisplayListener(this,handler);
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel("diauto-flight","DiAuto 飞屏",NotificationManager.IMPORTANCE_LOW));
        }
    }
    public boolean beginStart() { if (running || starting) return false; starting=true;token++;return true; }
    public int getActiveToken() { return token; }
    public boolean isRunning() { return running; }
    public boolean isBusy() { return running || starting; }
    public void cancelPendingStart(int value) { if (value == token && !running) starting=false; }
    public String getDebugState() { return "DiAuto 完整视频 running="+running+" starting="+starting; }
    private Display target() {
        if (displays == null) return null;
        for (Display display:displays.getDisplays()) {
            if (display.getDisplayId()!=Display.DEFAULT_DISPLAY && "PresentationScreen".equals(display.getName())
                && display.isValid() && display.getState()==Display.STATE_ON) return display;
        }
        // Explicit emulator preview uses the same video and metadata, never screen capture.
        return new ConfigManager(this).isSimulatorPreview() ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopMirror();return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) { starting=false;stopSelf();return START_NOT_STICKY; }
        Notification.Builder builder=Build.VERSION.SDK_INT>=26 ? new Notification.Builder(this,"diauto-flight") : new Notification.Builder(this);
        Notification notification=builder.setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("DiAuto 完整视频")
            .setContentText("仪表飞屏 · 独立导航与音乐").setOngoing(true).build();
        if (Build.VERSION.SDK_INT>=34) startForeground(702,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(702,notification);
        if (running) return START_NOT_STICKY;
        if (!ActivationGuard.isSecureRunning(this)) { fail("请先激活功能");return START_NOT_STICKY; }
        if (!FeatureNavigation.videoConnected(this)) { fail("请先连接手机并启动 DiAuto");return START_NOT_STICKY; }
        Display target=target();
        if (target == null) { fail("请先开启原车导航仪表投屏，或启用模拟器预览");return START_NOT_STICKY; }
        starting=true;
        try {
            presentation=new FlightPresentation(this,target);
            presentation.getWindow().setType(Build.VERSION.SDK_INT>=26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            presentation.setOnDismissListener(dialog -> { if (presentation==dialog) stopMirror(); });
            presentation.show();
            handler.postDelayed(health,500);
        } catch (RuntimeException e) { InAppLogger.e("DiAutoFlight","无法打开仪表屏",e);fail("无法打开仪表屏，请检查悬浮窗权限和屏幕状态"); }
        return START_NOT_STICKY;
    }
    private void fail(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show();stopMirror(); }
    private final Runnable health=new Runnable() {
        @Override public void run() {
            if (destroyed || presentation==null) return;
            if (!ActivationGuard.isSecureRunning(ScreenCaptureService.this) || !FeatureNavigation.videoConnected(ScreenCaptureService.this)) {
                InstrumentContent.clear();stopMirror();return;
            }
            presentation.update();handler.postDelayed(this,500);
        }
    };
    public void stopMirror() {
        handler.removeCallbacks(health);starting=false;running=false;token++;
        FlightPresentation old=presentation;presentation=null;
        if (old!=null) { old.close();old.dismiss(); }
        new ConfigManager(this).setEnabled(false);
        sendBroadcast(new Intent(ACTION_QUERY).setPackage(getPackageName()).putExtra("running",false));
        stopForeground(true);stopSelf();
    }
    @Override public void onDestroy() { destroyed=true;stopMirror();if(displays!=null)displays.unregisterDisplayListener(this);super.onDestroy(); }
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) { if(presentation!=null && presentation.getDisplay().getDisplayId()==id)stopMirror(); }
    @Override public void onDisplayChanged(int id) {
        if(presentation==null || presentation.getDisplay().getDisplayId()!=id)return;
        Display display=displays.getDisplay(id);
        if(display==null || !display.isValid() || display.getState()!=Display.STATE_ON) stopMirror();
    }

    private final class FlightPresentation extends Presentation implements SurfaceHolder.Callback {
        private TextView nav, song, status;
        private ImageView cover;
        private ProgressBar progress;
        private boolean attached, closed;
        private int artHash;
        private int artGeneration;
        private final ExecutorService artWorker=Executors.newSingleThreadExecutor();
        FlightPresentation(Context context,Display display) { super(context,display); }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            LinearLayout root=new LinearLayout(getContext());root.setOrientation(LinearLayout.HORIZONTAL);root.setBackgroundColor(Color.BLACK);
            SurfaceView video=new SurfaceView(getContext());video.getHolder().addCallback(this);
            root.addView(video,new LinearLayout.LayoutParams(0,-1,3f));
            LinearLayout cards=new LinearLayout(getContext());cards.setOrientation(LinearLayout.VERTICAL);
            int pad=(int)(12*getContext().getResources().getDisplayMetrics().density);cards.setPadding(pad,pad,pad,pad);
            nav=text(24);cards.addView(nav,new LinearLayout.LayoutParams(-1,0,1f));
            cover=new ImageView(getContext());cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cards.addView(cover,new LinearLayout.LayoutParams(-1,0,1f));
            song=text(20);cards.addView(song);status=text(15);cards.addView(status);
            if(getDisplay().getDisplayId()==Display.DEFAULT_DISPLAY) {
                Button exit=new Button(getContext());exit.setText("停止预览");exit.setOnClickListener(v->stopMirror());cards.addView(exit);
            }
            song.setMaxLines(2);song.setEllipsize(android.text.TextUtils.TruncateAt.END);
            progress=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);cards.addView(progress);
            root.addView(cards,new LinearLayout.LayoutParams(0,-1,1f));setContentView(root);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
            getWindow().setLayout(-1,-1);update();
        }
        private TextView text(int size) { TextView view=new TextView(getContext());view.setTextColor(Color.WHITE);view.setTextSize(size);return view; }
        @Override public void surfaceCreated(SurfaceHolder holder) {}
        @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) {
            if (closed || presentation!=this || !FeatureNavigation.videoConnected(ScreenCaptureService.this)) return;
            try {
                attached=true;
                FeatureNavigation.attachVideo(ScreenCaptureService.this,holder.getSurface(),width,height);
                starting=false;running=true;new ConfigManager(ScreenCaptureService.this).setEnabled(true);
                sendBroadcast(new Intent(ACTION_QUERY).setPackage(getPackageName()).putExtra("running",true));
            } catch (RuntimeException e) { InAppLogger.e("DiAutoFlight","无法连接 DiAuto 视频",e);fail("DiAuto 视频输出失败，请查看日志"); }
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { detach(); }
        private void detach() {
            if(attached) { attached=false;FeatureNavigation.detachVideo(ScreenCaptureService.this,()->{}); }
        }
        void close() { closed=true;artGeneration++;detach();artWorker.shutdownNow(); }
        void update() {
            if(closed || nav==null)return;
            InstrumentContent.Navigation n=InstrumentContent.navigation();
            nav.setText(n.active ? n.arrow+"  "+(n.distanceMeters==null ? "—" : n.distanceMeters>=1000 ? String.format(java.util.Locale.CHINA,"%.1f km",n.distanceMeters/1000f) : n.distanceMeters+" m")+"\n"+n.action+"\n"+n.road : "暂无导航提示");
            InstrumentContent.Music m=InstrumentContent.music();long position=m.positionAt(SystemClock.elapsedRealtime());
            song.setText(m.title.isEmpty() ? "暂无音乐信息" : m.title+"\n"+m.artist);
            status.setText(m.title.isEmpty() ? "等待媒体数据" : (m.playing ? "播放中" : "已暂停")+"  "+time(position)+" / "+time(m.durationMs));
            progress.setProgress(m.durationMs>0 ? (int)(1000*position/m.durationMs) : 0);
            byte[] art=m.artwork();int hash=Arrays.hashCode(art);
            if(hash!=artHash) {
                artHash=hash;final int generation=++artGeneration;cover.setImageDrawable(null);
                if(art!=null && art.length>0)artWorker.execute(()->{
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(art,0,art.length,options);
                    options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>256) options.inSampleSize*=2;
                    options.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(art,0,art.length,options);
                    handler.post(()->{ if(!closed && generation==artGeneration)cover.setImageBitmap(bitmap); });
                });
            }
        }
        private String time(long ms) { return String.format(java.util.Locale.CHINA,"%d:%02d",ms/60000,(ms/1000)%60); }
    }
}

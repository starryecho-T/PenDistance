package io.github.pendistance;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;
import java.util.concurrent.*;

public class MonitorService extends Service {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private static final String CHANNEL = "pen-distance";
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction("pause"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("笔程 · 笔尖接触距离").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(new Notification.Action.Builder(null, "暂停", pause).build()).build();
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "书写距离统计", NotificationManager.IMPORTANCE_LOW));
        startForeground(17, notification("正在检查记录程序…"));
        executor.scheduleWithFixedDelay(() -> {
            String text;
            try {
                JSONObject status = AgentClient.request(this, "status");
                JSONObject today = status.getJSONObject("days").getJSONObject(status.getString("today"));
                text = (status.getBoolean("enabled") && status.getBoolean("readerAlive") ? "记录中 · 今日 " : "已暂停 · 今日 ") + AgentClient.distance(today.getDouble("mm"));
            } catch (Exception e) { text = "记录程序未连接，请通过电脑启动"; }
            manager.notify(17, notification(text));
        }, 0, 2, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "pause".equals(intent.getAction())) executor.execute(() -> {
            try { AgentClient.request(this, "pause"); } catch (Exception ignored) { }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        });
        return START_STICKY;
    }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

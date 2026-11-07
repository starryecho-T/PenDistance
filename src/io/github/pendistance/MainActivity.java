package io.github.pendistance;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> refreshTask;
    private TextView state, distance, detail, history, diagnostic;
    private JSONObject latest;
    private LinearLayout page;
    private final int ink = Color.rgb(24, 40, 54);
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(ink); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(8), 0, dp(8)); return t;
    }
    private void button(String title, View.OnClickListener click) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setOnClickListener(click);
        page.addView(b, new LinearLayout.LayoutParams(-1, dp(54)));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 250));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(28), dp(22), dp(28), dp(28));
        page.setBackgroundColor(Color.rgb(244, 247, 250)); scroll.addView(page); setContentView(scroll);
        page.addView(text("笔程  /  PEN DISTANCE", 27, true));
        page.addView(text("只累计笔尖接触屏幕时走过的距离", 16, false));
        state = text("正在连接…", 17, true); page.addView(state);
        distance = text("0.000 米", 46, true); page.addView(distance);
        detail = text("今日接触 0 次 · 接触时长 0 分钟", 17, false); page.addView(detail);
        button("开始 / 继续记录", v -> command("start"));
        button("暂停记录", v -> command("pause"));
        button("用尺子校准屏幕尺寸", v -> calibration());
        button("导出每日统计 CSV", v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "pen-distance.csv"); startActivityForResult(i, 42);
        });
        page.addView(text("最近 7 天", 21, true)); history = text("暂无记录", 16, false); page.addView(history);
        diagnostic = text("", 14, false); page.addView(diagnostic);
        page.addView(text("统计范围：所有应用中的笔尖接触，包括 StarNote。手指、掌触、悬空移动不计；笔尖点按、擦除也会计入。\n\n距离按屏幕物理尺寸估算，可用尺子校准。它表示笔尖在玻璃上走过的长度，不受笔记页面缩放影响。\n\n数据仅保存在本机；不保存笔迹坐标、笔记内容，不连接云端。首次需电脑启动；拔掉 USB 后可继续。平板重启后需重新用电脑启动。", 14, false));
        try { AgentClient.token(this); } catch (Exception e) { state.setText("无法创建记录凭据"); }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        try { startForegroundService(new Intent(this, MonitorService.class)); } catch (Exception ignored) { }
        JSONObject cached = AgentClient.cached(this); if (cached != null) display(cached, false);
    }
    @Override public void onResume() {
        super.onResume(); refreshTask = executor.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
    }
    @Override public void onPause() { if (refreshTask != null) refreshTask.cancel(false); super.onPause(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
    private void refresh() {
        try { JSONObject value = AgentClient.request(this, "status"); runOnUiThread(() -> display(value, true)); }
        catch (Exception e) { runOnUiThread(() -> { state.setText("未连接记录程序 · 请通过电脑启动"); state.setTextColor(Color.rgb(160, 73, 19)); }); }
    }
    private void display(JSONObject value, boolean connected) {
        try {
            latest = value; JSONObject days = value.getJSONObject("days"), today = days.getJSONObject(value.getString("today"));
            boolean running = value.getBoolean("enabled") && value.getBoolean("readerAlive");
            state.setText(!connected ? "上次保存的统计 · 正在连接" : running ? "● 正在记录 · 可以切回 StarNote" : value.getBoolean("readerAlive") ? "已暂停" : "记录程序异常 · 请连接电脑重新启动");
            state.setTextColor(running && connected ? Color.rgb(20, 115, 82) : Color.rgb(160, 73, 19));
            distance.setText(AgentClient.distance(today.getDouble("mm")));
            detail.setText(String.format(Locale.SIMPLIFIED_CHINESE, "今日接触 %d 次 · 接触时长 %.1f 分钟", today.getLong("strokes"), today.getDouble("contactMs") / 60000));
            List<String> keys = new ArrayList<>(); for (Iterator<String> it = days.keys(); it.hasNext();) keys.add(it.next());
            Collections.sort(keys, Collections.reverseOrder()); StringBuilder list = new StringBuilder();
            for (int i = 0; i < Math.min(7, keys.size()); i++) {
                JSONObject d = days.getJSONObject(keys.get(i));
                list.append(keys.get(i)).append("    ").append(AgentClient.distance(d.getDouble("mm"))).append("    ").append(d.getLong("strokes")).append(" 次\n");
            }
            history.setText(list.toString().trim());
            diagnostic.setText(String.format(Locale.SIMPLIFIED_CHINESE, "尺寸：%.2f × %.2f mm（%s）\n已读 %d 帧 · 丢帧标记 %d\n%s", value.getDouble("widthMm"), value.getDouble("heightMm"), value.getBoolean("calibrated") ? "已按尺子设置" : "系统物理 DPI 估算，尚未尺校", value.getLong("frames"), value.getLong("droppedFrames"), value.optString("error")));
        } catch (Exception e) { state.setText("统计数据格式异常"); }
    }
    private void command(String name) {
        if (name.equals("start")) try { startForegroundService(new Intent(this, MonitorService.class)); } catch (Exception ignored) { }
        executor.execute(() -> {
            try { JSONObject value = AgentClient.request(this, name); runOnUiThread(() -> display(value, true)); }
            catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "请先连接电脑启动记录程序", Toast.LENGTH_LONG).show()); }
        });
    }
    private void calibration() {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(22), dp(8), dp(22), 0);
        fields.addView(text("用尺子量发光显示区域，不含边框。按竖屏填写短边与长边，单位毫米。只影响之后的统计。", 15, false));
        EditText w = new EditText(this), h = new EditText(this);
        w.setInputType(8194); h.setInputType(8194); w.setHint("竖屏短边（mm）"); h.setHint("竖屏长边（mm）");
        if (latest != null) { w.setText(String.format(Locale.US, "%.2f", latest.optDouble("widthMm"))); h.setText(String.format(Locale.US, "%.2f", latest.optDouble("heightMm"))); }
        fields.addView(w); fields.addView(h);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("校准实际距离").setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double width = Double.parseDouble(w.getText().toString()), height = Double.parseDouble(h.getText().toString());
                if (!Double.isFinite(width) || !Double.isFinite(height) || width < 50 || height < width || height > 1000) throw new IllegalArgumentException();
                JSONObject request = new JSONObject().put("widthMm", width).put("heightMm", height);
                executor.execute(() -> {
                    try { JSONObject value = AgentClient.request(this, "calibrate", request); runOnUiThread(() -> { display(value, true); dialog.dismiss(); }); }
                    catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "保存失败，请检查记录程序是否连接", Toast.LENGTH_LONG).show()); }
                });
            } catch (Exception e) { Toast.makeText(this, "请输入有效的毫米尺寸", Toast.LENGTH_SHORT).show(); }
        })); dialog.show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 42 || result != RESULT_OK || data == null || data.getData() == null || latest == null) return;
        try {
            JSONObject days = latest.getJSONObject("days"); List<String> keys = new ArrayList<>();
            for (Iterator<String> it = days.keys(); it.hasNext();) keys.add(it.next()); Collections.sort(keys);
            StringBuilder csv = new StringBuilder("\ufeffdate,distance_m,contacts,contact_seconds\r\n");
            for (String key : keys) { JSONObject d = days.getJSONObject(key); csv.append(String.format(Locale.US, "%s,%.6f,%d,%.3f\r\n", key, d.getDouble("mm") / 1000, d.getLong("strokes"), d.getDouble("contactMs") / 1000)); }
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { out.write(csv.toString().getBytes(StandardCharsets.UTF_8)); }
            Toast.makeText(this, "每日统计已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show(); }
    }
}

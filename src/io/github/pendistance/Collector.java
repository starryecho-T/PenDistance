package io.github.pendistance;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A shell-identity companion, started once by USB; all recording and storage stay on the tablet. */
public final class Collector implements PenTracker.Sink {
    public static final int PORT = 38761;
    private final Object lock = new Object();
    private final String token, node;
    private final File state;
    private final TreeMap<String, double[]> days = new TreeMap<>();
    private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final PenTracker tracker;
    private double width, height;
    private boolean calibrated, enabled = true, alive;
    private String error = "";
    private long lastEventWall;
    private final long started = System.currentTimeMillis();
    private String day() { return date.format(new Date()); }
    private double[] today() { return days.computeIfAbsent(day(), k -> new double[3]); }
    @Override public void stroke() { if (enabled) today()[1]++; }
    @Override public void distance(double mm) { if (enabled) today()[0] += mm; }
    @Override public void contact(double ms) { if (enabled) addContact(days, ms, System.currentTimeMillis()); }
    private void addContact(TreeMap<String, double[]> target, double ms, long end) {
        // Split contact time at local midnight, including a stationary pen held across midnight.
        double start = end - ms;
        while (start < end) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis((long)Math.floor(start));
            String key = date.format(c.getTime());
            c.add(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            double finish = Math.min((double)end, (double)c.getTimeInMillis());
            target.computeIfAbsent(key, k -> new double[3])[2] += finish - start;
            start = finish;
        }
    }
    private Collector(String[] args) throws Exception {
        token = Objects.requireNonNull(System.getenv("PEN_DISTANCE_TOKEN"), "Missing session credential");
        node = args[0]; state = new File(args[1]);
        width = Double.parseDouble(args[4]); height = Double.parseDouble(args[5]);
        if (state.isFile()) {
            JSONObject old = new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8));
            width = old.optDouble("widthMm", width); height = old.optDouble("heightMm", height);
            calibrated = old.optBoolean("calibrated", false);
            enabled = old.optBoolean("enabled", true);
            JSONObject data = old.optJSONObject("days");
            if (data != null) for (Iterator<String> it = data.keys(); it.hasNext();) {
                String key = it.next(); JSONObject item = data.getJSONObject(key);
                days.put(key, new double[]{item.getDouble("mm"), item.getDouble("strokes"), item.getDouble("contactMs")});
            }
        }
        tracker = new PenTracker(Integer.parseInt(args[2]), Integer.parseInt(args[3]), width, height, this);
    }
    private JSONObject snapshot() throws Exception {
        synchronized (lock) {
            JSONObject data = new JSONObject();
            String currentDay = day();
            TreeMap<String, double[]> live = new TreeMap<>();
            for (Map.Entry<String, double[]> item : days.entrySet()) live.put(item.getKey(), item.getValue().clone());
            if (enabled) addContact(live, tracker.unreportedContact(SystemClock.uptimeMillis()), System.currentTimeMillis());
            for (Map.Entry<String, double[]> item : live.entrySet()) {
                double[] v = item.getValue();
                data.put(item.getKey(), new JSONObject().put("mm", v[0]).put("strokes", (long)v[1])
                    .put("contactMs", v[2]));
            }
            if (!data.has(currentDay)) data.put(currentDay, new JSONObject().put("mm", 0).put("strokes", 0).put("contactMs", 0));
            return new JSONObject().put("version", 1).put("enabled", enabled).put("readerAlive", alive)
                .put("touching", tracker.touching()).put("error", error).put("days", data).put("today", currentDay)
                .put("widthMm", width).put("heightMm", height).put("calibrated", calibrated)
                .put("frames", tracker.frames).put("droppedFrames", tracker.dropped)
                .put("lastEventWall", lastEventWall).put("startedWall", started).put("device", node);
        }
    }
    private synchronized void persist() throws Exception {
        JSONObject value = snapshot();
        File tmp = new File(state.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(tmp.toPath(), state.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private void readPen() {
        new Thread(() -> {
            try {
                Process process = new ProcessBuilder("/system/bin/getevent", "-lt", node).redirectErrorStream(true).start();
                synchronized (lock) { alive = true; }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) synchronized (lock) {
                        if (line.contains("Permission denied") || line.contains("could not") || line.contains("No such")) error = line;
                        // Keep coordinates current even while paused; sink callbacks suppress accumulation.
                        if (line.contains("EV_")) { tracker.accept(line); lastEventWall = System.currentTimeMillis(); }
                    }
                }
                synchronized (lock) { tracker.endContact(SystemClock.uptimeMillis()); alive = false; error = "笔尖事件读取已停止，需重新连接电脑启动。"; }
            } catch (Exception e) { synchronized (lock) { alive = false; error = e.toString(); } }
        }, "pen-event-reader").start();
    }
    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(2500);
            InputStream in = client.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b; while ((b = in.read()) != -1 && b != '\n') { if (bytes.size() >= 4096) return; bytes.write(b); }
            JSONObject request = new JSONObject(bytes.toString("UTF-8"));
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), request.optString("token").getBytes(StandardCharsets.UTF_8))) return;
            String command = request.optString("command", "status");
            synchronized (lock) {
                if (command.equals("start")) { if (!enabled) tracker.reset(); enabled = true; }
                else if (command.equals("pause")) { tracker.endContact(SystemClock.uptimeMillis()); enabled = false; }
                else if (command.equals("calibrate")) {
                    double w = request.getDouble("widthMm"), h = request.getDouble("heightMm");
                    tracker.endContact(SystemClock.uptimeMillis()); tracker.calibrate(w, h);
                    width = w; height = h; calibrated = true;
                } else if (!command.equals("status")) return;
            }
            if (!command.equals("status")) persist();
            client.getOutputStream().write((snapshot().toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
    public static void main(String[] args) throws Exception {
        Collector collector = new Collector(args);
        // Bind before starting workers: a duplicate startup must not start another reader or writer.
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            collector.readPen();
            ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor();
            saver.scheduleWithFixedDelay(() -> { try { collector.persist(); } catch (Exception e) { synchronized (collector.lock) { collector.error = "保存失败：" + e.getMessage(); } } }, 2, 2, TimeUnit.SECONDS);
            ExecutorService clients = Executors.newFixedThreadPool(2);
            while (true) { Socket client = server.accept(); clients.execute(() -> collector.handle(client)); }
        }
    }
}

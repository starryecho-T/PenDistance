package io.github.pendistance;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

final class AgentClient {
    static synchronized String token(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "collector.token");
        if (file.isFile()) return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder();
        for (byte b : random) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        try (FileOutputStream out = context.openFileOutput("collector.token", Context.MODE_PRIVATE)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        return value.toString();
    }
    static JSONObject request(Context context, String command) throws Exception { return request(context, command, new JSONObject()); }
    static JSONObject request(Context context, String command, JSONObject data) throws Exception {
        data.put("token", token(context)).put("command", command);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Collector.PORT), 1200); socket.setSoTimeout(2500);
            socket.getOutputStream().write((data.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            InputStream in = socket.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream();
            int b; while ((b = in.read()) != -1 && b != '\n') { if (out.size() > 2000000) throw new IOException("Response too large"); out.write(b); }
            JSONObject result = new JSONObject(out.toString("UTF-8"));
            saveCache(context, result);
            return result;
        }
    }
    private static synchronized void saveCache(Context context, JSONObject result) throws Exception {
        android.util.AtomicFile file = new android.util.AtomicFile(new File(context.getFilesDir(), "latest.json"));
        FileOutputStream out = file.startWrite();
        try { out.write(result.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
    static synchronized JSONObject cached(Context context) {
        try { return new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(), "latest.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }
    static String distance(double mm) { return String.format(java.util.Locale.SIMPLIFIED_CHINESE, "%.3f 米", mm / 1000); }
}

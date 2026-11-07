package io.github.pendistance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Processes complete Linux input frames, never individual X/Y updates as separate moves. */
public final class PenTracker {
    public interface Sink {
        void stroke();
        void distance(double millimeters);
        void contact(double milliseconds);
    }
    private static final Pattern EVENT = Pattern.compile("\\[\\s*([0-9.]+)\\]\\s+(EV_\\w+)\\s+(\\w+)\\s+(DOWN|UP|REPEAT|[0-9a-fA-F]+)(?:\\s|$)");
    private final Sink sink;
    private final int maxX, maxY;
    private double scaleX, scaleY;
    private int x, y, lastX, lastY;
    private boolean knownX, knownY, touch, previousTouch, previousPosition, dropping;
    private double lastTime = -1;
    public long frames, dropped;

    public PenTracker(int maxX, int maxY, double widthMm, double heightMm, Sink sink) {
        this.maxX = maxX; this.maxY = maxY; this.sink = sink;
        calibrate(widthMm, heightMm);
    }
    public void calibrate(double widthMm, double heightMm) {
        if (!Double.isFinite(widthMm) || !Double.isFinite(heightMm) || widthMm < 50 || widthMm > 1000 || heightMm < 50 || heightMm > 1000)
            throw new IllegalArgumentException("Invalid physical screen dimensions");
        scaleX = widthMm / maxX; scaleY = heightMm / maxY;
    }
    public boolean touching() { return previousTouch; }
    public double unreportedContact(double nowMs) {
        return previousTouch && lastTime >= 0 ? Math.max(0, nowMs - lastTime) : 0;
    }
    public void endContact(double nowMs) {
        double remaining = unreportedContact(nowMs);
        if (remaining > 0) sink.contact(remaining);
        reset();
    }
    public void reset() {
        touch = previousTouch = previousPosition = false;
        lastTime = -1;
    }
    public void accept(String line) {
        Matcher m = EVENT.matcher(line);
        if (!m.find()) return;
        double time = Double.parseDouble(m.group(1)) * 1000;
        String type = m.group(2), code = m.group(3);
        String raw = m.group(4);
        long value = raw.equals("DOWN") ? 1 : raw.equals("UP") ? 0 : raw.equals("REPEAT") ? 2 : Long.parseLong(raw, 16);
        if (code.equals("SYN_DROPPED")) {
            dropped++; reset(); knownX = knownY = false; dropping = true; return;
        }
        if (dropping) { if (code.equals("SYN_REPORT")) dropping = false; return; }
        if (type.equals("EV_ABS")) {
            if (code.equals("ABS_X") && value <= maxX) { x = (int)value; knownX = true; }
            if (code.equals("ABS_Y") && value <= maxY) { y = (int)value; knownY = true; }
        } else if (type.equals("EV_KEY") && code.equals("BTN_TOUCH")) touch = value != 0;
        if (!code.equals("SYN_REPORT")) return;
        frames++;
        if (previousTouch && lastTime >= 0 && time >= lastTime) sink.contact(time - lastTime);
        if (touch && !previousTouch) sink.stroke();
        // Include a final coordinate delivered in the same frame as pen-up.
        if (previousTouch && previousPosition && knownX && knownY) {
            double dx = (x - lastX) * scaleX, dy = (y - lastY) * scaleY;
            double mm = Math.hypot(dx, dy);
            if (mm > 0) sink.distance(mm);
        }
        previousPosition = touch && knownX && knownY;
        if (previousPosition) { lastX = x; lastY = y; }
        previousTouch = touch;
        lastTime = time;
    }
}

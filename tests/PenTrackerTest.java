package io.github.pendistance;

public class PenTrackerTest implements PenTracker.Sink {
    private double distance, contact;
    private int strokes;
    private boolean recording = true;
    @Override public void stroke() { if (recording) strokes++; }
    @Override public void distance(double mm) { if (recording) distance += mm; }
    @Override public void contact(double ms) { if (recording) contact += ms; }
    private static void close(double expected, double actual) {
        if (Math.abs(expected - actual) > 1e-6) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static String event(double seconds, String type, String code, int value) {
        return String.format(java.util.Locale.US, "[ %f] %s %s %08x", seconds, type, code, value);
    }
    private static void frame(PenTracker p, double t, Integer x, Integer y, Integer touch) {
        if (x != null) p.accept(event(t, "EV_ABS", "ABS_X", x));
        if (y != null) p.accept(event(t, "EV_ABS", "ABS_Y", y));
        if (touch != null) p.accept(event(t, "EV_KEY", "BTN_TOUCH", touch));
        p.accept(event(t, "EV_SYN", "SYN_REPORT", 0));
    }
    public static void main(String[] args) {
        PenTrackerTest data = new PenTrackerTest();
        PenTracker p = new PenTracker(1000, 1000, 100, 100, data);
        frame(p, 1, 0, 0, 0); frame(p, 2, 900, 900, null); close(0, data.distance);
        frame(p, 3, 0, 0, 1); frame(p, 3.1, 30, 40, null);
        close(5, data.distance); // A diagonal must be Euclidean, not two separate X/Y distances.
        frame(p, 3.2, 60, 80, 0); close(10, data.distance); close(200, data.contact);
        frame(p, 4, 1000, 1000, null); close(10, data.distance);
        frame(p, 5, 900, 900, 1); frame(p, 5.1, 870, 860, 0);
        close(15, data.distance); close(2, data.strokes); // Never bridge separate strokes.
        p.calibrate(200, 100);
        frame(p, 6, 0, 0, 1); frame(p, 6.1, 50, 0, null); close(25, data.distance);
        close(100, p.unreportedContact(6200)); p.endContact(6200); close(500, data.contact);
        frame(p, 7, 900, 900, 0); close(25, data.distance);
        frame(p, 8, 100, 100, 1); p.accept(event(8.1, "EV_SYN", "SYN_DROPPED", 0));
        frame(p, 8.2, 900, 900, null); frame(p, 8.3, 990, 990, null); close(25, data.distance);
        close(1, p.dropped);
        boolean rejected = false; try { p.calibrate(Double.NaN, 100); } catch (IllegalArgumentException e) { rejected = true; }
        if (!rejected) throw new AssertionError("NaN calibration accepted");
        PenTrackerTest labeled = new PenTrackerTest();
        PenTracker labels = new PenTracker(1000, 1000, 100, 100, labeled);
        frame(labels, 1, 0, 0, 0);
        labels.accept("[ 2.000000] EV_KEY BTN_TOUCH DOWN"); frame(labels, 2, null, null, null);
        frame(labels, 2.1, 30, 40, null);
        labels.accept("[ 2.200000] EV_KEY BTN_TOUCH UP"); frame(labels, 2.2, null, null, null);
        frame(labels, 3, 900, 900, null); close(5, labeled.distance); close(1, labeled.strokes);
        if (labels.touching()) throw new AssertionError("Textual UP was not recognized");
        PenTrackerTest resumed = new PenTrackerTest(); resumed.recording = false;
        PenTracker live = new PenTracker(1000, 1000, 100, 100, resumed);
        frame(live, 1, 0, 0, 0); frame(live, 2, 900, 800, 1);
        live.reset(); resumed.recording = true;
        frame(live, 3, null, 820, 1); frame(live, 3.1, 900, 830, 0);
        close(1, resumed.distance); close(1, resumed.strokes);
        System.out.println("PASS: hover, Euclidean frame distance, pen-up endpoint, stroke separation, timing, calibration, SYN_DROPPED, invalid dimensions, textual DOWN/UP, pause/resume coordinates");
    }
}

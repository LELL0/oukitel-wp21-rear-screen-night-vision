package com.lello.wp21rear.daemon;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Root daemon. Runs as root through
 *   CLASSPATH=&lt;apk&gt; app_process /system/bin com.lello.wp21rear.daemon.RearDaemon [mode]
 *
 * It owns the rear panel HAL (vendor.yft.hardware.tinylcd@1.0::ITinylcd/default), reads the rear
 * touch controller (hyn_ts) and the programmable side key (mtk-kpd KEY_F1), and runs privileged
 * shell commands for the app. It talks to the app over stdin/stdout with the framing in {@link Proto}.
 *
 * Modes:
 *   (none)   serve the app over stdin/stdout
 *   off      emergency: backlight off, panel power off, exit
 *   on N     panel on at brightness N with a test pattern, exit (diagnostics)
 *   bench N  push N frames and print the average frame time (diagnostics)
 *
 * The panel is turned off when stdin closes (the app died or was stopped), unless the app said
 * otherwise with C_OFF_ON_EXIT.
 */
public final class RearDaemon {
    static final String TAG = "WP21RearDaemon";
    static final String DESC = "vendor.yft.hardware.tinylcd@1.0::ITinylcd";

    // linux/input-event-codes.h
    static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
    static final int SYN_REPORT = 0, SYN_MT_REPORT = 2;
    static final int BTN_TOUCH = 0x14a;
    static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36, ABS_MT_TRACKING_ID = 0x39;
    // _IOW('E', 0x90, int)
    static final int EVIOCGRAB = 0x40044590;

    private static IHwBinder sService;
    private static DataOutputStream sOut;
    private static volatile boolean sOffOnExit = true;
    private static volatile boolean sWantGrab = true;
    private static volatile FileInputStream sTouchStream;
    private static volatile boolean sTouchGrabbed;

    // ---------------------------------------------------------------- HAL

    private static synchronized IHwBinder service() throws Exception {
        if (sService == null) {
            sService = HwBinder.getService(DESC, "default", true);
            if (sService == null) throw new IllegalStateException("ITinylcd service not found");
        }
        return sService;
    }

    static synchronized int hal(int code, int arg) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HwParcel req = new HwParcel();
            req.writeInterfaceToken(DESC);
            if (arg >= 0) req.writeInt32(arg);
            HwParcel rep = new HwParcel();
            try {
                service().transact(code, req, rep, 0);
                rep.verifySuccess();
                req.releaseTemporaryStorage();
                return rep.readInt32();
            } catch (Exception e) {
                last = e;
                sService = null; // the HAL may have restarted; fetch it again
            } finally {
                rep.release();
            }
        }
        throw last;
    }

    static synchronized int frame(byte[] data) throws Exception {
        // Only write when the HAL says the previous frame is done (status <= 1), as the stock app does.
        for (int i = 0; i < 50; i++) {
            if (hal(Proto.HAL_STATUS, -1) <= 1) break;
            Thread.sleep(2);
        }
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HwParcel req = new HwParcel();
            req.writeInterfaceToken(DESC);
            HwBlob blob = new HwBlob(Proto.FRAME_BYTES);
            blob.putInt8Array(0L, data);
            req.writeBuffer(blob);
            HwParcel rep = new HwParcel();
            try {
                service().transact(Proto.HAL_FRAME, req, rep, 0);
                rep.verifySuccess();
                req.releaseTemporaryStorage();
                return rep.readInt32();
            } catch (Exception e) {
                last = e;
                sService = null;
            } finally {
                rep.release();
            }
        }
        throw last;
    }

    static void panelOff() {
        try {
            hal(Proto.HAL_BL, 0);
            Thread.sleep(100);
            hal(Proto.HAL_POWER, 0);
        } catch (Throwable t) {
            Log.e(TAG, "panel off failed", t);
        }
    }

    // ---------------------------------------------------------------- output to the app

    static void send(int type, byte[] payload) {
        DataOutputStream out = sOut;
        if (out == null) return;
        synchronized (out) {
            try {
                out.writeByte(type);
                out.writeByte(0);
                out.writeShort(0);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                // the app is gone; the stdin reader notices and exits
            }
        }
    }

    static void reply(int id, int ret, int micros) {
        ByteBuffer b = ByteBuffer.allocate(12);
        b.putInt(id).putInt(ret).putInt(micros);
        send(Proto.D_REPLY, b.array());
    }

    static void error(int id, String msg) {
        byte[] m = msg.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + m.length);
        b.putInt(id).put(m);
        send(Proto.D_ERROR, b.array());
    }

    static void log(String msg) {
        Log.i(TAG, msg);
        send(Proto.D_LOG, msg.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- input devices

    static String findEventDevice(String name) {
        File[] devs = new File("/sys/class/input").listFiles();
        if (devs == null) return null;
        for (File d : devs) {
            if (!d.getName().startsWith("event")) continue;
            try {
                String n = readFile(new File(d, "device/name")).trim();
                if (n.equals(name)) return "/dev/input/" + d.getName();
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** EVIOCGRAB through the hidden libcore ioctl. Any non-null argument grabs; closing the fd releases. */
    static boolean grab(FileDescriptor fd) {
        try {
            Class<?> os = Class.forName("android.system.Os");
            for (Method m : os.getMethods()) {
                if (!m.getName().equals("ioctlInt")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2) {
                    m.invoke(null, fd, EVIOCGRAB);
                    return true;
                }
                if (p.length == 3) {
                    Object ref = p[2].getConstructor(int.class).newInstance(1);
                    m.invoke(null, fd, EVIOCGRAB, ref);
                    return true;
                }
            }
            log("grab: no ioctlInt method");
        } catch (Throwable t) {
            log("grab failed: " + (t.getCause() != null ? t.getCause() : t));
        }
        return false;
    }

    /** Reads struct input_event (arm64: 16-byte timeval, u16 type, u16 code, s32 value). */
    static void touchLoop() {
        while (true) {
            String path = findEventDevice("hyn_ts");
            if (path == null) {
                log("touch: hyn_ts not found");
                return;
            }
            try (FileInputStream in = new FileInputStream(path)) {
                sTouchStream = in;
                sTouchGrabbed = sWantGrab && grab(in.getFD());
                log("touch: " + path + (sTouchGrabbed ? " (grabbed)" : " (shared)"));
                byte[] ev = new byte[24];
                ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
                int x = -1, y = -1;
                boolean down = false, wasDown = false, sawPos = false;
                while (true) {
                    readFully(in, ev);
                    int type = bb.getShort(16) & 0xffff;
                    int code = bb.getShort(18) & 0xffff;
                    int value = bb.getInt(20);
                    if (type == EV_KEY && code == BTN_TOUCH) {
                        down = value != 0;
                    } else if (type == EV_ABS) {
                        if (code == ABS_MT_POSITION_X) { x = value; sawPos = true; }
                        else if (code == ABS_MT_POSITION_Y) { y = value; sawPos = true; }
                    } else if (type == EV_SYN && code == SYN_REPORT) {
                        long t = SystemClock.uptimeMillis();
                        if (down && sawPos) {
                            sendTouch(wasDown ? Proto.TOUCH_MOVE : Proto.TOUCH_DOWN, x, y, t);
                            wasDown = true;
                        } else if (!down && wasDown) {
                            sendTouch(Proto.TOUCH_UP, x, y, t);
                            wasDown = false;
                        }
                        sawPos = false;
                    }
                    if (sWantGrab != sTouchGrabbed) break; // reopen to change grab state
                }
            } catch (IOException e) {
                log("touch: " + e);
            } finally {
                sTouchStream = null;
            }
            SystemClock.sleep(200);
        }
    }

    private static long sLastWake;

    /**
     * Keeps the CPU awake for 2 s after an input event so the app can react before the kernel
     * suspends again (Android's own input reader uses EPOLLWAKEUP for the same reason).
     * The kernel wakelock expires by itself.
     */
    static void inputWakelock() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastWake < 500) return;
        sLastWake = now;
        try (FileOutputStream f = new FileOutputStream("/sys/power/wake_lock")) {
            f.write("wp21rear_input 2000000000".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException ignored) {
        }
    }

    static void sendTouch(int action, int x, int y, long t) {
        inputWakelock();
        ByteBuffer b = ByteBuffer.allocate(13);
        b.put((byte) action).putShort((short) x).putShort((short) y).putLong(t);
        send(Proto.D_TOUCH, b.array());
    }

    static void keyLoop() {
        String path = findEventDevice("mtk-kpd");
        if (path == null) {
            log("keys: mtk-kpd not found");
            return;
        }
        // Not grabbed: the same device carries volume-up and the camera key.
        try (FileInputStream in = new FileInputStream(path)) {
            log("keys: " + path);
            byte[] ev = new byte[24];
            ByteBuffer bb = ByteBuffer.wrap(ev).order(ByteOrder.LITTLE_ENDIAN);
            while (true) {
                readFully(in, ev);
                int type = bb.getShort(16) & 0xffff;
                if (type != EV_KEY) continue;
                int code = bb.getShort(18) & 0xffff;
                int value = bb.getInt(20);
                inputWakelock();
                ByteBuffer b = ByteBuffer.allocate(11);
                b.putShort((short) code).put((byte) value).putLong(SystemClock.uptimeMillis());
                send(Proto.D_KEY, b.array());
            }
        } catch (IOException e) {
            log("keys: " + e);
        }
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    // ---------------------------------------------------------------- exec

    static void exec(int id, String cmd) {
        int code = -1;
        String output;
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (b.size() < 256 * 1024) b.write(buf, 0, n);
            }
            if (p.waitFor(20, TimeUnit.SECONDS)) code = p.exitValue();
            else p.destroyForcibly();
            output = b.toString("UTF-8");
        } catch (Throwable t) {
            output = String.valueOf(t);
        }
        byte[] o = output.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + o.length);
        bb.putInt(id).putInt(code).put(o);
        send(Proto.D_EXEC, bb.array());
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) {
        // Anything printed by mistake must not corrupt the binary channel.
        FileOutputStream rawOut = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err);

        String mode = args.length > 0 ? args[0] : "serve";
        try {
            switch (mode) {
                case "off":
                    panelOff();
                    System.err.println("panel off");
                    System.exit(0);
                    return;
                case "on":
                    standaloneOn(args.length > 1 ? Integer.parseInt(args[1]) : 4);
                    System.exit(0);
                    return;
                case "bench":
                    bench(args.length > 1 ? Integer.parseInt(args[1]) : 100, args.length > 2 ? args[2] : "pattern");
                    System.exit(0);
                    return;
                default:
                    break;
            }
        } catch (Throwable t) {
            System.err.println("error: " + t);
            System.exit(1);
            return;
        }

        sOut = new DataOutputStream(new BufferedOutputStream(rawOut, 1 << 16));
        String info;
        try {
            service();
            info = "hal ok";
        } catch (Throwable t) {
            info = "hal error: " + t;
        }
        byte[] ib = info.getBytes(StandardCharsets.UTF_8);
        ByteBuffer rb = ByteBuffer.allocate(4 + ib.length);
        rb.putInt(Proto.VERSION).put(ib);
        send(Proto.D_READY, rb.array());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (sOffOnExit) panelOff();
        }));

        Thread touch = new Thread(RearDaemon::touchLoop, "touch");
        touch.setDaemon(true);
        touch.start();
        Thread keys = new Thread(RearDaemon::keyLoop, "keys");
        keys.setDaemon(true);
        keys.start();

        ExecutorService execPool = Executors.newFixedThreadPool(2);
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(FileDescriptor.in), 1 << 18));
        byte[] frame = new byte[Proto.FRAME_BYTES];
        try {
            while (true) {
                int type = in.readUnsignedByte();
                in.readUnsignedByte();
                in.readUnsignedShort();
                int len = in.readInt();
                if (len < 0 || len > Proto.FRAME_BYTES + 64) throw new IOException("bad length " + len);
                switch (type) {
                    case Proto.C_PING: {
                        int id = in.readInt();
                        reply(id, 0, 0);
                        break;
                    }
                    case Proto.C_HAL: {
                        int id = in.readInt();
                        int code = in.readInt();
                        int arg = in.readInt();
                        long t0 = System.nanoTime();
                        try {
                            int r = hal(code, arg);
                            reply(id, r, (int) ((System.nanoTime() - t0) / 1000));
                        } catch (Throwable t) {
                            error(id, String.valueOf(t));
                        }
                        break;
                    }
                    case Proto.C_FRAME: {
                        int id = in.readInt();
                        in.readFully(frame);
                        long t0 = System.nanoTime();
                        try {
                            int r = frame(frame);
                            reply(id, r, (int) ((System.nanoTime() - t0) / 1000));
                        } catch (Throwable t) {
                            error(id, String.valueOf(t));
                        }
                        break;
                    }
                    case Proto.C_EXEC: {
                        int id = in.readInt();
                        byte[] c = new byte[len - 4];
                        in.readFully(c);
                        String cmd = new String(c, StandardCharsets.UTF_8);
                        execPool.execute(() -> exec(id, cmd));
                        break;
                    }
                    case Proto.C_GRAB: {
                        sWantGrab = in.readUnsignedByte() != 0;
                        log("grab requested: " + sWantGrab + " (applies on the next touch event)");
                        break;
                    }
                    case Proto.C_PANEL_OFF: {
                        int id = in.readInt();
                        panelOff();
                        reply(id, 0, 0);
                        break;
                    }
                    case Proto.C_OFF_ON_EXIT: {
                        sOffOnExit = in.readUnsignedByte() != 0;
                        break;
                    }
                    case Proto.C_QUIT:
                        in.skipBytes(len);
                        sOffOnExit = false;
                        System.exit(0);
                        return;
                    default:
                        in.skipBytes(len);
                        log("unknown command " + type);
                }
            }
        } catch (EOFException e) {
            // the app went away
        } catch (Throwable t) {
            Log.e(TAG, "daemon loop", t);
        }
        System.exit(0); // the shutdown hook turns the panel off when sOffOnExit
    }

    // ---------------------------------------------------------------- diagnostics modes

    static byte[] testPattern() {
        byte[] f = new byte[Proto.FRAME_BYTES];
        int[] bars = {0xF800, 0x07E0, 0x001F, 0xFFFF};
        for (int y = 0; y < Proto.H; y++) {
            for (int x = 0; x < Proto.W; x++) {
                int c = bars[Math.min(3, y * 4 / Proto.H)];
                if (x < 40 && y < 40) c = 0xFFE0; // yellow square marks the top-left corner
                int i = (y * Proto.W + x) * 2;
                f[i] = (byte) (c >> 8);
                f[i + 1] = (byte) c;
            }
        }
        return f;
    }

    static void standaloneOn(int level) throws Exception {
        byte[] f = testPattern();
        hal(Proto.HAL_POWER, 1);
        frame(f);
        Thread.sleep(400);
        hal(Proto.HAL_BL, 1);
        hal(Proto.HAL_LEVEL, level);
        frame(f);
        System.err.println("panel on, level " + level);
    }

    static void bench(int n, String kind) throws Exception {
        standaloneOn(4);
        byte[][] frames = new byte[2][];
        if (kind.equals("solid")) {
            frames[0] = new byte[Proto.FRAME_BYTES];
            frames[1] = new byte[Proto.FRAME_BYTES];
            java.util.Arrays.fill(frames[1], (byte) 0xff);
        } else if (kind.equals("noise")) {
            java.util.Random r = new java.util.Random(1);
            frames[0] = new byte[Proto.FRAME_BYTES];
            frames[1] = new byte[Proto.FRAME_BYTES];
            r.nextBytes(frames[0]);
            r.nextBytes(frames[1]);
        } else {
            frames[0] = testPattern();
            frames[1] = new byte[Proto.FRAME_BYTES];
        }
        long statusTotal = 0, writeTotal = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long a = System.nanoTime();
            hal(Proto.HAL_STATUS, -1);
            long b = System.nanoTime();
            frame(frames[i & 1]);
            long c = System.nanoTime();
            statusTotal += b - a;
            writeTotal += c - b;
        }
        long total = System.nanoTime() - t0;
        System.err.printf("frames=%d total=%.1fms avg=%.2fms fps=%.1f status=%.2fms write=%.2fms%n",
                n, total / 1e6, total / 1e6 / n, n * 1e9 / total, statusTotal / 1e6 / n, writeTotal / 1e6 / n);
    }
}

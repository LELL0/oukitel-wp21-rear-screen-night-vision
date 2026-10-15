package com.lello.wp21rear.daemon;

/**
 * Binary framing shared by the root daemon and the app.
 *
 * Every message, in both directions, is an 8-byte header followed by the payload:
 *   u8  type
 *   u8  0
 *   u16 0
 *   u32 payload length (big-endian)
 * All integers inside payloads are big-endian (DataInput/DataOutput).
 */
public final class Proto {
    private Proto() {}

    public static final int VERSION = 1;
    public static final int W = 360;
    public static final int H = 360;
    public static final int FRAME_BYTES = W * H * 2;

    // HAL transaction codes of vendor.yft.hardware.tinylcd@1.0::ITinylcd
    public static final int HAL_POWER = 1;      // lcd_set_power(int) 0/1
    public static final int HAL_LEVEL = 2;      // lcd_set_backlight(int) 0..7
    public static final int HAL_BL = 3;         // lcd_set_bl(int) 0/1
    public static final int HAL_FRAME = 4;      // lcd_wirte_frame(uint8_t[259200])
    public static final int HAL_STATUS = 5;     // lcd_frame_status()

    // app -> daemon
    public static final int C_PING = 1;         // i32 id
    public static final int C_HAL = 2;          // i32 id, i32 code, i32 arg (arg < 0: no argument)
    public static final int C_FRAME = 3;        // i32 id, FRAME_BYTES RGB565 big-endian
    public static final int C_EXEC = 4;         // i32 id, utf8 shell command (run as root with sh -c)
    public static final int C_GRAB = 5;         // u8 1 = grab the rear touch device exclusively, 0 = release
    public static final int C_PANEL_OFF = 6;    // i32 id; bl(0), 100 ms, power(0)
    public static final int C_QUIT = 7;         // panel stays as it is; the app turns it off first if needed
    public static final int C_OFF_ON_EXIT = 8;  // u8 1 = turn the panel off when the app goes away (default 1)

    // daemon -> app
    public static final int D_READY = 1;        // i32 version, utf8 info
    public static final int D_REPLY = 2;        // i32 id, i32 ret, i32 micros
    public static final int D_TOUCH = 3;        // u8 action (0 down, 1 move, 2 up), i16 x, i16 y, i64 uptime ms
    public static final int D_KEY = 4;          // i16 linux key code, u8 value (0 up, 1 down, 2 repeat), i64 ms
    public static final int D_LOG = 5;          // utf8
    public static final int D_EXEC = 6;         // i32 id, i32 exit code, utf8 output
    public static final int D_ERROR = 7;        // i32 id, utf8 message

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_MOVE = 1;
    public static final int TOUCH_UP = 2;
}

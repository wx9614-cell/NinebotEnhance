package dev.ichinomiya.ninebotenhance.ipc;

public final class Protocol {
    public static final String VERSION = "1.1.5.2";
    public static final int VERSION_CODE = 54;
    public static final String DISPLAY_NAME = "Ninebot Enhance Display";
    public static final String DAEMON_CLASS = "dev.ichinomiya.ninebotenhance.display.RootDisplayMain";
    public static final String MODULE = "dev.ichinomiya.ninebotenhance", TARGET = "cn.ninebot.ninebot";
    public static final String DESCRIPTOR = MODULE + ".VirtualDisplay.v6", ROOT_AUTHORITY = MODULE + ".root";
    public static final String REQUEST = "mirror_request", TAG = "NinebotEnhance";
    public static final int READ = 1, REPORT = 2, STOP_DIRECT = 3, BEGIN = 4, SETTINGS = 5, LOG = 6;
    public static final int UI_BACK = 8, UI_INPUT = 9, UI_RESTART_APP = 10, APP_ICON = 11;
    public static final int UI_TEXT = 12, UI_TYPING_KEY = 13, UI_DELETE = 14;
    public static final int PRIVILEGE = 15;
    public static final int PROJECTION_SURFACE = 16;
    public static final int LOG_EXPORT_BEGIN = 17, LOG_EXPORT_FINISH = 18, LOG_EXPORT_CANCEL = 19;
    public static final int NOTIFICATION_SETTINGS = 20;
    public static final int HUD_SNAPSHOT = 21;
    public static final int LAUNCH_APP_PICKER = 22;
    /** Navigation apps publish their turn-by-turn state; the Ninebot process polls the latest one. */
    public static final int NAVI_UPDATE = 23, NAVI_SNAPSHOT = 24;
    /** Opens the module's own lamp screen, where its Bluetooth permissions are requested. */
    public static final int LAMP_SETTINGS = 25, BMS_SETTINGS = 26;
    /** Opens the module's own touch panel screen: pick an external touchscreen and bind it to the virtual display. */
    public static final int TOUCH_SETTINGS = 27;
    /** Touch panel calibration from the preview toolbar: enter / leave the tapping mode, then store the solved map. */
    public static final int TOUCH_CALIBRATE = 28, TOUCH_CALIBRATION = 29;
    public static final String SCREEN_CAPTURE = "screen_capture", CAPTURE_WIDTH = "capture_width", CAPTURE_HEIGHT = "capture_height";
    /** Status flag of the drawn picture source: the host paints the frame itself, nothing is captured. */
    public static final String DRAWN = "drawn";
    public static final String CAPTURE_REVISION = "capture_revision", CAPTURE_CONSENT = "capture_consent";
    public static final int ROOT_STOP = 30, ROOT_INPUT = 32, ROOT_KEY = 33, ROOT_RESTART_APP = 34;
    public static final int ROOT_TEXT = 35, ROOT_TYPING_KEY = 36, ROOT_DELETE = 37;
    public static final int ROOT_TOUCH_CALIBRATE = 38, ROOT_TOUCH_CALIBRATION = 39;
    /** Daemon to Ninebot, on the session owner Binder: the touch panel's contacts as x / y pairs in frame pixels, and one raw calibration tap. */
    public static final int OWNER_TOUCH_MARKS = 40, OWNER_TOUCH_SAMPLE = 41;
    /** The host asks for the module's cached GitHub release lookup; a stale cache starts a fresh one. */
    public static final int UPDATE_CHECK = 42;
    /** The bound board's poll interval, protocol and whether it is the voltage and power source; saved with {@code save}. */
    public static final int BMS_CONFIG = 43;
    public static final String APP_RECOVERY = "appRecovery", APP_RECOVERY_DETAIL = "appRecoveryDetail";
    public static final String APP_LAYOUT_POLICY = "appLayoutPolicy";
    public static boolean validRequest(String value) { return value != null && value.matches("[a-f0-9]{32}"); }
    private Protocol() {}
}

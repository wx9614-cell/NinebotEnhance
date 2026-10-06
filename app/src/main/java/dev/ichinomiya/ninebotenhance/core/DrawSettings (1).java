package dev.ichinomiya.ninebotenhance.core;

/**
 * The drawn picture: the speed gauge's full-scale value and up to three information fields under it. Saved in the host's
 * widget preferences next to the other card settings; the frame the module draws into comes from the cast configuration.
 */
public record DrawSettings(int maxSpeed, int first, int second, int third) {
    public static final int NONE = 0, VOLTAGE = 1, POWER = 2, SPEED = 3, TYRES = 4, BMS_SOC = 5;
    public static final String[] FIELD_NAMES = {"无", "电压", "功率", "速度", "胎压", "BMS 电量"};
    public static final int MIN_MAX_SPEED = 30, MAX_MAX_SPEED = 200, MAX_SPEED_STEP = 10, DEFAULT_MAX_SPEED = 80;
    public static final DrawSettings DEFAULT = new DrawSettings(DEFAULT_MAX_SPEED, VOLTAGE, POWER, NONE);
    public DrawSettings {
        maxSpeed = clampSpeed(maxSpeed); first = field(first); second = field(second); third = field(third);
        if (first == NONE && second == NONE && third == NONE) first = VOLTAGE;
    }
    /** Rounded onto the 10 km/h step between 30 and 200. */
    public static int clampSpeed(int value) {
        int steps = Math.round(value / (float) MAX_SPEED_STEP);
        return MAX_SPEED_STEP * Math.max(MIN_MAX_SPEED / MAX_SPEED_STEP, Math.min(MAX_MAX_SPEED / MAX_SPEED_STEP, steps));
    }
    private static int field(int value) { return value < NONE || value >= FIELD_NAMES.length ? NONE : value; }
    /** The chosen fields in order, without the empty slots; never empty. */
    public int[] shown() {
        int count = (first != NONE ? 1 : 0) + (second != NONE ? 1 : 0) + (third != NONE ? 1 : 0);
        int[] out = new int[count]; int i = 0;
        if (first != NONE) out[i++] = first; if (second != NONE) out[i++] = second; if (third != NONE) out[i] = third;
        return out;
    }
    public boolean uses(int field) { return first == field || second == field || third == field; }
    public static DrawSettings read(DisplaySettings.IntSetting values) {
        return new DrawSettings(values.get("draw_max_speed", DEFAULT_MAX_SPEED), values.get("draw_field_1", VOLTAGE),
                values.get("draw_field_2", POWER), values.get("draw_field_3", NONE));
    }
    public static String fieldName(int field) { return FIELD_NAMES[field(field)]; }
    public String label() {
        StringBuilder out = new StringBuilder("表程 ").append(maxSpeed).append(" km/h");
        for (int field : shown()) out.append("，").append(fieldName(field));
        return out.toString();
    }
}

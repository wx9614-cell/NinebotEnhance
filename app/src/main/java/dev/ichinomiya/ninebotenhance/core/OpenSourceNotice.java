package dev.ichinomiya.ninebotenhance.core;

/** The one-time sentence the settings screen asks for, plus the project links shown on the about screen. */
public final class OpenSourceNotice {
    public static final String REQUIRED = "我已知本项目免费开源在GitHub，如果你是买的教程那你亏大了。";
    public static final String REPOSITORY = "https://github.com/Margele/NinebotEnhance";
    public static final String AUTHORS = "酷安@夜里X/@Shirona1337 · GitHub@wx9614-cell";
    public static final String QQ_GROUP = "暂无qq，敬请期待（夜里）";
    public static final String KEY = "open_source_notice";
    /** Whitespace around the sentence and an ASCII full stop are accepted; anything else must be typed exactly. */
    public static boolean matches(CharSequence input) {
        if (input == null) return false;
        String value = input.toString().trim();
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1) + "。";
        return REQUIRED.equals(value);
    }
    private OpenSourceNotice() {}
}

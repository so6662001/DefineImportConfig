package com.eiss.erp.defineimport.util;

import java.math.BigDecimal;

/**
 * 规格字符串解析：壁厚与基底规格。
 */
public final class SpecParseUtil {

    private SpecParseUtil() {
    }

    /**
     * 取最后一个分隔符之后的末段，解析为数字（壁厚等）。
     *
     * @return 解析成功返回 {@link BigDecimal}，无分隔符或无法解析时返回 {@code null}
     */
    public static BigDecimal extractWallThickness(String spec, String separator) {
        if (spec == null || separator == null || separator.isEmpty()) {
            return null;
        }
        String s = spec.trim();
        int last = s.lastIndexOf(separator);
        if (last < 0) {
            return null;
        }
        String tail = s.substring(last + separator.length()).trim();
        String num = SafeConvertUtil.extractNumber(tail);
        if (num == null) {
            return null;
        }
        try {
            return new BigDecimal(num);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 去掉最后一个分隔符及其后的末段，得到基底规格（不含壁厚段）。
     * 若无分隔符，原样返回 {@code spec}（经 trim）。
     */
    public static String removeWallThickness(String spec, String separator) {
        if (spec == null) {
            return null;
        }
        if (separator == null || separator.isEmpty()) {
            return spec.trim();
        }
        String s = spec.trim();
        int last = s.lastIndexOf(separator);
        if (last < 0) {
            return s;
        }
        return s.substring(0, last).trim();
    }
}

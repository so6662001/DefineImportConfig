package com.eiss.erp.defineimport.util;

import java.util.List;
import java.util.Locale;

/**
 * 区间表达式解析与包含、重叠判断。
 */
public final class RangeUtil {

    private RangeUtil() {
    }

    /**
     * 闭区间 [min, max]。
     */
    public static final class Range {
        private final double min;
        private final double max;

        public Range(double min, double max) {
            this.min = min;
            this.max = max;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }
    }

    /**
     * 解析区间文本，如 {@code 10-20}、{@code 0~100}。
     * <p>
     * 按 {@code separators} 顺序尝试切分为左右界；若无分隔符匹配，则尝试整体解析为单点区间 [x, x]。
     * </p>
     *
     * @param infinityKeywords 出现在左界表示负无穷，出现在右界表示正无穷（子串匹配，忽略大小写）
     * @param zeroKeywords     表示数值 0 的关键字（子串匹配，忽略大小写）
     * @return 解析成功返回区间，否则 {@code null}
     */
    public static Range parseRange(
            String text,
            List<String> separators,
            List<String> infinityKeywords,
            List<String> zeroKeywords) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (separators != null) {
            for (String sep : separators) {
                if (sep == null || sep.isEmpty()) {
                    continue;
                }
                int idx = s.indexOf(sep);
                if (idx >= 0) {
                    String left = s.substring(0, idx).trim();
                    String right = s.substring(idx + sep.length()).trim();
                    Double lo = parseBound(left, true, infinityKeywords, zeroKeywords);
                    Double hi = parseBound(right, false, infinityKeywords, zeroKeywords);
                    if (lo != null && hi != null) {
                        double min = Math.min(lo, hi);
                        double max = Math.max(lo, hi);
                        return new Range(min, max);
                    }
                    return null;
                }
            }
        }
        Double single = parseBound(s, true, infinityKeywords, zeroKeywords);
        if (single != null) {
            return new Range(single, single);
        }
        return null;
    }

    /** {@code outer} 是否完全包含 {@code inner}（闭区间）。 */
    public static boolean contains(Range outer, Range inner) {
        if (outer == null || inner == null) {
            return false;
        }
        return outer.min <= inner.min && outer.max >= inner.max;
    }

    /** 两闭区间是否相交。 */
    public static boolean overlaps(Range a, Range b) {
        if (a == null || b == null) {
            return false;
        }
        return !(a.max < b.min || b.max < a.min);
    }

    private static Double parseBound(
            String part,
            boolean isLeft,
            List<String> infinityKeywords,
            List<String> zeroKeywords) {
        if (part == null || part.isEmpty()) {
            return null;
        }
        String lower = part.toLowerCase(Locale.ROOT);
        if (matchesAny(lower, infinityKeywords)) {
            return isLeft ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (matchesAny(lower, zeroKeywords)) {
            return 0.0d;
        }
        String num = SafeConvertUtil.extractNumber(part);
        if (num == null) {
            return null;
        }
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean matchesAny(String lowerHaystack, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String k : keywords) {
            if (k == null || k.isEmpty()) {
                continue;
            }
            if (lowerHaystack.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

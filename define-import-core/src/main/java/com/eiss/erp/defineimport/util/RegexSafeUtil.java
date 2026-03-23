package com.eiss.erp.defineimport.util;

import org.slf4j.Logger;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Safe regex compilation for user/config-supplied patterns (ReDoS mitigation via length cap).
 */
public final class RegexSafeUtil {

    public static final int MAX_PATTERN_LENGTH = 200;

    private RegexSafeUtil() {
    }

    /**
     * Compiles a pattern if it is non-empty, within {@link #MAX_PATTERN_LENGTH}, and syntactically valid.
     *
     * @return compiled pattern, or {@code null} if compilation must be skipped
     */
    public static Pattern safeCompile(String pattern, Logger log) {
        if (pattern == null || pattern.isEmpty()) {
            return null;
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            log.warn("正则表达式过长(>{}), 已跳过该规则: length={}", MAX_PATTERN_LENGTH, pattern.length());
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            log.warn("正则表达式编译失败: {}, 原因: {}", pattern, e.getMessage());
            return null;
        }
    }
}

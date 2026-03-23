package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.util.RangeUtil;
import com.eiss.erp.defineimport.util.RegexSafeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规格区间后缀解析器 (v1.3)
 * <p>
 * 将带区间后缀的规格拆分为基础规格和区间范围。
 * 如: "5#（10-15）" → baseSpec="5#", range=[10,15]
 * </p>
 * <p>
 * 默认模式匹配末尾的中英文括号内容: {@code [（(]([^）)]+)[）)]\s*$}
 * </p>
 */
public class SpecRangeParser {

    private static final Logger log = LoggerFactory.getLogger(SpecRangeParser.class);

    /** 默认区间后缀正则：匹配末尾中/英文括号 */
    private static final String DEFAULT_RANGE_PATTERN = "[（(]([^）)]+)[）)]\\s*$";

    private SpecRangeParser() {
    }

    /**
     * 解析结果：基础规格 + 可选的数值区间。
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SpecWithRange {
        private String baseSpec;
        /** 如无区间后缀则为 null */
        private RangeUtil.Range range;
    }

    /**
     * 解析规格字符串，提取尾部区间后缀。
     *
     * @param specValue 原始规格文本
     * @param config    区间解析配置（含正则、分隔符、关键字等）
     * @return 解析结果，baseSpec 始终非 null（除非输入为 null）
     */
    public static SpecWithRange parse(String specValue, SpecRangeConfig config) {
        if (specValue == null || specValue.isEmpty() || config == null) {
            return new SpecWithRange(specValue, null);
        }

        String pattern = config.getRangePattern();
        if (pattern == null || pattern.isEmpty()) {
            pattern = DEFAULT_RANGE_PATTERN;
        }

        Pattern p = RegexSafeUtil.safeCompile(pattern, log);
        if (p == null) {
            p = Pattern.compile(DEFAULT_RANGE_PATTERN);
        }
        Matcher m = p.matcher(specValue);
        if (m.find()) {
            String rangeText = m.group(1);
            String baseSpec = specValue.substring(0, m.start()).trim();
            // 若截取后 baseSpec 为空，则保留原始值
            if (baseSpec.isEmpty()) {
                return new SpecWithRange(specValue, null);
            }
            RangeUtil.Range range = RangeUtil.parseRange(
                    rangeText,
                    config.getRangeSeparators(),
                    config.getInfinityKeywords(),
                    config.getZeroKeywords());
            return new SpecWithRange(baseSpec, range);
        }

        return new SpecWithRange(specValue, null);
    }
}

package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.util.RegexSafeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 字符转换引擎
 * <p>
 * 执行预构建的转换规则管道，支持四种匹配类型：
 * <ul>
 *   <li>LITERAL - 字面量替换</li>
 *   <li>REGEX - 正则表达式替换（编译后缓存）</li>
 *   <li>FULLWIDTH - 全角→半角转换</li>
 *   <li>CHARCLASS - 字符类替换（本质上是正则字符类语法）</li>
 * </ul>
 * </p>
 */
public class CharTransformer {

    private static final Logger log = LoggerFactory.getLogger(CharTransformer.class);

    private static final int MAX_PATTERN_CACHE = 500;

    /**
     * 编译后的正则缓存（LRU，最多 {@link #MAX_PATTERN_CACHE} 条）
     */
    private static final Object PATTERN_CACHE_LOCK = new Object();
    private static final Map<String, Pattern> PATTERN_CACHE = new LinkedHashMap<String, Pattern>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
            return size() > MAX_PATTERN_CACHE;
        }
    };

    private CharTransformer() {
    }

    /**
     * 在输入值上依次执行管道中的每条转换规则
     *
     * @param value    原始字符串
     * @param pipeline 预构建的有序规则列表
     * @return 转换后的字符串
     */
    public static String execute(String value, List<CharTransformConfig.CharRule> pipeline) {
        if (value == null || value.isEmpty() || pipeline == null) {
            return value;
        }
        for (CharTransformConfig.CharRule rule : pipeline) {
            if (rule == null) {
                continue;
            }
            value = applyRule(value, rule);
        }
        return value;
    }

    /**
     * 应用单条规则
     */
    private static String applyRule(String value, CharTransformConfig.CharRule rule) {
        String matchType = rule.getMatchType();
        if (matchType == null || matchType.isBlank()) {
            return value;
        }

        String replaceValue = rule.getReplaceValue() != null ? rule.getReplaceValue() : "";

        switch (matchType.toUpperCase()) {
            case "LITERAL":
                return applyLiteral(value, rule.getMatchPattern(), replaceValue);
            case "REGEX":
                return applyRegex(value, rule.getMatchPattern(), replaceValue);
            case "FULLWIDTH":
                return applyFullwidth(value);
            case "CHARCLASS":
                return applyCharClass(value, rule.getMatchPattern(), replaceValue);
            default:
                log.warn("未知的字符转换类型: {}, 跳过该规则", matchType);
                return value;
        }
    }

    /**
     * 字面量替换：直接 String.replace
     */
    private static String applyLiteral(String value, String matchPattern, String replaceValue) {
        if (matchPattern == null || matchPattern.isEmpty()) {
            return value;
        }
        return value.replace(matchPattern, replaceValue);
    }

    /**
     * 正则表达式替换：使用缓存的 Pattern
     */
    private static String applyRegex(String value, String matchPattern, String replaceValue) {
        if (matchPattern == null || matchPattern.isEmpty()) {
            return value;
        }
        Pattern pattern = getOrCompilePattern(matchPattern);
        if (pattern == null) {
            return value;
        }
        try {
            return pattern.matcher(value).replaceAll(replaceValue);
        } catch (Exception e) {
            log.warn("正则替换执行失败, pattern={}, value={}: {}", matchPattern, value, e.getMessage());
            return value;
        }
    }

    /**
     * 全角→半角转换：将全角ASCII(0xFF01~0xFF5E)转半角，全角空格(0x3000)转半角
     */
    private static String applyFullwidth(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 字符类替换：matchPattern 作为正则字符类语法（如 "[，。、]"），匹配后替换
     */
    private static String applyCharClass(String value, String matchPattern, String replaceValue) {
        if (matchPattern == null || matchPattern.isEmpty()) {
            return value;
        }
        Pattern pattern = getOrCompilePattern(matchPattern);
        if (pattern == null) {
            return value;
        }
        try {
            return pattern.matcher(value).replaceAll(replaceValue);
        } catch (Exception e) {
            log.warn("字符类替换执行失败, pattern={}: {}", matchPattern, e.getMessage());
            return value;
        }
    }

    /**
     * 获取或编译正则表达式，编译失败时记录警告并返回 null
     */
    private static Pattern getOrCompilePattern(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        synchronized (PATTERN_CACHE_LOCK) {
            Pattern existing = PATTERN_CACHE.get(regex);
            if (existing != null) {
                return existing;
            }
        }
        Pattern compiled = RegexSafeUtil.safeCompile(regex, log);
        if (compiled == null) {
            return null;
        }
        synchronized (PATTERN_CACHE_LOCK) {
            Pattern again = PATTERN_CACHE.get(regex);
            if (again != null) {
                return again;
            }
            PATTERN_CACHE.put(regex, compiled);
            return compiled;
        }
    }

    /**
     * 构建合并后的转换规则管道。
     * <p>
     * 优先级：字段级规则 > 模板级规则 > 预设规则。
     * 通过 excludePresetCodes 可在字段配置中排除特定预设规则。
     * 最终按 sortOrder 升序排列。
     * </p>
     *
     * @param presetRules   全局预设规则列表
     * @param templateRules 模板级规则列表
     * @param fieldConfig   字段级字符转换配置（可为null）
     * @return 排序后的合并规则管道
     */
    public static List<CharTransformConfig.CharRule> buildPipeline(
            List<CharTransformConfig.CharRule> presetRules,
            List<CharTransformConfig.CharRule> templateRules,
            CharTransformConfig fieldConfig) {

        List<CharTransformConfig.CharRule> pipeline = new ArrayList<>();

        // 收集需排除的预设规则代码
        Set<String> excludeCodes = new HashSet<>();
        if (fieldConfig != null && fieldConfig.getExcludePresetCodes() != null) {
            excludeCodes.addAll(fieldConfig.getExcludePresetCodes());
        }

        // 1. 添加预设规则（排除被屏蔽的）
        if (presetRules != null) {
            for (CharTransformConfig.CharRule rule : presetRules) {
                if (rule == null) {
                    continue;
                }
                String ruleCode = rule.getRuleCode();
                if (ruleCode != null && excludeCodes.contains(ruleCode)) {
                    continue;
                }
                pipeline.add(rule);
            }
        }

        // 2. 添加模板级规则
        if (templateRules != null) {
            for (CharTransformConfig.CharRule rule : templateRules) {
                if (rule != null) {
                    pipeline.add(rule);
                }
            }
        }

        // 3. 添加字段级规则（最高优先级）
        if (fieldConfig != null && fieldConfig.getFieldRules() != null) {
            for (CharTransformConfig.CharRule rule : fieldConfig.getFieldRules()) {
                if (rule != null) {
                    pipeline.add(rule);
                }
            }
        }

        // 按 sortOrder 升序排序，null 的 sortOrder 排在最后
        pipeline.sort(Comparator.comparingInt(r ->
                r.getSortOrder() != null ? r.getSortOrder() : Integer.MAX_VALUE));

        return pipeline;
    }
}

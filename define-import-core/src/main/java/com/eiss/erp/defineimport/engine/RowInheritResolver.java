package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.RowInheritConfig;
import com.eiss.erp.defineimport.util.RegexSafeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 行间继承解析器
 * <p>
 * 适用场景：钢贸Excel中规格列经常出现合并或省略写法，
 * 例如上一行为 "219*6"，下一行仅写 "8"（表示壁厚变化），
 * 需要继承上一行的前缀 "219" 拼装为 "219*8"。
 * </p>
 * <p>
 * 判断逻辑：
 * <ol>
 *   <li>用 partialPattern 正则检测当前值是否为"部分值"（仅含壁厚等局部信息）</li>
 *   <li>若为完整值，提取并缓存前缀（按 separator 分割取前段）</li>
 *   <li>若为部分值，取缓存中的前缀，按 assembleTemplate 拼装完整值</li>
 * </ol>
 * </p>
 */
public class RowInheritResolver {

    private static final Logger log = LoggerFactory.getLogger(RowInheritResolver.class);

    /**
     * 每个字段的前缀缓存，key 为 fieldCode
     */
    private final Map<String, String> prevPrefixMap = new HashMap<>();

    /** 已成功编译的 partialPattern */
    private final Map<String, Pattern> partialPatternCache = new HashMap<>();
    /** 编译失败过的 partialPattern，避免重复日志与重复尝试 */
    private final Set<String> failedPartialPatterns = new HashSet<>();

    /**
     * 对当前值进行行间继承处理。
     *
     * @param fieldCode    字段代码
     * @param currentValue 当前行的原始值
     * @param config       行继承配置（可为 null，表示不启用）
     * @return 处理后的值（可能带继承的前缀）
     */
    public String resolve(String fieldCode, String currentValue, RowInheritConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return currentValue;
        }
        if (currentValue == null || currentValue.isEmpty()) {
            return currentValue;
        }

        String partialPattern = config.getPartialPattern();
        if (partialPattern == null || partialPattern.isBlank()) {
            return currentValue;
        }

        Pattern compiled = getPartialPattern(partialPattern);
        if (compiled == null) {
            return currentValue;
        }

        boolean isPartial = compiled.matcher(currentValue).matches();

        if (!isPartial) {
            // 当前值是完整值，更新前缀缓存
            String sep = config.getSeparator();
            if (sep != null && !sep.isEmpty()) {
                int lastIdx = currentValue.lastIndexOf(sep);
                if (lastIdx >= 0) {
                    prevPrefixMap.put(fieldCode, currentValue.substring(0, lastIdx));
                } else {
                    // 完整值但无分隔符，可能是单段值（如纯规格号），不更新前缀
                    prevPrefixMap.remove(fieldCode);
                }
            }
            return currentValue;
        }

        // 当前值是部分值，尝试继承前缀
        String prefix = prevPrefixMap.get(fieldCode);
        if (prefix == null) {
            return currentValue;
        }

        String template = config.getAssembleTemplate();
        if (template == null || template.isBlank()) {
            // 无模板时用分隔符直接拼接
            String sep = config.getSeparator() != null ? config.getSeparator() : "";
            return prefix + sep + currentValue;
        }

        return template
                .replace("${prefix}", prefix)
                .replace("${current}", currentValue);
    }

    /**
     * 重置前缀缓存（在切换工作表时调用）
     */
    public void reset() {
        prevPrefixMap.clear();
        partialPatternCache.clear();
        failedPartialPatterns.clear();
    }

    private Pattern getPartialPattern(String partialPattern) {
        Pattern cached = partialPatternCache.get(partialPattern);
        if (cached != null) {
            return cached;
        }
        if (failedPartialPatterns.contains(partialPattern)) {
            return null;
        }
        Pattern p = RegexSafeUtil.safeCompile(partialPattern, log);
        if (p == null) {
            failedPartialPatterns.add(partialPattern);
            return null;
        }
        partialPatternCache.put(partialPattern, p);
        return p;
    }
}

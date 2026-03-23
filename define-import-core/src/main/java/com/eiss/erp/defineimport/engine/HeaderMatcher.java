package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.ColumnSourceConfig;
import com.eiss.erp.defineimport.model.enums.MatchModeEnum;

import java.util.*;

/**
 * 表头匹配器
 * <p>
 * 将实际Excel表头列名与模板中定义的 headerAliases 进行匹配，
 * 支持三种模式：按索引(INDEX)、按别名(ALIAS)、索引优先(INDEX_FIRST)。
 * </p>
 * <p>
 * 归一化规则：trim → 全角→半角 → 大写，确保中英文标点、空格差异不影响匹配。
 * </p>
 */
public class HeaderMatcher {

    private HeaderMatcher() {
    }

    /**
     * 将模板字段配置与实际Excel表头进行匹配。
     *
     * @param headMap      EasyExcel 读取的表头行数据（列索引 → 单元格文本）
     * @param fieldConfigs 模板字段配置（fieldCode → ColumnSourceConfig）
     * @return fieldCode → 实际列索引 的映射，未匹配到的字段不包含在结果中
     */
    public static Map<String, Integer> match(Map<Integer, String> headMap,
                                              Map<String, ColumnSourceConfig> fieldConfigs) {
        Map<String, Integer> result = new HashMap<>();
        if (fieldConfigs == null || fieldConfigs.isEmpty()) {
            return result;
        }
        if (headMap == null) {
            headMap = Collections.emptyMap();
        }

        // 预构建归一化后的表头映射，避免重复计算
        Map<Integer, String> normalizedHeadMap = new HashMap<>(headMap.size());
        for (Map.Entry<Integer, String> entry : headMap.entrySet()) {
            normalizedHeadMap.put(entry.getKey(), normalize(entry.getValue()));
        }

        for (Map.Entry<String, ColumnSourceConfig> entry : fieldConfigs.entrySet()) {
            String fieldCode = entry.getKey();
            ColumnSourceConfig config = entry.getValue();
            if (config == null) {
                continue;
            }

            MatchModeEnum mode = resolveMatchMode(config.getMatchMode());
            Integer matchedIndex = null;

            switch (mode) {
                case INDEX:
                    matchedIndex = matchByIndex(config);
                    break;
                case ALIAS:
                    matchedIndex = matchByAlias(config, normalizedHeadMap);
                    break;
                case INDEX_FIRST:
                    // 索引优先：先尝试按索引，索引无效时回退到别名
                    matchedIndex = matchByIndex(config);
                    if (matchedIndex == null) {
                        matchedIndex = matchByAlias(config, normalizedHeadMap);
                    }
                    break;
            }

            if (matchedIndex != null) {
                result.put(fieldCode, matchedIndex);
            }
        }

        return result;
    }

    /**
     * 按列索引匹配：直接使用配置中的 columnIndex
     */
    private static Integer matchByIndex(ColumnSourceConfig config) {
        Integer idx = config.getColumnIndex();
        if (idx != null && idx >= 0) {
            return idx;
        }
        return null;
    }

    /**
     * 按别名匹配：遍历 headerAliases，与归一化后的表头单元格逐一比对，
     * 返回首个匹配到的列索引。
     */
    private static Integer matchByAlias(ColumnSourceConfig config,
                                         Map<Integer, String> normalizedHeadMap) {
        List<String> aliases = config.getHeaderAliases();
        if (aliases == null || aliases.isEmpty()) {
            // 无别名列表时尝试 headerName
            String headerName = config.getHeaderName();
            if (headerName == null || headerName.isBlank()) {
                return null;
            }
            aliases = Collections.singletonList(headerName);
        }

        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String normalizedAlias = normalize(alias);
            for (Map.Entry<Integer, String> headEntry : normalizedHeadMap.entrySet()) {
                if (normalizedAlias.equals(headEntry.getValue())) {
                    return headEntry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * 解析匹配模式；默认为 ALIAS
     */
    private static MatchModeEnum resolveMatchMode(String matchMode) {
        if (matchMode == null || matchMode.isBlank()) {
            return MatchModeEnum.ALIAS;
        }
        try {
            return MatchModeEnum.fromCode(matchMode.trim());
        } catch (IllegalArgumentException e) {
            return MatchModeEnum.ALIAS;
        }
    }

    /**
     * 文本归一化：trim → 全角→半角 → 大写
     * <ul>
     *   <li>全角ASCII字符(0xFF01~0xFF5E)转半角(减0xFEE0)</li>
     *   <li>全角空格(0x3000)转半角空格</li>
     *   <li>转大写以忽略大小写差异</li>
     * </ul>
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        text = text.trim();
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase();
    }
}

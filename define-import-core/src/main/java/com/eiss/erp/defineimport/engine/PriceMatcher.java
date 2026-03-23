package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import com.eiss.erp.defineimport.util.RangeUtil;
import com.eiss.erp.defineimport.util.SpecParseUtil;

import java.math.BigDecimal;
import java.util.*;

/**
 * 价格→库存匹配器 (v1.3增强)
 * <p>
 * 支持精确匹配、壁厚区间匹配、规格区间后缀匹配。
 * </p>
 * <p>
 * 规格区间匹配策略 (matchStrategy):
 * <ul>
 *   <li>PRICE_CONTAINS_INVENTORY — 价格区间完全包含库存壁厚值</li>
 *   <li>RANGE_OVERLAP — 价格区间与库存壁厚区间有交集</li>
 *   <li>INVENTORY_MIN_IN_PRICE — 库存壁厚最小值落在价格区间内</li>
 *   <li>BASE_SPEC_ONLY — 仅按基础规格匹配，忽略区间</li>
 * </ul>
 * </p>
 */
public class PriceMatcher {

    private PriceMatcher() {
    }

    /**
     * 将价格行匹配到库存行并填充价格字段。
     *
     * @param inventoryRows          库存数据
     * @param priceRows              价格数据
     * @param matchFields            匹配用字段列表 (如 ["category", "spec", "origin"])
     * @param wallThicknessMatchMode 壁厚匹配模式: 0=精确, 1=区间
     * @param specRangeMatchMode     规格区间模式: 0=不处理, 1=拆分+区间匹配
     * @param specRangeConfig        区间解析配置（可为 null）
     * @param specSeparator          规格中壁厚分隔符（如 "*"）
     * @return 未匹配到的价格行错误列表
     */
    public static List<ExcelImportError> match(List<ParsedRowDto> inventoryRows,
                                                List<ParsedRowDto> priceRows,
                                                List<String> matchFields,
                                                int wallThicknessMatchMode,
                                                int specRangeMatchMode,
                                                SpecRangeConfig specRangeConfig,
                                                String specSeparator) {
        List<ExcelImportError> errors = new ArrayList<>();
        if (inventoryRows == null || priceRows == null || matchFields == null) {
            return errors;
        }

        // 构建库存索引: matchKey → 库存行列表
        Map<String, List<ParsedRowDto>> inventoryIndex = buildIndex(
                inventoryRows, matchFields, specRangeMatchMode, specRangeConfig);

        for (ParsedRowDto priceRow : priceRows) {
            boolean matched = tryMatch(priceRow, inventoryIndex, matchFields,
                    wallThicknessMatchMode, specRangeMatchMode, specRangeConfig, specSeparator);

            if (!matched) {
                errors.add(ExcelImportError.builder()
                        .sheetName(priceRow.getSheetName())
                        .rowIndex(priceRow.getRowIndex())
                        .fieldName("价格匹配")
                        .errorMsg(String.format("未在库存中找到匹配记录(品类=%s, 规格=%s, 产地=%s)",
                                nullToEmpty(priceRow.getCategory()),
                                nullToEmpty(priceRow.getSpec()),
                                nullToEmpty(priceRow.getOrigin())))
                        .errorLevel("WARNING")
                        .errorType("MATCH_FAIL")
                        .build());
            }
        }
        return errors;
    }

    /**
     * 尝试将一条价格行匹配到库存索引中的行。
     * 匹配成功时将价格写入所有匹配到的库存行。
     */
    private static boolean tryMatch(ParsedRowDto priceRow,
                                     Map<String, List<ParsedRowDto>> inventoryIndex,
                                     List<String> matchFields,
                                     int wallThicknessMatchMode,
                                     int specRangeMatchMode,
                                     SpecRangeConfig specRangeConfig,
                                     String specSeparator) {
        boolean matched = false;

        // 第一步：构建精确匹配键
        String exactKey = buildMatchKey(priceRow, matchFields);
        List<ParsedRowDto> candidates = inventoryIndex.get(exactKey);

        // 第二步：如果精确匹配未命中且启用了规格区间模式，尝试按 baseSpec 匹配
        if (candidates == null && specRangeMatchMode == 1 && specRangeConfig != null) {
            SpecRangeParser.SpecWithRange priceSpecRange =
                    SpecRangeParser.parse(priceRow.getSpec(), specRangeConfig);
            if (priceSpecRange.getRange() != null) {
                // 用 baseSpec 替换 spec 构建键
                String baseSpecKey = buildMatchKeyWithSpec(priceRow, matchFields, priceSpecRange.getBaseSpec());
                candidates = inventoryIndex.get(baseSpecKey);

                // 对候选行按区间策略筛选
                if (candidates != null) {
                    String strategy = specRangeConfig.getMatchStrategy();
                    if (strategy == null || strategy.isEmpty()) {
                        strategy = "PRICE_CONTAINS_INVENTORY";
                    }
                    candidates = filterByRangeStrategy(candidates, priceSpecRange.getRange(),
                            strategy, specRangeConfig, specSeparator);
                }
            }
        }

        if (candidates != null && !candidates.isEmpty()) {
            for (ParsedRowDto invRow : candidates) {
                // 壁厚区间匹配检查
                if (wallThicknessMatchMode == 1 && specSeparator != null) {
                    if (!wallThicknessMatches(priceRow, invRow, specSeparator)) {
                        continue;
                    }
                }
                invRow.setPrice(priceRow.getPrice());
                matched = true;
            }
        }

        return matched;
    }

    /**
     * 壁厚匹配：提取价格行和库存行的壁厚，比较是否相等。
     * 价格行壁厚可以是区间表达式，库存行壁厚为具体值——只要库存值落在价格区间内即视为匹配。
     */
    private static boolean wallThicknessMatches(ParsedRowDto priceRow, ParsedRowDto invRow,
                                                 String specSeparator) {
        BigDecimal priceWt = SpecParseUtil.extractWallThickness(priceRow.getSpec(), specSeparator);
        BigDecimal invWt = SpecParseUtil.extractWallThickness(invRow.getSpec(), specSeparator);

        if (priceWt == null && invWt == null) {
            return true;
        }
        if (priceWt == null || invWt == null) {
            return false;
        }
        return priceWt.compareTo(invWt) == 0;
    }

    /**
     * 根据区间匹配策略筛选库存候选行。
     *
     * @param candidates  基础规格匹配到的库存行
     * @param priceRange  价格行的区间
     * @param strategy    匹配策略
     * @param config      区间配置
     * @param separator   壁厚分隔符
     * @return 筛选后的库存行
     */
    private static List<ParsedRowDto> filterByRangeStrategy(List<ParsedRowDto> candidates,
                                                             RangeUtil.Range priceRange,
                                                             String strategy,
                                                             SpecRangeConfig config,
                                                             String separator) {
        if ("BASE_SPEC_ONLY".equals(strategy)) {
            // 仅按基础规格匹配，不做区间筛选
            return candidates;
        }

        List<ParsedRowDto> result = new ArrayList<>();
        for (ParsedRowDto inv : candidates) {
            // 尝试解析库存行的区间信息
            SpecRangeParser.SpecWithRange invSpecRange = SpecRangeParser.parse(inv.getSpec(), config);

            if (invSpecRange.getRange() != null) {
                // 库存行本身也有区间后缀
                boolean match = switch (strategy) {
                    case "PRICE_CONTAINS_INVENTORY" -> RangeUtil.contains(priceRange, invSpecRange.getRange());
                    case "RANGE_OVERLAP" -> RangeUtil.overlaps(priceRange, invSpecRange.getRange());
                    case "INVENTORY_MIN_IN_PRICE" ->
                            priceRange.getMin() <= invSpecRange.getRange().getMin()
                                    && invSpecRange.getRange().getMin() <= priceRange.getMax();
                    default -> RangeUtil.contains(priceRange, invSpecRange.getRange());
                };
                if (match) {
                    result.add(inv);
                }
            } else {
                // 库存行无区间后缀，尝试从壁厚中提取数值做点判断
                BigDecimal wt = separator != null ? SpecParseUtil.extractWallThickness(inv.getSpec(), separator) : null;
                if (wt != null) {
                    double val = wt.doubleValue();
                    // 对于单点值，检查其是否落在价格区间内
                    RangeUtil.Range pointRange = new RangeUtil.Range(val, val);
                    boolean match = switch (strategy) {
                        case "PRICE_CONTAINS_INVENTORY" -> RangeUtil.contains(priceRange, pointRange);
                        case "RANGE_OVERLAP" -> RangeUtil.overlaps(priceRange, pointRange);
                        case "INVENTORY_MIN_IN_PRICE" ->
                                priceRange.getMin() <= val && val <= priceRange.getMax();
                        default -> RangeUtil.contains(priceRange, pointRange);
                    };
                    if (match) {
                        result.add(inv);
                    }
                } else {
                    // 无法提取数值信息，按基础规格已匹配视为命中
                    result.add(inv);
                }
            }
        }
        return result;
    }

    /**
     * 构建匹配键：拼接指定字段值，以 "|" 分隔。
     */
    private static String buildMatchKey(ParsedRowDto row, List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(nullToEmpty(getFieldValue(row, fields.get(i))));
        }
        return sb.toString();
    }

    /**
     * 构建匹配键，但将 spec 字段替换为指定的值。
     */
    private static String buildMatchKeyWithSpec(ParsedRowDto row, List<String> fields, String specOverride) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            if ("spec".equals(fields.get(i))) {
                sb.append(nullToEmpty(specOverride));
            } else {
                sb.append(nullToEmpty(getFieldValue(row, fields.get(i))));
            }
        }
        return sb.toString();
    }

    /**
     * 构建库存索引。若启用了规格区间模式，使用 baseSpec 作为索引键。
     */
    private static Map<String, List<ParsedRowDto>> buildIndex(List<ParsedRowDto> rows,
                                                                List<String> fields,
                                                                int specRangeMatchMode,
                                                                SpecRangeConfig config) {
        Map<String, List<ParsedRowDto>> index = new HashMap<>();
        for (ParsedRowDto row : rows) {
            String key;
            if (specRangeMatchMode == 1 && config != null && fields.contains("spec")) {
                // 用 baseSpec 建索引，使价格行可按 baseSpec 查找
                SpecRangeParser.SpecWithRange parsed = SpecRangeParser.parse(row.getSpec(), config);
                key = buildMatchKeyWithSpec(row, fields, parsed.getBaseSpec());
            } else {
                key = buildMatchKey(row, fields);
            }
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return index;
    }

    /**
     * 按字段代码获取行中对应的值。
     */
    private static String getFieldValue(ParsedRowDto row, String fieldCode) {
        if (row == null || fieldCode == null) {
            return null;
        }
        return switch (fieldCode.trim()) {
            case "category" -> row.getCategory();
            case "spec" -> row.getSpec();
            case "origin" -> row.getOrigin();
            case "material" -> row.getMaterial();
            case "remark" -> row.getRemark();
            case "wall_thickness" -> row.getWallThickness();
            case "group_name" -> row.getGroupName();
            default -> null;
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}

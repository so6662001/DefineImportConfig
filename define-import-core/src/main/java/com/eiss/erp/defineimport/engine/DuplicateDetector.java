package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存数据重复性检测器 (v1.8)
 * <p>
 * 唯一键: 品类+规格+产地+材质+备注
 * 利用 {@link ParsedRowDto#buildUniqueKey()} 生成键值，
 * 首次出现记录为基准行，后续相同键视为重复。
 * </p>
 */
public class DuplicateDetector {

    private final Map<String, DuplicateEntry> seen = new LinkedHashMap<>();

    /**
     * 检查行是否与已处理的行重复。
     *
     * @param row 当前解析行
     * @return 重复时返回错误对象，首次出现返回 null
     */
    public ExcelImportError check(ParsedRowDto row) {
        if (row == null) {
            return null;
        }
        String key = row.buildUniqueKey();
        DuplicateEntry existing = seen.get(key);
        if (existing != null) {
            existing.count++;
            return ExcelImportError.builder()
                    .sheetName(row.getSheetName())
                    .rowIndex(row.getRowIndex())
                    .fieldName("唯一键(品类+规格+产地+材质+备注)")
                    .errorMsg(String.format("数据重复: 与%s第%d行重复(品类=%s, 规格=%s, 产地=%s, 材质=%s, 备注=%s)",
                            existing.sheetName, existing.rowIndex,
                            nullToEmpty(row.getCategory()), nullToEmpty(row.getSpec()),
                            nullToEmpty(row.getOrigin()), nullToEmpty(row.getMaterial()),
                            nullToEmpty(row.getRemark())))
                    .rawValue(key)
                    .errorLevel("ERROR")
                    .errorType("DUPLICATE")
                    .duplicateOfRow(existing.rowIndex)
                    .duplicateOfSheet(existing.sheetName)
                    .build();
        }
        seen.put(key, new DuplicateEntry(row.getRowIndex(), row.getSheetName(), 1));
        return null;
    }

    /**
     * 获取当前已检测到的重复行总数（不含首次出现的那一行）。
     */
    public int getDuplicateCount() {
        return (int) seen.values().stream()
                .mapToInt(e -> e.count - 1)
                .filter(c -> c > 0)
                .sum();
    }

    /**
     * 重置检测器状态，清除所有已记录的行。
     */
    public void reset() {
        seen.clear();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static class DuplicateEntry {
        int rowIndex;
        String sheetName;
        int count;

        DuplicateEntry(int rowIndex, String sheetName, int count) {
            this.rowIndex = rowIndex;
            this.sheetName = sheetName;
            this.count = count;
        }
    }
}

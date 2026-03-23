package com.eiss.erp.defineimport.engine;

import com.alibaba.excel.metadata.CellExtra;

import java.util.HashMap;
import java.util.Map;

/**
 * 合并单元格收集器
 * <p>
 * 在EasyExcel第一遍读取时，通过 {@code extra(CellExtra)} 回调收集所有MERGE类型的
 * CellExtra 信息，构建快速查找结构。
 * </p>
 * <p>
 * 核心思路：对于每个合并区域，将区域内所有单元格坐标映射到首单元格坐标，
 * 从而在后续数据读取时，任意被合并单元格都可以取到首单元格的值。
 * </p>
 * <p>
 * Key 格式: "rowIndex_colIndex"
 * </p>
 */
public class MergeCellCollector {

    /**
     * 合并区域映射：被合并单元格坐标 → 首单元格坐标
     */
    private final Map<String, String> mergeRegionMap = new HashMap<>();

    /**
     * 第一遍读取时记录的单元格值（用于查询首单元格的值）
     */
    private final Map<String, String> cellValueMap = new HashMap<>();

    /**
     * 记录一个合并区域。EasyExcel 的 CellExtra(MERGE) 携带了
     * firstRowIndex/lastRowIndex/firstColumnIndex/lastColumnIndex。
     * 将区域内每个单元格坐标都映射到首单元格。
     *
     * @param cellExtra EasyExcel 的合并单元格信息
     */
    public void addMergeRegion(CellExtra cellExtra) {
        if (cellExtra == null) {
            return;
        }
        int firstRow = cellExtra.getFirstRowIndex();
        int lastRow = cellExtra.getLastRowIndex();
        int firstCol = cellExtra.getFirstColumnIndex();
        int lastCol = cellExtra.getLastColumnIndex();

        String firstCellKey = buildKey(firstRow, firstCol);

        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                String key = buildKey(row, col);
                mergeRegionMap.put(key, firstCellKey);
            }
        }
    }

    /**
     * 记录单元格值（在第一遍读取时调用）
     */
    public void addCellValue(int row, int col, String value) {
        cellValueMap.put(buildKey(row, col), value);
    }

    /**
     * 获取合并单元格的值。
     * <p>
     * 若该坐标在某个合并区域内，返回该区域首单元格对应的值；
     * 否则返回 null。
     * </p>
     */
    public String getMergedValue(int row, int col) {
        String key = buildKey(row, col);
        String firstCellKey = mergeRegionMap.get(key);
        if (firstCellKey != null) {
            return cellValueMap.getOrDefault(firstCellKey, null);
        }
        return null;
    }

    /**
     * 判断指定坐标是否在合并区域内
     */
    public boolean isMergedCell(int row, int col) {
        return mergeRegionMap.containsKey(buildKey(row, col));
    }

    /**
     * 构建坐标键: "row_col"
     */
    public static String buildKey(int row, int col) {
        return row + "_" + col;
    }

    public Map<String, String> getCellValueMap() {
        return cellValueMap;
    }

    public Map<String, String> getMergeRegionMap() {
        return mergeRegionMap;
    }
}

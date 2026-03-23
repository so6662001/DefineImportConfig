package com.eiss.erp.defineimport.util;

/**
 * Excel单元格引用工具类
 * "B3" ↔ (row=2, col=1) 互转
 */
public final class CellRefUtil {

    private CellRefUtil() {
    }

    /** "B3" → row index (0-based), returns 2 */
    public static int getRowIndex(String cellRef) {
        String rowStr = cellRef.replaceAll("[A-Za-z]", "");
        return Integer.parseInt(rowStr) - 1;
    }

    /** "B3" → col index (0-based), returns 1 */
    public static int getColIndex(String cellRef) {
        String colStr = cellRef.replaceAll("[0-9]", "").toUpperCase();
        int col = 0;
        for (int i = 0; i < colStr.length(); i++) {
            col = col * 26 + (colStr.charAt(i) - 'A' + 1);
        }
        return col - 1;
    }

    /** (row=2, col=1) → "B3" */
    public static String toRef(int row, int col) {
        StringBuilder sb = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            c--;
            sb.insert(0, (char) ('A' + c % 26));
            c /= 26;
        }
        sb.append(row + 1);
        return sb.toString();
    }
}

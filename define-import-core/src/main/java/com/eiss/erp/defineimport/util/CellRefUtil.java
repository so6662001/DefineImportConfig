package com.eiss.erp.defineimport.util;

import java.util.regex.Pattern;

/**
 * Excel单元格引用工具类
 * "B3" ↔ (row=2, col=1) 互转
 */
public final class CellRefUtil {

    /** Letters (max 3 for column) + row number starting with 1–9, optional trailing digits. */
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^[A-Za-z]{1,3}[1-9][0-9]*$");

    private CellRefUtil() {
    }

    /** "B3" → row index (0-based), returns 2; invalid input returns -1 */
    public static int getRowIndex(String cellRef) {
        if (cellRef == null || cellRef.isBlank()) {
            return -1;
        }
        String trimmed = cellRef.trim();
        if (!CELL_REF_PATTERN.matcher(trimmed).matches()) {
            return -1;
        }
        String rowStr = trimmed.replaceAll("[A-Za-z]", "");
        try {
            int row = Integer.parseInt(rowStr);
            return row - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "B3" → col index (0-based), returns 1; invalid input returns -1 */
    public static int getColIndex(String cellRef) {
        if (cellRef == null || cellRef.isBlank()) {
            return -1;
        }
        String trimmed = cellRef.trim();
        if (!CELL_REF_PATTERN.matcher(trimmed).matches()) {
            return -1;
        }
        String colStr = trimmed.replaceAll("[0-9]", "").toUpperCase();
        if (colStr.length() > 3) {
            return -1;
        }
        int col = 0;
        for (int i = 0; i < colStr.length(); i++) {
            col = col * 26 + (colStr.charAt(i) - 'A' + 1);
        }
        return col - 1;
    }

    /** (row=2, col=1) → "B3" */
    public static String toRef(int row, int col) {
        if (row < 0 || col < 0) {
            throw new IllegalArgumentException("row and col must be non-negative, got row=" + row + ", col=" + col);
        }
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

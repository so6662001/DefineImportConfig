package com.eiss.erp.defineimport.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellRefUtilTest {

    @Test
    void shouldConvertA1ToRowCol() {
        assertThat(CellRefUtil.getRowIndex("A1")).isEqualTo(0);
        assertThat(CellRefUtil.getColIndex("A1")).isEqualTo(0);
    }

    @Test
    void shouldConvertB3ToRowCol() {
        assertThat(CellRefUtil.getRowIndex("B3")).isEqualTo(2);
        assertThat(CellRefUtil.getColIndex("B3")).isEqualTo(1);
    }

    @Test
    void shouldConvertAA1ToRowCol() {
        assertThat(CellRefUtil.getRowIndex("AA1")).isEqualTo(0);
        assertThat(CellRefUtil.getColIndex("AA1")).isEqualTo(26);
    }

    @Test
    void shouldConvertRowColToRef() {
        assertThat(CellRefUtil.toRef(0, 0)).isEqualTo("A1");
        assertThat(CellRefUtil.toRef(2, 1)).isEqualTo("B3");
        assertThat(CellRefUtil.toRef(0, 26)).isEqualTo("AA1");
    }

    @Test
    void shouldRoundTripConversion() {
        String ref = "C5";
        int row = CellRefUtil.getRowIndex(ref);
        int col = CellRefUtil.getColIndex(ref);
        assertThat(CellRefUtil.toRef(row, col)).isEqualTo(ref);
    }

    @Test
    void shouldHandleLargeColumnIndex() {
        assertThat(CellRefUtil.getColIndex("AZ1")).isEqualTo(51);
        assertThat(CellRefUtil.toRef(0, 51)).isEqualTo("AZ1");
    }

    @Test
    void shouldHandleLowercaseInput() {
        assertThat(CellRefUtil.getColIndex("aa1")).isEqualTo(26);
        assertThat(CellRefUtil.getRowIndex("aa10")).isEqualTo(9);
    }

    @Test
    void shouldReturnNegativeOneForInvalidCellRef() {
        assertThat(CellRefUtil.getRowIndex(null)).isEqualTo(-1);
        assertThat(CellRefUtil.getColIndex("")).isEqualTo(-1);
        assertThat(CellRefUtil.getRowIndex("invalid")).isEqualTo(-1);
        assertThat(CellRefUtil.getColIndex("A0")).isEqualTo(-1);
    }

    @Test
    void toRefShouldRejectNegativeIndices() {
        assertThatThrownBy(() -> CellRefUtil.toRef(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> CellRefUtil.toRef(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

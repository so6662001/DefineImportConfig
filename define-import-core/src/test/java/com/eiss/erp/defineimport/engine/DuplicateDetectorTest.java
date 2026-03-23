package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateDetectorTest {

    private DuplicateDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DuplicateDetector();
    }

    private ParsedRowDto row(int rowIndex, String category, String spec, String origin, String material, String remark) {
        ParsedRowDto r = new ParsedRowDto();
        r.setRowIndex(rowIndex);
        r.setSheetName("Sheet1");
        r.setCategory(category);
        r.setSpec(spec);
        r.setOrigin(origin);
        r.setMaterial(material);
        r.setRemark(remark);
        return r;
    }

    @Test
    void shouldDetectDuplicate() {
        ParsedRowDto first = row(1, "槽钢", "10#", "唐山", "Q235B", null);
        ParsedRowDto dup = row(3, "槽钢", "10#", "唐山", "Q235B", null);

        assertThat(detector.check(first)).isNull();
        ExcelImportError error = detector.check(dup);
        assertThat(error).isNotNull();
        assertThat(error.getErrorType()).isEqualTo("DUPLICATE");
        assertThat(error.getDuplicateOfRow()).isEqualTo(1);
        assertThat(error.getRowIndex()).isEqualTo(3);
    }

    @Test
    void shouldDistinguishByRemark() {
        ParsedRowDto a = row(1, "槽钢", "10#", "唐山", "Q235B", "冷水");
        ParsedRowDto b = row(2, "槽钢", "10#", "唐山", "Q235B", "热水");

        assertThat(detector.check(a)).isNull();
        assertThat(detector.check(b)).isNull();
    }

    @Test
    void shouldHandleNullFields() {
        ParsedRowDto a = row(1, null, null, null, null, null);
        ParsedRowDto b = row(2, null, null, null, null, null);

        assertThat(detector.check(a)).isNull();
        assertThat(detector.check(b)).isNotNull();
    }

    @Test
    void shouldCountDuplicates() {
        detector.check(row(1, "A", "B", "C", "D", null));
        detector.check(row(2, "A", "B", "C", "D", null));
        detector.check(row(3, "A", "B", "C", "D", null));
        detector.check(row(4, "X", "Y", "Z", "W", null));

        assertThat(detector.getDuplicateCount()).isEqualTo(2);
    }

    @Test
    void shouldResetState() {
        detector.check(row(1, "A", "B", "C", "D", null));
        detector.reset();
        assertThat(detector.check(row(2, "A", "B", "C", "D", null))).isNull();
        assertThat(detector.getDuplicateCount()).isEqualTo(0);
    }

    @Test
    void shouldReturnNullForNullRow() {
        assertThat(detector.check(null)).isNull();
    }
}

package com.eiss.erp.defineimport.util;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafeConvertUtilTest {

    @Test
    void shouldExtractNumberFromMixedText() {
        assertThat(SafeConvertUtil.extractNumber("127支/件")).isEqualTo("127");
        assertThat(SafeConvertUtil.extractNumber("约5吨")).isEqualTo("5");
        assertThat(SafeConvertUtil.extractNumber("重量3.5kg")).isEqualTo("3.5");
    }

    @Test
    void shouldReturnNullForNonNumericText() {
        assertThat(SafeConvertUtil.extractNumber(null)).isNull();
        assertThat(SafeConvertUtil.extractNumber("无数字")).isNull();
        assertThat(SafeConvertUtil.extractNumber("")).isNull();
    }

    @Test
    void shouldExtractNegativeNumber() {
        assertThat(SafeConvertUtil.extractNumber("-3.14")).isEqualTo("-3.14");
        assertThat(SafeConvertUtil.extractNumber("温度-5度")).isEqualTo("-5");
    }

    @Test
    void shouldConvertToBigDecimal() {
        List<ExcelImportError> errors = new ArrayList<>();
        BigDecimal result = SafeConvertUtil.toBigDecimal("4200.50元", "price", 1, "Sheet1", errors);
        assertThat(result).isEqualByComparingTo("4200.50");
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRecordErrorForNonNumericBigDecimal() {
        List<ExcelImportError> errors = new ArrayList<>();
        BigDecimal result = SafeConvertUtil.toBigDecimal("无价格", "price", 1, "Sheet1", errors);
        assertThat(result).isNull();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getErrorType()).isEqualTo("NUMERIC");
    }

    @Test
    void shouldConvertToInteger() {
        List<ExcelImportError> errors = new ArrayList<>();
        Integer result = SafeConvertUtil.toInteger("127支/件", "package_num", 1, "Sheet1", errors);
        assertThat(result).isEqualTo(127);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRoundDecimalToInteger() {
        List<ExcelImportError> errors = new ArrayList<>();
        Integer result = SafeConvertUtil.toInteger("3.5", "count", 1, "Sheet1", errors);
        assertThat(result).isEqualTo(4);
    }

    @Test
    void shouldReturnNullForBlankInput() {
        List<ExcelImportError> errors = new ArrayList<>();
        assertThat(SafeConvertUtil.toBigDecimal("", "f", 1, "S", errors)).isNull();
        assertThat(SafeConvertUtil.toBigDecimal(null, "f", 1, "S", errors)).isNull();
        assertThat(SafeConvertUtil.toInteger("  ", "f", 1, "S", errors)).isNull();
        assertThat(errors).isEmpty();
    }
}

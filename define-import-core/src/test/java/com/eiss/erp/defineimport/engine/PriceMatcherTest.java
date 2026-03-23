package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceMatcherTest {

    private ParsedRowDto invRow(String category, String spec, String origin) {
        ParsedRowDto row = new ParsedRowDto();
        row.setRowIndex(1);
        row.setSheetName("库存Sheet");
        row.setCategory(category);
        row.setSpec(spec);
        row.setOrigin(origin);
        return row;
    }

    private ParsedRowDto priceRow(String category, String spec, String origin, BigDecimal price) {
        ParsedRowDto row = new ParsedRowDto();
        row.setRowIndex(1);
        row.setSheetName("价格Sheet");
        row.setCategory(category);
        row.setSpec(spec);
        row.setOrigin(origin);
        row.setPrice(price);
        return row;
    }

    @Test
    void shouldMatchExactly() {
        List<ParsedRowDto> inventory = new ArrayList<>();
        inventory.add(invRow("槽钢", "10#", "唐山"));
        inventory.add(invRow("槽钢", "12#", "唐山"));

        List<ParsedRowDto> prices = new ArrayList<>();
        prices.add(priceRow("槽钢", "10#", "唐山", new BigDecimal("4200")));

        List<String> matchFields = List.of("category", "spec", "origin");

        List<ExcelImportError> errors = PriceMatcher.match(
                inventory, prices, matchFields, 0, 0, null, null);

        assertThat(errors).isEmpty();
        assertThat(inventory.get(0).getPrice()).isEqualByComparingTo("4200");
        assertThat(inventory.get(1).getPrice()).isNull();
    }

    @Test
    void shouldReportUnmatchedPrice() {
        List<ParsedRowDto> inventory = new ArrayList<>();
        inventory.add(invRow("槽钢", "10#", "唐山"));

        List<ParsedRowDto> prices = new ArrayList<>();
        prices.add(priceRow("角钢", "10#", "唐山", new BigDecimal("3800")));

        List<ExcelImportError> errors = PriceMatcher.match(
                inventory, prices, List.of("category", "spec", "origin"),
                0, 0, null, null);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getErrorType()).isEqualTo("MATCH_FAIL");
    }

    @Test
    void shouldReturnEmptyErrorsForNullInputs() {
        List<ExcelImportError> errors = PriceMatcher.match(null, null, null, 0, 0, null, null);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldMatchMultipleInventoryRows() {
        List<ParsedRowDto> inventory = new ArrayList<>();
        inventory.add(invRow("槽钢", "10#", "唐山"));
        inventory.add(invRow("槽钢", "10#", "唐山"));

        List<ParsedRowDto> prices = new ArrayList<>();
        prices.add(priceRow("槽钢", "10#", "唐山", new BigDecimal("4500")));

        List<ExcelImportError> errors = PriceMatcher.match(
                inventory, prices, List.of("category", "spec", "origin"),
                0, 0, null, null);

        assertThat(errors).isEmpty();
        assertThat(inventory.get(0).getPrice()).isEqualByComparingTo("4500");
        assertThat(inventory.get(1).getPrice()).isEqualByComparingTo("4500");
    }
}

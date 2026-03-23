package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.util.RangeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecRangeParserTest {

    private SpecRangeConfig config;

    @BeforeEach
    void setUp() {
        config = new SpecRangeConfig();
        config.setRangeSeparators(List.of("-", "~"));
        config.setInfinityKeywords(List.of("以上", "∞", "+"));
        config.setZeroKeywords(List.of("以下", "以内"));
    }

    @Test
    void shouldParseRangeWithDash() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("5#（10-15）", config);
        assertThat(result.getBaseSpec()).isEqualTo("5#");
        assertThat(result.getRange()).isNotNull();
        assertThat(result.getRange().getMin()).isEqualTo(10.0);
        assertThat(result.getRange().getMax()).isEqualTo(15.0);
    }

    @Test
    void shouldParseInfinityKeyword() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("5#(10-以上)", config);
        assertThat(result.getBaseSpec()).isEqualTo("5#");
        assertThat(result.getRange()).isNotNull();
        assertThat(result.getRange().getMin()).isEqualTo(10.0);
        assertThat(result.getRange().getMax()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void shouldParsePlusSuffix() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("10#(10-∞)", config);
        assertThat(result.getBaseSpec()).isEqualTo("10#");
        assertThat(result.getRange()).isNotNull();
        assertThat(result.getRange().getMin()).isEqualTo(10.0);
        assertThat(result.getRange().getMax()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void shouldParseZeroKeyword() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("8#(以下-20)", config);
        assertThat(result.getBaseSpec()).isEqualTo("8#");
        assertThat(result.getRange()).isNotNull();
        assertThat(result.getRange().getMin()).isEqualTo(0.0);
        assertThat(result.getRange().getMax()).isEqualTo(20.0);
    }

    @Test
    void shouldReturnNullRangeForNoSuffix() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("20*3.0", config);
        assertThat(result.getBaseSpec()).isEqualTo("20*3.0");
        assertThat(result.getRange()).isNull();
    }

    @Test
    void shouldHandleNullInput() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse(null, config);
        assertThat(result.getBaseSpec()).isNull();
        assertThat(result.getRange()).isNull();
    }

    @Test
    void shouldHandleEmptyInput() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("", config);
        assertThat(result.getBaseSpec()).isEqualTo("");
        assertThat(result.getRange()).isNull();
    }

    @Test
    void shouldParseWithFullwidthParens() {
        SpecRangeParser.SpecWithRange result = SpecRangeParser.parse("5#（10-15）", config);
        assertThat(result.getBaseSpec()).isEqualTo("5#");
        assertThat(result.getRange().getMin()).isEqualTo(10.0);
        assertThat(result.getRange().getMax()).isEqualTo(15.0);
    }
}

package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CharTransformerTest {

    private CharTransformConfig.CharRule rule(String matchType, String matchPattern, String replaceValue, int sortOrder) {
        CharTransformConfig.CharRule r = new CharTransformConfig.CharRule();
        r.setMatchType(matchType);
        r.setMatchPattern(matchPattern);
        r.setReplaceValue(replaceValue);
        r.setSortOrder(sortOrder);
        return r;
    }

    @Test
    void shouldApplyLiteralReplacement() {
        List<CharTransformConfig.CharRule> pipeline = List.of(
                rule("LITERAL", "×", "*", 1),
                rule("LITERAL", "Φ", "", 2)
        );
        assertThat(CharTransformer.execute("Φ219×6", pipeline)).isEqualTo("219*6");
    }

    @Test
    void shouldApplyRegexReplacement() {
        List<CharTransformConfig.CharRule> pipeline = List.of(
                rule("REGEX", "(?<=\\d)x(?=\\d)", "*", 1)
        );
        assertThat(CharTransformer.execute("20x3.5", pipeline)).isEqualTo("20*3.5");
    }

    @Test
    void shouldApplyFullwidthConversion() {
        List<CharTransformConfig.CharRule> pipeline = List.of(
                rule("FULLWIDTH", null, null, 1)
        );
        assertThat(CharTransformer.execute("ＡＢＣ１２３", pipeline)).isEqualTo("ABC123");
        assertThat(CharTransformer.execute("（规格）", pipeline)).isEqualTo("(规格)");
    }

    @Test
    void shouldReturnOriginalForNullOrEmpty() {
        assertThat(CharTransformer.execute(null, List.of())).isNull();
        assertThat(CharTransformer.execute("", List.of())).isEqualTo("");
        assertThat(CharTransformer.execute("abc", null)).isEqualTo("abc");
    }

    @Test
    void shouldBuildPipelineWithSorting() {
        CharTransformConfig.CharRule preset1 = rule("LITERAL", "a", "b", 10);
        CharTransformConfig.CharRule template1 = rule("LITERAL", "c", "d", 5);
        CharTransformConfig.CharRule field1 = rule("LITERAL", "e", "f", 1);

        CharTransformConfig fieldConfig = new CharTransformConfig();
        fieldConfig.setFieldRules(List.of(field1));

        List<CharTransformConfig.CharRule> pipeline = CharTransformer.buildPipeline(
                List.of(preset1), List.of(template1), fieldConfig);

        assertThat(pipeline).hasSize(3);
        assertThat(pipeline.get(0).getSortOrder()).isEqualTo(1);
        assertThat(pipeline.get(1).getSortOrder()).isEqualTo(5);
        assertThat(pipeline.get(2).getSortOrder()).isEqualTo(10);
    }

    @Test
    void shouldBuildPipelineWithExclusions() {
        CharTransformConfig.CharRule preset = rule("LITERAL", "x", "y", 1);
        preset.setRuleCode("RULE_TO_EXCLUDE");

        CharTransformConfig fieldConfig = new CharTransformConfig();
        fieldConfig.setExcludePresetCodes(List.of("RULE_TO_EXCLUDE"));

        List<CharTransformConfig.CharRule> pipeline = CharTransformer.buildPipeline(
                List.of(preset), null, fieldConfig);

        assertThat(pipeline).isEmpty();
    }

    @Test
    void shouldHandleCharClassRule() {
        List<CharTransformConfig.CharRule> pipeline = List.of(
                rule("CHARCLASS", "[，。、]", ",", 1)
        );
        assertThat(CharTransformer.execute("A，B。C、D", pipeline)).isEqualTo("A,B,C,D");
    }
}

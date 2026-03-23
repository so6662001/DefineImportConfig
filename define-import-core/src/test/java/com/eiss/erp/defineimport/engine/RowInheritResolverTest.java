package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.RowInheritConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowInheritResolverTest {

    private RowInheritResolver resolver;
    private RowInheritConfig config;

    @BeforeEach
    void setUp() {
        resolver = new RowInheritResolver();
        config = new RowInheritConfig();
        config.setEnabled(true);
        config.setSeparator("*");
        config.setPartialPattern("^\\d+(\\.\\d+)?$");
        config.setAssembleTemplate("${prefix}*${current}");
    }

    @Test
    void shouldInheritFullSpecThenPartialSpec() {
        assertThat(resolver.resolve("spec", "20*2.0", config)).isEqualTo("20*2.0");
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("20*2.5");
        assertThat(resolver.resolve("spec", "1.8", config)).isEqualTo("20*1.8");
    }

    @Test
    void shouldUpdatePrefixOnNewFullSpec() {
        resolver.resolve("spec", "20*2.0", config);
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("20*2.5");

        assertThat(resolver.resolve("spec", "25*3.0", config)).isEqualTo("25*3.0");
        assertThat(resolver.resolve("spec", "2.0", config)).isEqualTo("25*2.0");
    }

    @Test
    void shouldReturnOriginalWhenPartialButNoPrefix() {
        assertThat(resolver.resolve("spec", "3.5", config)).isEqualTo("3.5");
    }

    @Test
    void shouldReturnOriginalWhenDisabled() {
        config.setEnabled(false);
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("2.5");
    }

    @Test
    void shouldReturnOriginalWhenConfigNull() {
        assertThat(resolver.resolve("spec", "2.5", null)).isEqualTo("2.5");
    }

    @Test
    void shouldResetPrefixCache() {
        resolver.resolve("spec", "20*2.0", config);
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("20*2.5");

        resolver.reset();
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("2.5");
    }

    @Test
    void shouldUseSeparatorFallbackWhenNoTemplate() {
        config.setAssembleTemplate(null);
        resolver.resolve("spec", "20*2.0", config);
        assertThat(resolver.resolve("spec", "2.5", config)).isEqualTo("20*2.5");
    }
}

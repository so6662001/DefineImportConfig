package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.ColumnSourceConfig;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderMatcherTest {

    @Test
    void shouldMatchByAlias() {
        Map<Integer, String> headMap = Map.of(0, "品类", 1, "规格", 2, "产地", 3, "重量");
        Map<String, ColumnSourceConfig> fieldConfigs = new LinkedHashMap<>();

        ColumnSourceConfig specConfig = new ColumnSourceConfig();
        specConfig.setMatchMode("ALIAS");
        specConfig.setHeaderAliases(List.of("规格", "型号"));
        fieldConfigs.put("spec", specConfig);

        ColumnSourceConfig categoryConfig = new ColumnSourceConfig();
        categoryConfig.setMatchMode("ALIAS");
        categoryConfig.setHeaderName("品类");
        fieldConfigs.put("category", categoryConfig);

        Map<String, Integer> result = HeaderMatcher.match(headMap, fieldConfigs);

        assertThat(result).containsEntry("spec", 1);
        assertThat(result).containsEntry("category", 0);
    }

    @Test
    void shouldMatchByIndex() {
        Map<Integer, String> headMap = Map.of(0, "A", 1, "B", 2, "C");
        Map<String, ColumnSourceConfig> fieldConfigs = new LinkedHashMap<>();

        ColumnSourceConfig config = new ColumnSourceConfig();
        config.setMatchMode("INDEX");
        config.setColumnIndex(2);
        fieldConfigs.put("field_c", config);

        Map<String, Integer> result = HeaderMatcher.match(headMap, fieldConfigs);
        assertThat(result).containsEntry("field_c", 2);
    }

    @Test
    void shouldNormalizeFullwidthChars() {
        assertThat(HeaderMatcher.normalize("　Ａ　")).isEqualTo(" A ");
        assertThat(HeaderMatcher.normalize("品类")).isEqualTo("品类");
        assertThat(HeaderMatcher.normalize("ＡＢＣ")).isEqualTo("ABC");
        assertThat(HeaderMatcher.normalize("（规格）")).isEqualTo("(规格)");
    }

    @Test
    void shouldMatchAliasWithFullwidthHeader() {
        Map<Integer, String> headMap = Map.of(0, "品类", 1, "规格（型号）");
        Map<String, ColumnSourceConfig> fieldConfigs = new LinkedHashMap<>();

        ColumnSourceConfig config = new ColumnSourceConfig();
        config.setMatchMode("ALIAS");
        config.setHeaderAliases(List.of("规格(型号)"));
        fieldConfigs.put("spec", config);

        Map<String, Integer> result = HeaderMatcher.match(headMap, fieldConfigs);
        assertThat(result).containsEntry("spec", 1);
    }

    @Test
    void shouldReturnEmptyForNullInputs() {
        assertThat(HeaderMatcher.match(null, null)).isEmpty();
        assertThat(HeaderMatcher.match(Map.of(), null)).isEmpty();
        assertThat(HeaderMatcher.match(null, Map.of())).isEmpty();
    }

    @Test
    void shouldUseIndexFirstMode() {
        Map<Integer, String> headMap = Map.of(0, "品类", 1, "规格", 2, "重量");
        Map<String, ColumnSourceConfig> fieldConfigs = new LinkedHashMap<>();

        ColumnSourceConfig config = new ColumnSourceConfig();
        config.setMatchMode("INDEX_FIRST");
        config.setColumnIndex(1);
        config.setHeaderAliases(List.of("品类"));
        fieldConfigs.put("test", config);

        Map<String, Integer> result = HeaderMatcher.match(headMap, fieldConfigs);
        assertThat(result).containsEntry("test", 1);

        ColumnSourceConfig fallbackConfig = new ColumnSourceConfig();
        fallbackConfig.setMatchMode("INDEX_FIRST");
        fallbackConfig.setColumnIndex(-1);
        fallbackConfig.setHeaderAliases(List.of("重量"));
        fieldConfigs.put("test2", fallbackConfig);

        Map<String, Integer> result2 = HeaderMatcher.match(headMap, fieldConfigs);
        assertThat(result2).containsEntry("test2", 2);
    }
}

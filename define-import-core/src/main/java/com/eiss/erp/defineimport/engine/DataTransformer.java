package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.config.TransformConfig;
import com.eiss.erp.defineimport.util.SafeConvertUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 数据转换器
 * <p>
 * 对从 Excel 提取的原始字段值，按管道顺序执行清洗/转换：
 * <ol>
 *   <li>去空白 (trimWhitespace)</li>
 *   <li>字符转换 (CharTransformer 管道)</li>
 *   <li>数值提取 (extractNumber)：从混合文本中提取数字，如 "127支/件" → "127"</li>
 *   <li>去单位 (removeUnit + unitPatterns)：移除指定单位文本</li>
 *   <li>正则提取 (regexExtract)：用正则捕获组提取目标子串</li>
 *   <li>数值精度：在类型转换阶段由 ParsedRowDto.setFieldValue 处理</li>
 * </ol>
 * </p>
 */
public class DataTransformer {

    private static final Logger log = LoggerFactory.getLogger(DataTransformer.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DataTransformer() {
    }

    /**
     * 对原始值执行配置中定义的转换管道。
     *
     * @param rawValue            原始字符串值
     * @param transformConfigJson 转换配置 JSON（可为 null，表示仅做默认 trim）
     * @param charPipeline        预构建的字符转换规则管道（可为 null）
     * @return 转换后的值
     */
    public static String transform(String rawValue, String transformConfigJson,
                                    List<CharTransformConfig.CharRule> charPipeline) {
        if (rawValue == null) {
            return null;
        }

        TransformConfig config = parseConfig(transformConfigJson);
        String value = rawValue;

        // Step 1: 去空白（默认启用）
        if (config == null || config.getTrimWhitespace() == null || config.getTrimWhitespace()) {
            value = value.trim();
        }

        // Step 2: 字符转换管道
        if (charPipeline != null && !charPipeline.isEmpty()) {
            value = CharTransformer.execute(value, charPipeline);
        }

        // Step 3: 数值提取 — 从混合文本中提取首个数字
        if (config != null && Boolean.TRUE.equals(config.getExtractNumber())) {
            String num = SafeConvertUtil.extractNumber(value);
            if (num != null) {
                value = num;
            }
        }

        // Step 4: 去单位 — 移除配置中列出的单位文本
        if (config != null && Boolean.TRUE.equals(config.getRemoveUnit())
                && config.getUnitPatterns() != null) {
            for (String unit : config.getUnitPatterns()) {
                if (unit != null && !unit.isEmpty()) {
                    value = value.replace(unit, "");
                }
            }
            value = value.trim();
        }

        // Step 5: 正则提取 — 使用第一个捕获组作为结果
        if (config != null && config.getRegexExtract() != null
                && !config.getRegexExtract().isBlank()) {
            value = applyRegexExtract(value, config.getRegexExtract());
        }

        return value;
    }

    /**
     * 应用正则提取：编译正则并用第一个捕获组替换整个值。
     * 若正则无捕获组则返回整个匹配结果；若不匹配则返回原值。
     */
    private static String applyRegexExtract(String value, String regex) {
        try {
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                // 优先取第一个捕获组
                if (matcher.groupCount() >= 1) {
                    String group = matcher.group(1);
                    return group != null ? group : value;
                }
                // 无捕获组时返回整个匹配
                return matcher.group();
            }
        } catch (PatternSyntaxException e) {
            log.warn("regexExtract 正则编译失败: {}, 原因: {}", regex, e.getMessage());
        }
        return value;
    }

    /**
     * 安全解析转换配置 JSON
     */
    private static TransformConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, TransformConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("TransformConfig JSON解析失败: {}", e.getMessage());
            return null;
        }
    }
}

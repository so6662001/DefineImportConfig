package com.eiss.erp.defineimport.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模板字符处理规则
 */
@Data
@TableName("import_template_char_rule")
public class ImportTemplateCharRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private String applyFieldCodes;

    private Long presetRuleId;

    private String matchType;

    private String matchPattern;

    private String replaceValue;

    private String description;

    private Integer sortOrder;

    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

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
 * 导入模板字段值映射
 */
@Data
@TableName("import_template_field_value_mapping")
public class ImportTemplateFieldValueMapping {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long sheetConfigId;

    private Long groupId;

    private String targetField;

    private String sourceValue;

    private String qualifier;

    private String targetValue;

    private Integer sortOrder;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

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
 * 导入模板字段映射
 */
@Data
@TableName("import_template_field")
public class ImportTemplateField {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long sheetConfigId;

    private Long groupId;

    private String fieldCode;

    private String fieldName;

    private String sourceType;

    private String sourceConfig;

    private String transformConfig;

    private Integer required;

    private String defaultValue;

    private Integer sortOrder;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

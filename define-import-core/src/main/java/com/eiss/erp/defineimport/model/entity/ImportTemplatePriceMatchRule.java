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
 * 导入模板价格匹配规则
 */
@Data
@TableName("import_template_price_match_rule")
public class ImportTemplatePriceMatchRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long sheetConfigId;

    private String matchFields;

    private Integer wallThicknessMatchMode;

    private Integer specRangeMatchMode;

    private String specRangeConfig;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

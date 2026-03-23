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
 * 导入模板数据分组
 */
@Data
@TableName("import_template_group")
public class ImportTemplateGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Long sheetConfigId;

    private Integer groupSeq;

    private String groupName;

    private String fixedCategory;

    private String fixedOrigin;

    private String fixedMaterial;

    private String fixedRemark;

    private Integer dataStartRow;

    private Integer dataEndRow;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

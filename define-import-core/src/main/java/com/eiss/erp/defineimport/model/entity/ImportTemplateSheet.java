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
 * 导入模板 Sheet 配置
 */
@Data
@TableName("import_template_sheet")
public class ImportTemplateSheet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private Integer sheetIndex;

    private String sheetName;

    private Integer contentType;

    private Integer headerRowIndex;

    private Integer dataStartRowIndex;

    private Integer dataEndRowIndex;

    private Integer emptyRowThreshold;

    private Integer enableMergeCell;

    private Integer sortOrder;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

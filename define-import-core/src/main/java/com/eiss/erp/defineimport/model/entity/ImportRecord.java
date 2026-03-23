package com.eiss.erp.defineimport.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.LocalDateTime;

/**
 * 导入记录
 */
@Data
@TableName("import_record")
public class ImportRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private String templateCode;

    private Long supplierId;

    private String supplierName;

    private String fileName;

    private String filePath;

    private Integer importType;

    private String batchNo;

    private Long linkedRecordId;

    private Integer totalRows;

    private Integer successRows;

    private Integer errorRows;

    private Integer importStatus;

    private Integer priceWritebackStatus;

    private String priceWritebackSummary;

    @TableField(jdbcType = JdbcType.LONGVARCHAR)
    private String errorDetail;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

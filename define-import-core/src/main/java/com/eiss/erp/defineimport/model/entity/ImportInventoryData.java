package com.eiss.erp.defineimport.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 导入库存数据
 */
@Data
@TableName("import_inventory_data")
public class ImportInventoryData {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long recordId;

    private String batchNo;

    private Long supplierId;

    private String category;

    private String spec;

    private String origin;

    private String material;

    private Integer packageNum;

    private Integer wholeNum;

    private Integer oddNum;

    private BigDecimal weight;

    private BigDecimal price;

    private Integer priceSource;

    private Long priceRecordId;

    private LocalDateTime priceUpdatedAt;

    private String remark;

    private String sourceSheet;

    private Integer sourceRow;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

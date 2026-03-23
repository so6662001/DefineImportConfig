# 钢贸动态 Excel 导入系统 — 详细设计文档

> 版本：v1.5  
> 技术栈：Java 17 + Spring Boot + Alibaba EasyExcel + MyBatis-Plus + MySQL + Vue 3 + Redis + RocketMQ/RabbitMQ + MinIO  
> v1.1 变更：新增「规格特殊符号转换」子系统设计  
> v1.2 变更：① 品类映射泛化为通用字段值映射 ② 行间继承 ③ 数值提取 ④ 多品类价格分组 ⑤ 场景 6-10  
> v1.3 变更：新增「规格附带区间后缀解析与匹配」  
> v1.4 变更：完成 6 项扩展方向的完整设计——智能表头识别、模板自动推荐、模板版本管理、异步导入、数据对账、规则复制  
> v1.5 变更：万人并发导入架构设计——全链路异步化、分布式文件存储、MQ 削峰填谷、Redis 缓存与进度、Worker 弹性伸缩、数据库读写分离与分表  

---

## 一、需求概述

### 1.1 业务背景

在钢贸 ERP 系统中，上游供应商提供的 Excel 库存单/报价单格式差异巨大：

| 差异维度 | 具体表现 |
|---------|---------|
| 表头行位置 | 有时在第 1 行，有时在第 3 行，前几行可能是提示文案 |
| 表尾结束行 | 存在不确定性，下方可能有多行说明文字 |
| 列名不统一 | "包装数量"、"件数"、"支/件"、"包装形式"代表同一业务含义 |
| 合并单元格 | A 列品类多行合并为"槽钢"，按行读取导致下方数据缺失 |
| 列顺序随意 | 不同供应商列顺序完全不同 |
| 多 Sheet 页 | 一个文件包含库存 + 价格，类型不同 |
| 品类灵活 | 品类列值 + 表头/单元格 → 派生新品类名称 |
| 价格列灵活 | 表头是产地/材质名称，表体是价格，一个 Sheet 可有多产地、多材质 |
| 规格特殊符号 | 规格中存在大量特殊符号需要归一化：全角×→半角*、Φ/φ/∅→清除、中文括号→英文括号、异形连字符→标准连字符等 |
| 规格组合 | 规格 = 多列拼接（如规格 + 壁厚），壁厚可能是区间值 |
| 规格行间继承 | 同列下一行只有壁厚值（如"2.5"），需继承上行规格前缀拼接为完整规格（如"20*2.5"） |
| 包装数量混合文本 | 包装形式写为"127支/件"，需提取纯数字 127 |
| 产地/材质灵活 | 产地和材质也存在类似品类的映射规则，如表头限定词派生、固定单元格指定等 |
| 一行多品类价格 | 同一行存在多个品类的价格列（如"焊管"列 + "华岐(镀锌管)"列 + "中天(镀锌管)"列），每列是独立品类+产地的价格 |
| 规格附带区间后缀 | 镀锌型材等品类的规格后附带重量/数量区间，如库存 `5#（10-15）`、价格 `5#(10以上)`，匹配时需拆分基础规格与区间后缀做包含判断 |
| 库存冷热水分组 | 钢塑管等品类中"冷水"和"热水"是两个品类，同一 Sheet 需按区域分组读取 |

### 1.2 核心目标

构建一套 **基于"动态映射规则"的 Excel 导入服务**：

1. **模板设计器**：运营人员上传 Excel 样本，在可视化界面配置读取规则
2. **规则持久化**：规则关联供应商，持久化至数据库
3. **动态导入引擎**：基于规则动态解析任意格式的 Excel，输出统一的预览数据
4. **价格-库存匹配**：价格数据根据规则自动匹配到库存记录

---

## 二、核心概念模型

```
┌──────────────────────────────────────────────────────────────────────┐
│                      ImportTemplate (导入模板)                        │
│  ┌─ template_code, supplier_id, all_sheets_price                    │
│  │                                                                   │
│  ├── SheetConfig (Sheet页配置) ×N                                    │
│  │   ┌─ sheet_index, content_type(库存/价格)                         │
│  │   │  header_row_index, data_start/end_row                        │
│  │   │                                                               │
│  │   ├── DataGroup (数据组) ×M                                       │
│  │   │   ┌─ group_seq, group_name                                   │
│  │   │   │  fixed_category / fixed_origin / fixed_material          │
│  │   │   │                                                           │
│  │   │   ├── FieldMapping (字段映射) ×K                              │
│  │   │   │   ┌─ field_code(品类/规格/产地/...)                       │
│  │   │   │   │  source_type(COLUMN/FIXED_CELL/...)                  │
│  │   │   │   │  source_config(JSON)                                 │
│  │   │   │   │  transform_config(JSON, 含行间继承/数值提取等)         │
│  │   │   │   └─ inherit_config(JSON, 规格行间继承)                   │
│  │   │   │                                                           │
│  │   │   └── FieldValueMapping (字段值映射) ×L    ← v1.2 泛化        │
│  │   │       ┌─ target_field(category/origin/material)              │
│  │   │       │  source_value + qualifier → target_value             │
│  │   │       └─ 如: "方管"+"白材" → "镀锌方管"                       │
│  │   │           "焊管"(表头) → 产地:"焊管厂"                        │
│  │   │                                                               │
│  │   └── PriceMatchRule (价格匹配规则)                                │
│  │       ┌─ match_fields, wall_thickness_range                       │
│  │       └─ 用于价格→库存的关联匹配                                   │
│  └───────────────────────────────────────────────────────────────────│
└──────────────────────────────────────────────────────────────────────┘
```

### 2.1 关键概念说明

#### 数据组 (DataGroup)

同一个 Sheet 页内可能存在 **多组数据**，典型场景：

- **价格表多产地列**：品类列 + 规格列 + 黑材价格列 + 白材价格列。"黑材"和"白材"各生成一组数据，品类分别派生为"方管"和"镀锌方管"
- **同一 Sheet 多品类区域**：上半部分是"槽钢"库存，下半部分是"角钢"库存
- **库存冷热水分组**：钢塑管的"冷水"和"热水"是不同品类，通过分组各自定义品类
- **一行多品类价格**：如 `规格|壁厚|焊管(价格)|华岐(价格)|中天(价格)`，三列分别对应三个品类+产地组合，需定义三个数据组

每个数据组可拥有独立的字段映射规则，也可共享 Sheet 级别的公共映射（`group_id=null` 的字段适用于所有组）。

**数据组的关键属性（v1.2 增强）**：
- 每个组可直接指定 `fixed_category`、`fixed_origin`、`fixed_material`（组级固定值，无需再定义字段映射）
- 组的字段映射优先于组级固定值（字段映射存在时覆盖固定值）

#### 字段数据来源类型 (SourceType)

| 类型 | 说明 | 典型用途 |
|------|------|---------|
| `COLUMN` | 来自某一数据列 | 规格列、重量列等常规数据列 |
| `FIXED_CELL` | 来自固定单元格 | 品类在某个合并单元格(如 A1)中 |
| `FIXED_VALUE` | 人工指定的常量 | 当 Sheet 中无品类列时人为指定 |
| `COMPOSITE` | 多来源组合拼接 | 规格 = 列1 + "*" + 列2 |
| `COLUMN_HEADER` | 列的表头文本即为值 | 表头是产地名称，表体是价格 |

#### 字段值映射 (FieldValueMapping) — v1.2 泛化

> v1.1 中此功能仅支持品类(CategoryMapping)，v1.2 泛化为**通用字段值映射**，支持品类、产地、材质三个字段。

当品类/产地/材质需要根据 **限定词(qualifier)** 派生时使用：

```
目标字段   原始值(列值/表头)    + 限定词(如表头文字)    → 目标值
──────────────────────────────────────────────────────────────
category  "方管"              + "黑材"                → "方管"
category  "方管"              + "白材"                → "镀锌方管"
category  null(无品类列)       + "焊管"(表头)          → "焊管"
category  null                + "华岐"(表头)          → "镀锌管"
origin    null                + "华岐"(表头)          → "华岐"
origin    "津西"              + null                  → "天津津西"
material  null                + "Q235B"(表头)         → "Q235B"
```

#### 规格行间继承 (RowInherit) — v1.2 新增

当规格列的某行仅包含壁厚值（如"2.5"），需要继承上一行的规格前缀，组合为完整规格。

```
Excel 原始值        继承后
──────────────────────────
20*2.0              20*2.0        ← 完整规格, 记录前缀 "20"
2.5                 20*2.5        ← 仅壁厚, 继承上行前缀 "20" + "*" + "2.5"
1.8                 20*1.8        ← 继承前缀
25*3.0              25*3.0        ← 新的完整规格, 更新前缀为 "25"
2.0                 25*2.0        ← 继承 "25"
```

---

## 三、数据库设计

### 3.1 ER 图

```
import_template (1) ──┬──< import_template_sheet (N)
                      │         │
                      │         ├──< import_template_group (M)
                      │         │         │
                      │         │         ├──< import_template_field (K)
                      │         │         │
                      │         │         └──< import_template_field_value_mapping (L)  ← v1.2 泛化(品类+产地+材质)
                      │         │
                      │         └──< import_template_price_match_rule (0..1)
                      │
                      ├──< import_template_char_rule (P)   ← 模板级字符转换规则
                      │
                      └── 关联 supplier(供应商)

import_char_rule_preset (独立)                              ← 系统预置字符转换规则
```

### 3.2 DDL

```sql
-- ============================================================
-- 1. 导入模板主表
-- ============================================================
CREATE TABLE `import_template` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_code`     VARCHAR(64)     NOT NULL                 COMMENT '模板编号(唯一)',
    `template_name`     VARCHAR(128)    NOT NULL                 COMMENT '模板名称',
    `supplier_id`       BIGINT          DEFAULT NULL             COMMENT '关联供应商ID',
    `supplier_name`     VARCHAR(128)    DEFAULT NULL             COMMENT '供应商名称(冗余)',
    `scope`             VARCHAR(256)    DEFAULT NULL             COMMENT '适用范围描述',
    `all_sheets_price`  TINYINT         NOT NULL DEFAULT 0       COMMENT '是否强制所有Sheet为价格类型 0-否 1-是',
    `status`            TINYINT         NOT NULL DEFAULT 1       COMMENT '状态 0-禁用 1-启用',
    `remark`            VARCHAR(512)    DEFAULT NULL             COMMENT '备注',
    `create_by`         VARCHAR(64)     DEFAULT NULL             COMMENT '创建人',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`         VARCHAR(64)     DEFAULT NULL             COMMENT '更新人',
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT         NOT NULL DEFAULT 0       COMMENT '逻辑删除 0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_code` (`template_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入模板主表';


-- ============================================================
-- 2. Sheet 页配置表
-- ============================================================
CREATE TABLE `import_template_sheet` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`           BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_index`           INT             NOT NULL DEFAULT 0       COMMENT 'Sheet页索引(0开始)',
    `sheet_name`            VARCHAR(128)    DEFAULT NULL             COMMENT 'Sheet页名称(仅展示用)',
    `content_type`          TINYINT         NOT NULL DEFAULT 1       COMMENT '内容类型 1-库存 2-价格',
    `header_row_index`      INT             NOT NULL DEFAULT 0       COMMENT '表头所在行索引(0开始)',
    `data_start_row_index`  INT             DEFAULT NULL             COMMENT '数据起始行索引(0开始, null=表头下一行)',
    `data_end_row_index`    INT             DEFAULT NULL             COMMENT '数据结束行索引(0开始, null=自动检测至连续空行)',
    `empty_row_threshold`   INT             NOT NULL DEFAULT 2       COMMENT '连续空行阈值(超过此数认为数据结束)',
    `enable_merge_cell`     TINYINT         NOT NULL DEFAULT 1       COMMENT '是否处理合并单元格 0-否 1-是',
    `sort_order`            INT             NOT NULL DEFAULT 0       COMMENT '排序(影响导入顺序,库存优先)',
    `remark`                VARCHAR(256)    DEFAULT NULL             COMMENT '备注',
    `create_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Sheet页配置表';


-- ============================================================
-- 3. 数据组配置表
-- ============================================================
CREATE TABLE `import_template_group` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`   BIGINT          NOT NULL                 COMMENT 'Sheet配置ID',
    `group_seq`         INT             NOT NULL DEFAULT 1       COMMENT '组序号(同一Sheet内从1递增)',
    `group_name`        VARCHAR(128)    DEFAULT NULL             COMMENT '组名称(便于识别, 如"冷水钢塑管"、"焊管价格")',
    `fixed_category`    VARCHAR(128)    DEFAULT NULL             COMMENT '组级固定品类(如"焊管"、"镀锌管"), 字段映射存在时被覆盖',
    `fixed_origin`      VARCHAR(128)    DEFAULT NULL             COMMENT '组级固定产地(如"华岐"、"中天")',
    `fixed_material`    VARCHAR(128)    DEFAULT NULL             COMMENT '组级固定材质(如"Q235B")',
    `data_start_row`    INT             DEFAULT NULL             COMMENT '组数据起始行(null=使用Sheet级配置)',
    `data_end_row`      INT             DEFAULT NULL             COMMENT '组数据结束行(null=使用Sheet级配置)',
    `remark`            VARCHAR(256)    DEFAULT NULL             COMMENT '备注',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_sheet_config_id` (`sheet_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据组配置表';


-- ============================================================
-- 4. 字段映射规则表
-- ============================================================
CREATE TABLE `import_template_field` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`   BIGINT          NOT NULL                 COMMENT 'Sheet配置ID',
    `group_id`          BIGINT          DEFAULT NULL             COMMENT '数据组ID(null表示适用于该Sheet所有组)',
    `field_code`        VARCHAR(64)     NOT NULL                 COMMENT '业务字段编码',
    `field_name`        VARCHAR(64)     NOT NULL                 COMMENT '业务字段中文名',
    `source_type`       VARCHAR(32)     NOT NULL                 COMMENT '数据来源类型: COLUMN/FIXED_CELL/FIXED_VALUE/COMPOSITE/COLUMN_HEADER',
    `source_config`     JSON            NOT NULL                 COMMENT '数据来源配置(JSON)',
    `transform_config`  JSON            DEFAULT NULL             COMMENT '数据转换/清洗配置(JSON)',
    `required`          TINYINT         NOT NULL DEFAULT 0       COMMENT '是否必填 0-否 1-是',
    `default_value`     VARCHAR(256)    DEFAULT NULL             COMMENT '默认值(当取值为空时使用)',
    `sort_order`        INT             NOT NULL DEFAULT 0       COMMENT '排序',
    `remark`            VARCHAR(256)    DEFAULT NULL             COMMENT '备注',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_group_id` (`group_id`),
    KEY `idx_sheet_config_id` (`sheet_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段映射规则表';


-- ============================================================
-- 5. 字段值映射规则表 (v1.2 从 category_mapping 泛化而来, 支持品类/产地/材质)
-- ============================================================
CREATE TABLE `import_template_field_value_mapping` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`   BIGINT          NOT NULL                 COMMENT 'Sheet配置ID',
    `group_id`          BIGINT          DEFAULT NULL             COMMENT '数据组ID',
    `target_field`      VARCHAR(64)     NOT NULL                 COMMENT '目标字段编码: category/origin/material',
    `source_value`      VARCHAR(128)    DEFAULT NULL             COMMENT '原始值(null表示匹配任意)',
    `qualifier`         VARCHAR(128)    DEFAULT NULL             COMMENT '限定词(如表头文字: 黑材/白材/华岐)',
    `target_value`      VARCHAR(128)    NOT NULL                 COMMENT '目标值(映射后的值)',
    `sort_order`        INT             NOT NULL DEFAULT 0       COMMENT '匹配优先级(越小越优先)',
    `remark`            VARCHAR(256)    DEFAULT NULL,
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_group_id` (`group_id`),
    KEY `idx_template_sheet` (`template_id`, `sheet_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段值映射规则表';


-- ============================================================
-- 6. 价格匹配规则表
-- ============================================================
CREATE TABLE `import_template_price_match_rule` (
    `id`                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`               BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`           BIGINT          DEFAULT NULL             COMMENT 'Sheet配置ID(null=模板级规则)',
    `match_fields`              JSON            NOT NULL                 COMMENT '参与匹配的字段列表 如["category","spec","origin","material"]',
    `wall_thickness_match_mode` TINYINT         NOT NULL DEFAULT 0       COMMENT '壁厚匹配模式 0-精确匹配 1-区间匹配',
    `spec_range_match_mode`     TINYINT         NOT NULL DEFAULT 0       COMMENT 'v1.3 规格区间后缀匹配模式 0-不处理(整体精确) 1-拆分基础规格+区间包含匹配',
    `spec_range_config`         JSON            DEFAULT NULL             COMMENT 'v1.3 规格区间解析配置(JSON)',
    `remark`                    VARCHAR(256)    DEFAULT NULL,
    `create_time`               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`                   TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='价格匹配规则表';


-- ============================================================
-- 7. 导入记录表(用于追溯)
-- ============================================================
CREATE TABLE `import_record` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '使用的模板ID',
    `template_code`     VARCHAR(64)     NOT NULL                 COMMENT '模板编号',
    `supplier_id`       BIGINT          DEFAULT NULL             COMMENT '供应商ID',
    `file_name`         VARCHAR(256)    NOT NULL                 COMMENT '上传文件名',
    `file_path`         VARCHAR(512)    NOT NULL                 COMMENT '文件存储路径',
    `total_rows`        INT             NOT NULL DEFAULT 0       COMMENT '总行数',
    `success_rows`      INT             NOT NULL DEFAULT 0       COMMENT '成功行数',
    `error_rows`        INT             NOT NULL DEFAULT 0       COMMENT '错误行数',
    `import_status`     TINYINT         NOT NULL DEFAULT 0       COMMENT '导入状态 0-预览中 1-已确认 2-已回滚',
    `error_detail`      LONGTEXT        DEFAULT NULL             COMMENT '错误详情JSON',
    `create_by`         VARCHAR(64)     DEFAULT NULL,
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`),
    KEY `idx_supplier_id` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入记录表';
```

-- ============================================================
-- 8. 系统预置字符转换规则表(全局通用, 不绑定模板)
-- ============================================================
CREATE TABLE `import_char_rule_preset` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `rule_code`         VARCHAR(64)     NOT NULL                 COMMENT '规则编码(唯一)',
    `rule_name`         VARCHAR(128)    NOT NULL                 COMMENT '规则名称(如: 全角转半角)',
    `rule_group`        VARCHAR(64)     NOT NULL DEFAULT 'COMMON' COMMENT '规则分组: COMMON-通用 / SPEC-规格专用 / UNIT-单位相关',
    `match_type`        VARCHAR(32)     NOT NULL                 COMMENT '匹配方式: LITERAL-精确字符 / REGEX-正则表达式 / FULLWIDTH-全角转半角 / CHARCLASS-字符类',
    `match_pattern`     VARCHAR(256)    NOT NULL                 COMMENT '匹配模式(精确字符串或正则)',
    `replace_value`     VARCHAR(256)    NOT NULL DEFAULT ''      COMMENT '替换为的值(空字符串=删除该字符)',
    `description`       VARCHAR(256)    DEFAULT NULL             COMMENT '说明(如: 全角乘号转半角星号)',
    `sort_order`        INT             NOT NULL DEFAULT 0       COMMENT '执行顺序(升序, 越小越先执行)',
    `enabled`           TINYINT         NOT NULL DEFAULT 1       COMMENT '是否启用 0-否 1-是',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_code` (`rule_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统预置字符转换规则表';


-- ============================================================
-- 9. 模板字符转换规则表(模板级, 可引用预置或自定义)
-- ============================================================
CREATE TABLE `import_template_char_rule` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `apply_field_codes` JSON            DEFAULT NULL             COMMENT '适用字段列表(null=适用所有文本字段) 如["spec","category"]',
    `preset_rule_id`    BIGINT          DEFAULT NULL             COMMENT '引用的预置规则ID(不为空时以预置规则为准)',
    `match_type`        VARCHAR(32)     DEFAULT NULL             COMMENT '匹配方式(自定义规则时使用)',
    `match_pattern`     VARCHAR(256)    DEFAULT NULL             COMMENT '匹配模式(自定义规则时使用)',
    `replace_value`     VARCHAR(256)    DEFAULT NULL             COMMENT '替换值(自定义规则时使用)',
    `description`       VARCHAR(256)    DEFAULT NULL             COMMENT '说明',
    `sort_order`        INT             NOT NULL DEFAULT 0       COMMENT '执行顺序(升序, 越小越先执行)',
    `enabled`           TINYINT         NOT NULL DEFAULT 1       COMMENT '是否启用 0-否 1-是',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板字符转换规则表';


-- ============================================================
-- 预置规则初始化数据(钢贸行业常见特殊符号)
-- ============================================================
INSERT INTO `import_char_rule_preset` (`rule_code`, `rule_name`, `rule_group`, `match_type`, `match_pattern`, `replace_value`, `description`, `sort_order`) VALUES
-- 全角→半角 基础符号
('FULLWIDTH_STAR',       '全角×→半角*',      'SPEC',    'LITERAL',   '×',   '*',  '全角乘号转半角星号, 规格分隔符归一化',          10),
('FULLWIDTH_X_UPPER',    '全角Ｘ→半角*',     'SPEC',    'LITERAL',   'Ｘ',  '*',  '全角大写X转半角星号',                          11),
('LOWERCASE_X_SEP',      '小写x→半角*',      'SPEC',    'REGEX',     '(?<=\\d)x(?=\\d)', '*', '数字间的小写x视为乘号',          12),
('UPPERCASE_X_SEP',      '大写X→半角*',      'SPEC',    'REGEX',     '(?<=\\d)X(?=\\d)', '*', '数字间的大写X视为乘号',          13),
('FULLWIDTH_HYPHEN',     '全角－→半角-',     'COMMON',  'LITERAL',   '－',  '-',  '全角减号/连字符转半角',                        20),
('EN_DASH',              '半角–→半角-',      'COMMON',  'LITERAL',   '–',   '-',  'EN DASH 转标准连字符',                        21),
('EM_DASH',              '全角—→半角-',      'COMMON',  'LITERAL',   '—',   '-',  'EM DASH 转标准连字符',                        22),
('FULLWIDTH_DOT',        '全角．→半角.',     'COMMON',  'LITERAL',   '．',  '.',  '全角句点转半角点(小数点)',                      23),
('FULLWIDTH_LPAREN',     '全角（→半角(',     'COMMON',  'LITERAL',   '（',  '(',  '全角左括号转半角',                             30),
('FULLWIDTH_RPAREN',     '全角）→半角)',     'COMMON',  'LITERAL',   '）',  ')',  '全角右括号转半角',                             31),
('FULLWIDTH_SLASH',      '全角／→半角/',     'COMMON',  'LITERAL',   '／',  '/',  '全角斜杠转半角',                              32),
-- 直径符号
('PHI_UPPER',            '大写Φ→清除',       'SPEC',    'LITERAL',   'Φ',   '',   '大写希腊字母Phi(直径符号)清除',                 40),
('PHI_LOWER',            '小写φ→清除',       'SPEC',    'LITERAL',   'φ',   '',   '小写希腊字母phi清除',                          41),
('DIAMETER_SIGN',        '∅→清除',           'SPEC',    'LITERAL',   '∅',   '',   'Unicode直径符号清除',                          42),
('PHI_FULLWIDTH',        'Ф→清除',           'SPEC',    'LITERAL',   'Ф',   '',   '西里尔字母Ef(常被误用为直径)清除',               43),
-- 空白字符
('NBSP',                 '不间断空格→清除',   'COMMON',  'LITERAL',   ' ',   '',   'Unicode不间断空格(U+00A0)清除',               50),
('IDEOGRAPHIC_SPACE',    '全角空格→清除',     'COMMON',  'LITERAL',   '　',  '',   '全角空格(U+3000)清除',                        51),
('MULTI_SPACES',         '连续空格→单空格',   'COMMON',  'REGEX',     '\\s{2,}', ' ', '多个连续空白压缩为单个空格',                52),
-- 井号处理(可选, 默认不启用)
('HASH_SIGN',            '#号→清除',          'SPEC',    'LITERAL',   '#',   '',   '井号清除(部分规格如10#中的#)',                  60),
-- 全角数字
('FULLWIDTH_DIGITS',     '全角数字→半角',     'COMMON',  'FULLWIDTH', '０-９', '0-9', '全角数字0-9转半角',                        70);


```

### 3.3 核心 JSON 配置结构详解

#### 3.3.1 source_config 各类型详解

**① COLUMN — 来自数据列**

```json
{
    "columnIndex": 2,
    "headerName": "规格",
    "headerAliases": ["规格", "型号", "规格型号", "尺寸", "SIZE"],
    "matchMode": "ALIAS"
}
```

| 字段 | 说明 |
|------|------|
| `columnIndex` | 列索引(0开始)，优先使用 |
| `headerName` | 表头列名 |
| `headerAliases` | 别名列表，任一匹配即视为此列 |
| `matchMode` | 匹配模式：`INDEX`(按索引) / `ALIAS`(按别名) / `INDEX_FIRST`(优先索引,回退别名) |

**② FIXED_CELL — 来自固定单元格**

```json
{
    "cellRef": "A1",
    "row": 0,
    "col": 0
}
```

支持两种定位方式：`cellRef` Excel 风格引用（如 "B3"）或 `row`+`col` 数字索引。

**③ FIXED_VALUE — 固定常量**

```json
{
    "value": "槽钢"
}
```

**④ COMPOSITE — 多源组合**

```json
{
    "parts": [
        { "sourceType": "COLUMN", "columnIndex": 1, "headerAliases": ["规格"] },
        { "sourceType": "FIXED_VALUE", "value": "*" },
        { "sourceType": "COLUMN", "columnIndex": 2, "headerAliases": ["壁厚"] }
    ],
    "separator": "",
    "template": "${0}${1}${2}"
}
```

`parts` 按顺序拼接。`template` 为可选的模板字符串，`${N}` 引用第 N 个 part 的值。

**⑤ COLUMN_HEADER — 列表头即为值**

```json
{
    "columnIndex": 3,
    "headerRowIndex": 0
}
```

典型场景：价格列的表头是产地名称(如"唐山"、"邯郸")，此时产地字段的值就取该列的表头文本。

#### 3.3.2 transform_config 详解

```json
{
    "trimWhitespace": true,
    "removeUnit": true,
    "unitPatterns": ["吨", "kg", "件", "支"],
    "numericPrecision": 2,
    "rangeDelimiter": "-",
    "parseAsRange": false,
    "regexExtract": null,
    "extractNumber": false,

    "charTransform": {
        "useTemplateRules": true,
        "usePresetGroups": ["SPEC", "COMMON"],
        "fieldRules": [
            { "matchType": "LITERAL", "matchPattern": "×", "replaceValue": "*" },
            { "matchType": "REGEX", "matchPattern": "(?<=\\d)[xX](?=\\d)", "replaceValue": "*" }
        ],
        "excludePresetCodes": ["HASH_SIGN"]
    },

    "rowInherit": {
        "enabled": true,
        "separator": "*",
        "partialPattern": "^[\\d.]+$",
        "inheritPart": "PREFIX",
        "assembleTemplate": "${prefix}*${current}"
    }
}
```

| 字段 | 说明 |
|------|------|
| `trimWhitespace` | 去除首尾空白 |
| `removeUnit` | 移除单位文字 |
| `unitPatterns` | 需移除的单位列表 |
| `numericPrecision` | 数值精度(小数位) |
| `parseAsRange` | 是否解析为区间值(壁厚场景) |
| `rangeDelimiter` | 区间分隔符(如 "-") |
| `regexExtract` | 正则提取(使用第一个捕获组) |
| `extractNumber` | **★ v1.2** 是否提取纯数字(如 "127支/件"→"127") |
| `charTransform` | **★ 字符转换配置**（见下方 3.3.3 详解） |
| `rowInherit` | **★ v1.2 行间继承配置**（见下方 3.3.4 详解） |

##### extractNumber 数值提取说明

当 `extractNumber = true` 时，从混合文本中提取第一个数值（含小数点）：

```
"127支/件"    →  "127"
"12.5吨"      →  "12.5"
"约5.0t"      →  "5.0"
"3件(散)"     →  "3"
```

实现方式：使用正则 `(-?\d+\.?\d*)` 提取第一个匹配的数值字符串。与 `removeUnit` 的区别在于 `extractNumber` 更激进——只保留数字部分，而 `removeUnit` 是删除已知单位文字。当两者同时启用时，`extractNumber` 优先执行。

#### 3.3.3 charTransform 字符转换子系统详解

##### 设计理念：三级规则 + 有序管道

规格字段中的特殊符号在不同供应商的 Excel 中表现形式各异，同一个 `*` 分隔符可能写成 `×`、`Ｘ`、`x`、`X`、`﹡` 等。为此设计**三级规则体系**，按优先级合并后按 `sort_order` 顺序执行：

```
┌─────────────────────────────────────────────────────────────┐
│                   字符转换执行管道                            │
│                                                             │
│  ① 系统预置规则 (import_char_rule_preset)                    │
│     └─ 按 rule_group 筛选 + 全局启用的规则                   │
│                     ↓ 合并                                   │
│  ② 模板级规则 (import_template_char_rule)                    │
│     └─ 当前模板配置的规则(可引用预置或自定义)                  │
│                     ↓ 合并                                   │
│  ③ 字段级规则 (transform_config.charTransform.fieldRules)    │
│     └─ 当前字段特有的转换规则                                 │
│                     ↓                                        │
│  按 sort_order 排序 → 去重(字段级 > 模板级 > 系统级) → 顺序执行│
└─────────────────────────────────────────────────────────────┘
```

##### charTransform JSON 字段说明

```json
{
    "useTemplateRules": true,
    "usePresetGroups": ["SPEC", "COMMON"],
    "fieldRules": [
        {
            "matchType": "LITERAL",
            "matchPattern": "㎜",
            "replaceValue": "mm",
            "sortOrder": 100,
            "description": "平方毫米符号转标准写法"
        }
    ],
    "excludePresetCodes": ["HASH_SIGN", "MULTI_SPACES"]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `useTemplateRules` | boolean | 是否应用模板级字符转换规则，默认 true |
| `usePresetGroups` | string[] | 引用的系统预置规则分组，如 `["SPEC","COMMON"]`。为 null 或空 = 使用当前字段的 field_code 自动推断 |
| `fieldRules` | object[] | 字段级自定义转换规则（仅对当前字段生效） |
| `excludePresetCodes` | string[] | 需排除的预置规则编码（如规格中想保留 `#` 号，排除 `HASH_SIGN`） |

##### 字段级规则 (fieldRules) 每条的结构

| 字段 | 类型 | 说明 |
|------|------|------|
| `matchType` | string | `LITERAL` / `REGEX` / `FULLWIDTH` / `CHARCLASS` |
| `matchPattern` | string | 匹配模式 |
| `replaceValue` | string | 替换值（空字符串 = 删除） |
| `sortOrder` | int | 执行顺序，默认 0 |
| `description` | string | 规则说明（便于运维理解） |

##### matchType 四种匹配模式详解

| 模式 | 说明 | matchPattern 示例 | replaceValue 示例 |
|------|------|------------------|------------------|
| `LITERAL` | 精确字符/字符串替换 | `×` | `*` |
| `REGEX` | Java 正则表达式替换 | `(?<=\d)[xX](?=\d)` | `*` |
| `FULLWIDTH` | 全角→半角批量转换 | `０-９` | `0-9`（按码位偏移转换） |
| `CHARCLASS` | Unicode 字符类清除 | `[\u00A0\u3000\u200B]` | `` (清除不可见字符) |

##### 钢贸行业常见规格特殊符号速查

```
原始值(供应商Excel)          →  标准化后
──────────────────────────────────────────
50×100×2.0                   →  50*100*2.0      (全角×→半角*)
Φ219×6                       →  219*6           (Φ删除, ×→*)
∅76×4.0                      →  76*4.0          (∅删除, ×→*)
50X100X2.0                   →  50*100*2.0      (大写X→*)
100﹡50﹡3                    →  100*50*3        (小型星号→*)
HW200×200                    →  HW200*200       (×→*, 保留字母前缀)
10#                          →  10#  (或 10)    (可配置是否清除#)
（50×100）                    →  (50*100)        (全角括号→半角)
２００＊３００                  →  200*300         (全角数字→半角, 全角*→半角)
50*100 * 2.0                 →  50*100*2.0      (清除多余空格)
40×80×1.0—1.5                →  40*80*1.0-1.5   (全角—→半角-)
```

##### 执行时序（在 DataTransformer 中）

```
原始值 rawValue
    │
    ├─ Step 1: trimWhitespace (去首尾空白)
    │
    ├─ Step 2: ★ charTransform (字符转换管道)   ← 新增, 在其他清洗之前执行
    │   │
    │   ├─ 2a. 收集规则: 预置(按group) + 模板级 + 字段级
    │   ├─ 2b. 排除 excludePresetCodes 中指定的预置规则
    │   ├─ 2c. 合并去重, 按 sort_order 升序排列
    │   └─ 2d. 逐条执行: LITERAL→String.replace / REGEX→Pattern.replaceAll / ...
    │
    ├─ Step 3: extractNumber (数值提取, 如 "127支/件"→"127")    ← v1.2 新增
    ├─ Step 4: removeUnit (去单位, 如 "吨")
    ├─ Step 5: regexExtract (正则提取)
    ├─ Step 6: numericPrecision (数值精度处理)
    ├─ Step 7: parseAsRange (区间解析)
    │
    └─ Step 8: ★ rowInherit (行间继承, 如 "2.5"→"20*2.5")      ← v1.2 新增
               (在 DataTransformer 之后, 由 RowInheritResolver 单独执行)

输出 cleanValue → 进入 FieldValueMapper(品类/产地/材质映射)
```

> **关键设计决策**：字符转换（Step 2）在去单位（Step 3）之前执行，因为去单位依赖于标准化后的字符（如全角"吨"需要先转为半角才能被 unitPatterns 匹配），同时在数值转换之前确保所有数字和分隔符已标准化。

#### 3.3.4 rowInherit 行间继承配置详解 — v1.2 新增

##### 业务场景

钢塑管、镀锌管等品类的库存/价格表中，规格列经常存在"省略写法"：第一行写完整规格（如 `20*2.0`），后续行若仅写壁厚（如 `2.5`），表示公称口径不变，仅壁厚变化，实际规格应为 `20*2.5`。

##### JSON 配置

```json
{
    "enabled": true,
    "separator": "*",
    "partialPattern": "^[\\d.]+$",
    "inheritPart": "PREFIX",
    "assembleTemplate": "${prefix}*${current}"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `enabled` | boolean | 是否启用行间继承 |
| `separator` | string | 完整规格的分隔符（如 `*`），用于拆分前缀和后缀 |
| `partialPattern` | string | 判断"仅部分值"的正则表达式，命中则认为需要继承 |
| `inheritPart` | string | 继承方向：`PREFIX`(继承前缀)、`SUFFIX`(继承后缀) |
| `assembleTemplate` | string | 组装模板：`${prefix}` 为继承部分，`${current}` 为当前行值 |

##### 执行逻辑伪代码

```java
// RowInheritResolver 在 DataTransformer 之后执行
// 维护一个 prevFullValue 状态(按字段缓存)

String resolve(String currentValue, RowInheritConfig config) {
    if (!config.isEnabled()) return currentValue;
    if (currentValue == null || currentValue.isEmpty()) return currentValue;

    boolean isPartial = Pattern.matches(config.getPartialPattern(), currentValue);

    if (!isPartial) {
        // 当前值是完整规格, 更新缓存的前缀
        String[] parts = currentValue.split(Pattern.quote(config.getSeparator()), 2);
        if (parts.length > 1) {
            this.prevPrefix = parts[0]; // 缓存前缀, 如 "20"
        }
        return currentValue; // 原值返回
    }

    // 当前值是部分值(仅壁厚), 需要继承
    if (this.prevPrefix == null) {
        // 无上行前缀可继承, 记录警告
        return currentValue;
    }

    // 用模板组装: "${prefix}*${current}" → "20*2.5"
    return config.getAssembleTemplate()
        .replace("${prefix}", this.prevPrefix)
        .replace("${current}", currentValue);
}
```

##### 多层规格的继承

某些规格有三段（如 `50*100*2.0`），此时继承前缀为 `50*100`：

```
Excel 原始值        separator="*"   prefix缓存       继承后
────────────────────────────────────────────────────────────
50*100*2.0          完整             "50*100"         50*100*2.0
2.5                 部分             (不变)           50*100*2.5
1.8                 部分             (不变)           50*100*1.8
60*120*3.0          完整             "60*120"         60*120*3.0
2.0                 部分             (不变)           60*120*2.0
```

> 实现细节：拆分时使用 `split(separator, 2)` 只拆分最后一个分隔符之前的部分作为前缀。实际实现中应为 `lastIndexOf(separator)` 取前缀。

#### 3.3.5 match_fields 详解

```json
["category", "spec", "origin", "material"]
```

指定价格匹配库存时参与比对的业务字段。壁厚区间匹配由 `wall_thickness_match_mode` 单独控制，规格区间后缀匹配由 `spec_range_match_mode` 单独控制。

#### 3.3.6 spec_range_config 规格区间后缀配置详解 — v1.3 新增

##### 业务场景

镀锌型材等品类的规格中，基础型号后面会附带一个用括号包裹的重量/数量区间后缀：

```
库存规格               解析为
──────────────────────────────────────────
5#（10-15）            基础规格="5#", 区间=[10, 15]
5#(10-15)              基础规格="5#", 区间=[10, 15]
10#（5-10）             基础规格="10#", 区间=[5, 10]
10#(20以上)             基础规格="10#", 区间=[20, +∞)
10#(20+)               基础规格="10#", 区间=[20, +∞)
10#(20-∞)              基础规格="10#", 区间=[20, +∞)
10#(∞)                 基础规格="10#", 区间=[0, +∞)
10#(20以下)             基础规格="10#", 区间=[0, 20]
5#                     基础规格="5#", 区间=null(无区间)
50*100*2.0             基础规格="50*100*2.0", 区间=null
```

价格表中可能使用不同的区间表达方式。匹配时需判断库存的区间是否被价格的区间**包含**（价格区间覆盖了库存区间）或**重叠**。

##### JSON 配置

```json
{
    "rangePattern": "[（(]([^）)]+)[）)]\\s*$",
    "rangeSeparators": ["-", "~", "—", "－"],
    "infinityKeywords": ["以上", "+", "∞", "以上(含)", "及以上", "↑"],
    "zeroKeywords": ["以下", "以内", "以下(含)", "及以下", "↓"],
    "matchStrategy": "PRICE_CONTAINS_INVENTORY"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `rangePattern` | string | 从规格字符串末尾提取区间后缀的正则表达式。捕获组1为括号内文本 |
| `rangeSeparators` | string[] | 区间分隔符列表，用于将 `"10-15"` 拆分为 min=10, max=15 |
| `infinityKeywords` | string[] | 正无穷关键词，如 `"以上"` → max=+∞ |
| `zeroKeywords` | string[] | 下界关键词，如 `"以下"` → min=0 |
| `matchStrategy` | string | 匹配策略（见下方详解） |

##### 匹配策略 (matchStrategy)

| 策略 | 说明 | 匹配条件 |
|------|------|---------|
| `PRICE_CONTAINS_INVENTORY` | 价格区间完全包含库存区间 | price.min ≤ inv.min AND inv.max ≤ price.max |
| `RANGE_OVERLAP` | 两区间存在交集 | price.min ≤ inv.max AND inv.min ≤ price.max |
| `INVENTORY_MIN_IN_PRICE` | 库存下界落在价格区间内 | price.min ≤ inv.min ≤ price.max |
| `BASE_SPEC_ONLY` | 仅匹配基础规格，忽略区间 | baseSpec 相等即可 |

##### 区间解析算法 (SpecRangeParser)

```
输入: rangeText = 括号内文本(已去除括号)
输出: Range(min, max)

算法:
  1. trim(rangeText)
  2. 检查是否匹配 infinityKeywords:
     a. 如 "10以上" → 数字部分="10", 关键词="以上"
        → Range(10, Double.MAX_VALUE)
     b. 如 "∞" → Range(0, Double.MAX_VALUE)
  3. 检查是否匹配 zeroKeywords:
     a. 如 "20以下" → 数字部分="20", 关键词="以下"
        → Range(0, 20)
  4. 尝试按 rangeSeparators 拆分:
     a. 如 "10-15" → 按"-"拆分 → ["10", "15"]
        → Range(10, 15)
     b. 如 "10-∞" → 按"-"拆分 → ["10", "∞"]
        → Range(10, Double.MAX_VALUE)
  5. 尝试解析为单个数字:
     a. 如 "10" → Range(10, 10) (精确值)
  6. 解析失败 → 记录 WARNING, 返回 null(回退为整体精确匹配)
```

##### 规格拆分算法 (SpecRangeSplitter)

```
输入: fullSpec = "5#（10-15）", rangePattern

算法:
  1. 用 rangePattern 正则匹配 fullSpec
  2. 若命中:
     a. 捕获组1 = "10-15" (括号内文本)
     b. 基础规格 = fullSpec 去掉匹配部分 = "5#"
     c. 返回 SpecWithRange(baseSpec="5#", rangeText="10-15")
  3. 若未命中:
     a. 返回 SpecWithRange(baseSpec=fullSpec, rangeText=null)
```

##### 匹配示例

```
库存: 5#（10-15）   → base="5#", range=[10, 15]
价格: 5#(10以上)    → base="5#", range=[10, +∞)

策略=PRICE_CONTAINS_INVENTORY:
  price.min(10) ≤ inv.min(10)  ✓
  inv.max(15) ≤ price.max(+∞)  ✓
  → 匹配成功 ✓

──────────────────────────────────────────────

库存: 5#（10-15）   → base="5#", range=[10, 15]
价格: 5#(20以上)    → base="5#", range=[20, +∞)

策略=PRICE_CONTAINS_INVENTORY:
  price.min(20) ≤ inv.min(10)  ✗ (20 > 10)
  → 匹配失败 ✗

策略=RANGE_OVERLAP:
  price.min(20) ≤ inv.max(15)  ✗ (20 > 15)
  → 匹配失败 ✗ (完全不重叠)

──────────────────────────────────────────────

库存: 10#（5-10）   → base="10#", range=[5, 10]
价格: 10#(8-20)     → base="10#", range=[8, 20]

策略=PRICE_CONTAINS_INVENTORY:
  price.min(8) ≤ inv.min(5)  ✗ (8 > 5)
  → 匹配失败 ✗

策略=RANGE_OVERLAP:
  price.min(8) ≤ inv.max(10)  ✓
  inv.min(5) ≤ price.max(20)  ✓
  → 匹配成功 ✓ (区间 [8,10] 重叠)

──────────────────────────────────────────────

库存: 5#             → base="5#", range=null
价格: 5#(10以上)     → base="5#", range=[10, +∞)

规则: 库存无区间时, 只匹配基础规格, 忽略价格区间
→ 匹配成功 ✓ (base相等, 库存无区间约束)
```

---

## 四、业务字段编码表

| field_code | 中文名 | 说明 | 适用类型 |
|-----------|--------|------|---------|
| `category` | 品类 | 如"槽钢"、"方管" | 库存+价格 |
| `spec` | 规格 | 如"50*100*2.0" | 库存+价格 |
| `origin` | 产地 | 如"唐山"、"邯郸" | 库存+价格 |
| `material` | 材质 | 如"Q235B"、"Q355B" | 库存+价格 |
| `package_num` | 包装数量 | 每件支数 | 库存 |
| `whole_num` | 整件数 | 完整包装件数 | 库存 |
| `odd_num` | 零数 | 散支数量 | 库存 |
| `weight` | 重量 | 单位：吨 | 库存 |
| `price` | 单价 | 含税单价 | 价格(+库存) |
| `wall_thickness` | 壁厚 | 管材壁厚，可能为区间 | 价格 |
| `remark` | 备注 | 附加说明 | 库存+价格 |

---

## 五、后端架构设计

### 5.1 包结构

```
com.eiss.erp.defineimport
├── controller/
│   ├── ImportTemplateController.java           // 模板 CRUD 接口
│   ├── ExcelPreviewController.java             // Excel 文件预览接口(模板设计器用)
│   └── DynamicExcelImportController.java       // 动态导入接口
│
├── service/
│   ├── ImportTemplateService.java              // 模板管理服务接口
│   ├── ExcelPreviewService.java                // Excel 预览服务接口
│   ├── DynamicExcelImportService.java          // 动态导入服务接口
│   └── impl/
│       ├── ImportTemplateServiceImpl.java
│       ├── ExcelPreviewServiceImpl.java
│       └── DynamicExcelImportServiceImpl.java
│
├── engine/                                      // ★ 核心解析引擎
│   ├── DynamicExcelParser.java                 // 动态 Excel 解析器(总入口)
│   ├── DynamicExcelListener.java               // EasyExcel 行级监听器
│   ├── MergeCellCollector.java                 // 合并单元格收集器
│   ├── HeaderMatcher.java                      // 表头匹配器
│   ├── FieldValueResolver.java                 // 字段值解析器(根据 source_type 取值)
│   ├── DataTransformer.java                    // 数据转换器(清洗/格式化, 内部调用 CharTransformer)
│   ├── CharTransformer.java                    // ★ 字符转换引擎(三级规则合并+有序管道执行)
│   ├── RowInheritResolver.java                 // ★ v1.2 行间继承解析器(规格前缀继承)
│   ├── FieldValueMapper.java                   // ★ v1.2 通用字段值映射(品类/产地/材质, 原 CategoryMapper)
│   ├── SpecRangeParser.java                    // ★ v1.3 规格区间后缀解析器(拆分基础规格+区间)
│   ├── PriceMatcher.java                       // 价格→库存匹配器(v1.3 增强: 支持规格区间匹配)
│   └── DataValidator.java                      // 数据校验器
│
├── model/
│   ├── entity/                                  // MyBatis-Plus 实体
│   │   ├── ImportTemplate.java
│   │   ├── ImportTemplateSheet.java
│   │   ├── ImportTemplateGroup.java
│   │   ├── ImportTemplateField.java
│   │   ├── ImportTemplateFieldValueMapping.java // v1.2 泛化(原 CategoryMapping)
│   │   ├── ImportTemplatePriceMatchRule.java
│   │   ├── ImportCharRulePreset.java            // 系统预置字符转换规则
│   │   ├── ImportTemplateCharRule.java          // 模板字符转换规则
│   │   └── ImportRecord.java
│   │
│   ├── dto/                                     // 数据传输对象
│   │   ├── template/
│   │   │   ├── ImportTemplateDto.java          // 模板完整 DTO(嵌套子表)
│   │   │   ├── SheetConfigDto.java
│   │   │   ├── GroupConfigDto.java
│   │   │   ├── FieldMappingDto.java
│   │   │   └── FieldValueMappingDto.java       // v1.2 泛化(原 CategoryMappingDto)
│   │   │
│   │   ├── preview/
│   │   │   ├── ExcelFilePreviewDto.java        // Excel 文件预览(含全部 Sheet)
│   │   │   ├── SheetPreviewDto.java            // 单 Sheet 预览
│   │   │   └── CellDto.java                    // 单元格数据
│   │   │
│   │   └── importing/
│   │       ├── PreviewEasyDto.java             // ★ 复用现有导入预览结构
│   │       ├── ImportResultDto.java            // 导入结果
│   │       └── ParsedRowDto.java               // 解析后的行数据
│   │
│   ├── vo/                                      // 视图对象
│   │   ├── ImportTemplateVo.java
│   │   ├── ImportTemplateListVo.java
│   │   └── ImportResultVo.java
│   │
│   ├── config/                                  // 配置类(source_config 反序列化)
│   │   ├── ColumnSourceConfig.java
│   │   ├── FixedCellSourceConfig.java
│   │   ├── FixedValueSourceConfig.java
│   │   ├── CompositeSourceConfig.java
│   │   ├── ColumnHeaderSourceConfig.java
│   │   ├── TransformConfig.java
│   │   ├── CharTransformConfig.java             // 字符转换配置(含 fieldRules, excludePresetCodes等)
│   │   ├── RowInheritConfig.java                // v1.2 行间继承配置
│   │   └── SpecRangeConfig.java                 // v1.3 规格区间解析配置
│   │
│   └── enums/
│       ├── ContentTypeEnum.java                // INVENTORY(1), PRICE(2)
│       ├── SourceTypeEnum.java                 // COLUMN, FIXED_CELL, FIXED_VALUE, COMPOSITE, COLUMN_HEADER
│       ├── BusinessFieldEnum.java              // CATEGORY, SPEC, ORIGIN, ... 
│       ├── MatchModeEnum.java                  // INDEX, ALIAS, INDEX_FIRST
│       └── ImportStatusEnum.java               // PREVIEWING, CONFIRMED, ROLLBACK
│
├── mapper/
│   ├── ImportTemplateMapper.java
│   ├── ImportTemplateSheetMapper.java
│   ├── ImportTemplateGroupMapper.java
│   ├── ImportTemplateFieldMapper.java
│   ├── ImportTemplateFieldValueMappingMapper.java  // v1.2 泛化
│   ├── ImportTemplatePriceMatchRuleMapper.java
│   ├── ImportCharRulePresetMapper.java          // 系统预置字符规则 Mapper
│   ├── ImportTemplateCharRuleMapper.java        // 模板字符规则 Mapper
│   └── ImportRecordMapper.java
│
└── util/
    ├── CellRefUtil.java                        // "B3" ↔ (row=2, col=1) 互转
    ├── SpecParseUtil.java                      // 规格字符串解析(提取宽、高、壁厚)
    ├── RangeUtil.java                          // 区间解析与匹配("0.5-1.0" → [0.5, 1.0], "10以上" → [10, +∞))
    └── SafeConvertUtil.java                    // 安全类型转换(String→BigDecimal, 含异常处理)
```

### 5.2 核心类职责说明

#### DynamicExcelParser（解析引擎总入口）

```
职责: 协调整个 Excel 的解析流程
输入: InputStream(Excel文件) + ImportTemplateDto(模板规则)
输出: List<PreviewEasyDto>(统一预览数据) + List<ExcelImportError>(错误列表)

流程:
  1. 加载模板的完整配置(含Sheet/Group/Field/CategoryMapping)
  2. 遍历各 Sheet 配置 → 按 sort_order 排序(库存优先)
  3. 对每个 Sheet:
     a. 第一遍读取: 收集 CellExtra(合并单元格信息)
     b. 第二遍读取: 流式逐行解析
  4. 库存数据全部解析完成后，再解析价格数据
  5. 执行价格→库存匹配
  6. 返回结果
```

#### DynamicExcelListener（EasyExcel 行级监听器）

```
职责: 逐行接收 EasyExcel 的回调，完成行级数据解析
关键方法:
  - invokeHead(Map<Integer, CellData>)    // 接收表头行
  - invoke(Map<Integer, CellData>, AnalysisContext) // 接收数据行
  - doAfterAllAnalysed(AnalysisContext)   // 所有数据读取完毕
  - extra(CellExtra, AnalysisContext)     // 接收合并单元格信息

核心逻辑:
  - 表头行: 调用 HeaderMatcher 匹配列→字段映射
  - 数据行: 调用 FieldValueResolver 按规则取值
  - 合并单元格: 将合并区域首单元格值填充到所有被合并格
  - 跳过表尾: 根据 data_end_row 或 empty_row_threshold 判断
```

#### HeaderMatcher（表头匹配器）

```
职责: 将实际的 Excel 表头列名 与 模板中定义的 headerAliases 进行匹配

算法:
  1. 读取表头行所有列的文本
  2. 对每个 COLUMN 类型字段:
     a. 若 matchMode=INDEX → 直接用 columnIndex
     b. 若 matchMode=ALIAS → 遍历 headerAliases, 逐一与表头列名做 归一化比较
     c. 若 matchMode=INDEX_FIRST → 先试 INDEX, 失败则回退 ALIAS
  3. 归一化比较: trim + 全角转半角 + 统一大小写
  4. 输出: Map<String fieldCode, Integer columnIndex> — 字段编码→实际列索引
```

#### FieldValueResolver（字段值解析器）

```
职责: 根据 source_type 和 source_config 从 Excel 中提取字段原始值

各类型处理:
  COLUMN:
    → 从当前行数据中按 columnIndex 取值
    → 若为合并单元格, 从 mergeCellMap 取合并首格的值

  FIXED_CELL:
    → 从预读的全 Sheet 单元格缓存中取 (row, col) 的值

  FIXED_VALUE:
    → 直接返回 config.value

  COMPOSITE:
    → 按 parts 顺序, 递归解析每个 part 的值, 最后按 template/separator 拼接

  COLUMN_HEADER:
    → 从表头行数据中取 columnIndex 位置的文本
```

#### MergeCellCollector（合并单元格收集器）

```
职责: 在第一遍读取时收集所有 CellExtra(MERGE), 构建快速查找结构

数据结构:
  Map<String "row_col", CellValue> mergeCellMap
    Key: 被合并的子单元格坐标 "rowIndex_colIndex"
    Value: 合并区域首单元格的值

使用:
  在 FieldValueResolver.resolveColumn() 中:
    - 先取当前格值
    - 若为空, 查 mergeCellMap 看是否属于某合并区域
    - 若命中, 使用首格值
```

#### FieldValueMapper（通用字段值映射处理器）★ v1.2 泛化（原 CategoryMapper）

```
职责: 根据 FieldValueMapping 规则, 将原始字段值转换为目标值
      支持品类(category)、产地(origin)、材质(material) 三个字段

输入: targetField(目标字段编码) + rawValue(原始值) + qualifier(限定词)
输出: mappedValue(映射后的值)

算法:
  1. 从 FieldValueMapping 列表中筛选 target_field 匹配的规则
  2. 在筛选结果中查找:
     (source_value = rawValue OR source_value IS NULL)
     AND (qualifier = inputQualifier OR qualifier IS NULL)
  3. 按 sort_order 取第一条命中的规则
  4. 若命中 → 返回 target_value
  5. 若未命中 → 返回 rawValue(原值透传)

示例(品类):
  规则: target_field=category, source_value="方管", qualifier="白材", target_value="镀锌方管"
  输入: targetField=category, rawValue="方管", qualifier="白材"
  输出: "镀锌方管"

示例(产地):
  规则: target_field=origin, source_value=null, qualifier="华岐", target_value="华岐"
  输入: targetField=origin, rawValue=null, qualifier="华岐"
  输出: "华岐"

示例(材质):
  规则: target_field=material, source_value=null, qualifier="Q235B", target_value="Q235B"
  输入: targetField=material, rawValue=null, qualifier="Q235B"
  输出: "Q235B"
```

#### RowInheritResolver（行间继承解析器）★ v1.2 新增

```
职责: 当规格列的当前行仅包含壁厚等部分值时, 继承上一行的前缀拼接为完整规格

状态:
  Map<String fieldCode, String prevPrefix> — 每个启用继承的字段独立维护前缀缓存

输入: fieldCode + currentValue + RowInheritConfig
输出: 完整的规格字符串

算法:
  1. 若 config 未启用 → 直接返回 currentValue
  2. 判断 currentValue 是否为"部分值":
     Pattern.matches(config.partialPattern, currentValue)
  3. 若非部分值(完整规格):
     a. 用 lastIndexOf(separator) 拆分, 取前缀部分
     b. 更新 prevPrefix 缓存
     c. 返回 currentValue(原值)
  4. 若为部分值:
     a. 取 prevPrefix 缓存
     b. 若缓存为空 → 记录 WARNING, 返回原值
     c. 按 assembleTemplate 组装: "${prefix}*${current}" → "20*2.5"

性能:
  - prevPrefix 是按行顺序维护的状态, 与 SAX 流式读取兼容
  - 无额外内存开销, 仅保存一个字符串缓存
```

#### PriceMatcher（价格→库存匹配器）★ v1.3 增强

```
职责: 将解析出的价格记录匹配到库存记录, 填入库存的 price 字段

算法:
  1. 读取 PriceMatchRule, 确定参与匹配的字段列表
  2. 对每条价格记录:
     a. 提取匹配键: category + spec + origin + material (按规则)

     b. 若 spec_range_match_mode=1(规格区间匹配) ★v1.3:
        - 调用 SpecRangeParser 拆分库存规格:
          "5#（10-15）" → baseSpec="5#", invRange=[10, 15]
        - 调用 SpecRangeParser 拆分价格规格:
          "5#(10以上)" → baseSpec="5#", priceRange=[10, +∞)
        - 先匹配 baseSpec 是否相等
        - 再按 matchStrategy 判断区间关系:
          · PRICE_CONTAINS_INVENTORY: priceRange 完全包含 invRange
          · RANGE_OVERLAP: 两区间存在交集
          · INVENTORY_MIN_IN_PRICE: 库存下界落在价格区间内
          · BASE_SPEC_ONLY: 仅匹配基础规格
        - 特殊处理: 库存无区间后缀 → 只匹配基础规格(忽略价格区间)

     c. 若 spec_range_match_mode=0(不处理区间):
        - spec 作为整体精确匹配键

     d. 若 wall_thickness_match_mode=1(壁厚区间匹配):
        - 从价格记录解析壁厚区间 [min, max]
        - 从库存记录解析壁厚精确值 val
        - 匹配条件: min <= val <= max
     e. 若 wall_thickness_match_mode=0(壁厚精确匹配):
        - 壁厚也作为精确匹配键

  3. 匹配成功 → 将价格写入库存记录的 price 字段
  4. 未匹配的价格 → 生成 ExcelImportError
```

#### SpecRangeParser（规格区间后缀解析器）★ v1.3 新增

```
职责: 将带区间后缀的规格字符串拆分为"基础规格"和"区间范围"

输入: specValue(如 "5#（10-15）") + SpecRangeConfig
输出: SpecWithRange(baseSpec, Range)

核心方法:
  - parse(specValue, config) → SpecWithRange

数据结构:
  SpecWithRange {
      String baseSpec;     // "5#"
      Range  range;        // nullable, [10, 15] 或 [10, +∞)
  }
  Range {
      double min;          // 下界
      double max;          // 上界, Double.MAX_VALUE 表示正无穷
      boolean minInclusive;// 下界是否包含, 默认 true
      boolean maxInclusive;// 上界是否包含, 默认 true
  }

算法:
  1. 用 config.rangePattern 匹配规格字符串末尾的括号部分
     正则默认: [（(]([^）)]+)[）)]\s*$
     示例: "5#（10-15）" → 捕获组1="10-15", baseSpec="5#"

  2. 对捕获的括号内文本 rangeText, 解析为 Range:
     a. 检查 infinityKeywords:
        "10以上" → 提取数字"10" + 关键词"以上" → Range(10, MAX)
        "∞"     → Range(0, MAX)
        "10+"   → Range(10, MAX)
     b. 检查 zeroKeywords:
        "20以下" → Range(0, 20)
     c. 按 rangeSeparators 拆分:
        "10-15"  → Range(10, 15)
        "10-∞"   → Range(10, MAX)
        "10~20"  → Range(10, 20)
     d. 单个数字:
        "10"     → Range(10, 10)
     e. 无法解析 → null, 记录 WARNING

  3. 返回 SpecWithRange(baseSpec, range)

性能:
  - rangePattern 预编译, 缓存 Pattern 对象
  - 解析结果可按 specValue 缓存(同一规格不重复解析)

错误处理:
  - 括号不匹配(有开无关) → 整体作为 baseSpec, range=null
  - 区间文本无法解析 → 记录 WARNING, range=null, 回退为精确匹配
  - 数值转换失败 → 同上
```

#### DataValidator（数据校验器）

```
职责: 对解析后的每行数据进行业务校验

校验规则:
  1. 必填校验: required=1 的字段不能为空
  2. 数值校验: price/weight/package_num 等字段必须是合法数值
  3. 枚举校验: content_type 等枚举值必须在合法范围内
  4. 业务规则: 库存记录的品类+规格不能为空等

错误处理:
  - 每个校验失败生成一条 ExcelImportError
  - 包含: 行号、列号、字段名、错误原因、原始值
  - 错误不中断解析, 继续处理后续行
```

#### CharTransformer（字符转换引擎）★ v1.1 新增

```
职责: 基于三级规则(系统预置/模板级/字段级)合并后, 对字段原始值进行有序的字符转换

核心方法:
  - buildPipeline(templateId, fieldCode, charTransformConfig)
      → 构建当前字段的转换规则管道(初始化时调用一次, 缓存复用)
  - transform(rawValue, pipeline)
      → 按管道顺序逐条执行转换, 返回标准化后的字符串

管道构建算法:
  1. 收集系统预置规则:
     a. 根据 charTransformConfig.usePresetGroups 筛选分组
     b. 若 usePresetGroups 为空, 根据 fieldCode 自动推断:
        - spec/wall_thickness → ["SPEC", "COMMON"]
        - 其他文本字段 → ["COMMON"]
     c. 排除 excludePresetCodes 中列出的规则
  2. 收集模板级规则:
     a. 若 charTransformConfig.useTemplateRules = true
     b. 从 import_template_char_rule 表加载当前模板的规则
     c. 按 apply_field_codes 过滤(null = 适用所有字段)
     d. 若规则引用了 preset_rule_id, 从预置表获取实际配置
  3. 收集字段级规则:
     a. 从 charTransformConfig.fieldRules 数组
  4. 合并去重:
     a. 若同一 matchPattern 在多级都有定义, 字段级 > 模板级 > 系统级
  5. 按 sort_order 升序排列 → 输出为 List<CharRule>

单条规则执行逻辑:
  LITERAL:
    → String.replace(matchPattern, replaceValue)
  REGEX:
    → Pattern.compile(matchPattern).matcher(value).replaceAll(replaceValue)
    → Pattern 预编译并缓存, 避免重复编译
  FULLWIDTH:
    → 遍历字符, 若在全角范围(0xFF01-0xFF5E)则偏移0xFEE0转半角
    → 特殊处理: 全角空格(0x3000)→半角空格(0x20)
  CHARCLASS:
    → 与 REGEX 类似, 但 matchPattern 直接作为字符类 [...]

性能优化:
  - pipeline 按 (templateId, fieldCode) 缓存, 整个模板生命周期内只构建一次
  - REGEX 类型的 Pattern 预编译并缓存
  - LITERAL 类型使用 String.replace() (JDK内部已优化)
  - 空 pipeline (无规则) 直接返回原值, 零开销

错误处理:
  - 正则编译失败 → 记录 WARNING 日志, 跳过该规则, 不中断
  - 转换后值为空 → 保留空字符串(不回退到原值, 因为可能是有意为之)
```

### 5.3 关键数据流

```
┌──────────┐     ┌──────────────┐     ┌────────────────┐
│ Excel    │────▶│ EasyExcel    │────▶│ DynamicExcel   │
│ 文件     │     │ SAX 流式读取  │     │ Listener       │
└──────────┘     └──────────────┘     └───────┬────────┘
                                              │
                       ┌──────────────────────┤
                       ▼                      ▼
              ┌─────────────┐       ┌──────────────────┐
              │ MergeCell   │       │ HeaderMatcher    │
              │ Collector   │       │ (表头别名匹配)    │
              │ (合并单元格) │       └────────┬─────────┘
              └──────┬──────┘                │
                     │                       │
                     ▼                       ▼
              ┌──────────────────────────────────────┐
              │       FieldValueResolver             │
              │  (按 source_type 从各位置取值)         │
              │  COLUMN / FIXED_CELL / COMPOSITE...  │
              └──────────────────┬───────────────────┘
                                 │
                                 ▼
              ┌──────────────────────────────────────┐
              │       DataTransformer                │
              │  (去空格/字符转换/数值提取/去单位/...)  │
              │         ↓ 内部调用                    │
              │  ┌──────────────────────────────┐    │
              │  │     CharTransformer           │    │
              │  │  (三级规则合并+有序管道)        │    │
              │  │  ×→* / Φ→清除 / 全角→半角...  │    │
              │  └──────────────────────────────┘    │
              └──────────────────┬───────────────────┘
                                 │
                                 ▼
              ┌──────────────────────────────────────┐
              │       RowInheritResolver ★v1.2       │
              │  (规格行间继承: 20 + 2.5 → 20*2.5)    │
              └──────────────────┬───────────────────┘
                                 │
                                 ▼
              ┌──────────────────────────────────────┐
              │       FieldValueMapper ★v1.2         │
              │  (通用字段值映射:                      │
              │   品类: 方管+白材→镀锌方管             │
              │   产地: +华岐→华岐                    │
              │   材质: +Q235B→Q235B)                 │
              └──────────────────┬───────────────────┘
                                 │
                                 ▼
              ┌──────────────────────────────────────┐
              │       DataValidator                  │
              │  (必填/数值/业务规则 校验)              │
              └──────────────────┬───────────────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
         ┌──────────────────┐      ┌──────────────────┐
         │   有效数据行       │      │  ExcelImportError│
         │  ParsedRowDto    │      │  (错误记录)       │
         └────────┬─────────┘      └──────────────────┘
                  │
                  ▼
         ┌──────────────────┐
         │  PriceMatcher    │  ◀── 库存解析完成后, 价格匹配
         │  (价格→库存匹配)   │
         └────────┬─────────┘
                  │
                  ▼
         ┌──────────────────┐
         │  PreviewEasyDto  │  ← 统一输出格式
         └──────────────────┘
```

---

## 六、API 接口设计

### 6.1 Excel 预览接口（模板设计器使用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/excel-preview/upload` | 上传 Excel 并返回文件 ID 和基础信息 |
| GET | `/api/v1/excel-preview/{fileId}/sheets` | 获取所有 Sheet 页列表(名称、行列数) |
| GET | `/api/v1/excel-preview/{fileId}/sheet/{sheetIndex}` | 获取指定 Sheet 的单元格数据(含合并信息) |

#### POST `/api/v1/excel-preview/upload`

**Request**: `multipart/form-data`, 字段 `file`

**Response**:
```json
{
    "code": 200,
    "data": {
        "fileId": "f_20260323_001",
        "fileName": "唐钢库存.xlsx",
        "sheets": [
            {
                "sheetIndex": 0,
                "sheetName": "库存",
                "rowCount": 150,
                "colCount": 12
            },
            {
                "sheetIndex": 1,
                "sheetName": "价格表",
                "rowCount": 80,
                "colCount": 8
            }
        ]
    }
}
```

#### GET `/api/v1/excel-preview/{fileId}/sheet/{sheetIndex}`

**Response**:
```json
{
    "code": 200,
    "data": {
        "sheetIndex": 0,
        "sheetName": "库存",
        "rows": [
            {
                "rowIndex": 0,
                "cells": [
                    {
                        "rowIndex": 0,
                        "colIndex": 0,
                        "value": "品类",
                        "cellRef": "A1",
                        "isMerged": false,
                        "mergeRowSpan": 1,
                        "mergeColSpan": 1
                    }
                ]
            }
        ],
        "mergeRegions": [
            {
                "firstRow": 1,
                "lastRow": 5,
                "firstCol": 0,
                "lastCol": 0,
                "value": "槽钢"
            }
        ]
    }
}
```

### 6.2 模板管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/import-template` | 创建模板(含全部子配置) |
| PUT | `/api/v1/import-template/{id}` | 更新模板 |
| DELETE | `/api/v1/import-template/{id}` | 删除模板 |
| GET | `/api/v1/import-template/{id}` | 获取模板详情(含嵌套子表) |
| GET | `/api/v1/import-template/page` | 分页查询模板列表 |
| GET | `/api/v1/import-template/list` | 查询指定供应商的可用模板 |

### 6.2.1 字符转换规则接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/char-rule-preset/list` | 查询所有系统预置规则(按分组) |
| POST | `/api/v1/char-rule-preset/test` | 测试预置规则：输入样本值 → 输出转换结果 |
| POST | `/api/v1/char-rule/test` | 测试自定义规则：输入规则 + 样本值 → 输出转换结果 |

#### POST `/api/v1/char-rule-preset/test` — 规则测试

**Request**:
```json
{
    "sampleValues": ["50×100×2.0", "Φ219×6", "∅76×4.0", "200＊300", "10#"],
    "presetGroups": ["SPEC", "COMMON"],
    "excludePresetCodes": ["HASH_SIGN"],
    "customRules": [
        { "matchType": "LITERAL", "matchPattern": "﹡", "replaceValue": "*" }
    ]
}
```

**Response**:
```json
{
    "code": 200,
    "data": {
        "results": [
            {
                "original": "50×100×2.0",
                "transformed": "50*100*2.0",
                "appliedRules": ["FULLWIDTH_STAR"]
            },
            {
                "original": "Φ219×6",
                "transformed": "219*6",
                "appliedRules": ["PHI_UPPER", "FULLWIDTH_STAR"]
            },
            {
                "original": "∅76×4.0",
                "transformed": "76*4.0",
                "appliedRules": ["DIAMETER_SIGN", "FULLWIDTH_STAR"]
            },
            {
                "original": "200＊300",
                "transformed": "200*300",
                "appliedRules": ["custom_0"]
            },
            {
                "original": "10#",
                "transformed": "10#",
                "appliedRules": []
            }
        ]
    }
}
```

#### POST `/api/v1/import-template` — 创建模板

**Request**:
```json
{
    "templateCode": "TPL_TANGSHAN_001",
    "templateName": "唐钢库存+价格模板",
    "supplierId": 1001,
    "supplierName": "唐山钢铁",
    "scope": "唐钢月度库存报价",
    "allSheetsPrice": false,
    "remark": "唐钢标准格式",
    "charRules": [
        {
            "applyFieldCodes": ["spec", "wall_thickness"],
            "presetRuleId": null,
            "matchType": "LITERAL",
            "matchPattern": "﹡",
            "replaceValue": "*",
            "description": "小型星号(U+FF0A)转标准星号",
            "sortOrder": 15,
            "enabled": true
        },
        {
            "applyFieldCodes": null,
            "presetRuleId": 1,
            "description": "引用系统预置: 全角×→半角*",
            "sortOrder": 10,
            "enabled": true
        }
    ],
    "sheets": [
        {
            "sheetIndex": 0,
            "sheetName": "库存",
            "contentType": 1,
            "headerRowIndex": 2,
            "dataStartRowIndex": 3,
            "dataEndRowIndex": null,
            "emptyRowThreshold": 2,
            "enableMergeCell": true,
            "groups": [
                {
                    "groupSeq": 1,
                    "groupName": "默认组",
                    "fields": [
                        {
                            "fieldCode": "category",
                            "fieldName": "品类",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "columnIndex": 0,
                                "headerAliases": ["品类", "品名", "产品名称", "钢种"],
                                "matchMode": "ALIAS"
                            },
                            "required": true
                        },
                        {
                            "fieldCode": "spec",
                            "fieldName": "规格",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "columnIndex": 1,
                                "headerAliases": ["规格", "型号", "规格型号"],
                                "matchMode": "ALIAS"
                            },
                            "transformConfig": {
                                "trimWhitespace": true,
                                "charTransform": {
                                    "useTemplateRules": true,
                                    "usePresetGroups": ["SPEC", "COMMON"],
                                    "fieldRules": [],
                                    "excludePresetCodes": ["HASH_SIGN"]
                                }
                            },
                            "required": true
                        },
                        {
                            "fieldCode": "origin",
                            "fieldName": "产地",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "columnIndex": 3,
                                "headerAliases": ["产地", "厂家", "钢厂"],
                                "matchMode": "ALIAS"
                            }
                        },
                        {
                            "fieldCode": "weight",
                            "fieldName": "重量",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "headerAliases": ["重量", "吨位", "数量(吨)", "过磅重量"],
                                "matchMode": "ALIAS"
                            },
                            "transformConfig": {
                                "removeUnit": true,
                                "unitPatterns": ["吨", "T", "t"],
                                "numericPrecision": 3
                            }
                        }
                    ]
                }
            ]
        },
        {
            "sheetIndex": 1,
            "sheetName": "价格表",
            "contentType": 2,
            "headerRowIndex": 0,
            "enableMergeCell": false,
            "groups": [
                {
                    "groupSeq": 1,
                    "groupName": "黑材价格",
                    "fields": [
                        {
                            "fieldCode": "category",
                            "fieldName": "品类",
                            "sourceType": "COMPOSITE",
                            "sourceConfig": {
                                "parts": [
                                    { "sourceType": "COLUMN_HEADER", "columnIndex": 3 },
                                    { "sourceType": "COLUMN", "columnIndex": 0 }
                                ]
                            }
                        },
                        {
                            "fieldCode": "price",
                            "fieldName": "单价",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "columnIndex": 3,
                                "matchMode": "INDEX"
                            }
                        },
                        {
                            "fieldCode": "origin",
                            "fieldName": "产地",
                            "sourceType": "COLUMN_HEADER",
                            "sourceConfig": {
                                "columnIndex": 3
                            }
                        }
                    ],
                    "fieldValueMappings": [
                        {
                            "targetField": "category",
                            "sourceValue": "方管",
                            "qualifier": "黑材",
                            "targetValue": "方管"
                        }
                    ]
                },
                {
                    "groupSeq": 2,
                    "groupName": "白材价格",
                    "fields": [
                        {
                            "fieldCode": "price",
                            "fieldName": "单价",
                            "sourceType": "COLUMN",
                            "sourceConfig": {
                                "columnIndex": 4,
                                "matchMode": "INDEX"
                            }
                        },
                        {
                            "fieldCode": "origin",
                            "fieldName": "产地",
                            "sourceType": "COLUMN_HEADER",
                            "sourceConfig": {
                                "columnIndex": 4
                            }
                        }
                    ],
                    "fieldValueMappings": [
                        {
                            "targetField": "category",
                            "sourceValue": "方管",
                            "qualifier": "白材",
                            "targetValue": "镀锌方管"
                        }
                    ]
                }
            ],
            "priceMatchRule": {
                "matchFields": ["category", "spec", "origin", "material"],
                "wallThicknessMatchMode": 1
            }
        }
    ]
}
```

### 6.3 动态导入接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/dynamic-import/preview` | 上传 Excel + 选择模板 → 返回预览数据 |
| POST | `/api/v1/dynamic-import/confirm` | 确认导入预览数据 |

#### POST `/api/v1/dynamic-import/preview`

**Request**: `multipart/form-data`
- `file`: Excel 文件
- `templateId`: 模板 ID

**Response**:
```json
{
    "code": 200,
    "data": {
        "taskId": "import_20260323_001",
        "templateName": "唐钢库存+价格模板",
        "inventoryRows": [
            {
                "rowIndex": 3,
                "sheetName": "库存",
                "category": "槽钢",
                "spec": "10#",
                "origin": "唐山",
                "material": "Q235B",
                "packageNum": 10,
                "wholeNum": 5,
                "oddNum": 3,
                "weight": 12.500,
                "price": 4200.00,
                "remark": ""
            }
        ],
        "errors": [
            {
                "sheetName": "库存",
                "rowIndex": 15,
                "colIndex": 6,
                "fieldName": "重量",
                "errorMsg": "数值格式错误: '约5吨' 无法转换为数值",
                "rawValue": "约5吨"
            }
        ],
        "summary": {
            "totalSheets": 2,
            "inventorySheets": 1,
            "priceSheets": 1,
            "totalRows": 120,
            "successRows": 118,
            "errorRows": 2,
            "priceMatchedRows": 95,
            "priceUnmatchedRows": 23
        }
    }
}
```

---

## 七、前端架构设计（Vue 3）

### 7.1 技术选型

| 依赖 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.4+ | 核心框架 |
| Vite | 5.x | 构建工具 |
| Element Plus | 2.x | UI 组件库 |
| Pinia | 2.x | 状态管理 |
| Vue Router | 4.x | 路由 |
| Axios | 1.x | HTTP 请求 |
| Luckysheet/FortuneSheet | latest | Excel 预览渲染 |

> **Excel 预览组件选型说明**：推荐使用 `FortuneSheet`（Luckysheet 的 React/Vue 适配版），支持合并单元格渲染、Sheet 页签切换、单元格点击事件。如 FortuneSheet Vue3 支持不佳，备选方案为自行基于 `<table>` 实现简化版表格渲染（仅需只读展示 + 单元格选择）。

### 7.2 页面与组件结构

```
src/
├── views/
│   ├── template/
│   │   ├── TemplateListPage.vue              // 模板列表页
│   │   └── TemplateDesignerPage.vue          // ★ 模板设计器主页
│   │
│   └── import/
│       └── DynamicImportPage.vue             // 导入操作页
│
├── components/
│   ├── excel-viewer/
│   │   ├── ExcelViewer.vue                   // Excel 预览主组件
│   │   ├── SheetTabs.vue                     // Sheet 页签栏
│   │   ├── CellGrid.vue                     // 单元格网格渲染
│   │   └── CellHighlight.vue                // 已映射单元格高亮
│   │
│   ├── config-panel/
│   │   ├── ConfigPanel.vue                   // 右侧配置面板容器
│   │   ├── SheetConfigForm.vue               // Sheet 基础配置
│   │   ├── GroupConfigForm.vue               // 数据组配置
│   │   ├── FieldMappingList.vue              // 字段映射列表
│   │   ├── FieldMappingForm.vue              // 单字段映射编辑
│   │   ├── SourceConfigEditor.vue            // 数据来源配置编辑器
│   │   ├── CharRuleEditor.vue                // ★ 字符转换规则编辑器
│   │   ├── CharRulePresetPicker.vue          // ★ 系统预置规则选择器
│   │   ├── CharRuleTestPanel.vue             // ★ 字符转换规则测试面板
│   │   ├── FieldValueMappingEditor.vue        // ★ v1.2 通用字段值映射编辑(品类/产地/材质)
│   │   ├── RowInheritConfigForm.vue           // ★ v1.2 行间继承配置
│   │   ├── SpecRangeConfigForm.vue           // ★ v1.3 规格区间后缀匹配配置
│   │   └── PriceMatchRuleForm.vue            // 价格匹配规则(v1.3 增强: 区间匹配策略选择)
│   │
│   └── import-result/
│       ├── PreviewTable.vue                  // 导入预览表格
│       ├── ErrorTable.vue                    // 错误明细表格
│       └── ImportSummary.vue                 // 导入统计摘要
│
├── hooks/
│   ├── useExcelPreview.ts                    // Excel 预览状态与逻辑
│   ├── useTemplateConfig.ts                  // 模板配置状态与逻辑
│   ├── useCellSelection.ts                   // 单元格选择交互
│   └── useImport.ts                          // 导入操作逻辑
│
├── api/
│   ├── excelPreview.ts                       // Excel 预览 API
│   ├── importTemplate.ts                     // 模板 CRUD API
│   └── dynamicImport.ts                      // 动态导入 API
│
├── store/
│   └── templateDesigner.ts                   // 模板设计器 Pinia Store
│
└── types/
    ├── template.ts                           // 模板相关 TypeScript 类型
    ├── excel.ts                              // Excel 预览相关类型
    └── import.ts                             // 导入相关类型
```

### 7.3 模板设计器交互流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                     模板设计器 (TemplateDesignerPage)                 │
│                                                                      │
│  ┌────────────────────────────┐  ┌────────────────────────────────┐  │
│  │      左侧: Excel 预览区     │  │      右侧: 配置面板             │  │
│  │                            │  │                                │  │
│  │  ┌──────────────────────┐  │  │  Sheet 信息                    │  │
│  │  │  Sheet页签栏          │  │  │  ├─ 名称: [库存]              │  │
│  │  │  [库存] [价格表]      │  │  │  ├─ 类型: ○库存 ●价格         │  │
│  │  └──────────────────────┘  │  │  ├─ 表头行: [2]                │  │
│  │                            │  │  └─ 数据起始行: [3]            │  │
│  │  ┌──┬──┬──┬──┬──┬──┬──┐  │  │                                │  │
│  │  │A │B │C │D │E │F │G │  │  │  数据组 #1 [+ 新增组]           │  │
│  │  ├──┼──┼──┼──┼──┼──┼──┤  │  │  ├─ 组名: [默认组]              │  │
│  │  │提│示│文│案│  │  │  │  │  │                                │  │
│  │  ├──┼──┼──┼──┼──┼──┼──┤  │  │  字段映射                      │  │
│  │  │品│规│产│材│件│重│备│  │  │  ┌────────────────────────────┐│  │
│  │  │类│格│地│质│数│量│注│  │  │  │ ☑ 品类   来源: [A列]  ✎   ││  │
│  │  ├──┼──┼──┼──┼──┼──┼──┤  │  │  │ ☑ 规格   来源: [B列]  ✎   ││  │
│  │  │槽│10│唐│Q2│10│12│  │  │  │  │ ☐ 产地   来源: [未设置] ✎  ││  │
│  │  │钢│# │山│35│  │.5│  │  │  │  │ ☐ 材质   来源: [未设置] ✎  ││  │
│  │  │  │12│邯│Q2│8 │9.│  │  │  │  │ ☐ 包装   来源: [E列]  ✎   ││  │
│  │  │  │# │郸│35│  │2 │  │  │  │  │ ☐ 重量   来源: [F列]  ✎   ││  │
│  │  │  │14│  │  │6 │15│  │  │  │  └────────────────────────────┘│  │
│  │  │  │# │  │  │  │.0│  │  │  │                                │  │
│  │  ├──┼──┼──┼──┼──┼──┼──┤  │  │  字符转换规则 ★                 │  │
│  │  │角│50│天│Q3│12│20│  │  │  │  ┌──────────────────────────┐  │  │
│  │  │钢│*5│津│55│  │.0│  │  │  │  │预置: ☑SPEC ☑COMMON     │  │  │
│  │  └──┴──┴──┴──┴──┴──┴──┘  │  │  │排除: ☐HASH_SIGN        │  │  │
│  │                            │  │  │自定义: [+新增]          │  │  │
│  │                            │  │  │ ﹡→* ㎜→mm             │  │  │
│  │                            │  │  │[测试] Φ219×6 → 219*6   │  │  │
│  │                            │  │  └──────────────────────────┘  │  │
│  │                            │  │                                │  │
│  │                            │  │  字段值映射规则 ★v1.2           │  │
│  │                            │  │  ┌──────────────────────────┐  │  │
│  │                            │  │  │品类映射 [+新增]          │  │  │
│  │                            │  │  │ 方管+白材→镀锌方管       │  │  │
│  │                            │  │  │产地映射 [+新增]          │  │  │
│  │                            │  │  │ +华岐→华岐              │  │  │
│  │                            │  │  │材质映射 [+新增]          │  │  │
│  │                            │  │  │ +Q235B唐山→Q235B        │  │  │
│  │                            │  │  └──────────────────────────┘  │  │
│  │                            │  │                                │  │
│  │                            │  │  行间继承 ★v1.2                │  │
│  │                            │  │  (规格字段: 2.5→20*2.5)        │  │
│  │                            │  │                                │  │
│  │                            │  │  价格匹配规则                   │  │
│  │                            │  │  (仅价格类型Sheet显示)          │  │
│  │  点击单元格 → 右侧显示定位   │  │                                │  │
│  └────────────────────────────┘  └────────────────────────────────┘  │
│                                                                      │
│  [取消]                                          [保存模板]           │
└─────────────────────────────────────────────────────────────────────┘
```

### 7.4 设计器核心交互逻辑

#### 单元格选择与字段绑定流程

```
1. 用户在右侧「字段映射」中点击某字段的 ✎(编辑) → 进入"选择模式"
2. 此时左侧 Excel 预览区进入"可选择"状态, 鼠标变为十字
3. 用户在左侧点击一个单元格:
   a. 若单元格在表头行 → 自动识别为 COLUMN 类型, 填入 columnIndex
   b. 若单元格在数据区 → 自动识别为 FIXED_CELL 类型, 填入 cellRef
   c. 若单元格是合并区域 → 显示合并范围
4. 右侧配置面板实时更新:
   - 显示选中的单元格定位(如 "B3")
   - 数据来源类型自动推断(用户可手动切换)
   - 预览该位置的实际值
5. 用户可进一步配置:
   - 添加表头别名(用于兼容不同模板)
   - 设置默认值
   - 配置数据转换规则
   - 切换来源类型(如从 COLUMN 改为 COMPOSITE)
6. 点击「确认」→ 字段映射保存, 左侧对应列/单元格高亮标记
```

#### 字段值映射配置交互（v1.2 泛化）

```
1. 展开「字段值映射规则」折叠区域
2. 三个子页签: [品类映射] [产地映射] [材质映射]
3. 以品类映射为例, 点击 [+ 新增映射]:
   - 原始值:   [方管]     (来自品类列的值, 可留空表示匹配任意)
   - 限定词:   [白材]     (来自表头或指定单元格的值)
   - 目标值:   [镀锌方管]  (映射后的标准名称)
4. 产地映射示例:
   - 原始值:   (空)
   - 限定词:   [华岐]     (表头文字)
   - 目标值:   [华岐]     (标准产地名)
5. 材质映射示例:
   - 原始值:   (空)
   - 限定词:   [Q235B唐山] (表头文字, 同时含材质和产地)
   - 目标值:   [Q235B]    (标准材质名)
6. 可添加多条映射规则, 按优先级排序
7. 预览: 根据当前 Excel 数据实时展示映射结果

注: 产地和材质的映射规则与品类完全一致, 区别仅在于 target_field 字段
```

#### 行间继承配置交互 ★ v1.2 新增

```
1. 在规格字段的映射编辑中, 展开「数据转换 → 行间继承」
2. ☑ 启用行间继承
3. 配置项:
   - 分隔符:     [*]        (用于拆分规格的前缀和后缀)
   - 部分值检测:  [^\d.]+$]  (正则: 纯数字即为部分值)
   - 继承方向:    [前缀]     (PREFIX: 继承前缀; SUFFIX: 继承后缀)
   - 组装模板:    [${prefix}*${current}]
4. 实时预览:
   从当前 Sheet 规格列取数据, 展示继承效果:
   ┌──────────────┬──────────────┐
   │ Excel 原始值  │ 继承后        │
   ├──────────────┼──────────────┤
   │ 20*2.0       │ 20*2.0       │
   │ 2.5          │ 20*2.5 ←继承 │
   │ 3.0          │ 20*3.0 ←继承 │
   │ 25*2.0       │ 25*2.0       │
   │ 2.5          │ 25*2.5 ←继承 │
   └──────────────┴──────────────┘
```

#### 字符转换规则配置交互 ★ v1.1 新增

```
一、模板级规则配置（适用于当前模板所有字段或指定字段）

1. 在配置面板顶部展开「字符转换规则」折叠区域
2. 「系统预置规则」区域:
   a. 分组显示所有预置规则: SPEC(规格专用) / COMMON(通用)
   b. 每条预置规则显示: 规则名称 + 匹配→替换 + 启用开关
   c. 用户可逐条启用/禁用(对应 excludePresetCodes)
   d. 特殊标记: 规格字段默认启用 SPEC+COMMON 组, 其他字段默认只启用 COMMON 组
3. 「模板自定义规则」区域:
   a. 点击 [+ 新增规则], 弹出规则编辑表单:
      - 匹配方式: [精确字符 ▼] (LITERAL/REGEX/FULLWIDTH/CHARCLASS)
      - 匹配内容: [﹡]
      - 替换为:   [*]  (留空 = 删除该字符)
      - 适用字段: [全部 ▼] / [规格, 壁厚]  (多选)
      - 执行顺序: [15]
      - 说明:     [小型星号转标准星号]
   b. 已添加的规则以列表展示, 支持拖拽排序、编辑、删除

二、字段级规则配置（在单字段映射编辑中）

1. 用户编辑某个字段(如"规格")的映射规则时
2. 在「数据转换」区域展开「字符转换」子项:
   a. ☑ 使用模板级规则 (useTemplateRules, 默认勾选)
   b. 预置规则分组: [SPEC, COMMON ▼]  (usePresetGroups)
   c. 排除预置规则: [HASH_SIGN ▼]  (多选, excludePresetCodes)
   d. 字段专属规则: [+ 新增]  (fieldRules, 仅对当前字段生效)

三、实时测试功能

1. 在字符转换规则区域底部有 [测试转换] 按钮
2. 点击后弹出测试面板:
   a. 左侧: 输入框, 可手动输入或从当前 Excel 中选取样本值
   b. 右侧: 实时显示转换结果
   c. 下方: 显示命中的规则列表(哪些规则被触发)
3. 支持批量测试: 从当前 Sheet 的规格列自动提取前 20 个不重复值作为样本
4. 测试结果示例:
   ┌─────────────┬──────────────┬─────────────────────┐
   │ 原始值       │ 转换后        │ 命中规则             │
   ├─────────────┼──────────────┼─────────────────────┤
   │ 50×100×2.0  │ 50*100*2.0   │ FULLWIDTH_STAR      │
   │ Φ219×6      │ 219*6        │ PHI_UPPER, F_STAR   │
   │ ∅76×4.0     │ 76*4.0       │ DIAMETER_SIGN, F_S  │
   │ 10#         │ 10#          │ (无命中,#已排除)     │
   │ 200＊300     │ 200*300      │ 模板自定义_﹡→*      │
   └─────────────┴──────────────┴─────────────────────┘
```

---

## 八、DynamicExcelListener 核心伪代码

```java
/**
 * 动态 Excel 监听器
 * 基于模板规则逐行解析 Excel 数据，支持合并单元格、动态列映射
 */
public class DynamicExcelListener extends AnalysisEventListener<Map<Integer, CellData<?>>> {

    // ===== 注入的配置与依赖 =====
    private final SheetConfigDto sheetConfig;           // 当前Sheet的配置
    private final List<GroupConfigDto> groups;           // 数据组列表
    private final Map<String, CellValue> mergeCellMap;  // 合并单元格映射 "row_col" → value
    private final Map<String, String> fixedCellCache;   // 固定单元格缓存 "row_col" → value

    // ===== 运行时状态 =====
    private Map<String, Integer> headerColumnMap;       // fieldCode → 实际列索引(表头匹配结果)
    private final List<ParsedRowDto> parsedRows;        // 解析成功的数据行
    private final List<ExcelImportError> errors;        // 错误记录
    private int consecutiveEmptyRows = 0;               // 连续空行计数

    @Override
    public void invokeHead(Map<Integer, CellData<?>> headMap, AnalysisContext context) {
        int currentRow = context.readRowHolder().getRowIndex();
        if (currentRow != sheetConfig.getHeaderRowIndex()) return; // 非目标表头行则跳过

        // HeaderMatcher: 将表头列名与字段别名进行匹配
        headerColumnMap = HeaderMatcher.match(headMap, groups);
        // 结果示例: {"category"→0, "spec"→1, "origin"→3, "weight"→5}
    }

    @Override
    public void invoke(Map<Integer, CellData<?>> rowData, AnalysisContext context) {
        int currentRow = context.readRowHolder().getRowIndex();

        // 1. 跳过非数据行(表头之前的行)
        if (currentRow < sheetConfig.getDataStartRowIndex()) return;

        // 2. 检查是否超过数据结束行
        if (sheetConfig.getDataEndRowIndex() != null
                && currentRow > sheetConfig.getDataEndRowIndex()) return;

        // 3. 空行检测
        if (isEmptyRow(rowData)) {
            consecutiveEmptyRows++;
            if (consecutiveEmptyRows >= sheetConfig.getEmptyRowThreshold()) return;
            return; // 跳过单个空行但不终止
        }
        consecutiveEmptyRows = 0;

        // 4. 对每个数据组分别处理(一行数据可能产生多条记录)
        for (GroupConfigDto group : groups) {
            try {
                ParsedRowDto row = resolveRow(currentRow, rowData, group);
                if (row != null) {
                    parsedRows.add(row);
                }
            } catch (Exception e) {
                errors.add(buildError(currentRow, group, e));
            }
        }
    }

    /**
     * 解析一行数据中属于指定数据组的字段
     */
    private ParsedRowDto resolveRow(int rowIndex,
                                     Map<Integer, CellData<?>> rowData,
                                     GroupConfigDto group) {
        ParsedRowDto row = new ParsedRowDto();
        row.setRowIndex(rowIndex);
        row.setSheetName(sheetConfig.getSheetName());

        for (FieldMappingDto field : group.getFields()) {
            String rawValue = FieldValueResolver.resolve(
                field, rowData, headerColumnMap, mergeCellMap, fixedCellCache, rowIndex
            );

            // 数据转换(内含字符转换管道+数值提取: 系统预置→模板级→字段级规则按序执行)
            String cleanValue = DataTransformer.transform(rawValue, field.getTransformConfig(),
                charTransformPipelineCache.get(field.getFieldCode()));

            // 行间继承(规格字段: 当前行仅壁厚时继承上行前缀)
            if (field.getTransformConfig() != null && field.getTransformConfig().getRowInherit() != null) {
                cleanValue = rowInheritResolver.resolve(field.getFieldCode(), cleanValue,
                    field.getTransformConfig().getRowInherit());
            }

            // 通用字段值映射(品类/产地/材质, v1.2 从 CategoryMapper 泛化)
            if (isValueMappableField(field.getFieldCode()) && group.getFieldValueMappings() != null) {
                String qualifier = resolveQualifier(group, rowData, field.getFieldCode());
                cleanValue = FieldValueMapper.map(field.getFieldCode(), cleanValue,
                    qualifier, group.getFieldValueMappings());
            }

            // 组级固定值回退: 若字段值仍为空且组上定义了固定值, 使用固定值
            if (StringUtils.isBlank(cleanValue)) {
                cleanValue = group.getFixedValueForField(field.getFieldCode());
            }

            // 安全赋值(含类型转换异常处理)
            try {
                row.setFieldValue(field.getFieldCode(), cleanValue);
            } catch (Exception e) {
                errors.add(buildFieldError(rowIndex, field, rawValue, e));
            }
        }

        // 必填校验
        List<ExcelImportError> validationErrors = DataValidator.validate(row, group.getFields());
        if (!validationErrors.isEmpty()) {
            errors.addAll(validationErrors);
        }

        return row;
    }

    @Override
    public void extra(CellExtra extra, AnalysisContext context) {
        // 收集合并单元格信息(在第一遍读取时调用)
        if (extra.getType() == CellExtraTypeEnum.MERGE) {
            // 将合并区域所有子单元格指向首单元格的值
            // 首单元格坐标: (extra.getFirstRowIndex(), extra.getFirstColumnIndex())
            // 范围: firstRow~lastRow, firstCol~lastCol
            // 存入 mergeCellMap
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        // Sheet 解析完成, parsedRows 和 errors 已就绪
    }
}
```

---

## 九、高并发架构设计（支撑万人同时在线导入）— v1.5

### 9.0 容量目标与约束

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 并发用户 | 10,000 | 同时在线触发导入操作 |
| 峰值导入 TPS | 500~1,000 次/秒 | 提交导入请求（文件上传+任务创建） |
| 单文件上限 | 50MB / 10万行 | 超出需分片或拒绝 |
| 任务完成时延 P99 | ≤ 60s（≤5000行） / ≤ 5min（≤10万行） | 从提交到结果可查 |
| 结果保留 | 24h | 超时自动清理 |

### 9.1 整体架构

```
                                   ┌──────────────┐
                                   │   Nginx/SLB   │
                                   │  负载均衡+限流  │
                                   └──────┬───────┘
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
            ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
            │  API Server  │    │  API Server  │    │  API Server  │
            │   实例 1      │    │   实例 2      │    │   实例 N      │
            │  (Spring Boot)│    │  (Spring Boot)│    │  (Spring Boot)│
            │              │    │              │    │              │
            │ · 文件接收    │    │ · 文件接收    │    │ · 文件接收    │
            │ · 任务创建    │    │ · 任务创建    │    │ · 任务创建    │
            │ · 进度查询    │    │ · 进度查询    │    │ · 进度查询    │
            │ · 模板CRUD   │    │ · 模板CRUD   │    │ · 模板CRUD   │
            └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
                   │                   │                   │
       ┌───────────┼───────────────────┼───────────────────┼────────┐
       │           ▼                   ▼                   ▼        │
       │   ┌──────────────────────────────────────────────────┐     │
       │   │                  MinIO / OSS                      │     │
       │   │              分布式文件存储                         │     │
       │   │    (Excel 文件上传后存入, Worker 拉取处理)          │     │
       │   └──────────────────────────────────────────────────┘     │
       │                                                            │
       │   ┌──────────────────────────────────────────────────┐     │
       │   │             RocketMQ / RabbitMQ                    │     │
       │   │               消息队列                             │     │
       │   │                                                    │     │
       │   │  Topic: import-task-queue                          │     │
       │   │  ┌─────┬─────┬─────┬─────┬─────┐                 │     │
       │   │  │ msg │ msg │ msg │ msg │ ... │  ← API投递任务   │     │
       │   │  └─────┴─────┴─────┴─────┴─────┘                 │     │
       │   │                                                    │     │
       │   │  Topic: import-progress (进度事件)                  │     │
       │   │  Topic: import-result   (完成事件)                  │     │
       │   └──────────────────────────────────────────────────┘     │
       │                                                            │
       │           ▼                   ▼                   ▼        │
       │   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐│
       │   │Import Worker │    │Import Worker │    │Import Worker ││
       │   │   实例 1      │    │   实例 2      │    │   实例 M      ││
       │   │              │    │              │    │              ││
       │   │ · 消费任务    │    │ · 消费任务    │    │ · 消费任务    ││
       │   │ · Excel解析  │    │ · Excel解析  │    │ · Excel解析  ││
       │   │ · 进度上报    │    │ · 进度上报    │    │ · 进度上报    ││
       │   │ · 结果写入    │    │ · 结果写入    │    │ · 结果写入    ││
       │   └──────────────┘    └──────────────┘    └──────────────┘│
       │                                                            │
       │   ┌─────────────────────────────────────────────────────┐  │
       │   │                    Redis Cluster                     │  │
       │   │                                                      │  │
       │   │  · 任务进度 (Hash: task:{taskNo})                    │  │
       │   │  · 模板配置缓存 (String: tpl:{id})                   │  │
       │   │  · 词库缓存 (Hash: lexicon:all)                     │  │
       │   │  · 字符规则缓存 (String: charRule:{tplId})           │  │
       │   │  · 用户限流计数器 (String: rateLimit:{userId})       │  │
       │   │  · 分布式锁 (SET NX: lock:import:{supplierId})      │  │
       │   │  · WebSocket 会话路由 (Hash: ws:session:{taskNo})    │  │
       │   └─────────────────────────────────────────────────────┘  │
       │                                                            │
       │   ┌─────────────────────────────────────────────────────┐  │
       │   │               MySQL (主从集群)                        │  │
       │   │                                                      │  │
       │   │  Master ──写──→  import_async_task                   │  │
       │   │    │              import_record                       │  │
       │   │    │              import_reconciliation_detail        │  │
       │   │    ▼                                                  │  │
       │   │  Slave(s) ──读──→  import_template_*                 │  │
       │   │                   import_char_rule_preset             │  │
       │   │                   import_header_lexicon               │  │
       │   └─────────────────────────────────────────────────────┘  │
       └────────────────────────────────────────────────────────────┘
```

### 9.2 全链路异步化：从同步到"全异步"

v1.4 中异步是可选的（行数>5000才切异步）。v1.5 中**所有导入全部走异步**，同步模式仅保留为小文件的语法糖（内部仍经过队列，只是等待时间短到感知不到）。

```
用户上传 Excel
    │
    ▼
┌─────────────────────────────────────────────┐
│ API Server                                   │
│                                              │
│  1. 接收 multipart 文件流                     │
│  2. 流式转存至 MinIO (不落本地磁盘)            │
│  3. INSERT import_async_task (status=QUEUED)  │
│  4. 发送 MQ 消息 {taskNo, fileKey, templateId}│
│  5. 返回 {taskNo, async:true}                │
│                                              │
│  耗时: < 500ms (只做文件转存+MQ投递)          │
└─────────────────────────────────────────────┘
    │
    ▼  MQ 消息
┌─────────────────────────────────────────────┐
│ Import Worker (消费者)                        │
│                                              │
│  1. 消费消息, 获取 {taskNo, fileKey, tplId}   │
│  2. 从 Redis 加载模板配置(缓存)               │
│  3. 从 MinIO 下载文件流                       │
│  4. EasyExcel SAX 流式解析                    │
│  5. 每 500 行: 写进度至 Redis + 发 MQ 进度事件│
│  6. 完成: 写结果至 MySQL + 发 MQ 完成事件     │
│  7. 删除 MinIO 临时文件                       │
└─────────────────────────────────────────────┘
    │
    ▼  MQ 进度/完成事件
┌─────────────────────────────────────────────┐
│ API Server (WebSocket 推送层)                 │
│                                              │
│  1. 消费 import-progress Topic               │
│  2. 从 Redis 查询 ws:session:{taskNo}        │
│     → 找到该用户连接的 API Server 实例         │
│  3. 推送 WebSocket 消息到前端                  │
└─────────────────────────────────────────────┘
```

**关键变化**：API Server 不再执行任何 Excel 解析逻辑——只做接收、转存、投递。所有 CPU/IO 密集型工作由 Worker 独立进程承担。

### 9.3 分布式文件存储 (MinIO/OSS)

#### 为什么不用本地磁盘

| 方案 | 10000并发问题 |
|------|-------------|
| 本地磁盘 | API Server 多实例时文件在实例 A，Worker 在实例 B 无法读取 |
| NFS 共享 | IO 瓶颈，单点故障 |
| **MinIO/OSS** | 分布式、高可用、原生支持流式上传下载，API Server 和 Worker 均可访问 |

#### 文件生命周期

```
上传 → MinIO(bucket: import-files)
  ├─ key: {yyyy}/{MM}/{dd}/{taskNo}/{fileName}
  ├─ 设置 lifecycle: 24h 后自动删除
  └─ 设置 content-type: application/octet-stream

API Server:
  使用 MinIO 的 putObject(stream) 流式上传
  不将文件写入本地磁盘, 避免磁盘 IO 成为瓶颈

Worker:
  使用 MinIO 的 getObject(stream) 流式下载
  直接喂给 EasyExcel, 无需先下载到本地
```

### 9.4 消息队列削峰填谷

#### Topic 设计

| Topic | 生产者 | 消费者 | 消息量 | 说明 |
|-------|--------|--------|--------|------|
| `import-task-queue` | API Server | Import Worker | 峰值 1000/s | 导入任务消息 |
| `import-progress` | Import Worker | API Server | 峰值 10000/s | 进度更新事件(Worker→前端推送) |
| `import-result` | Import Worker | API Server + 对账服务 | 与任务量等比 | 任务完成/失败事件 |

#### import-task-queue 消息体

```json
{
    "taskNo": "task_20260323_abc123",
    "fileKey": "2026/03/23/task_20260323_abc123/唐钢库存.xlsx",
    "templateId": 101,
    "supplierId": 1001,
    "userId": "user_001",
    "priority": 1,
    "createTime": "2026-03-23T14:30:00"
}
```

#### 削峰核心机制

```
场景: 某一时刻 5000 人同时点击"导入"

API Server (N 台):
  5000 个请求 → 5000 条 MQ 消息 (耗时 < 500ms/条, 无压力)

MQ 队列:
  积压 5000 条消息 (RocketMQ 单 Topic 百万级积压无压力)

Import Worker (M 台, 如 20 台):
  每台 Worker 单线程消费(避免 Excel 解析的内存竞争)
  20 台并行消费 → 20 条/s 吞吐
  5000 条消息 → 约 250s(4分钟)消化完毕

  若需更快: 增加 Worker 实例数(弹性伸缩)
```

#### 消费者重试策略

```
消费失败(如 Excel 解析异常):
  1. 第一次重试: 延迟 5s
  2. 第二次重试: 延迟 30s
  3. 第三次重试: 延迟 120s
  4. 第三次仍失败 → 进入死信队列(DLQ)
  5. 更新 import_async_task.task_status = 3(失败)
  6. 通知用户
```

### 9.5 Redis 缓存层

#### 缓存策略一览

| Key 模式 | 类型 | TTL | 用途 | 写入时机 |
|---------|------|-----|------|---------|
| `tpl:config:{templateId}` | String(JSON) | 30min | 模板完整配置 | 模板首次使用时/修改后淘汰 |
| `tpl:charPipeline:{templateId}:{fieldCode}` | String(JSON) | 30min | 字符转换管道 | 首次构建后缓存 |
| `charRule:preset:{group}` | String(JSON) | 1h | 系统预置字符规则 | 应用启动/规则修改后淘汰 |
| `lexicon:all` | Hash | 1h | 智能识别词库 | 应用启动/词库修改后淘汰 |
| `task:progress:{taskNo}` | Hash | 25h | 任务进度 | Worker每500行写入 |
| `task:result:{taskNo}` | String(JSON) | 25h | 任务结果(小结果) | 任务完成时写入 |
| `ws:route:{taskNo}` | String | 25h | WebSocket会话路由(哪台API Server) | WS连接时写入 |
| `rateLimit:import:{userId}` | String(计数器) | 60s | 每用户每分钟导入次数限制 | 每次导入请求 |
| `lock:supplier:import:{supplierId}` | String(分布式锁) | 300s | 同一供应商串行导入锁 | 可选, 防同供应商数据冲突 |

#### 任务进度 Redis Hash 结构

```
HSET task:progress:{taskNo}
  status          "PARSING"
  percent         45
  detail          "正在解析Sheet'库存' 第2250/5000行"
  parsedRows      2250
  successRows     2230
  errorRows       3
  currentSheet    "库存"
  startedAt       "2026-03-23T14:30:05"
  updatedAt       "2026-03-23T14:30:18"
```

Worker 进度上报（替代原来的直接 UPDATE 数据库）：

```
原方案(v1.4): 每500行 → UPDATE MySQL import_async_task
问题: 10000任务并发, 每个任务平均20次UPDATE → 200,000次DB写入
瓶颈: MySQL 写入成为热点

新方案(v1.5): 每500行 → HSET Redis (微秒级, 无压力)
             任务完成时 → 一次性 UPDATE MySQL (仅1次/任务)
```

#### 模板配置缓存

```java
// 缓存加载伪代码(Worker 侧)
public ImportTemplateDto loadTemplate(Long templateId) {
    String cacheKey = "tpl:config:" + templateId;
    String json = redis.get(cacheKey);
    if (json != null) {
        return JSON.parseObject(json, ImportTemplateDto.class);
    }
    // 缓存未命中, 从 DB 加载
    ImportTemplateDto dto = templateService.loadFullConfig(templateId);
    redis.setex(cacheKey, 1800, JSON.toJSONString(dto)); // 30min
    return dto;
}

// 模板修改时淘汰缓存
public void onTemplateUpdated(Long templateId) {
    redis.del("tpl:config:" + templateId);
    // 同时淘汰该模板的字符管道缓存
    redis.del(redis.keys("tpl:charPipeline:" + templateId + ":*"));
}
```

### 9.6 Import Worker 设计

#### Worker 进程模型

```
每个 Worker 进程:
  ├─ 1 个 MQ 消费者线程 (从 import-task-queue 拉取消息)
  ├─ 1 个解析线程 (执行 EasyExcel 解析, CPU/IO密集)
  └─ 1 个进度上报线程 (定时将进度批量刷入 Redis)

设计原则:
  · 单 Worker 同一时刻只处理 1 个导入任务(避免内存竞争)
  · 通过增加 Worker 实例数实现水平扩容
  · Worker 无状态, 可随时启停
```

#### 并发容量计算

```
假设:
  · 平均文件 3000 行
  · EasyExcel 解析速度 ≈ 5000 行/秒(含业务逻辑)
  · 平均任务耗时 ≈ 1s(解析) + 0.5s(结果写入) = 1.5s/任务
  · Worker 单实例吞吐 ≈ 0.67 任务/秒

目标: 10000 个并发请求在 5 分钟内消化完毕:
  需要吞吐 = 10000 / 300s ≈ 34 任务/秒
  需要 Worker 数 = 34 / 0.67 ≈ 51 台

预留余量: 部署 60~80 个 Worker 实例
  (可使用 K8s HPA 根据队列积压数自动伸缩)
```

#### Worker 内存控制

```
每个 Worker JVM 配置:
  -Xmx512m -Xms512m  (单任务不需要大堆)

内存使用估算(单任务):
  · EasyExcel SAX 解析器: ~20MB
  · mergeCellMap (10万行): ~30MB
  · parsedRows 中间结果: ~50MB (10万行 * 每行500B)
  · 模板配置缓存: ~1MB
  · 其他开销: ~50MB
  合计: ≤ 200MB, 512MB 堆绰绰有余

大文件保护:
  · 行数超过 100,000 → 拒绝 (返回错误提示分批上传)
  · 文件大小超过 50MB → 拒绝
  · 解析过程中 parsedRows 超过 50,000 → 批量 flush 到 DB, 清空内存
```

### 9.7 WebSocket 万人推送

#### 挑战

10000 个前端同时监听导入进度，每秒可能有数千条进度更新。API Server 是多实例的，WebSocket 连接分散在不同实例上。

#### 解决方案：MQ 广播 + Redis 路由

```
用户浏览器 ←WebSocket→ API Server 实例 A
                            │
                            │ 1. 连接建立时:
                            │    HSET ws:route:{taskNo} serverId "A"
                            │
Worker 完成进度上报 ──MQ──→ import-progress Topic
                            │
                            │ 2. 所有 API Server 实例消费 MQ:
                            │    读取 ws:route:{taskNo} → "A"
                            │    若本实例 == "A" → 推送 WebSocket
                            │    若本实例 != "A" → 忽略
```

#### WebSocket 降级

```
优先: WebSocket 推送 (实时, 低延迟)
降级: SSE (Server-Sent Events, 单向推送, 兼容性更好)
兜底: HTTP 轮询 (前端每 2s 调 GET /progress, 从 Redis 读取)
```

#### 连接数压力

```
10000 WebSocket 连接 / N 台 API Server
若 N = 5 → 每台 2000 连接 (Spring WebSocket + Netty 轻松支撑)
若 N = 10 → 每台 1000 连接

Nginx 配置:
  worker_connections 65535;
  proxy_http_version 1.1;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";
```

### 9.8 数据库优化

#### 读写分离

```
写入(Master):
  · import_async_task (INSERT 1次/任务, UPDATE 1次/任务完成)
  · import_record (INSERT)
  · import_reconciliation_detail (批量INSERT)

读取(Slave):
  · import_template_* (模板配置读取, 但优先走 Redis 缓存)
  · import_char_rule_preset (走缓存)
  · import_header_lexicon (走缓存)
  · import_async_task (进度查询, 但优先走 Redis)

效果:
  写入集中在 Master, 且通过 Redis 缓存大幅减少读 Slave 的压力
  10000 并发的 DB 写入量 ≈ 10000(任务创建) + 10000(任务完成) = 20000次
  分散在 5 分钟内 ≈ 67次/秒, MySQL 轻松承受
```

#### import_async_task 表优化

```sql
-- 添加索引优化查询
ALTER TABLE `import_async_task`
  ADD KEY `idx_status_create` (`task_status`, `create_time`),
  ADD KEY `idx_user_create` (`create_by`, `create_time`);

-- 历史数据归档(定时任务, 每日凌晨)
-- 将 7 天前的已完成/已失败任务转移到 import_async_task_archive 表
-- 保持主表数据量可控
```

#### import_reconciliation_detail 分表策略

```
当对账明细量级增长到百万级:
  按 reconciliation_id 范围分表
  或按 create_time 按月分表: import_reconciliation_detail_202603

MyBatis-Plus 动态表名插件:
  DynamicTableNameInnerInterceptor
  根据查询条件自动路由到对应分表
```

### 9.9 限流与反压

#### API 层限流

```yaml
# Nginx 限流
limit_req_zone $binary_remote_addr zone=import:10m rate=10r/s;

location /api/v1/dynamic-import/preview {
    limit_req zone=import burst=20 nodelay;
}
```

```java
// Spring 侧: 基于 Redis 的用户级限流
@RateLimiter(key = "'import:' + #userId", rate = 5, interval = 60)
public Result submitImport(String userId, MultipartFile file, Long templateId) {
    // 每用户每分钟最多 5 次导入
}
```

#### MQ 消费者反压

```
Worker 消费策略:
  · prefetchCount = 1 (RabbitMQ) / pullBatchSize = 1 (RocketMQ)
  · 每次只拉取 1 条消息, 处理完才拉下一条
  · 队列积压时: Worker 自然形成反压, 消息不丢失

队列监控告警:
  · 积压超过 5000 条 → WARNING (可能需要扩容 Worker)
  · 积压超过 20000 条 → CRITICAL (自动触发 K8s HPA 扩容)
```

#### 全局熔断

```
当系统负载过高时的降级策略:
  1. API Server CPU > 80% → 拒绝新请求, 返回 429 Too Many Requests
  2. MQ 积压 > 20000 → 前端展示"系统繁忙, 请稍后重试"
  3. Redis 响应 > 100ms → 降级为直写 MySQL (进度上报)
  4. MinIO 不可用 → 回退为本地临时文件 + NFS
```

### 9.10 单任务级性能保留

以上为系统级并发设计。单个任务内部的解析性能优化仍然有效：

| 策略 | 实现方式 | 影响范围 |
|------|---------|---------|
| SAX 流式读取 | EasyExcel 默认 SAX 模式 | 单任务内存 |
| 批量 flush | 每 500 行 batch 处理 | 单任务内存 |
| 合并单元格 O(1) | HashMap 查找 | 单任务 CPU |
| 表头匹配预计算 | invokeHead 一次性计算 | 单任务 CPU |
| 固定单元格预读 | 缓存到 Map | 单任务 IO |
| 字符规则 Pipeline 缓存 | 按 (templateId, fieldCode) 缓存 | 单任务 CPU |
| 正则 Pattern 预编译 | Pattern 对象复用 | 单任务 CPU |
| 多 Sheet 并行 | CompletableFuture(库存Sheet) | 单任务吞吐 |

### 9.11 部署架构参考

#### 最小部署（开发/测试环境）

```
1 台 API Server + 1 台 Worker + 1 台 Redis + 1 台 MySQL + 1 台 MinIO
支撑: ~100 并发
```

#### 标准部署（生产环境）

```
3 台 API Server (4C8G)
10 台 Import Worker (2C4G)
3 节点 Redis Cluster
1 主 2 从 MySQL
3 节点 MinIO Cluster
3 节点 RocketMQ Cluster
支撑: ~3,000 并发
```

#### 高性能部署（万人并发）

```
5~10 台 API Server (4C8G)
60~80 台 Import Worker (2C4G, K8s HPA 自动伸缩)
6 节点 Redis Cluster (3主3从)
1 主 3 从 MySQL (SSD, 读写分离)
4 节点 MinIO Cluster (SSD)
5 节点 RocketMQ Cluster
1 台 Nginx/SLB (或云厂商 LB)
支撑: 10,000+ 并发

K8s HPA 配置:
  Worker Deployment:
    minReplicas: 10
    maxReplicas: 100
    metrics:
      - type: External
        external:
          metricName: mq_queue_depth
          targetAverageValue: 50   # 每个 Worker 积压 50 条时触发扩容
```

### 9.12 监控指标

| 指标 | 采集方式 | 告警阈值 |
|------|---------|---------|
| MQ 队列积压数 | MQ Dashboard / Prometheus | > 5000 WARNING, > 20000 CRITICAL |
| Worker 活跃数 | K8s metrics | < minReplicas CRITICAL |
| 任务平均耗时 P99 | Prometheus histogram | > 300s WARNING |
| 任务失败率 | import_async_task 统计 | > 5% WARNING |
| Redis 命中率 | Redis INFO | < 80% WARNING |
| API Server 请求延迟 P99 | Prometheus | > 2s WARNING |
| MinIO 上传延迟 P99 | MinIO metrics | > 5s WARNING |
| MySQL 主库 QPS | MySQL metrics | > 5000 WARNING |
| WebSocket 连接数 | Spring Actuator | 单实例 > 5000 WARNING |
| JVM 堆内存使用率 | JMX / Actuator | > 85% WARNING |

---

## 十、错误处理机制

### 10.1 错误分类

| 错误类型 | 示例 | 处理方式 |
|---------|------|---------|
| 表头匹配失败 | 必填字段在表头中找不到匹配列 | 中断该 Sheet 解析, 生成 Sheet 级错误 |
| 数值转换失败 | "约5吨" → BigDecimal 失败 | 记录行级错误, 继续解析后续行 |
| 必填字段为空 | 品类列为空且无默认值 | 记录行级错误, 继续解析 |
| 品类映射未命中 | 原始品类+限定词 无匹配规则 | 使用原始品类值(透传), 记录警告 |
| 价格匹配失败 | 价格记录未匹配到任何库存 | 记录到未匹配列表, 在预览中展示 |
| 文件格式错误 | 非 xlsx/xls 文件 | 立即返回, 提示文件格式错误 |

### 10.2 ExcelImportError 结构

```java
public class ExcelImportError {
    private String sheetName;       // Sheet页名称
    private Integer rowIndex;       // 行号(1开始, 对用户友好)
    private Integer colIndex;       // 列号
    private String fieldName;       // 字段中文名
    private String errorMsg;        // 错误描述
    private String rawValue;        // 原始值
    private String errorLevel;      // ERROR / WARNING
}
```

---

## 十一、典型场景解析示例

### 场景 1：单 Sheet 库存表（含合并单元格）

**Excel 原始数据**:
```
| A(品类,合并) | B(规格) | C(产地) | D(件数) | E(重量) |
|-------------|---------|---------|---------|---------|
| 槽钢(合并3行) | 10#    | 唐山    | 10      | 12.5    |
|              | 12#    | 邯郸    | 8       | 9.2     |
|              | 14#    | 唐山    | 6       | 15.0    |
| 角钢(合并2行) | 50*5   | 天津    | 12      | 20.0    |
|              | 63*6   | 唐山    | 5       | 8.5     |
```

**模板配置要点**:
- enableMergeCell = true
- category → COLUMN, columnIndex=0
- spec → COLUMN, columnIndex=1

**解析结果**: 5 行数据，品类列通过 mergeCellMap 自动填充。

### 场景 2：价格表多产地列（pivot→flat）

**Excel 原始数据**:
```
| A(品类) | B(规格) | C(唐山) | D(邯郸) | E(天津) |
|---------|---------|---------|---------|---------|
| 方管    | 50*100  | 4200    | 4150    | 4300    |
| 方管    | 60*120  | 4500    | 4450    | 4600    |
```

**模板配置要点**:
- 3 个数据组, 分别对应 C/D/E 列
- Group1: price→COLUMN(col=2), origin→COLUMN_HEADER(col=2)
- Group2: price→COLUMN(col=3), origin→COLUMN_HEADER(col=3)
- Group3: price→COLUMN(col=4), origin→COLUMN_HEADER(col=4)

**解析结果**: 2行 × 3组 = 6 条价格记录。

### 场景 3：品类派生（黑材/白材）

**Excel 原始数据**:
```
| A(品类) | B(规格) | C(黑材价格) | D(白材价格) |
|---------|---------|------------|------------|
| 方管    | 50*100  | 4200       | 5500       |
```

**模板配置要点**:
- Group1(黑材): price→col=2, category→COMPOSITE(品类列+表头"黑材")
  - CategoryMapping: 方管+黑材 → 方管
- Group2(白材): price→col=3, category→COMPOSITE(品类列+表头"白材")
  - CategoryMapping: 方管+白材 → 镀锌方管

**解析结果**:
- 记录1: category=方管, spec=50*100, price=4200
- 记录2: category=镀锌方管, spec=50*100, price=5500

### 场景 4：壁厚区间匹配

**价格表**:
```
| 品类 | 规格    | 壁厚范围  | 价格 |
|------|---------|----------|------|
| 方管 | 50*100  | 0.5-1.0  | 4200 |
| 方管 | 50*100  | 1.0-2.0  | 4100 |
```

**库存表**:
```
| 品类 | 规格       | 重量 |
|------|-----------|------|
| 方管 | 50*100*0.8 | 12.5 |  ← 壁厚0.8, 命中第1条价格
| 方管 | 50*100*1.5 | 9.2  |  ← 壁厚1.5, 命中第2条价格
```

**匹配逻辑**:
1. 从库存规格 "50*100*0.8" 中解析壁厚 = 0.8
2. 从价格壁厚范围 "0.5-1.0" 解析 min=0.5, max=1.0
3. 0.5 ≤ 0.8 ≤ 1.0 → 匹配成功, price=4200

### 场景 5：规格特殊符号转换 ★ v1.1 新增

**Excel 原始数据**（某供应商的库存表）:
```
| A(品类) | B(规格)       | C(产地) | D(重量) |
|---------|--------------|---------|---------|
| 方管    | 50×100×2.0   | 唐山    | 12.5    |
| 圆管    | Φ219×6       | 邯郸    | 9.2     |
| 圆管    | ∅76×4.0      | 天津    | 15.0    |
| 方管    | ２００＊３００   | 唐山    | 20.0    |
| 槽钢    | 10#          | 邯郸    | 8.5     |
| 角钢    | 50*5  （热轧） | 天津    | 6.0     |
```

**模板配置要点**:
- spec 字段的 charTransform:
  - usePresetGroups: ["SPEC", "COMMON"]
  - excludePresetCodes: ["HASH_SIGN"]（保留#号，因为10#是标准型号名称）
- 模板级自定义规则：无额外规则（系统预置已覆盖）

**字符转换执行过程**:

```
原始值                → Step1 trimWhitespace  → Step2 charTransform          → 最终值
─────────────────────────────────────────────────────────────────────────────────────
50×100×2.0           → 50×100×2.0            → 50*100*2.0  (×→*)           → 50*100*2.0
Φ219×6               → Φ219×6               → 219*6       (Φ删,×→*)      → 219*6
∅76×4.0              → ∅76×4.0              → 76*4.0      (∅删,×→*)      → 76*4.0
２００＊３００          → ２００＊３００         → 200*300     (全角数字→半角, ＊→*) → 200*300
10#                  → 10#                  → 10#         (#已排除)       → 10#
50*5  （热轧）        → 50*5  （热轧）        → 50*5 (热轧) (全角括号→半角, 多空格→单空格) → 50*5 (热轧)
```

**解析结果**: 6 行库存数据，规格全部归一化为标准格式。

### 场景 6：库存冷水/热水分组（钢塑管）★ v1.2 新增

**Excel 原始数据**（钢塑管库存，同一 Sheet 内两个区域）:
```
Row 1: | 品名     | 规格   | 件数  | 重量  |
Row 2: | 冷水钢塑管 |        |       |       |    ← 合并单元格标题行
Row 3: |          | 20*2.0 | 10    | 5.2   |
Row 4: |          | 25*2.5 | 8     | 4.8   |
Row 5: |          | 32*3.0 | 6     | 6.0   |
Row 6: | 热水钢塑管 |        |       |       |    ← 合并单元格标题行
Row 7: |          | 20*2.8 | 12    | 6.5   |
Row 8: |          | 25*3.5 | 9     | 5.2   |
```

**模板配置要点**:
- Sheet: contentType=1(库存), enableMergeCell=true
- **Group 1**（冷水）:
  - groupName="冷水钢塑管", fixed_category="冷水钢塑管"
  - data_start_row=2, data_end_row=4（组级行范围）
  - spec→COLUMN(col=1), package_num→COLUMN(col=2), weight→COLUMN(col=3)
- **Group 2**（热水）:
  - groupName="热水钢塑管", fixed_category="热水钢塑管"
  - data_start_row=6, data_end_row=7
  - 字段映射与 Group 1 相同

**解析结果**: 5 行库存记录，品类分别为"冷水钢塑管"(3行)和"热水钢塑管"(2行)。

### 场景 7：包装数量文本提取 ★ v1.2 新增

**Excel 原始数据**:
```
| 品类 | 规格   | 包装形式   | 件数 | 重量  |
|------|--------|----------|------|-------|
| 槽钢 | 10#    | 127支/件  | 5    | 12.5  |
| 角钢 | 50*5   | 42支/件   | 8    | 9.2   |
| 工字钢| 20#    | 约30支    | 6    | 15.0  |
```

**模板配置要点**:
- package_num 字段的 transformConfig:
```json
{
    "extractNumber": true,
    "trimWhitespace": true
}
```

**转换过程**:
```
"127支/件"  → extractNumber → "127"
"42支/件"   → extractNumber → "42"
"约30支"    → extractNumber → "30"
```

### 场景 8：规格行间继承 ★ v1.2 新增

**Excel 原始数据**（钢塑管价格表）:
```
| 规格   | 壁厚  | 单价  |
|--------|------|-------|
| 20*2.0 |      | 4200  |    ← 完整规格
| 2.5    |      | 4300  |    ← 仅壁厚, 实际为 20*2.5
| 3.0    |      | 4400  |    ← 仅壁厚, 实际为 20*3.0
| 25*2.0 |      | 4500  |    ← 新的完整规格
| 2.5    |      | 4600  |    ← 实际为 25*2.5
```

**模板配置要点**:
- spec 字段的 transformConfig:
```json
{
    "trimWhitespace": true,
    "charTransform": { "usePresetGroups": ["SPEC", "COMMON"] },
    "rowInherit": {
        "enabled": true,
        "separator": "*",
        "partialPattern": "^[\\d.]+$",
        "inheritPart": "PREFIX",
        "assembleTemplate": "${prefix}*${current}"
    }
}
```

**继承过程**:
```
原始值    partialPattern命中?   prevPrefix缓存   继承后
────────────────────────────────────────────────────
20*2.0    否(含*)              "20"             20*2.0
2.5       是(纯数字)           (不变)           20*2.5
3.0       是                   (不变)           20*3.0
25*2.0    否                   "25"             25*2.0
2.5       是                   (不变)           25*2.5
```

**解析结果**: 5 条价格记录，规格完整归一化。

### 场景 9：一行多品类多产地价格表 ★ v1.2 新增

**Excel 原始数据**:
```
| A(规格) | B(壁厚)   | C(焊管)  | D(华岐)  | E(中天)  |
|---------|----------|---------|---------|---------|
| 4分     | 1.0-4.0  | 5000    | 5500    | 5510    |
| 6分     | 1.0-4.0  | 5200    | 5700    | 5710    |
| 1寸     | 2.0-5.0  | 5500    | 6000    | 6010    |
```

> **业务含义**: "焊管"是一个品类(如 焊管)，"华岐"是品类"镀锌管"+产地"华岐"，"中天"是品类"镀锌管"+产地"中天"。

**模板配置要点**:
- Sheet: contentType=2(价格), headerRowIndex=0
- **Group 1**（焊管）:
  - groupSeq=1, groupName="焊管价格"
  - fixed_category="焊管"
  - fields:
    - spec→COLUMN(col=0), wall_thickness→COLUMN(col=1), price→COLUMN(col=2)
- **Group 2**（华岐镀锌管）:
  - groupSeq=2, groupName="华岐镀锌管价格"
  - fixed_category="镀锌管", fixed_origin="华岐"
  - fields:
    - spec→COLUMN(col=0), wall_thickness→COLUMN(col=1), price→COLUMN(col=3)
- **Group 3**（中天镀锌管）:
  - groupSeq=3, groupName="中天镀锌管价格"
  - fixed_category="镀锌管", fixed_origin="中天"
  - fields:
    - spec→COLUMN(col=0), wall_thickness→COLUMN(col=1), price→COLUMN(col=4)

**解析结果**: 3行 × 3组 = 9 条价格记录:

```
品类      规格   壁厚范围   产地    价格
─────────────────────────────────────────
焊管      4分    1.0-4.0   (空)    5000
焊管      6分    1.0-4.0   (空)    5200
焊管      1寸    2.0-5.0   (空)    5500
镀锌管    4分    1.0-4.0   华岐    5500
镀锌管    6分    1.0-4.0   华岐    5700
镀锌管    1寸    2.0-5.0   华岐    6000
镀锌管    4分    1.0-4.0   中天    5510
镀锌管    6分    1.0-4.0   中天    5710
镀锌管    1寸    2.0-5.0   中天    6010
```

### 场景 10：产地/材质表头派生 + 字段值映射 ★ v1.2 新增

**Excel 原始数据**（价格表，表头含产地和材质信息）:
```
| A(品类) | B(规格) | C(Q235B唐山) | D(Q235B邯郸) | E(Q355B唐山) |
|---------|---------|-------------|-------------|-------------|
| 槽钢    | 10#     | 4200        | 4150        | 4800        |
| 槽钢    | 12#     | 4300        | 4250        | 4900        |
```

> **业务含义**: 表头 "Q235B唐山" 同时包含材质(Q235B)和产地(唐山)信息。

**模板配置要点**:
- **Group 1** (Q235B唐山):
  - fields: category→COLUMN(0), spec→COLUMN(1), price→COLUMN(2)
  - fieldValueMappings:
    - { targetField:"material", sourceValue:null, qualifier:"Q235B唐山", targetValue:"Q235B" }
    - { targetField:"origin", sourceValue:null, qualifier:"Q235B唐山", targetValue:"唐山" }

- **Group 2** (Q235B邯郸):
  - fields: category→COLUMN(0), spec→COLUMN(1), price→COLUMN(3)
  - fieldValueMappings:
    - { targetField:"material", sourceValue:null, qualifier:"Q235B邯郸", targetValue:"Q235B" }
    - { targetField:"origin", sourceValue:null, qualifier:"Q235B邯郸", targetValue:"邯郸" }

- **Group 3** (Q355B唐山):
  - fields: category→COLUMN(0), spec→COLUMN(1), price→COLUMN(4)
  - fieldValueMappings:
    - { targetField:"material", sourceValue:null, qualifier:"Q355B唐山", targetValue:"Q355B" }
    - { targetField:"origin", sourceValue:null, qualifier:"Q355B唐山", targetValue:"唐山" }

**解析结果**: 2行 × 3组 = 6 条价格记录，每条都有独立的品类+规格+产地+材质+价格。

### 场景 11：规格附带区间后缀匹配（镀锌型材）★ v1.3 新增

**库存表**:
```
| 品类     | 规格        | 产地 | 重量  |
|---------|------------|------|-------|
| 镀锌槽钢 | 5#（10-15） | 唐山 | 12.5  |
| 镀锌槽钢 | 5#（15-20） | 唐山 | 18.0  |
| 镀锌槽钢 | 10#（5-10） | 邯郸 | 7.5   |
| 镀锌槽钢 | 10#         | 天津 | 3.0   |
```

**价格表**:
```
| 品类     | 规格        | 单价  |
|---------|------------|-------|
| 镀锌槽钢 | 5#(10以上)  | 4200  |
| 镀锌槽钢 | 5#(10以下)  | 4500  |
| 镀锌槽钢 | 10#(8-20)  | 4100  |
| 镀锌槽钢 | 10#(8+)    | 4100  |
```

**模板配置要点**:
- priceMatchRule:
```json
{
    "matchFields": ["category", "spec", "origin"],
    "specRangeMatchMode": 1,
    "specRangeConfig": {
        "rangePattern": "[（(]([^）)]+)[）)]\\s*$",
        "rangeSeparators": ["-", "~"],
        "infinityKeywords": ["以上", "+", "∞", "及以上"],
        "zeroKeywords": ["以下", "及以下"],
        "matchStrategy": "PRICE_CONTAINS_INVENTORY"
    }
}
```

**匹配过程** (策略=PRICE_CONTAINS_INVENTORY):

```
库存                    价格                   基础规格   库存区间    价格区间      匹配结果
─────────────────────────────────────────────────────────────────────────────────────────
5#（10-15）             5#(10以上)              5#=5# ✓   [10,15]    [10,+∞)      price.min(10)≤inv.min(10) ✓ inv.max(15)≤price.max(+∞) ✓ → 匹配 ✓ price=4200
5#（15-20）             5#(10以上)              5#=5# ✓   [15,20]    [10,+∞)      price.min(10)≤inv.min(15) ✓ inv.max(20)≤price.max(+∞) ✓ → 匹配 ✓ price=4200
10#（5-10）             10#(8-20)               10#=10# ✓ [5,10]     [8,20]       price.min(8)≤inv.min(5) ✗ (8>5) → 匹配失败 ✗
10#（5-10）             10#(8+)                 10#=10# ✓ [5,10]     [8,+∞)       price.min(8)≤inv.min(5) ✗ → 匹配失败 ✗
10#(无区间后缀)          10#(8-20)               10#=10# ✓ 无区间     [8,20]       库存无区间 → 仅匹配base → 匹配 ✓ price=4100
```

> **注意**: 库存 `10#（5-10）` 在 PRICE_CONTAINS_INVENTORY 策略下无法匹配 `10#(8-20)`，因为价格区间 [8,20] 不完全包含库存区间 [5,10]。若改为 `RANGE_OVERLAP` 策略则可匹配（[5,10] 与 [8,20] 有交集 [8,10]）。策略选择取决于业务规则。

**带各种特殊符号的区间表达速查**:

```
原始规格文本          rangePattern解析              基础规格   区间
───────────────────────────────────────────────────────────────
5#（10-15）           捕获"10-15"                   5#        [10, 15]
5#(10以上)            捕获"10以上"                   5#        [10, +∞)
5#(10+)               捕获"10+"                     5#        [10, +∞)
5#(10-∞)              捕获"10-∞"                    5#        [10, +∞)
5#(∞)                 捕获"∞"                       5#        [0, +∞)
5#(20以下)            捕获"20以下"                   5#        [0, 20]
5#（10～20）           捕获"10～20"(需在separators加~) 5#        [10, 20]
5#                    未命中pattern                  5#        null
50*100*2.0            未命中pattern                  50*100*2.0 null
```

---

## 十二、安全与健壮性设计

### 12.1 类型转换安全

所有从 Excel 读取的值在转换为业务类型前, 必须经过 `SafeConvertUtil`:

```java
public class SafeConvertUtil {

    public static BigDecimal toBigDecimal(String value, String fieldName, int rowIndex,
                                           List<ExcelImportError> errors) {
        if (StringUtils.isBlank(value)) return null;
        try {
            String cleaned = value.replaceAll("[^\\d.\\-]", ""); // 移除非数字字符
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            errors.add(new ExcelImportError(rowIndex, fieldName,
                "数值格式错误: '" + value + "' 无法转换为数值", value));
            return null;
        }
    }

    public static Integer toInteger(String value, String fieldName, int rowIndex,
                                     List<ExcelImportError> errors) {
        BigDecimal bd = toBigDecimal(value, fieldName, rowIndex, errors);
        return bd != null ? bd.intValue() : null;
    }
}
```

### 12.2 空指针防护

```
- 所有 Map.get() 操作使用 getOrDefault() 或前置 null 检查
- CellData 取值前检查 type, 非 STRING/NUMBER 类型特殊处理
- source_config JSON 反序列化使用 try-catch, 失败时生成明确错误
- 合并单元格查找返回 null 时回退到空字符串
```

### 12.3 上传文件安全

```
- 文件扩展名白名单: .xlsx, .xls
- 文件大小限制: 默认 20MB, 可配置
- 文件内容嗅探: 验证文件头是否为有效的 ZIP/OLE2 格式
- 临时文件自动清理: 预览完成后定时清理
```

---

## 十三、测试策略

### 13.1 单元测试范围

| 测试类 | 覆盖范围 |
|-------|---------|
| `HeaderMatcherTest` | 各种别名匹配、大小写、全半角、空格 |
| `FieldValueResolverTest` | 5种 source_type 的取值正确性 |
| `DataTransformerTest` | 去空格、去单位、数值精度、区间解析、字符转换管道集成 |
| `CharTransformerTest` | ★ 三级规则合并、sort_order排序、四种matchType、excludePresetCodes、pipeline缓存、正则异常容错、全角转半角批量、空pipeline快速路径 |
| `CharRulePresetTest` | ★ 系统预置规则覆盖率: 所有INSERT初始数据的正确性验证 |
| `FieldValueMapperTest` | 品类/产地/材质映射命中/未命中/通配/多字段联合 |
| `RowInheritResolverTest` | 完整规格/部分值/连续部分值/前缀切换/空值/首行即部分值 |
| `SpecRangeParserTest` | ★ v1.3 括号解析、各种区间表达(以上/以下/+/∞/数字-数字)、无区间规格、全半角括号混用、解析失败容错 |
| `PriceMatcherTest` | 精确匹配、壁厚区间匹配、★ v1.3 规格区间匹配(四种策略)、库存无区间回退、base规格不等短路 |
| `MergeCellCollectorTest` | 合并区域填充、边界条件 |
| `SafeConvertUtilTest` | 各种异常字符串的安全转换 |
| `DynamicExcelParserTest` | 完整的端到端解析测试(含样本 Excel) |

### 13.2 前端测试页面

提供一个完整的测试页面, 包含:

1. **模板管理页**: 列表查询 + 新增 + 编辑 + 删除
2. **模板设计器**: 上传 Excel + 可视化配置 + 保存
3. **导入测试页**: 选择模板 + 上传 Excel + 查看预览结果 + 查看错误列表

---

## 十四、技术风险与应对

| 风险 | 影响 | 应对方案 |
|------|------|---------|
| FortuneSheet/Luckysheet 与 Vue3 兼容性 | 前端 Excel 预览无法渲染 | 备选: 自研 `<table>` 简化渲染, 仅需只读+选择功能 |
| EasyExcel 合并单元格读取顺序 | CellExtra 在数据行之后才回调 | 采用两遍读取: 第一遍收集合并信息, 第二遍解析数据 |
| 超大文件（>10万行） | 内存占用过高 | 批量flush + 限制单文件最大行数 |
| source_config JSON 结构变更 | 历史模板不兼容 | JSON 中增加 version 字段, 解析时做版本兼容 |
| 品类映射规则过于复杂 | 运营难以配置 | 提供"规则测试"功能: 输入样本值, 实时显示映射结果 |
| 正则表达式书写错误 | 字符转换规则报错或死循环(ReDOS) | 后端正则预编译时 try-catch + 超时保护; 前端提供下拉预设降低手写正则频率 |
| 字符转换规则冲突/顺序问题 | 先替换的字符影响后续规则匹配 | 严格按 sort_order 执行 + 测试面板实时显示每步中间结果 |
| 新出现的特殊符号未覆盖 | 导入后规格不标准, 影响价格匹配 | 系统预置规则可由管理员在线新增, 无需发版 |
| 区间后缀表达多样性 | 新供应商使用未覆盖的区间关键词 | infinityKeywords/zeroKeywords 可配置扩展; 解析失败时回退精确匹配并记录 WARNING |
| 区间匹配策略选择困难 | 不同品类可能需要不同匹配策略 | 支持 4 种策略可选, 可在模板/Sheet 级分别配置 |

---

## 十五、扩展功能详细设计 — v1.4

---

### 15.1 智能表头识别

#### 15.1.1 业务目标

运营人员在模板设计器中上传一个全新的 Excel 时，系统自动识别：
1. **表头行位置**（哪一行是真正的列标题行）
2. **列含义推断**（哪一列是"规格"、哪一列是"产地"等）
3. **内容类型推断**（当前 Sheet 是库存还是价格）

运营人员可在推荐结果的基础上微调，而非从零配置。

#### 15.1.2 数据库设计

```sql
-- ============================================================
-- 表头识别词库表(可在线维护, 作为识别的知识库)
-- ============================================================
CREATE TABLE `import_header_lexicon` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `field_code`    VARCHAR(64)     NOT NULL                 COMMENT '业务字段编码: category/spec/origin/...',
    `keyword`       VARCHAR(128)    NOT NULL                 COMMENT '关键词(如: 规格/型号/SIZE)',
    `weight`        DECIMAL(5,2)    NOT NULL DEFAULT 1.00    COMMENT '权重(0-10, 越高越可信)',
    `match_mode`    VARCHAR(32)     NOT NULL DEFAULT 'EXACT' COMMENT '匹配模式: EXACT-精确/CONTAINS-包含/REGEX-正则',
    `enabled`       TINYINT         NOT NULL DEFAULT 1,
    `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_field_code` (`field_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表头识别词库表';

-- 初始化数据示例
INSERT INTO `import_header_lexicon` (`field_code`, `keyword`, `weight`, `match_mode`) VALUES
('category',    '品类',      10,  'EXACT'),
('category',    '品名',      9,   'EXACT'),
('category',    '产品名称',   9,   'EXACT'),
('category',    '钢种',      7,   'EXACT'),
('category',    '品种',      8,   'EXACT'),
('spec',        '规格',      10,  'EXACT'),
('spec',        '型号',      9,   'EXACT'),
('spec',        '规格型号',   10,  'EXACT'),
('spec',        'SIZE',      6,   'EXACT'),
('spec',        '尺寸',      7,   'EXACT'),
('origin',      '产地',      10,  'EXACT'),
('origin',      '厂家',      8,   'EXACT'),
('origin',      '钢厂',      8,   'EXACT'),
('origin',      '生产厂家',   9,   'EXACT'),
('material',    '材质',      10,  'EXACT'),
('material',    '钢号',      7,   'EXACT'),
('material',    '牌号',      7,   'EXACT'),
('package_num', '包装数量',   10,  'EXACT'),
('package_num', '件数',      8,   'EXACT'),
('package_num', '支/件',     8,   'CONTAINS'),
('package_num', '包装形式',   7,   'EXACT'),
('weight',      '重量',      10,  'EXACT'),
('weight',      '吨位',      8,   'EXACT'),
('weight',      '数量(吨)',   9,   'CONTAINS'),
('weight',      '过磅重量',   8,   'EXACT'),
('price',       '单价',      10,  'EXACT'),
('price',       '价格',      9,   'EXACT'),
('price',       '含税价',    8,   'EXACT'),
('price',       '报价',      8,   'EXACT'),
('remark',      '备注',      10,  'EXACT'),
('remark',      '说明',      7,   'EXACT');
```

#### 15.1.3 后端架构

```
com.eiss.erp.defineimport.intellect
├── HeaderRecognizer.java              // 表头识别器(总入口)
├── HeaderRowDetector.java             // 表头行定位器
├── ColumnMeaningInferrer.java         // 列含义推断器
├── ContentTypeInferrer.java           // 内容类型推断器(库存/价格)
└── RecognitionResult.java             // 识别结果 DTO
```

#### 15.1.4 核心算法

##### 表头行定位 (HeaderRowDetector)

```
输入: Sheet 的前 N 行数据(默认 N=10)
输出: 最可能的表头行索引

算法:
  对每行 row[i] 计算"表头得分" score[i]:

  1. 文本占比得分 (0-30分):
     textRatio = 非空文本单元格数 / 总非空单元格数
     若 textRatio > 0.7 → +30

  2. 关键词命中得分 (0-50分):
     hitCount = row[i] 中命中 import_header_lexicon 词库的单元格数
     score += hitCount * (50 / 总列数)

  3. 下一行数值占比得分 (0-20分):
     若 row[i+1] 中数值型单元格占比 > 0.3 → +20
     (表头下一行通常是数据, 含有数值)

  4. 惩罚项:
     若 row[i] 仅有 1-2 个非空格(可能是提示文案) → -20
     若 row[i] 是合并单元格且只有一个值 → -15

  取 score 最高的行作为表头行
  若最高 score < 30 → 返回 null(无法识别, 需人工指定)
```

##### 列含义推断 (ColumnMeaningInferrer)

```
输入: 表头行的所有单元格文本
输出: Map<Integer colIndex, FieldSuggestion>

算法:
  对每个非空表头单元格 header[j]:
    1. 在 import_header_lexicon 中查找匹配:
       - EXACT: header[j].trim() == keyword
       - CONTAINS: header[j].contains(keyword)
       - REGEX: Pattern.matches(keyword, header[j])
    2. 收集所有命中的 (field_code, weight) 对
    3. 按 weight 降序, 取 top-1 作为推荐字段
    4. 若同一 field_code 被多列命中, 取 weight 最高的列

  输出 FieldSuggestion:
    { colIndex, fieldCode, fieldName, confidence(0-100), matchedKeyword }
```

##### 内容类型推断 (ContentTypeInferrer)

```
算法:
  1. Sheet 名称包含"价格"/"报价"/"price" → PRICE (置信度 90%)
  2. 表头列中存在"单价"/"价格"/"报价"关键词:
     a. 若同时存在"重量"/"件数" → INVENTORY (库存表中也可能有单价列)
     b. 若不存在"重量"/"件数" → PRICE (置信度 70%)
  3. 表头列中存在"重量"/"件数"/"包装" → INVENTORY (置信度 80%)
  4. 无法判断 → UNKNOWN, 需用户选择
```

#### 15.1.5 API 接口

```
POST /api/v1/intellect/recognize
  Request: { fileId, sheetIndex }
  Response: {
    headerRowIndex: 2,
    headerConfidence: 85,
    contentType: "INVENTORY",
    contentTypeConfidence: 90,
    columns: [
      { colIndex: 0, fieldCode: "category", fieldName: "品类", confidence: 95, matchedKeyword: "品名" },
      { colIndex: 1, fieldCode: "spec", fieldName: "规格", confidence: 100, matchedKeyword: "规格" },
      { colIndex: 2, fieldCode: "origin", fieldName: "产地", confidence: 90, matchedKeyword: "钢厂" },
      { colIndex: 5, fieldCode: null, fieldName: null, confidence: 0, headerText: "编号" }
    ]
  }
```

#### 15.1.6 前端交互

```
1. 用户在模板设计器上传 Excel, 选择某 Sheet
2. 系统自动调用 /recognize 接口
3. 右侧配置面板弹出「智能识别结果」卡片:
   ┌──────────────────────────────────────┐
   │ 🔍 智能识别结果          [应用] [忽略] │
   │                                      │
   │ 表头行: 第 3 行 (置信度 85%)          │
   │ 类型:   库存 (置信度 90%)             │
   │                                      │
   │ 列映射推荐:                           │
   │  A列 "品名"     → 品类 (95%) ☑       │
   │  B列 "规格"     → 规格 (100%) ☑      │
   │  C列 "钢厂"     → 产地 (90%) ☑       │
   │  D列 "材质"     → 材质 (85%) ☑       │
   │  E列 "件数"     → 包装数量 (80%) ☑   │
   │  F列 "编号"     → ？(未识别) ☐       │
   │                                      │
   │ 可逐项勾选/取消, 点击[应用]批量填充    │
   └──────────────────────────────────────┘
4. 用户点击 [应用] → 自动填充 headerRowIndex + 各字段映射
5. 用户可在此基础上微调
```

#### 15.1.7 学习闭环

```
每次用户保存模板时, 记录实际使用的表头关键词:
  1. 若词库中已有该关键词 → weight += 0.1 (最高不超过 10)
  2. 若词库中没有 → 自动新增一条 (weight=5.0)
  3. 若用户拒绝了推荐 → 对应 keyword 的 weight -= 0.2 (最低不低于 0)

长期运行后, 词库会越来越准确。
```

---

### 15.2 模板自动推荐

#### 15.2.1 业务目标

用户上传 Excel 后，系统自动从所有已保存的模板中找出"最可能适用"的模板，按匹配度排序推荐。用户可一键选用而非手动翻找。

#### 15.2.2 数据库设计

```sql
-- ============================================================
-- 模板指纹表(用于快速匹配, 每次模板保存时生成)
-- ============================================================
CREATE TABLE `import_template_fingerprint` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_count`       INT             NOT NULL DEFAULT 1       COMMENT 'Sheet页数量',
    `sheet_names_hash`  VARCHAR(64)     DEFAULT NULL             COMMENT 'Sheet页名称组合的哈希值',
    `header_keywords`   JSON            NOT NULL                 COMMENT '所有Sheet表头关键词集合(去重)',
    `column_count_sig`  VARCHAR(256)    DEFAULT NULL             COMMENT '各Sheet列数签名 如"12,8,5"',
    `content_type_sig`  VARCHAR(64)     DEFAULT NULL             COMMENT '内容类型签名 如"INV,PRICE"',
    `supplier_id`       BIGINT          DEFAULT NULL             COMMENT '供应商ID(优先匹配同供应商)',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_template_id` (`template_id`),
    KEY `idx_supplier_id` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板指纹表';
```

#### 15.2.3 核心算法

```
输入: 上传的 Excel 文件 + 可选的 supplierId
输出: List<TemplateMatchResult> 按 score 降序排列

算法:
  1. 提取上传文件的特征:
     a. sheetCount = Sheet 页数量
     b. sheetNames = 各 Sheet 名称列表
     c. headerKeywords = 对每个 Sheet 读取前 10 行, 提取文本关键词集合
     d. columnCounts = 各 Sheet 列数
     e. contentTypeGuess = 按 Sheet 名推断内容类型

  2. 从 import_template_fingerprint 表加载所有启用模板的指纹

  3. 对每个模板指纹计算匹配得分 (0-100):

     a. 供应商匹配 (0-25分):
        若 supplierId 相同 → +25
        若 supplierId 不同但非空 → +0
        若模板未绑定供应商 → +5

     b. Sheet 结构匹配 (0-25分):
        sheetCount 相同 → +10
        sheetNames Jaccard 相似度 * 15

     c. 表头关键词匹配 (0-35分):
        keyword Jaccard 相似度 = |交集| / |并集|
        score += similarity * 35

     d. 列数匹配 (0-15分):
        对各 Sheet 列数差异计算:
        deviation = avg(|upload.colCount[i] - tpl.colCount[i]|)
        若 deviation == 0 → +15
        若 deviation <= 2 → +10
        若 deviation <= 5 → +5

  4. 按 score 降序排列, 取 top-5 返回
  5. 若 top-1 的 score < 30 → 标记为"无高置信推荐"
```

#### 15.2.4 API 接口

```
POST /api/v1/template-recommend
  Request: multipart/form-data { file, supplierId? }
  Response: {
    recommendations: [
      {
        templateId: 101,
        templateCode: "TPL_TANGSHAN_001",
        templateName: "唐钢库存+价格模板",
        supplierName: "唐山钢铁",
        matchScore: 92,
        matchDetails: {
          supplierMatch: true,
          sheetCountMatch: true,
          keywordSimilarity: 0.85,
          columnDeviation: 1
        }
      },
      { templateId: 203, matchScore: 67, ... },
      { templateId: 305, matchScore: 45, ... }
    ],
    bestMatch: { templateId: 101, matchScore: 92, confidence: "HIGH" }
  }
```

#### 15.2.5 前端交互

```
用户在「导入页面」上传 Excel 后:

┌───────────────────────────────────────────────────┐
│  📎 已上传: 唐钢2026年3月库存.xlsx                   │
│                                                    │
│  系统推荐以下模板:                                   │
│  ┌─────────────────────────────────────────────┐   │
│  │ ★ 唐钢库存+价格模板        匹配度: 92%  [选用] │   │
│  │   供应商: 唐山钢铁 | Sheet结构一致 | 表头高度吻合│   │
│  ├─────────────────────────────────────────────┤   │
│  │   邯钢库存模板              匹配度: 67%  [选用] │   │
│  ├─────────────────────────────────────────────┤   │
│  │   通用库存模板              匹配度: 45%  [选用] │   │
│  └─────────────────────────────────────────────┘   │
│                                                    │
│  或 [手动选择模板 ▼]                                │
└───────────────────────────────────────────────────┘
```

---

### 15.3 模板版本管理

#### 15.3.1 业务目标

同一供应商的 Excel 格式可能随时间变化。运营修改模板后，若新格式有误需要回滚到历史版本。需要支持：
1. 模板的每次保存自动生成版本快照
2. 查看版本历史与差异
3. 回滚到指定版本
4. 标记当前生效版本

#### 15.3.2 数据库设计

```sql
-- ============================================================
-- 模板版本快照表
-- ============================================================
CREATE TABLE `import_template_version` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `version_no`        INT             NOT NULL                 COMMENT '版本号(从1递增)',
    `version_tag`       VARCHAR(64)     DEFAULT NULL             COMMENT '版本标签(如"v2-新增壁厚列")',
    `snapshot_data`     LONGTEXT        NOT NULL                 COMMENT '模板完整配置快照(JSON, 含所有子表数据)',
    `change_summary`    VARCHAR(512)    DEFAULT NULL             COMMENT '变更摘要(自动生成或用户填写)',
    `is_current`        TINYINT         NOT NULL DEFAULT 0       COMMENT '是否为当前生效版本 0-否 1-是',
    `create_by`         VARCHAR(64)     DEFAULT NULL             COMMENT '保存人',
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '保存时间',
    PRIMARY KEY (`id`),
    KEY `idx_template_id` (`template_id`),
    UNIQUE KEY `uk_template_version` (`template_id`, `version_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模板版本快照表';
```

#### 15.3.3 snapshot_data JSON 结构

```json
{
    "template": { /* import_template 主表全部字段 */ },
    "sheets": [
        {
            "sheet": { /* import_template_sheet 全部字段 */ },
            "groups": [
                {
                    "group": { /* import_template_group 全部字段 */ },
                    "fields": [ /* import_template_field 列表 */ ],
                    "fieldValueMappings": [ /* import_template_field_value_mapping 列表 */ ]
                }
            ],
            "priceMatchRule": { /* import_template_price_match_rule */ }
        }
    ],
    "charRules": [ /* import_template_char_rule 列表 */ ]
}
```

#### 15.3.4 核心流程

```
保存模板时自动创建版本:
  1. 将当前模板配置(主表+所有子表)序列化为 snapshot_data JSON
  2. 计算 version_no = 当前最大版本号 + 1
  3. 自动生成 change_summary:
     - 对比当前快照与上一版快照的 JSON Diff
     - 提取关键变更: "新增Sheet'价格表', 修改spec字段映射, 新增2条品类映射"
  4. 将旧版本的 is_current 置为 0, 新版本 is_current = 1
  5. INSERT import_template_version

回滚到指定版本:
  1. 读取目标版本的 snapshot_data JSON
  2. 反序列化为各实体对象
  3. 在事务中:
     a. 删除当前模板的所有子表数据(sheet/group/field/mapping/charRule)
     b. 从快照重建所有子表数据(分配新的主键ID)
     c. 更新主表字段
     d. 创建一个新版本(version_tag="回滚至v{N}")
     e. 更新 is_current 标记
```

#### 15.3.5 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/import-template/{id}/versions` | 获取模板的版本历史列表 |
| GET | `/api/v1/import-template/{id}/version/{versionNo}` | 获取指定版本详情(含完整快照) |
| GET | `/api/v1/import-template/{id}/version-diff?from={v1}&to={v2}` | 比较两个版本的差异 |
| POST | `/api/v1/import-template/{id}/rollback/{versionNo}` | 回滚到指定版本 |

#### 15.3.6 版本差异 (Diff) 输出

```json
{
    "fromVersion": 2,
    "toVersion": 3,
    "changes": [
        {
            "path": "sheets[0].groups[0].fields[2]",
            "type": "MODIFIED",
            "fieldName": "产地",
            "detail": "sourceType: COLUMN → FIXED_VALUE"
        },
        {
            "path": "sheets[1]",
            "type": "ADDED",
            "detail": "新增Sheet '价格表' (contentType=PRICE)"
        },
        {
            "path": "sheets[0].groups[0].fieldValueMappings[3]",
            "type": "REMOVED",
            "detail": "删除品类映射: 方管+黑材→方管"
        }
    ]
}
```

#### 15.3.7 前端交互

```
模板编辑页右上角: [版本历史 🕐]

点击后弹出侧滑面板:

┌──────────────────────────────────────────┐
│  版本历史                    [关闭]       │
│                                          │
│  v5 (当前) 2026-03-23 14:30   张三       │
│  ├ 修改spec字段映射, 新增壁厚列           │
│  │                         [查看] [对比]  │
│  │                                       │
│  v4  2026-03-20 10:15   李四             │
│  ├ 新增Sheet'价格表'                     │
│  │                  [查看] [对比] [回滚]  │
│  │                                       │
│  v3  2026-03-15 09:00   张三             │
│  ├ 新增2条品类映射规则                    │
│  │                  [查看] [对比] [回滚]  │
│  │                                       │
│  v2  2026-03-10 16:45   张三             │
│  ├ 初始版本                              │
│  │                  [查看] [对比] [回滚]  │
│  │                                       │
│  v1  2026-03-01 11:00   张三             │
│  ├ 创建模板                              │
│                            [查看]         │
└──────────────────────────────────────────┘

[对比] → 弹出左右对比视图, 高亮变更字段
[回滚] → 二次确认后执行回滚, 自动创建 v6 (回滚至v4)
```

---

### 15.4 异步导入

> **注**：v1.5 已将异步导入升级为「全链路异步 + MQ 削峰 + Worker 集群」架构，详见**第九章**。本节保留原始设计作为单机部署的简化方案参考。

#### 15.4.1 业务目标

当 Excel 文件行数超过阈值（如 5000 行）时，同步解析会导致 HTTP 超时。需要：
1. 大文件自动切换为异步模式
2. 后台队列处理，进度实时推送至前端
3. 处理完成后通知用户查看结果

#### 15.4.2 数据库设计

```sql
-- ============================================================
-- 异步导入任务表
-- ============================================================
CREATE TABLE `import_async_task` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `task_no`           VARCHAR(64)     NOT NULL                 COMMENT '任务编号(UUID)',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `supplier_id`       BIGINT          DEFAULT NULL             COMMENT '供应商ID',
    `file_name`         VARCHAR(256)    NOT NULL                 COMMENT '原始文件名',
    `file_path`         VARCHAR(512)    NOT NULL                 COMMENT '服务端文件路径',
    `file_size`         BIGINT          NOT NULL DEFAULT 0       COMMENT '文件大小(字节)',
    `estimated_rows`    INT             DEFAULT NULL             COMMENT '预估总行数',
    `task_status`       TINYINT         NOT NULL DEFAULT 0       COMMENT '任务状态 0-排队中 1-解析中 2-已完成 3-失败 4-已取消',
    `progress_percent`  INT             NOT NULL DEFAULT 0       COMMENT '进度百分比 0-100',
    `progress_detail`   VARCHAR(256)    DEFAULT NULL             COMMENT '进度描述(如: 正在解析Sheet"库存" 第1200/5000行)',
    `current_sheet`     VARCHAR(128)    DEFAULT NULL             COMMENT '当前正在处理的Sheet名称',
    `parsed_rows`       INT             NOT NULL DEFAULT 0       COMMENT '已解析行数',
    `success_rows`      INT             NOT NULL DEFAULT 0       COMMENT '成功行数',
    `error_rows`        INT             NOT NULL DEFAULT 0       COMMENT '错误行数',
    `result_data`       LONGTEXT        DEFAULT NULL             COMMENT '解析结果(JSON, 同步模式的preview响应)',
    `error_message`     VARCHAR(512)    DEFAULT NULL             COMMENT '失败原因(任务级错误)',
    `started_at`        DATETIME        DEFAULT NULL             COMMENT '开始处理时间',
    `completed_at`      DATETIME        DEFAULT NULL             COMMENT '完成时间',
    `create_by`         VARCHAR(64)     DEFAULT NULL,
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_template_id` (`template_id`),
    KEY `idx_task_status` (`task_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步导入任务表';
```

#### 15.4.3 后端架构

```
com.eiss.erp.defineimport.async
├── AsyncImportService.java             // 异步导入服务
├── ImportTaskExecutor.java             // 任务执行器(线程池)
├── ImportProgressReporter.java         // 进度上报器(写DB + 推WebSocket)
└── ImportTaskCleanupJob.java           // 定时清理过期任务
```

#### 15.4.4 核心流程

```
                         ┌─────────────┐
                         │ 用户上传文件  │
                         └──────┬──────┘
                                │
                                ▼
                     ┌────────────────────┐
                     │ 预估行数 > 阈值(5000)?│
                     └─────┬─────┬────────┘
                      否   │     │  是
                           ▼     ▼
              ┌──────────────┐  ┌──────────────────────┐
              │ 同步模式      │  │ 异步模式              │
              │ (现有流程)    │  │ 1. 保存文件至服务端    │
              │ 直接返回结果  │  │ 2. 创建 async_task    │
              └──────────────┘  │ 3. 返回 taskNo        │
                                │ 4. 提交至线程池        │
                                └──────────┬───────────┘
                                           │
                                           ▼
                                ┌────────────────────┐
                                │ ImportTaskExecutor  │
                                │ (后台线程)           │
                                │                    │
                                │ 1. 加载模板配置     │
                                │ 2. 逐Sheet解析      │
                                │ 3. 每500行上报进度   │─── WebSocket ──→ 前端进度条
                                │ 4. 完成后写结果      │
                                │ 5. 更新 task_status  │
                                └────────────────────┘

进度上报 (ImportProgressReporter):
  每处理 500 行调用一次:
    1. UPDATE import_async_task SET
         progress_percent = (parsedRows / estimatedRows) * 100,
         progress_detail = '正在解析Sheet"库存" 第{n}/{total}行',
         parsed_rows = n
    2. 通过 WebSocket 推送进度消息给前端:
       { taskNo, percent: 45, detail: "正在解析Sheet'库存' 第2250/5000行" }
```

#### 15.4.5 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/dynamic-import/preview` | 同步/异步自动切换（行数小于阈值走同步，否则返回 taskNo） |
| GET | `/api/v1/async-task/{taskNo}/progress` | 轮询进度（WebSocket 不可用时的降级方案） |
| GET | `/api/v1/async-task/{taskNo}/result` | 获取任务结果 |
| POST | `/api/v1/async-task/{taskNo}/cancel` | 取消任务 |
| WebSocket | `/ws/import-progress/{taskNo}` | 进度实时推送 |

#### 15.4.6 异步响应示例

```json
// POST /api/v1/dynamic-import/preview 返回(异步模式)
{
    "code": 200,
    "data": {
        "async": true,
        "taskNo": "task_20260323_abc123",
        "estimatedRows": 12000,
        "message": "文件行数较多, 已进入后台处理队列, 请通过进度页面查看结果",
        "wsUrl": "/ws/import-progress/task_20260323_abc123"
    }
}
```

```json
// WebSocket 推送消息
{ "taskNo": "task_20260323_abc123", "percent": 45, "status": "PARSING",
  "detail": "正在解析Sheet'库存' 第2250/5000行", "parsedRows": 2250, "errorRows": 3 }

{ "taskNo": "task_20260323_abc123", "percent": 100, "status": "COMPLETED",
  "detail": "解析完成", "successRows": 4980, "errorRows": 20 }
```

#### 15.4.7 前端交互

```
上传大文件后自动进入异步等待页面:

┌──────────────────────────────────────────────┐
│  📄 唐钢2026年3月库存.xlsx                     │
│  模板: 唐钢库存+价格模板                       │
│                                              │
│  ████████████████░░░░░░░░  45%               │
│                                              │
│  正在解析 Sheet"库存" 第 2,250 / 5,000 行      │
│  已成功: 2,230    错误: 3                     │
│                                              │
│  预计剩余时间: 约 15 秒                        │
│                                              │
│                              [取消]           │
└──────────────────────────────────────────────┘

完成后自动跳转至预览结果页(与同步模式相同的 PreviewTable)
```

#### 15.4.8 配置参数

```yaml
import:
  async:
    threshold: 5000           # 超过此行数自动切换异步
    thread-pool-size: 4       # 并发处理任务数
    progress-interval: 500    # 每N行上报一次进度
    task-expire-hours: 24     # 任务结果保留时长
    max-file-size-mb: 50      # 异步模式最大文件限制
```

---

### 15.5 数据对账

#### 15.5.1 业务目标

每次导入完成后，自动与上一次同供应商/同品类的导入数据进行对比，发现：
1. **价格异动**：哪些规格的价格涨了/跌了/持平
2. **库存变动**：新增/下架/数量变化的规格
3. **异常告警**：价格波动超过阈值时自动告警

#### 15.5.2 数据库设计

```sql
-- ============================================================
-- 对账记录表
-- ============================================================
CREATE TABLE `import_reconciliation` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `current_record_id`     BIGINT          NOT NULL                 COMMENT '本次导入记录ID',
    `previous_record_id`    BIGINT          DEFAULT NULL             COMMENT '上次导入记录ID',
    `supplier_id`           BIGINT          DEFAULT NULL             COMMENT '供应商ID',
    `compare_time`          DATETIME        NOT NULL                 COMMENT '对比时间',
    `total_items`           INT             NOT NULL DEFAULT 0       COMMENT '本次导入总条目数',
    `unchanged_items`       INT             NOT NULL DEFAULT 0       COMMENT '无变化条目数',
    `price_up_items`        INT             NOT NULL DEFAULT 0       COMMENT '价格上涨条目数',
    `price_down_items`      INT             NOT NULL DEFAULT 0       COMMENT '价格下降条目数',
    `new_items`             INT             NOT NULL DEFAULT 0       COMMENT '新增条目数',
    `removed_items`         INT             NOT NULL DEFAULT 0       COMMENT '下架条目数(上次有本次无)',
    `alert_items`           INT             NOT NULL DEFAULT 0       COMMENT '告警条目数(超阈值)',
    `create_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_current_record` (`current_record_id`),
    KEY `idx_supplier_id` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账记录表';

-- ============================================================
-- 对账明细表
-- ============================================================
CREATE TABLE `import_reconciliation_detail` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `reconciliation_id`     BIGINT          NOT NULL                 COMMENT '对账记录ID',
    `category`              VARCHAR(128)    DEFAULT NULL             COMMENT '品类',
    `spec`                  VARCHAR(128)    DEFAULT NULL             COMMENT '规格',
    `origin`                VARCHAR(128)    DEFAULT NULL             COMMENT '产地',
    `material`              VARCHAR(128)    DEFAULT NULL             COMMENT '材质',
    `change_type`           VARCHAR(32)     NOT NULL                 COMMENT 'UNCHANGED/PRICE_UP/PRICE_DOWN/NEW/REMOVED/QUANTITY_CHANGE',
    `prev_price`            DECIMAL(12,2)   DEFAULT NULL             COMMENT '上次价格',
    `curr_price`            DECIMAL(12,2)   DEFAULT NULL             COMMENT '本次价格',
    `price_diff`            DECIMAL(12,2)   DEFAULT NULL             COMMENT '价格差值(curr - prev)',
    `price_diff_percent`    DECIMAL(8,4)    DEFAULT NULL             COMMENT '价格变化百分比',
    `prev_weight`           DECIMAL(12,3)   DEFAULT NULL             COMMENT '上次重量/库存量',
    `curr_weight`           DECIMAL(12,3)   DEFAULT NULL             COMMENT '本次重量/库存量',
    `is_alert`              TINYINT         NOT NULL DEFAULT 0       COMMENT '是否告警 0-否 1-是',
    `alert_reason`          VARCHAR(256)    DEFAULT NULL             COMMENT '告警原因',
    `create_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_reconciliation_id` (`reconciliation_id`),
    KEY `idx_change_type` (`change_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账明细表';

-- ============================================================
-- 对账告警规则表
-- ============================================================
CREATE TABLE `import_reconciliation_alert_rule` (
    `id`                    BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `rule_name`             VARCHAR(128)    NOT NULL                 COMMENT '规则名称',
    `supplier_id`           BIGINT          DEFAULT NULL             COMMENT '供应商ID(null=全局)',
    `category_pattern`      VARCHAR(128)    DEFAULT NULL             COMMENT '品类匹配(null=全部, 支持通配符)',
    `alert_type`            VARCHAR(32)     NOT NULL                 COMMENT 'PRICE_UP/PRICE_DOWN/PRICE_CHANGE/NEW/REMOVED',
    `threshold_percent`     DECIMAL(8,4)    DEFAULT NULL             COMMENT '百分比阈值(如5.0000=5%)',
    `threshold_amount`      DECIMAL(12,2)   DEFAULT NULL             COMMENT '金额阈值(如200.00=涨跌超200元)',
    `notify_mode`           VARCHAR(64)     NOT NULL DEFAULT 'SYSTEM' COMMENT '通知方式: SYSTEM-系统消息/EMAIL/WEBHOOK',
    `notify_target`         VARCHAR(512)    DEFAULT NULL             COMMENT '通知目标(邮箱/webhook地址)',
    `enabled`               TINYINT         NOT NULL DEFAULT 1,
    `create_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`               TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_supplier_id` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账告警规则表';
```

#### 15.5.3 后端架构

```
com.eiss.erp.defineimport.reconciliation
├── ReconciliationService.java          // 对账服务(总入口)
├── ReconciliationEngine.java           // 对账引擎(数据对比核心)
├── AlertRuleEvaluator.java             // 告警规则评估器
├── AlertNotifier.java                  // 告警通知发送器
└── ReconciliationReportGenerator.java  // 对账报告生成器
```

#### 15.5.4 核心算法

```
输入: currentImportRecordId
输出: ReconciliationResult

算法:
  1. 查找"上一次"的导入记录:
     SELECT * FROM import_record
     WHERE supplier_id = ? AND import_status = 1 AND id < currentId
     ORDER BY create_time DESC LIMIT 1

  2. 若无上次记录 → 所有条目标记为 NEW, 无对比基准

  3. 构建匹配键:
     a. 本次数据: Map<matchKey, currentRow>
        matchKey = category + "|" + spec + "|" + origin + "|" + material
     b. 上次数据: Map<matchKey, previousRow>

  4. 遍历本次数据:
     对每条 currentRow:
       a. 在上次数据中查找相同 matchKey
       b. 若未找到 → change_type = NEW
       c. 若找到:
          - price_diff = curr_price - prev_price
          - 若 diff == 0 → UNCHANGED
          - 若 diff > 0  → PRICE_UP
          - 若 diff < 0  → PRICE_DOWN
          - 同时计算 weight 变化 → QUANTITY_CHANGE

  5. 遍历上次数据中未被本次匹配的条目:
     → change_type = REMOVED

  6. 对每条变更记录评估告警规则:
     a. 从 alert_rule 表加载规则(按 supplier_id + category_pattern 匹配)
     b. 检查 threshold_percent: |price_diff_percent| > threshold?
     c. 检查 threshold_amount: |price_diff| > threshold?
     d. 若触发 → is_alert = 1, 记录 alert_reason

  7. 批量写入 reconciliation + reconciliation_detail
  8. 触发告警通知
```

#### 15.5.5 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/reconciliation/run/{importRecordId}` | 对指定导入记录执行对账 |
| GET | `/api/v1/reconciliation/{id}` | 获取对账结果详情 |
| GET | `/api/v1/reconciliation/{id}/details` | 分页查询对账明细(支持按 change_type 筛选) |
| GET | `/api/v1/reconciliation/{id}/report` | 导出对账报告(Excel) |
| GET | `/api/v1/reconciliation/alerts` | 查询告警列表 |
| POST | `/api/v1/reconciliation/alert-rule` | 创建告警规则 |

#### 15.5.6 对账报告示例

```
┌────────────────────────────────────────────────────────────┐
│  对账报告: 唐山钢铁 2026-03-23 vs 2026-03-20               │
│                                                            │
│  摘要:                                                     │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 总条目: 150  |  持平: 120  |  涨价: 15  |  降价: 8   │  │
│  │ 新增: 5      |  下架: 2    |  ⚠ 告警: 3              │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ⚠ 告警条目:                                               │
│  ┌────────┬────────┬────────┬────────┬────────┬────────┐  │
│  │ 品类   │ 规格   │ 产地   │ 上次价  │ 本次价  │ 涨跌幅  │  │
│  ├────────┼────────┼────────┼────────┼────────┼────────┤  │
│  │ 槽钢   │ 10#    │ 唐山   │ 4200   │ 4550   │ +8.3%  │  │
│  │ 角钢   │ 50*5   │ 邯郸   │ 4100   │ 3750   │ -8.5%  │  │
│  │ 方管   │ 50*100 │ 天津   │ 5000   │ 5400   │ +8.0%  │  │
│  └────────┴────────┴────────┴────────┴────────┴────────┘  │
│                                                            │
│  价格变动明细:                                              │
│  ┌────────┬────────┬────────┬────────┬────────┬────────┐  │
│  │ 品类   │ 规格   │ 产地   │ 上次价  │ 本次价  │ 变动    │  │
│  ├────────┼────────┼────────┼────────┼────────┼────────┤  │
│  │ 槽钢   │ 10#    │ 唐山   │ 4200   │ 4550   │ +350   │  │
│  │ 槽钢   │ 12#    │ 唐山   │ 4300   │ 4350   │ +50    │  │
│  │ 角钢   │ 50*5   │ 邯郸   │ 4100   │ 3750   │ -350   │  │
│  │ ...                                                     │
│  └────────┴────────┴────────┴────────┴────────┴────────┘  │
└────────────────────────────────────────────────────────────┘
```

---

### 15.6 规则复制

#### 15.6.1 业务目标

当新供应商的 Excel 格式与已有模板相似时，运营人员需要：
1. 从已有模板快速复制全部或部分配置到新模板
2. 复制后可独立修改，不影响原模板
3. 支持细粒度选择（只复制某个 Sheet 的配置、某个组的映射规则等）

#### 15.6.2 复制粒度

| 复制级别 | 说明 | 复制内容 |
|---------|------|---------|
| **整模板复制** | 完整克隆一个模板 | 主表(新编号) + 所有 Sheet + Group + Field + Mapping + CharRule + PriceMatchRule |
| **Sheet 级复制** | 复制某个 Sheet 的配置到当前模板 | Sheet配置 + 其下所有 Group + Field + Mapping + PriceMatchRule |
| **组级复制** | 复制某个数据组到当前 Sheet | Group配置 + 其下所有 Field + Mapping |
| **字段级复制** | 复制某个字段映射到当前组 | Field 配置(含 source_config + transform_config) |

#### 15.6.3 API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/import-template/{id}/clone` | 整模板克隆(指定新编号+新供应商) |
| POST | `/api/v1/import-template/{id}/copy-sheet` | 从源模板复制 Sheet 到当前模板 |
| POST | `/api/v1/import-template/{id}/copy-group` | 从源模板复制数据组到当前 Sheet |
| POST | `/api/v1/import-template/{id}/copy-field` | 从源模板复制字段映射到当前组 |

#### 15.6.4 整模板克隆接口详解

```
POST /api/v1/import-template/{sourceId}/clone
  Request: {
    "newTemplateCode": "TPL_HANDAN_001",
    "newTemplateName": "邯钢库存模板(从唐钢复制)",
    "newSupplierId": 1002,
    "newSupplierName": "邯郸钢铁",
    "includeCharRules": true,
    "includePriceMatchRules": true
  }
  Response: {
    "code": 200,
    "data": {
      "newTemplateId": 205,
      "newTemplateCode": "TPL_HANDAN_001",
      "copiedSheets": 2,
      "copiedGroups": 5,
      "copiedFields": 28,
      "copiedMappings": 12,
      "copiedCharRules": 3
    }
  }
```

#### 15.6.5 核心流程

```
整模板克隆:
  1. 读取源模板的完整配置(同版本快照的序列化方式)
  2. 在事务中:
     a. INSERT import_template (新编号, 新供应商, 其余字段从源复制)
     b. 遍历源模板的 sheets:
        - INSERT import_template_sheet (template_id=新模板ID)
        - 遍历 groups:
          · INSERT import_template_group (sheet_config_id=新SheetID)
          · 遍历 fields:
            INSERT import_template_field (group_id=新GroupID)
          · 遍历 fieldValueMappings:
            INSERT import_template_field_value_mapping (group_id=新GroupID)
        - 复制 priceMatchRule:
          INSERT import_template_price_match_rule (sheet_config_id=新SheetID)
     c. 复制 charRules:
        INSERT import_template_char_rule (template_id=新模板ID)
  3. 自动为新模板创建版本 v1

Sheet级复制:
  1. 读取源 Sheet 的完整配置
  2. 对目标模板:
     a. 检查 sheet_index 是否冲突 → 若冲突, 自动分配下一个可用索引
     b. INSERT sheet + groups + fields + mappings (关联到目标模板)

注意事项:
  - 所有主键ID重新分配, 外键关系同步更新
  - 新模板与源模板完全独立, 后续修改互不影响
  - 克隆时自动在 remark 中标注来源: "从模板 TPL_TANGSHAN_001 克隆"
```

#### 15.6.6 前端交互

```
一、整模板克隆(在模板列表页)

模板列表中每行操作栏:  [编辑] [删除] [克隆]

点击 [克隆] → 弹出对话框:
┌──────────────────────────────────────┐
│  克隆模板                             │
│                                      │
│  源模板:  唐钢库存+价格模板            │
│                                      │
│  新模板编号: [TPL_HANDAN_001    ]     │
│  新模板名称: [邯钢库存模板       ]     │
│  关联供应商: [邯郸钢铁 ▼         ]     │
│                                      │
│  复制选项:                            │
│  ☑ 字符转换规则                       │
│  ☑ 价格匹配规则                       │
│                                      │
│          [取消]           [确认克隆]   │
└──────────────────────────────────────┘

二、Sheet/组/字段级复制(在模板设计器中)

右键菜单或工具栏:
  [从其他模板导入配置 ▼]
    ├─ 导入整个Sheet配置...
    ├─ 导入数据组配置...
    └─ 导入字段映射...

选择后弹出选择器:
┌──────────────────────────────────────────┐
│  从其他模板导入 Sheet 配置                  │
│                                          │
│  选择源模板: [唐钢库存+价格模板 ▼]          │
│  选择源Sheet: [○ 库存(Sheet0)             │
│               ● 价格表(Sheet1)  ]         │
│                                          │
│  预览:                                    │
│  ├ 2 个数据组                              │
│  ├ 12 个字段映射                           │
│  ├ 4 条字段值映射                          │
│  └ 1 条价格匹配规则                        │
│                                          │
│            [取消]            [导入]        │
└──────────────────────────────────────────┘
```

---

### 15.7 扩展功能数据库 ER 图（汇总）

```
import_template (1) ──┬──< import_template_sheet (N)
                      │         │
                      │         ├──< import_template_group (M)
                      │         │         │
                      │         │         ├──< import_template_field (K)
                      │         │         │
                      │         │         └──< import_template_field_value_mapping (L)
                      │         │
                      │         └──< import_template_price_match_rule (0..1)
                      │
                      ├──< import_template_char_rule (P)
                      │
                      ├──< import_template_version (Q)            ← v1.4 版本管理
                      │
                      ├──< import_template_fingerprint (0..1)     ← v1.4 模板推荐
                      │
                      └── 关联 supplier(供应商)

import_record (1) ──< import_reconciliation (0..1)                ← v1.4 数据对账
                              │
                              └──< import_reconciliation_detail (N)

import_async_task (独立)                                           ← v1.4 异步导入

import_char_rule_preset (独立)                                     ← v1.1 字符转换
import_header_lexicon (独立)                                       ← v1.4 智能识别
import_reconciliation_alert_rule (独立)                             ← v1.4 对账告警
```

### 15.8 扩展功能新增表汇总

| 序号 | 表名 | 功能模块 | 说明 |
|------|------|---------|------|
| 10 | `import_header_lexicon` | 智能表头识别 | 表头关键词词库(可在线维护) |
| 11 | `import_template_fingerprint` | 模板自动推荐 | 模板结构指纹(表头关键词+列数+Sheet数) |
| 12 | `import_template_version` | 模板版本管理 | 版本快照(含完整JSON+变更摘要) |
| 13 | `import_async_task` | 异步导入 | 异步任务跟踪(进度+状态+结果) |
| 14 | `import_reconciliation` | 数据对账 | 对账记录(汇总统计) |
| 15 | `import_reconciliation_detail` | 数据对账 | 对账明细(逐条变更记录) |
| 16 | `import_reconciliation_alert_rule` | 数据对账 | 告警规则(阈值+通知方式) |

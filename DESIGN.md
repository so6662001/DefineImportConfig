# 钢贸动态 Excel 导入系统 — 详细设计文档

> 版本：v1.1  
> 技术栈：Java 17 + Spring Boot + Alibaba EasyExcel + MyBatis-Plus + MySQL + Vue 3  
> v1.1 变更：新增「规格特殊符号转换」子系统设计（第三章新增表、第 3.3.3 节、第五章新增引擎类、第七章新增组件、第十一章新增场景5）  

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

### 1.2 核心目标

构建一套 **基于"动态映射规则"的 Excel 导入服务**：

1. **模板设计器**：运营人员上传 Excel 样本，在可视化界面配置读取规则
2. **规则持久化**：规则关联供应商，持久化至数据库
3. **动态导入引擎**：基于规则动态解析任意格式的 Excel，输出统一的预览数据
4. **价格-库存匹配**：价格数据根据规则自动匹配到库存记录

---

## 二、核心概念模型

```
┌─────────────────────────────────────────────────────────────────┐
│                      ImportTemplate (导入模板)                   │
│  ┌─ template_code, supplier_id, all_sheets_price               │
│  │                                                              │
│  ├── SheetConfig (Sheet页配置) ×N                               │
│  │   ┌─ sheet_index, content_type(库存/价格)                    │
│  │   │  header_row_index, data_start/end_row                   │
│  │   │                                                          │
│  │   ├── DataGroup (数据组) ×M                                  │
│  │   │   ┌─ group_seq, group_name                              │
│  │   │   │                                                      │
│  │   │   ├── FieldMapping (字段映射) ×K                         │
│  │   │   │   ┌─ field_code(品类/规格/产地/...)                  │
│  │   │   │   │  source_type(COLUMN/FIXED_CELL/...)             │
│  │   │   │   │  source_config(JSON)                            │
│  │   │   │   └─ transform_config(JSON)                         │
│  │   │   │                                                      │
│  │   │   └── CategoryMapping (品类映射) ×L                      │
│  │   │       ┌─ source_category + qualifier → target_category  │
│  │   │       └─ 如: "方管"+"白材" → "镀锌方管"                  │
│  │   │                                                          │
│  │   └── PriceMatchRule (价格匹配规则)                           │
│  │       ┌─ match_fields, wall_thickness_range                  │
│  │       └─ 用于价格→库存的关联匹配                              │
│  └──────────────────────────────────────────────────────────────│
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 关键概念说明

#### 数据组 (DataGroup)

同一个 Sheet 页内可能存在 **多组数据**，典型场景：

- **价格表多产地列**：品类列 + 规格列 + 黑材价格列 + 白材价格列。"黑材"和"白材"各生成一组数据，品类分别派生为"方管"和"镀锌方管"
- **同一 Sheet 多品类区域**：上半部分是"槽钢"库存，下半部分是"角钢"库存

每个数据组可拥有独立的字段映射规则，也可共享 Sheet 级别的公共映射。

#### 字段数据来源类型 (SourceType)

| 类型 | 说明 | 典型用途 |
|------|------|---------|
| `COLUMN` | 来自某一数据列 | 规格列、重量列等常规数据列 |
| `FIXED_CELL` | 来自固定单元格 | 品类在某个合并单元格(如 A1)中 |
| `FIXED_VALUE` | 人工指定的常量 | 当 Sheet 中无品类列时人为指定 |
| `COMPOSITE` | 多来源组合拼接 | 规格 = 列1 + "*" + 列2 |
| `COLUMN_HEADER` | 列的表头文本即为值 | 表头是产地名称，表体是价格 |

#### 品类映射 (CategoryMapping)

当品类需要根据 **限定词(qualifier)** 派生时使用：

```
原始品类(品类列值) + 限定词(如表头文字) → 目标品类
─────────────────────────────────────────────────
"方管"           + "黑材"                → "方管"
"方管"           + "白材"                → "镀锌方管"
"圆管"           + "黑材"                → "圆管"
"圆管"           + "白材"                → "镀锌圆管"
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
                      │         │         └──< import_template_category_mapping (L)
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
    `group_name`        VARCHAR(128)    DEFAULT NULL             COMMENT '组名称(便于识别)',
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
-- 5. 品类映射规则表
-- ============================================================
CREATE TABLE `import_template_category_mapping` (
    `id`                BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`       BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`   BIGINT          NOT NULL                 COMMENT 'Sheet配置ID',
    `group_id`          BIGINT          DEFAULT NULL             COMMENT '数据组ID',
    `source_category`   VARCHAR(128)    DEFAULT NULL             COMMENT '原始品类值(null表示匹配任意)',
    `qualifier`         VARCHAR(128)    DEFAULT NULL             COMMENT '限定词(如表头文字: 黑材/白材)',
    `target_category`   VARCHAR(128)    NOT NULL                 COMMENT '目标品类名称',
    `remark`            VARCHAR(256)    DEFAULT NULL,
    `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_template_sheet` (`template_id`, `sheet_config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品类映射规则表';


-- ============================================================
-- 6. 价格匹配规则表
-- ============================================================
CREATE TABLE `import_template_price_match_rule` (
    `id`                        BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '主键',
    `template_id`               BIGINT          NOT NULL                 COMMENT '模板ID',
    `sheet_config_id`           BIGINT          DEFAULT NULL             COMMENT 'Sheet配置ID(null=模板级规则)',
    `match_fields`              JSON            NOT NULL                 COMMENT '参与匹配的字段列表 如["category","spec","origin","material"]',
    `wall_thickness_match_mode` TINYINT         NOT NULL DEFAULT 0       COMMENT '壁厚匹配模式 0-精确匹配 1-区间匹配',
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

    "charTransform": {
        "useTemplateRules": true,
        "usePresetGroups": ["SPEC", "COMMON"],
        "fieldRules": [
            { "matchType": "LITERAL", "matchPattern": "×", "replaceValue": "*" },
            { "matchType": "REGEX", "matchPattern": "(?<=\\d)[xX](?=\\d)", "replaceValue": "*" }
        ],
        "excludePresetCodes": ["HASH_SIGN"]
    }
}
```

| 字段 | 说明 |
|------|------|
| `trimWhitespace` | 去除首尾空白 |
| `removeUnit` | 移除单位文字 |
| `numericPrecision` | 数值精度(小数位) |
| `parseAsRange` | 是否解析为区间值(壁厚场景) |
| `rangeDelimiter` | 区间分隔符(如 "-") |
| `regexExtract` | 正则提取(提取规格中的数字部分等) |
| `charTransform` | **★ 字符转换配置**（见下方 3.3.3 详解） |

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
    ├─ Step 3: removeUnit (去单位, 如 "吨")
    ├─ Step 4: regexExtract (正则提取)
    ├─ Step 5: numericPrecision (数值精度处理)
    └─ Step 6: parseAsRange (区间解析)

输出 cleanValue
```

> **关键设计决策**：字符转换（Step 2）在去单位（Step 3）之前执行，因为去单位依赖于标准化后的字符（如全角"吨"需要先转为半角才能被 unitPatterns 匹配），同时在数值转换之前确保所有数字和分隔符已标准化。

#### 3.3.3 match_fields 详解

```json
["category", "spec", "origin", "material"]
```

指定价格匹配库存时参与比对的业务字段。壁厚区间匹配由 `wall_thickness_match_mode` 单独控制。

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
│   ├── CategoryMapper.java                     // 品类映射处理器
│   ├── PriceMatcher.java                       // 价格→库存匹配器
│   └── DataValidator.java                      // 数据校验器
│
├── model/
│   ├── entity/                                  // MyBatis-Plus 实体
│   │   ├── ImportTemplate.java
│   │   ├── ImportTemplateSheet.java
│   │   ├── ImportTemplateGroup.java
│   │   ├── ImportTemplateField.java
│   │   ├── ImportTemplateCategoryMapping.java
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
│   │   │   └── CategoryMappingDto.java
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
│   │   └── CharTransformConfig.java             // 字符转换配置(含 fieldRules, excludePresetCodes等)
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
│   ├── ImportTemplateCategoryMappingMapper.java
│   ├── ImportTemplatePriceMatchRuleMapper.java
│   ├── ImportCharRulePresetMapper.java          // 系统预置字符规则 Mapper
│   ├── ImportTemplateCharRuleMapper.java        // 模板字符规则 Mapper
│   └── ImportRecordMapper.java
│
└── util/
    ├── CellRefUtil.java                        // "B3" ↔ (row=2, col=1) 互转
    ├── SpecParseUtil.java                      // 规格字符串解析(提取宽、高、壁厚)
    ├── RangeUtil.java                          // 区间解析与匹配("0.5-1.0" → [0.5, 1.0])
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

#### CategoryMapper（品类映射处理器）

```
职责: 根据 CategoryMapping 规则, 将原始品类值转换为目标品类

输入: rawCategory(原始品类) + qualifier(限定词, 可能来自表头)
输出: targetCategory(目标品类)

算法:
  1. 在 CategoryMapping 列表中查找:
     (source_category = rawCategory OR source_category IS NULL)
     AND (qualifier = inputQualifier OR qualifier IS NULL)
  2. 若命中 → 返回 target_category
  3. 若未命中 → 返回 rawCategory(原值透传)
```

#### PriceMatcher（价格→库存匹配器）

```
职责: 将解析出的价格记录匹配到库存记录, 填入库存的 price 字段

算法:
  1. 读取 PriceMatchRule, 确定参与匹配的字段列表
  2. 对每条价格记录:
     a. 提取匹配键: category + spec + origin + material (按规则)
     b. 若 wall_thickness_match_mode=1(区间匹配):
        - 从价格记录解析壁厚区间 [min, max]
        - 从库存记录解析壁厚精确值 val
        - 匹配条件: min <= val <= max
     c. 若 wall_thickness_match_mode=0(精确匹配):
        - 壁厚也作为精确匹配键
  3. 匹配成功 → 将价格写入库存记录的 price 字段
  4. 未匹配的价格 → 生成 ExcelImportError
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
              │  (去空格/字符转换/去单位/数值/区间)     │
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
              │       CategoryMapper                 │
              │  (品类映射: 方管+白材→镀锌方管)         │
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
                    "categoryMappings": [
                        {
                            "sourceCategory": "方管",
                            "qualifier": "黑材",
                            "targetCategory": "方管"
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
                    "categoryMappings": [
                        {
                            "sourceCategory": "方管",
                            "qualifier": "白材",
                            "targetCategory": "镀锌方管"
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
│   │   ├── CategoryMappingEditor.vue         // 品类映射规则编辑
│   │   └── PriceMatchRuleForm.vue            // 价格匹配规则
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
│  │                            │  │  品类映射规则 [+ 新增]          │  │
│  │                            │  │  (当品类需要派生时配置)          │  │
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

#### 品类映射配置交互

```
1. 用户在品类字段选择 COMPOSITE 或 COLUMN_HEADER 类型
2. 展开「品类映射规则」区域
3. 点击 [+ 新增映射]:
   - 原始品类: [方管]  (来自品类列的值)
   - 限定词:   [白材]  (来自表头或指定单元格的值)
   - 目标品类: [镀锌方管]  (系统中的标准品类名)
4. 可添加多条映射规则
5. 预览: 根据当前 Excel 数据实时展示映射结果
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

            // 数据转换(内含字符转换管道: 系统预置→模板级→字段级规则按序执行)
            String cleanValue = DataTransformer.transform(rawValue, field.getTransformConfig(),
                charTransformPipelineCache.get(field.getFieldCode()));

            // 品类映射
            if ("category".equals(field.getFieldCode()) && group.getCategoryMappings() != null) {
                String qualifier = resolveQualifier(group, rowData);
                cleanValue = CategoryMapper.map(cleanValue, qualifier, group.getCategoryMappings());
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

## 九、性能优化策略

### 9.1 大文件处理

| 策略 | 实现方式 |
|------|---------|
| SAX 流式读取 | EasyExcel 默认即为 SAX 模式, 内存占用与文件大小无关 |
| 批量处理 | 每累积 500 行调用一次 batch 处理(校验/转换), 避免 List 无限膨胀 |
| 合并单元格 O(1) 查找 | `mergeCellMap` 使用 HashMap, key 为 `"rowIndex_colIndex"` |
| 表头匹配预计算 | `headerColumnMap` 在 `invokeHead()` 中一次性计算, 后续行直接查表 |
| 固定单元格预读 | 需要的固定单元格值在解析前一次性读取并缓存 |

### 9.2 多 Sheet 并行

```
对于独立的库存 Sheet → 可并行解析
价格 Sheet 需等待所有库存 Sheet 完成 → 串行在后
使用 CompletableFuture 或线程池管理并发
```

### 9.3 内存控制

```
- 解析过程中只保留必要字段(ParsedRowDto), 不保留原始 CellData
- 合并单元格 map 仅存储首格值的字符串, 不存 CellData 对象
- 单个 Sheet 解析完成后立即释放该 Sheet 的临时数据
```

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
| `CategoryMapperTest` | 品类映射命中/未命中/通配 |
| `PriceMatcherTest` | 精确匹配、壁厚区间匹配 |
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

---

## 十五、后续扩展方向

1. **智能表头识别**: 基于机器学习自动识别表头行位置和列含义
2. **模板自动推荐**: 上传 Excel 后自动匹配最接近的已有模板
3. **模板版本管理**: 同一模板多版本, 支持回滚
4. **异步导入**: 大文件使用消息队列异步处理, 进度实时推送
5. **数据对账**: 导入后与历史数据对比, 发现价格异动
6. **规则复制**: 从已有模板快速复制规则到新模板

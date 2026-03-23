-- ============================================================
-- 1. 导入模板主表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_code     VARCHAR(64)     NOT NULL,
    template_name     VARCHAR(128)    NOT NULL,
    supplier_id       BIGINT          DEFAULT NULL,
    supplier_name     VARCHAR(128)    DEFAULT NULL,
    scope             VARCHAR(256)    DEFAULT NULL,
    all_sheets_price  TINYINT         NOT NULL DEFAULT 0,
    status            TINYINT         NOT NULL DEFAULT 1,
    remark            VARCHAR(512)    DEFAULT NULL,
    create_by         VARCHAR(64)     DEFAULT NULL,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_by         VARCHAR(64)     DEFAULT NULL,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_template_code UNIQUE (template_code)
);

-- ============================================================
-- 2. Sheet 页配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_sheet (
    id                    BIGINT      AUTO_INCREMENT PRIMARY KEY,
    template_id           BIGINT      NOT NULL,
    sheet_index           INT         NOT NULL DEFAULT 0,
    sheet_name            VARCHAR(128) DEFAULT NULL,
    content_type          TINYINT     NOT NULL DEFAULT 1,
    header_row_index      INT         NOT NULL DEFAULT 0,
    data_start_row_index  INT         DEFAULT NULL,
    data_end_row_index    INT         DEFAULT NULL,
    empty_row_threshold   INT         NOT NULL DEFAULT 2,
    enable_merge_cell     TINYINT     NOT NULL DEFAULT 1,
    sort_order            INT         NOT NULL DEFAULT 0,
    remark                VARCHAR(256) DEFAULT NULL,
    create_time           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               TINYINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sheet_template_id ON import_template_sheet (template_id);

-- ============================================================
-- 3. 数据组配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_group (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_id       BIGINT          NOT NULL,
    sheet_config_id   BIGINT          NOT NULL,
    group_seq         INT             NOT NULL DEFAULT 1,
    group_name        VARCHAR(128)    DEFAULT NULL,
    fixed_category    VARCHAR(128)    DEFAULT NULL,
    fixed_origin      VARCHAR(128)    DEFAULT NULL,
    fixed_material    VARCHAR(128)    DEFAULT NULL,
    fixed_remark      VARCHAR(512)    DEFAULT NULL,
    data_start_row    INT             DEFAULT NULL,
    data_end_row      INT             DEFAULT NULL,
    remark            VARCHAR(256)    DEFAULT NULL,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_group_sheet_config_id ON import_template_group (sheet_config_id);

-- ============================================================
-- 4. 字段映射规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_field (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_id       BIGINT          NOT NULL,
    sheet_config_id   BIGINT          NOT NULL,
    group_id          BIGINT          DEFAULT NULL,
    field_code        VARCHAR(64)     NOT NULL,
    field_name        VARCHAR(64)     NOT NULL,
    source_type       VARCHAR(32)     NOT NULL,
    source_config     CLOB            NOT NULL,
    transform_config  CLOB            DEFAULT NULL,
    required          TINYINT         NOT NULL DEFAULT 0,
    default_value     VARCHAR(256)    DEFAULT NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    remark            VARCHAR(256)    DEFAULT NULL,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_field_group_id ON import_template_field (group_id);
CREATE INDEX IF NOT EXISTS idx_field_sheet_config_id ON import_template_field (sheet_config_id);

-- ============================================================
-- 5. 字段值映射规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_field_value_mapping (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_id       BIGINT          NOT NULL,
    sheet_config_id   BIGINT          NOT NULL,
    group_id          BIGINT          DEFAULT NULL,
    target_field      VARCHAR(64)     NOT NULL,
    source_value      VARCHAR(128)    DEFAULT NULL,
    qualifier         VARCHAR(128)    DEFAULT NULL,
    target_value      VARCHAR(128)    NOT NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    remark            VARCHAR(256)    DEFAULT NULL,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fvm_group_id ON import_template_field_value_mapping (group_id);
CREATE INDEX IF NOT EXISTS idx_fvm_template_sheet ON import_template_field_value_mapping (template_id, sheet_config_id);

-- ============================================================
-- 6. 价格匹配规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_price_match_rule (
    id                        BIGINT      AUTO_INCREMENT PRIMARY KEY,
    template_id               BIGINT      NOT NULL,
    sheet_config_id           BIGINT      DEFAULT NULL,
    match_fields              CLOB        NOT NULL,
    wall_thickness_match_mode TINYINT     NOT NULL DEFAULT 0,
    spec_range_match_mode     TINYINT     NOT NULL DEFAULT 0,
    spec_range_config         CLOB        DEFAULT NULL,
    remark                    VARCHAR(256) DEFAULT NULL,
    create_time               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time               TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                   TINYINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_pmr_template_id ON import_template_price_match_rule (template_id);

-- ============================================================
-- 7. 导入记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_record (
    id                      BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_id             BIGINT          NOT NULL,
    template_code           VARCHAR(64)     NOT NULL,
    supplier_id             BIGINT          DEFAULT NULL,
    supplier_name           VARCHAR(128)    DEFAULT NULL,
    file_name               VARCHAR(256)    NOT NULL,
    file_path               VARCHAR(512)    NOT NULL,
    import_type             TINYINT         NOT NULL DEFAULT 0,
    batch_no                VARCHAR(64)     DEFAULT NULL,
    linked_record_id        BIGINT          DEFAULT NULL,
    total_rows              INT             NOT NULL DEFAULT 0,
    success_rows            INT             NOT NULL DEFAULT 0,
    error_rows              INT             NOT NULL DEFAULT 0,
    import_status           TINYINT         NOT NULL DEFAULT 0,
    price_writeback_status  TINYINT         DEFAULT NULL,
    price_writeback_summary CLOB            DEFAULT NULL,
    error_detail            CLOB            DEFAULT NULL,
    create_by               VARCHAR(64)     DEFAULT NULL,
    create_time             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                 TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_record_template_id ON import_record (template_id);
CREATE INDEX IF NOT EXISTS idx_record_supplier_id ON import_record (supplier_id);
CREATE INDEX IF NOT EXISTS idx_record_batch_no ON import_record (batch_no);
CREATE INDEX IF NOT EXISTS idx_record_linked ON import_record (linked_record_id);

-- ============================================================
-- 8. 系统预置字符转换规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_char_rule_preset (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    rule_code         VARCHAR(64)     NOT NULL,
    rule_name         VARCHAR(128)    NOT NULL,
    rule_group        VARCHAR(64)     NOT NULL DEFAULT 'COMMON',
    match_type        VARCHAR(32)     NOT NULL,
    match_pattern     VARCHAR(256)    NOT NULL,
    replace_value     VARCHAR(256)    NOT NULL DEFAULT '',
    description       VARCHAR(256)    DEFAULT NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    enabled           TINYINT         NOT NULL DEFAULT 1,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_rule_code UNIQUE (rule_code)
);

-- ============================================================
-- 9. 模板字符转换规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_template_char_rule (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    template_id       BIGINT          NOT NULL,
    apply_field_codes CLOB            DEFAULT NULL,
    preset_rule_id    BIGINT          DEFAULT NULL,
    match_type        VARCHAR(32)     DEFAULT NULL,
    match_pattern     VARCHAR(256)    DEFAULT NULL,
    replace_value     VARCHAR(256)    DEFAULT NULL,
    description       VARCHAR(256)    DEFAULT NULL,
    sort_order        INT             NOT NULL DEFAULT 0,
    enabled           TINYINT         NOT NULL DEFAULT 1,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tcr_template_id ON import_template_char_rule (template_id);

-- ============================================================
-- 10. 已导入库存数据表
-- ============================================================
CREATE TABLE IF NOT EXISTS import_inventory_data (
    id                BIGINT          AUTO_INCREMENT PRIMARY KEY,
    record_id         BIGINT          NOT NULL,
    batch_no          VARCHAR(64)     NOT NULL,
    supplier_id       BIGINT          DEFAULT NULL,
    category          VARCHAR(128)    DEFAULT NULL,
    spec              VARCHAR(128)    DEFAULT NULL,
    origin            VARCHAR(128)    DEFAULT NULL,
    material          VARCHAR(128)    DEFAULT NULL,
    package_num       INT             DEFAULT NULL,
    whole_num         INT             DEFAULT NULL,
    odd_num           INT             DEFAULT NULL,
    weight            DECIMAL(12,3)   DEFAULT NULL,
    price             DECIMAL(12,2)   DEFAULT NULL,
    price_source      TINYINT         DEFAULT NULL,
    price_record_id   BIGINT          DEFAULT NULL,
    price_updated_at  TIMESTAMP       DEFAULT NULL,
    remark            VARCHAR(512)    DEFAULT NULL,
    source_sheet      VARCHAR(128)    DEFAULT NULL,
    source_row        INT             DEFAULT NULL,
    create_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           TINYINT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_invdata_record_id ON import_inventory_data (record_id);
CREATE INDEX IF NOT EXISTS idx_invdata_batch_no ON import_inventory_data (batch_no);
CREATE INDEX IF NOT EXISTS idx_invdata_supplier_id ON import_inventory_data (supplier_id);
CREATE INDEX IF NOT EXISTS idx_invdata_category_spec ON import_inventory_data (category, spec);
CREATE INDEX IF NOT EXISTS idx_invdata_price_source ON import_inventory_data (price_source);

-- ============================================================
-- 预置字符转换规则初始化数据(钢贸行业常见特殊符号)
-- ============================================================
INSERT INTO import_char_rule_preset (rule_code, rule_name, rule_group, match_type, match_pattern, replace_value, description, sort_order) VALUES
('FULLWIDTH_STAR',       '全角×→半角*',      'SPEC',    'LITERAL',   '×',   '*',  '全角乘号转半角星号, 规格分隔符归一化',          10),
('FULLWIDTH_X_UPPER',    '全角Ｘ→半角*',     'SPEC',    'LITERAL',   'Ｘ',  '*',  '全角大写X转半角星号',                          11),
('LOWERCASE_X_SEP',      '小写x→半角*',      'SPEC',    'REGEX',     '(?<=\d)x(?=\d)', '*', '数字间的小写x视为乘号',              12),
('UPPERCASE_X_SEP',      '大写X→半角*',      'SPEC',    'REGEX',     '(?<=\d)X(?=\d)', '*', '数字间的大写X视为乘号',              13),
('FULLWIDTH_HYPHEN',     '全角－→半角-',     'COMMON',  'LITERAL',   '－',  '-',  '全角减号/连字符转半角',                        20),
('EN_DASH',              '半角–→半角-',      'COMMON',  'LITERAL',   '–',   '-',  'EN DASH 转标准连字符',                        21),
('EM_DASH',              '全角—→半角-',      'COMMON',  'LITERAL',   '—',   '-',  'EM DASH 转标准连字符',                        22),
('FULLWIDTH_DOT',        '全角．→半角.',     'COMMON',  'LITERAL',   '．',  '.',  '全角句点转半角点(小数点)',                      23),
('FULLWIDTH_LPAREN',     '全角（→半角(',     'COMMON',  'LITERAL',   '（',  '(',  '全角左括号转半角',                             30),
('FULLWIDTH_RPAREN',     '全角）→半角)',     'COMMON',  'LITERAL',   '）',  ')',  '全角右括号转半角',                             31),
('FULLWIDTH_SLASH',      '全角／→半角/',     'COMMON',  'LITERAL',   '／',  '/',  '全角斜杠转半角',                              32),
('PHI_UPPER',            '大写Φ→清除',       'SPEC',    'LITERAL',   'Φ',   '',   '大写希腊字母Phi(直径符号)清除',                 40),
('PHI_LOWER',            '小写φ→清除',       'SPEC',    'LITERAL',   'φ',   '',   '小写希腊字母phi清除',                          41),
('DIAMETER_SIGN',        '∅→清除',           'SPEC',    'LITERAL',   '∅',   '',   'Unicode直径符号清除',                          42),
('PHI_FULLWIDTH',        'Ф→清除',           'SPEC',    'LITERAL',   'Ф',   '',   '西里尔字母Ef(常被误用为直径)清除',               43),
('NBSP',                 '不间断空格→清除',   'COMMON',  'LITERAL',   ' ',   '',   'Unicode不间断空格(U+00A0)清除',               50),
('IDEOGRAPHIC_SPACE',    '全角空格→清除',     'COMMON',  'LITERAL',   '　',  '',   '全角空格(U+3000)清除',                        51),
('MULTI_SPACES',         '连续空格→单空格',   'COMMON',  'REGEX',     '\s{2,}', ' ', '多个连续空白压缩为单个空格',                  52);

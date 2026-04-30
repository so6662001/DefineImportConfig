# 在制品配比算法（meCreateMOWIP）优化版

> 优化日期：2026-04-30
> 原版作者：黄好庭 2025-12-02
> 业务：根据生产收货匹配上料，按 MO 配比核销，落到 `CO_MO_WIP` / `CO_MO_WIP_MESOut`
> **目标 SQL Server 版本：2008 R2 及以上**

---

## 目录结构

```
.
├── src/meCreateMOWIP.bas         VB6 模块（薄壳，调用存储过程）
├── sql/schema.sql                建表脚本（测试库用）
├── sql/usp_meCreateMOWIP.sql     存储过程：4 阶段算法实现
└── tests/test_meCreateMOWIP.sql  单元测试（15 个用例 + 守恒律检查）
```

---

## 架构

```
┌──────────────────────────┐    EXEC usp_meCreateMOWIP
│  VB6 modCreateMOWIP.bas  │  ────────────────────────────►  ┌─────────────────────────────────┐
│   meCreateMOWIP(...)     │   @PID, @BeginDate, @EndDate    │  SQL Server 存储过程             │
└──────────────────────────┘                                 │  4 阶段集合化算法                │
                                                              └─────────────────────────────────┘
```

把整个算法下沉到存储过程的好处：
- **VB 端只需一次往返**，原版每张工单都要 N 次 ExecSQL
- **单元测试脱离 VB6**：直接用 `sqlcmd` 跑 `tests/test_meCreateMOWIP.sql`
- **维护单点**：算法只在一个地方实现

---

## 算法 4 阶段

### 阶段 0：清理 + 期初结转
- 删除本期 `CO_MO_WIP_MESOut` / `CO_MO_WIP`（按 PID）
- 取上一会计期（`BEGINDATE < @BeginDate` 严格小于，避免误取未来期）
- 上期 `BalWeight ≠ 0` 的 MO 结转为本期 `InitWeight`
- `WeightTotal/GWeightTotal` 沿用上期值（含跨期累计）

### 阶段 1：本期上料/收货汇总
- 上料按 `MOBillID` 汇总 → MERGE 到 `CO_MO_WIP`
  - `IWeight`、`BalWeight`（+= 本期）、`WeightTotal`（+= 本期，跨期累计）
- 收货按 `MOBillID` 汇总 → MERGE
  - `GWeight`、`GWeightTotal`（+= 本期）
- 退/补料 `RBTAG IN (1,2)` 用 `−1.0 × weight` 反向扣减

### 阶段 2：第一轮——按单据级精确匹配
- 同 `MOBillID` 下，按重量排序后用 `ROW_NUMBER()` 一对一配对
- 配对条件：`ABS(IN.weight − OUT.weight) ≤ 0.01 kg`
- **仅对正向（weight > 0）单据配对**，避免净退料/退货被误配
- 命中的上料单据 → 临时表 `#MatchedOutBills`
- **精确匹配命中的上料单据不写入 `CO_MO_WIP_MESOut`**（业务规则）

### 阶段 3：第二轮——剩余收货按累计核销

每个 MO 定义：

| 符号 | 含义 |
| --- | --- |
| `MatchedOut` | 阶段 2 命中的上料合计 |
| `MatchedIn` | 阶段 2 命中的收货合计 |
| `Init` | `CO_MO_WIP.InitWeight`（上期结转） |
| `S` | 本期未被精确匹配的上料合计 = `IWeight − MatchedOut` |
| `N` | 需核销量 = `GWeight − MatchedIn` |
| `T` | 待核销总量 = `Init + S` |
| `InitConsumed` | 期初被消耗 = `MIN(Init, N)` |
| `NeedFromS` | 需从本期上料填补 = `MAX(N − Init, 0)` |

剩余上料按 `RBTAG DESC, billdate, acctime, billid, OutItmID` 排序，
用 `ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY ...)` 落到 `#SortedRemain` 临时表，
再用自连接 `SUM(s2.oweight) WHERE s2.rn <= s1.rn` 算累计 `cum`
（**SQL Server 2008 兼容写法**，因 2012+ 才支持 `SUM() OVER (ORDER BY ...)` 累计窗口）。
按 MOBillID 分区，单 MO 明细数有限，O(n²) 性能可接受。

#### 分配规则

```
若 N ≤ 0          ⇒ 不核销
若 NeedFromS ≤ 0  ⇒ 不写 MES 明细（期初足以覆盖收货）
若 T ≥ N          ⇒ 末笔加成
   - cum ≤ NeedFromS              → UseWeight = oweight,        SrcTag = 1
   - prev_cum < NeedFromS         → UseWeight = (NeedFromS − prev_cum)×1.05, SrcTag = 2
   - prev_cum ≥ NeedFromS         → 不写
若 T < N          ⇒ 待核销总量不足
   - 全部剩余上料按原值核销
   - 最后一条 SrcTag = 3，其余 SrcTag = 1
```

#### 回写 `CO_MO_WIP`（保证守恒）

```
OWeight   = MatchedOut + InitConsumed + SUM(MESOut.UseWeight)
BalWeight = Init + IWeight − OWeight
```

> **守恒律**：`Init + IWeight = OWeight + BalWeight` ——任意 MO 始终成立，单元测试中每个用例都验证此等式。

---

## SrcTag 来源标记

| SrcTag | 名称 | 含义 |
| :---: | --- | --- |
| `1` | `SRC_NORMAL` | 累计核销-普通行（整笔上料全部核销） |
| `2` | `SRC_LAST_BOOSTED` | 累计核销-末笔加成（× 1.05） |
| `3` | `SRC_LAST_PLAIN` | 累计核销-末笔无加成（待核销总量不足） |

> 精确匹配命中的上料**不写入** `CO_MO_WIP_MESOut`，所以无 `SrcTag = 0`。

---

## 单元测试

依赖：SQL Server 2008 R2 及以上 + `sqlcmd`
（实际跑通验证用的 SQL Server 2022，但代码严格只用 2008 支持的特性）

```bash
# 1. 创建测试库（如果还没有）
sqlcmd -S localhost,1433 -U sa -P 'YourPwd!' -C -N -Q "IF DB_ID('MOWIP_TEST') IS NULL CREATE DATABASE MOWIP_TEST;"

# 2. 建表
sqlcmd -S localhost,1433 -U sa -P 'YourPwd!' -C -N -d MOWIP_TEST -b -i sql/schema.sql

# 3. 装存储过程
sqlcmd -S localhost,1433 -U sa -P 'YourPwd!' -C -N -d MOWIP_TEST -b -i sql/usp_meCreateMOWIP.sql

# 4. 跑测试
sqlcmd -S localhost,1433 -U sa -P 'YourPwd!' -C -N -d MOWIP_TEST -b -i tests/test_meCreateMOWIP.sql
```

### 测试用例（15 个）

| #  | 名称 | 验证场景 |
| :---: | --- | --- |
| 01 | 空数据 | 无单据时不报错且不产生数据 |
| 02 | 精确匹配 | 上料 5kg + 收货 5kg ⇒ MES 不写, OWeight=5 |
| 03 | 精确匹配阈值 0.01 边界 | 5.005 vs 5.000 (差 0.005) 应匹配 |
| 04 | 阈值外 末笔加成 | 5.02 vs 5.00 (差 0.02) ⇒ UseWeight=5.25 (=5×1.05), SrcTag=2 |
| 05 | 多笔累计 末笔加成 | 上料 3+4+10 / 收货 5 ⇒ O1=3 (tag1), O2=2.10 (tag2), O3 不写 |
| 06 | T<N 全部核销 | 上料 2+3 / 收货 10 ⇒ 全部写, 末笔 SrcTag=3 |
| 07 | 期初结转 + 末笔加成 | Init=4 / 上料 2 / 收货 5 ⇒ OWeight=5.05 |
| 08 | 期初足够覆盖收货 | Init=10 / 上料 2 / 收货 5 ⇒ MES 不写, OWeight=5 |
| 09 | 退/补料 RBTAG | O1=10 (正) + O2=3 (rbtag=1, 视-3) / 收货 6 |
| 10 | 多 MO 同时跑 | partition by 隔离正确 |
| 11 | 幂等 | 连续跑两次结果一致 |
| 12 | 日期范围过滤 | 期外单据被忽略 |
| 13 | 失效单据过滤 | bstate=0 / deleted=1 被忽略 |
| 14 | 跨期累计 | WeightTotal/GWeightTotal 含上期值 |
| 15 | 临时表清理 | `#MatchedOutBills` 调用后被清理 |

每个用例都额外执行 `assertConservation` 验证 `Init + IWeight = OWeight + BalWeight` 守恒。

---

## 与原版（2025-12-02）的差异

### 修复的 Bug

| # | 原版问题 | 修复 |
| :---: | --- | --- |
| 1 | `Dim dblStartWeight As DoubleobjDS.NullToDbl(...)` 编译错误 | 整段 VB 端循环重写为存储过程 |
| 2 | `dblUseWeightTotal` 从未赋值 ⇒ 核销逻辑实际从未生效 | 用 SQL 直接计算 `N = GWeight − MatchedIn` |
| 3 | 缺少第一轮"精确匹配"逻辑 | 新增**阶段 2** |
| 4 | 1.05 系数仅注释 `'* 1.1`，没真正参与运算 | 阶段 3 SQL 中明确 `× @Coef` |
| 5 | `dblCurWeight/dblCurQTY` 在 `Do` 循环里 `Dim`，跨轮残值 | 重构后无此问题 |
| 6 | `rsTmp.RecordCount = 0` 分支用未声明变量 | 重构后无此问题 |
| 7 | 累计求和用 `s2.rn ≤ s1.rn` 自连接，O(n²) | 改为 `SUM() OVER (...)`，O(n) |
| 8 | 上一会计期 `TOP 1 ORDER BY BEGINDATE` 未限上界 | 加 `BEGINDATE < @BeginDate` |
| 9 | 完工率 > 90% 直接 `OWeight = IWeight + InitWeight` 不写明细 ⇒ 总账与明细对不平 | 取消该分支，统一走 1.05 系数累计核销 |
| 10 | 全过程无事务 | 加 `BEGIN TRAN/COMMIT/ROLLBACK + TRY/CATCH + RAISERROR` |
| 11 | 日期格式 `yyyy-MM-dd` 依赖会话语言 | 改为 `yyyyMMdd` 或参数化 DATETIME |
| 12 | 末笔判定用 `cum ≥ N`，未考虑期初先消耗 | 改为 `cum ≥ NeedFromS = max(N − Init, 0)` |
| 13 | `OWeight = SUM(UseWeight)` 漏算精确匹配 + 期初消耗 ⇒ 不守恒 | 改为 `OWeight = MatchedOut + InitConsumed + SUM(UseWeight)` |
| 14 | 期初足以覆盖收货时仍写一条 `UseWeight=0` 脏数据 | `NeedFromS ≤ 0` 时不写 MES |
| 15 | 阶段 2 精确匹配未限 `weight > 0`，可能误配净退料 | 加 `WHERE weight > 0` |

### 性能优化

- 取消 VB 端逐 MO `Do While` 循环，整个算法一次往返完成
- 阶段 0/1/2 全部用集合化 SQL（MERGE / CTE / ROW_NUMBER）
- `#MatchedOutBills` 加 `OutBillID` 主键 + `InBillID` 唯一约束加速 LEFT JOIN
- 阶段 3 累计因 SQL Server 2008 不支持 `SUM() OVER (ORDER BY ...)`，改为
  ROW_NUMBER + 自连接（按 MOBillID 分区，单 MO 明细数有限，O(n²) 可接受）

### SQL Server 2008 兼容性

本存储过程严格只使用 SQL Server 2008 R2 支持的特性：

| 特性 | 2008 支持 | 用法 |
| --- | :---: | --- |
| `MERGE` | ✅ (2008 引入) | 阶段 1 |
| `CTE`（`WITH ...`） | ✅ | 全部阶段 |
| `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` | ✅ | 阶段 2/3 |
| 聚合 `OVER (PARTITION BY ...)` 不带 ORDER BY | ✅ | 未使用 |
| `TRY ... CATCH` | ✅ | 异常处理 |
| `RAISERROR` | ✅ | 异常重抛 |
| `DECLARE @x INT = 1`（变量初始化） | ✅ (2008 引入) | 多处 |
| `SET XACT_ABORT ON` | ✅ | 事务一致性 |

不使用的特性（避开 2012+）：

| 特性 | 引入版本 | 替代方案 |
| --- | :---: | --- |
| `SUM() OVER (ORDER BY ... ROWS ...)` 累计窗口 | 2012 | ROW_NUMBER + 自连接 |
| `THROW` | 2012 | `RAISERROR(@msg, @sev, @state) + RETURN` |
| `OFFSET ... FETCH` 分页 | 2012 | 未使用 |
| `LAG()` / `LEAD()` 窗口函数 | 2012 | 用 `prev_cum = cum - oweight` 推导 |
| `CREATE TABLE` 内联 `INDEX` | 2014 | 独立的 `CREATE INDEX` 语句 |
| `STRING_AGG` / `STRING_SPLIT` / `OPENJSON` 等 | 2016/2017 | 未使用 |
| `DROP TABLE IF EXISTS` | 2016 | `IF OBJECT_ID(...) IS NOT NULL DROP TABLE` |

### 业务规则落地

| # | 规则 | 实现位置 |
| :---: | --- | --- |
| 1 | 精确匹配按单据级，阈值 0.01 kg，命中不写 MESOut | 阶段 2 |
| 2 | 1.05 末笔系数仅当 `T ≥ N` 时生效 | 阶段 3 `CASE WHEN T_Total >= N_NeedWriteOff` |
| 3 | 完工率 > 90% 也走 1.05 系数（统一处理） | 阶段 3 |
| 4 | `WeightTotal/GWeightTotal` 含跨期累计 | 阶段 0 沿用 + 阶段 1 累加 |
| 5 | `RBTAG=1/2` 用 `−1.0 × weight` 扣减 | 阶段 1/2/3 全部 `CASE WHEN ... IN (1,2) THEN -1.0 ELSE 1.0` |
| 6 | `SrcTag` 区分来源 (1/2/3) | 阶段 3 |

---

## 部署

1. 在生产数据库执行 `sql/usp_meCreateMOWIP.sql` 创建/更新存储过程
2. 把 `src/meCreateMOWIP.bas` 替换原工程同名模块
3. 调用方式不变：

```vb
Call meCreateMOWIP(objDS, #2025-12-01#, #2025-12-31#, "PERIOD-202512")
```

异常以 `vbObjectError + 1001` 抛出。

# 在制品配比算法（meCreateMOWIP）优化版

> 文件位置：`src/meCreateMOWIP.bas`
> 语言：VB6（调用 SQL Server 2012+）
> 优化日期：2026-04-30
> 原版作者：黄好庭 2025-12-02

---

## 一、业务目标

根据生产收货记录（主表 `mes_ws_materials_in_m` / 明细 `mes_ws_materials_in_i`）
匹配生产上料记录（主表 `mes_ws_materials_out_m` / 明细 `mes_ws_materials_out_i`），
按生产订单 `MOBillID` 配对核销，结果落到：

| 表 | 含义 |
| --- | --- |
| `CO_MO_WIP` | 在制品账：每个 MO 一行，含期初/本期/结余等汇总字段 |
| `CO_MO_WIP_MESOut` | 用料明细：本期每条被核销的上料明细（按 SrcTag 分类） |

---

## 二、算法分阶段

代码拆分为 **4 个阶段**，全部用集合化 SQL 完成（替代原版逐 MO 的 VB 端循环）。

### 阶段 0：清理 + 期初结转
- 删除本期 `CO_MO_WIP_MESOut` / `CO_MO_WIP`（按 PID）
- 取上一会计期（`BEGINDATE < 当前期开始日`，避免误取未来期）
- 上一会计期 `BalWeight <> 0` 的 MO 结转为本期初始记录
  - `InitWeight = 上期 BalWeight`
  - `WeightTotal / GWeightTotal` **沿用上期值**（含跨期累计）

### 阶段 1：本期上料/收货汇总
- `mes_ws_materials_out_*` 按 `MOBillID` 汇总 → MERGE 到 `CO_MO_WIP`
  - 更新 `IWeight`、`BalWeight`（+= 本期）、`WeightTotal`（+= 本期，跨期累计）
- `mes_ws_materials_in_*` 按 `MOBillID` 汇总 → MERGE
  - 更新 `GWeight`、`GWeightTotal`（+= 本期）
- 退/补料 `RBTAG IN (1,2)` 用 `-1.0 * weight` 反向扣减

### 阶段 2：第一轮——按单据级精确匹配
- 在同一 `MOBillID` 下，对**单据级**（`mes_ws_materials_in_m.billid` vs `mes_ws_materials_out_m.billid`）按重量排序，用 `ROW_NUMBER()` 一对一配对
- 配对条件：`ABS(IN.weight - OUT.weight) <= 0.01 kg`
- 命中的上料单据写入临时表 `#MatchedOutBills`
- **精确匹配命中的上料单据不写入 `CO_MO_WIP_MESOut`**（业务规则）

### 阶段 3：第二轮——剩余收货按累计核销

针对每个 MO，定义：

| 符号 | 含义 |
| --- | --- |
| `Init` | `CO_MO_WIP.InitWeight`，期初结余 |
| `S` | 本期未被精确匹配的上料合计 |
| `T` | **待核销总量** = `Init + S` |
| `N` | **需核销量** = 本期未被精确匹配的收货 = `GWeight - 已精确匹配的收货量` |

剩余上料明细按 `RBTAG DESC, billdate, acctime, billid, OutItmID` 排序，
用 `SUM(oweight) OVER (ORDER BY ...)` 计算累计 `cum`。

#### 分配规则

```
若 N <= 0          ⇒ 不做核销（剩余上料留作下期）
若 T >= N          ⇒ 末笔加成（× 1.05）
   - 末笔之前的明细：UseWeight = oweight,        SrcTag = 1
   - 末笔（首条 cum >= N 的明细）：
     UseWeight = (N - prev_cum) × 1.05,         SrcTag = 2
   - 末笔之后的明细：不写
若 T <  N          ⇒ 待核销总量不足覆盖收货
   - 全部剩余上料明细均核销：UseWeight = oweight
   - 最后一条：SrcTag = 3，其余：SrcTag = 1
   - 此时 OWeight = T，BalWeight = 0
```

最后回写：

```
OWeight   = SUM(UseWeight)                     —— 本次核销总量
BalWeight = InitWeight + IWeight - OWeight     —— 剩余待核销
```

---

## 三、SrcTag 来源标记

| SrcTag | 名称 | 含义 |
| :---: | --- | --- |
| `1` | `SRC_NORMAL` | 累计核销-普通行（整笔上料全部核销） |
| `2` | `SRC_LAST_BOOSTED` | 累计核销-末笔加成（× 1.05 系数） |
| `3` | `SRC_LAST_PLAIN` | 累计核销-末笔无加成（待核销总量不足覆盖收货） |

> 精确匹配命中的上料**不写入** `CO_MO_WIP_MESOut`，所以无 `SrcTag = 0`。

---

## 四、与原版（2025-12-02）的差异

### 修复的 Bug

| # | 原版问题 | 修复 |
| :---: | --- | --- |
| 1 | `Dim dblStartWeight As DoubleobjDS.NullToDbl(...)` 编译错误 | 重构后无此变量 |
| 2 | `dblUseWeightTotal` 从未赋值 ⇒ 核销逻辑实际从未生效 | 用 SQL 直接计算 `N = GWeight - 已匹配量` |
| 3 | 缺少第一轮"精确匹配"逻辑 | 新增**阶段 2** |
| 4 | 1.05 系数仅注释 `'* 1.1`，没真正参与运算 | 在阶段 3 SQL 中明确实现 |
| 5 | `dblCurWeight/dblCurQTY` 在 `Do` 循环里 `Dim`，跨轮残值 | 重构后无此问题 |
| 6 | `rsTmp.RecordCount = 0` 分支用未声明的局部变量 | 重构后无此问题 |
| 7 | 累计求和用 `s2.rn <= s1.rn` 自连接，O(n²) | 改为 `SUM() OVER (...)`，O(n) |
| 8 | 上一会计期取 `TOP 1 ORDER BY BEGINDATE`（未限制上界，可能取到未来期） | 加 `BEGINDATE < @CurBegin` |
| 9 | 完工率 > 90% 直接 `OWeight = IWeight + InitWeight, BalWeight = 0`，**不生成明细 ⇒ 总账与明细对不平** | 取消该分支，统一走 1.05 系数累计核销 |
| 10 | 全过程无事务包裹 | 加 `BEGIN/COMMIT/ROLLBACK TRAN` |
| 11 | 日期格式 `yyyy-MM-dd` 依赖会话语言 | 改为 ISO 8601 `yyyyMMdd` |

### 性能优化

- **去掉 VB 端逐 MO `Do While` 循环**：原版每张工单都开一个 `Recordset` + 多次 `ExecSQL` 往返；优化版整个阶段 3 一条 `INSERT ... SELECT` 完成所有 MO。
- **集合化窗口函数**：`SUM() OVER (ORDER BY ...)` 替代自连接，从 O(n²) 降到 O(n)。
- **临时表索引**：`#MatchedOutBills` 主键 `OutBillID`，加速阶段 3 的 `LEFT JOIN`。

### 业务规则落地（与您确认的 6 条）

| # | 规则 | 实现位置 |
| :---: | --- | --- |
| 1 | 精确匹配按单据级，阈值 0.01 kg，命中不写 MESOut | 阶段 2 |
| 2 | 1.05 末笔系数仅当 `T >= N` 时生效 | 阶段 3 `CASE WHEN T_Total >= N_NeedWriteOff` |
| 3 | 完工率 > 90% 也走 1.05 系数（不再特殊处理） | 阶段 3（统一处理） |
| 4 | `WeightTotal/GWeightTotal` 含跨期累计 | 阶段 0 沿用 + 阶段 1 累加 |
| 5 | `RBTAG=1/2` 用 `-1.0 * weight` 扣减 | 阶段 1/2/3 全部 `CASE WHEN ... IN (1,2) THEN -1.0 ELSE 1.0` |
| 6 | `SrcTag` 区分来源 | 阶段 3 用 `1/2/3` 三种值 |

---

## 五、使用方式

把 `src/meCreateMOWIP.bas` 中的内容替换原工程对应模块即可。
依赖：

- `HHDataService.sysDataService`（项目原有数据服务）
- `Me.AppParameters.getPeriodBeginDate(...)`（用于取本期开始日）
- SQL Server 2012+（窗口函数 `SUM() OVER` 和 `MERGE` 语法）

### 调用示例

```vb
Call meCreateMOWIP(objDS, #2025-12-01#, #2025-12-31#, "PERIOD-202512")
```

异常会以 `vbObjectError + 1001` 抛出，并自动 `ROLLBACK TRAN`。

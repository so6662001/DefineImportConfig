Attribute VB_Name = "modCreateMOWIP"
'==============================================================================
' Module    : modCreateMOWIP
' Purpose   : 生成生产入库单的用料明细（在制品配比算法）
' Author    : 优化版 - 2026-04-30
' Original  : 黄好庭 2025-12-02
'------------------------------------------------------------------------------
' 设计说明:
'   原版把整个 4 阶段算法用大段 SQL 字符串拼接 + VB 端逐 MO Do While 循环实现，
'   存在多处 Bug（dblUseWeightTotal 未赋值、缺精确匹配、1.05 系数未生效、
'   完工率>90% 总账明细对不平、O(n²) 自连接等）。
'
'   优化版把 4 阶段算法整体下沉到 SQL Server 存储过程 dbo.usp_meCreateMOWIP
'   （详见 sql/usp_meCreateMOWIP.sql），VB 端仅作为薄壳调用，避免：
'     - 大段 SQL 字符串拼接维护负担
'     - VB ↔ SQL 多次往返
'     - 单元测试困难（脱离 VB6 即可用 sqlcmd 测试存储过程）
'
'   算法简介（详见存储过程注释）：
'     阶段 0  清理 + 上一会计期 BalWeight 结转
'     阶段 1  本期上料/收货按 MOBillID 汇总 MERGE 到 CO_MO_WIP
'     阶段 2  按单据级精确匹配 |IN-OUT|<=0.01kg，命中【不写】CO_MO_WIP_MESOut
'     阶段 3  剩余按累计核销，T>=N 时末笔 × 1.05 系数
'             SrcTag: 1=普通核销 / 2=末笔加成 / 3=末笔无加成
'
'   守恒律: Init + IWeight = OWeight + BalWeight  （任意 MO 始终成立）
'==============================================================================

Option Explicit


'------------------------------------------------------------------------------
' 主过程：调用存储过程 dbo.usp_meCreateMOWIP
'------------------------------------------------------------------------------
Public Sub meCreateMOWIP(ByVal objDS As HHDataService.sysDataService, _
                         ByVal BeginDate As Date, _
                         ByVal EndDate As Date, _
                         ByVal strPID As String)
    Dim SQL As String

    On Error GoTo ErrHandler

    ' 把日期格式化为 ISO 8601，避免依赖 SQL Server 会话语言
    SQL = "EXEC dbo.usp_meCreateMOWIP " & _
          "@PID=N'" & meEscape(strPID) & "', " & _
          "@BeginDate='" & Format$(BeginDate, "yyyyMMdd") & "', " & _
          "@EndDate='"   & Format$(EndDate,   "yyyyMMdd") & "', " & _
          "@PrevAPID=NULL, @UseTran=1"

    Call objDS.ExecSQL(SQL)
    Exit Sub

ErrHandler:
    Err.Raise vbObjectError + 1001, "meCreateMOWIP", _
              "在制品配比生成失败：" & Err.Description
End Sub


'------------------------------------------------------------------------------
' 简单的单引号转义，防止 PID 中含 ' 引发 SQL 注入
'------------------------------------------------------------------------------
Private Function meEscape(ByVal s As String) As String
    meEscape = Replace(s, "'", "''")
End Function

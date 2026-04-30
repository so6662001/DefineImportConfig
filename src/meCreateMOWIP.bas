Attribute VB_Name = "modCreateMOWIP"
'==============================================================================
' Module    : modCreateMOWIP
' Purpose   : 生成生产入库单的用料明细（在制品配比算法）
' Author    : 优化版 - 2026-04-30
' Original  : 黄好庭 2025-12-02
'------------------------------------------------------------------------------
' 算法概述（4 个阶段）：
'   阶段 0：清理本期数据 + 上一会计期 BalWeight 结转为本期 InitWeight
'   阶段 1：本期上料/收货按 MOBillID 汇总，写入 CO_MO_WIP
'           （IWeight、GWeight、WeightTotal、GWeightTotal、BalWeight 含跨期累计）
'   阶段 2：第一轮——按"单据级"精确匹配
'           |IN.weight - OUT.weight| <= 0.01kg 一对一匹配
'           精确匹配命中的上料单据【不写】CO_MO_WIP_MESOut
'           但需从"待核销池"中剔除该上料单据
'   阶段 3：第二轮——剩余收货按累计核销
'           待核销总量 = InitWeight + 本期未被精确匹配的上料量
'           需核销量   = 本期未被精确匹配的收货量
'           按 RBTAG DESC, billdate, acctime 累计取上料明细
'           满足"待核销总量 >= 需核销量"时，末笔 × 1.05 系数
'           SrcTag: 1=普通核销行 / 2=末笔加成 / 3=末笔无加成（待核销不足）
'==============================================================================

Option Explicit

' SrcTag 常量
Private Const SRC_NORMAL        As Long = 1   ' 普通核销行（整笔上料全部核销）
Private Const SRC_LAST_BOOSTED  As Long = 2   ' 末笔加成（×1.05）
Private Const SRC_LAST_PLAIN    As Long = 3   ' 末笔无加成（待核销总量不足覆盖收货）

' 末笔加成系数
Private Const COEF_LAST_BOOST   As Double = 1.05#

' 精确匹配阈值（kg）
Private Const EPS_EXACT_MATCH   As String = "0.01"


'------------------------------------------------------------------------------
' 主过程
'------------------------------------------------------------------------------
Private Sub meCreateMOWIP(ByVal objDS As HHDataService.sysDataService, _
                          ByVal BeginDate As Date, _
                          ByVal EndDate As Date, _
                          ByVal strPID As String)
    Dim SQL         As String
    Dim strBegin    As String
    Dim strEnd      As String
    Dim strPrevAPID As String

    ' 用 ISO 8601 格式避免会话语言导致的隐式转换问题
    strBegin = Format$(BeginDate, "yyyyMMdd")
    strEnd = Format$(EndDate, "yyyyMMdd") & " 23:59:59"

    On Error GoTo ErrHandler

    Call objDS.ExecSQL("BEGIN TRAN")

    '======================================================================
    ' 阶段 0：清理本期数据 + 上一会计期结余结转
    '======================================================================
    Call meStage0_ClearAndCarryOver(objDS, strPID, BeginDate, strPrevAPID)

    '======================================================================
    ' 阶段 1：本期上料/收货汇总 MERGE 到 CO_MO_WIP
    '======================================================================
    Call meStage1_MergeIssueAndReceipt(objDS, strPID, strBegin, strEnd)

    '======================================================================
    ' 阶段 2：第一轮按单据级精确匹配
    '         匹配命中的上料单据写入临时表 #MatchedOutBills
    '======================================================================
    Call meStage2_ExactMatchByBill(objDS, strPID, strBegin, strEnd)

    '======================================================================
    ' 阶段 3：剩余收货按累计核销 + 末笔 1.05 系数
    '         一次性集合化 INSERT 到 CO_MO_WIP_MESOut
    '         同时回写 CO_MO_WIP.OWeight、BalWeight
    '======================================================================
    Call meStage3_CumulativeWriteOff(objDS, strPID, strBegin, strEnd)

    ' 清理临时表
    Call objDS.ExecSQL("IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills")

    ' GWeightTotalPer 完工率
    SQL = "UPDATE CO_MO_WIP " & vbCrLf & _
          "   SET GWeightTotalPer = CASE WHEN ISNULL(WeightTotal,0)=0 THEN 100 " & vbCrLf & _
          "                              ELSE GWeightTotal / WeightTotal * 100 END " & vbCrLf & _
          " WHERE PID = '" & strPID & "'"
    Call objDS.ExecSQL(SQL)

    Call objDS.ExecSQL("COMMIT TRAN")
    Exit Sub

ErrHandler:
    On Error Resume Next
    Call objDS.ExecSQL("ROLLBACK TRAN")
    Call objDS.ExecSQL("IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills")
    On Error GoTo 0
    Err.Raise vbObjectError + 1001, "meCreateMOWIP", _
              "在制品配比生成失败：" & Err.Description
End Sub


'==============================================================================
' 阶段 0：清理 + 期初结转
'==============================================================================
Private Sub meStage0_ClearAndCarryOver(ByVal objDS As HHDataService.sysDataService, _
                                       ByVal strPID As String, _
                                       ByVal BeginDate As Date, _
                                       ByRef strPrevAPID As String)
    Dim SQL    As String
    Dim rsTmp  As ADODB.Recordset

    SQL = "DELETE FROM CO_MO_WIP_MESOut WHERE PID='" & strPID & "'"
    Call objDS.ExecSQL(SQL)

    SQL = "DELETE FROM CO_MO_WIP WHERE PID='" & strPID & "'"
    Call objDS.ExecSQL(SQL)

    ' 取上一会计期：必须严格 < 当前期开始日，避免误取未来期
    Dim strCurBegin As String
    strCurBegin = Format$(Me.AppParameters.getPeriodBeginDate(BeginDate), "yyyyMMdd")

    SQL = "SELECT TOP 1 APID FROM ACCPERIOD " & vbCrLf & _
          " WHERE BEGINDATE < '" & strCurBegin & "' " & vbCrLf & _
          " ORDER BY BEGINDATE DESC"
    Set rsTmp = objDS.OpenRecordsetBySQL(SQL, True, True)

    strPrevAPID = ""
    If Not rsTmp.EOF Then
        strPrevAPID = CStr(rsTmp.Fields("APID").Value)

        ' 把上期 BalWeight <> 0 的 MO 作为本期初始记录
        ' InitWeight = 上期 BalWeight；本期 IWeight/OWeight/GWeight 等清零
        ' WeightTotal/GWeightTotal 含跨期累计 ⇒ 沿用上期值
        SQL = "INSERT INTO CO_MO_WIP (" & vbCrLf & _
              "    id, MOBillID, MOBillNo, PID, " & vbCrLf & _
              "    InitWeight, IWeight, OWeight, BalWeight, " & vbCrLf & _
              "    WeightTotal, GWeight, GWeightTotal, GWeightTotalPer, " & vbCrLf & _
              "    InitATM, IATM, OATM, BalATM, LTime, " & vbCrLf & _
              "    InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM, " & vbCrLf & _
              "    InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM, " & vbCrLf & _
              "    InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM, " & vbCrLf & _
              "    InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM" & vbCrLf & _
              ") " & vbCrLf & _
              "SELECT NEWID(), MOBillID, MOBillNo, '" & strPID & "', " & vbCrLf & _
              "       BalWeight, 0, 0, BalWeight, " & vbCrLf & _
              "       WeightTotal, 0, GWeightTotal, GWeightTotalPer, " & vbCrLf & _
              "       BalATM, 0, 0, BalATM, GETDATE(), " & vbCrLf & _
              "       BalStdCstATM, 0, 0, BalStdCstATM, " & vbCrLf & _
              "       BalTaxATM,    0, 0, BalTaxATM, " & vbCrLf & _
              "       BalStdTaxATM, 0, 0, BalStdTaxATM, " & vbCrLf & _
              "       BalExpTaxATM, 0, 0, BalExpTaxATM " & vbCrLf & _
              "  FROM CO_MO_WIP " & vbCrLf & _
              " WHERE PID = '" & strPrevAPID & "' " & vbCrLf & _
              "   AND ISNULL(BalWeight, 0) <> 0"
        Call objDS.ExecSQL(SQL)
    End If
    Call objDS.rs_Close(rsTmp)
End Sub


'==============================================================================
' 阶段 1：本期上料/收货汇总 MERGE 到 CO_MO_WIP
'==============================================================================
Private Sub meStage1_MergeIssueAndReceipt(ByVal objDS As HHDataService.sysDataService, _
                                          ByVal strPID As String, _
                                          ByVal strBegin As String, _
                                          ByVal strEnd As String)
    Dim SQL As String

    '------ 上料汇总 ------
    SQL = "MERGE INTO CO_MO_WIP AS target " & vbCrLf & _
          "USING ( " & vbCrLf & _
          "   SELECT m.srcbillid AS MOBillID, m.srcbillno AS MOBillNo, " & vbCrLf & _
          "          SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight " & vbCrLf & _
          "     FROM mes_ws_materials_out_m m " & vbCrLf & _
          "     INNER JOIN mes_ws_materials_out_i i ON m.billid = i.billid " & vbCrLf & _
          "     INNER JOIN pl ON m.plid = pl.plid " & vbCrLf & _
          "    WHERE m.billdate BETWEEN '" & strBegin & "' AND '" & strEnd & "' " & vbCrLf & _
          "      AND ISNULL(m.bstate, 0) <> 0 " & vbCrLf & _
          "      AND ISNULL(i.deleted, 0) = 0 " & vbCrLf & _
          "    GROUP BY m.srcbillid, m.srcbillno " & vbCrLf & _
          ") AS source " & vbCrLf & _
          "ON (target.PID = '" & strPID & "' AND target.MOBillID = source.MOBillID) " & vbCrLf & _
          "WHEN MATCHED THEN UPDATE SET " & vbCrLf & _
          "    target.IWeight     = ISNULL(source.weight, 0), " & vbCrLf & _
          "    target.BalWeight   = ISNULL(target.BalWeight, 0)   + ISNULL(source.weight, 0), " & vbCrLf & _
          "    target.WeightTotal = ISNULL(target.WeightTotal, 0) + ISNULL(source.weight, 0) " & vbCrLf & _
          "WHEN NOT MATCHED THEN INSERT ( " & vbCrLf & _
          "    id, MOBillID, MOBillNo, PID, " & vbCrLf & _
          "    InitWeight, IWeight, OWeight, BalWeight, " & vbCrLf & _
          "    WeightTotal, GWeight, GWeightTotal, GWeightTotalPer, " & vbCrLf & _
          "    InitATM, IATM, OATM, BalATM, LTime, " & vbCrLf & _
          "    InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM, " & vbCrLf & _
          "    InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM, " & vbCrLf & _
          "    InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM, " & vbCrLf & _
          "    InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM " & vbCrLf & _
          ") VALUES ( " & vbCrLf & _
          "    NEWID(), source.MOBillID, source.MOBillNo, '" & strPID & "', " & vbCrLf & _
          "    0, source.weight, 0, source.weight, " & vbCrLf & _
          "    source.weight, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0, GETDATE(), " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0 " & vbCrLf & _
          ");"
    Call objDS.ExecSQL(SQL)

    '------ 收货汇总 ------
    SQL = "MERGE INTO CO_MO_WIP AS target " & vbCrLf & _
          "USING ( " & vbCrLf & _
          "   SELECT i.MOBILLID AS MOBillID, i.MOBILLNO AS MOBillNo, " & vbCrLf & _
          "          SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight " & vbCrLf & _
          "     FROM mes_ws_materials_in_m m " & vbCrLf & _
          "     INNER JOIN mes_ws_materials_in_i i ON m.billid = i.billid " & vbCrLf & _
          "     INNER JOIN pl ON m.plid = pl.plid " & vbCrLf & _
          "    WHERE m.billdate BETWEEN '" & strBegin & "' AND '" & strEnd & "' " & vbCrLf & _
          "      AND ISNULL(m.bstate, 0) <> 0 " & vbCrLf & _
          "      AND ISNULL(i.deleted, 0) = 0 " & vbCrLf & _
          "    GROUP BY i.MOBILLID, i.MOBILLNO " & vbCrLf & _
          ") AS source " & vbCrLf & _
          "ON (target.PID = '" & strPID & "' AND target.MOBillID = source.MOBillID) " & vbCrLf & _
          "WHEN MATCHED THEN UPDATE SET " & vbCrLf & _
          "    target.GWeight      = ISNULL(source.weight, 0), " & vbCrLf & _
          "    target.GWeightTotal = ISNULL(target.GWeightTotal, 0) + ISNULL(source.weight, 0) " & vbCrLf & _
          "WHEN NOT MATCHED THEN INSERT ( " & vbCrLf & _
          "    id, MOBillID, MOBillNo, PID, " & vbCrLf & _
          "    InitWeight, IWeight, OWeight, BalWeight, " & vbCrLf & _
          "    WeightTotal, GWeight, GWeightTotal, GWeightTotalPer, " & vbCrLf & _
          "    InitATM, IATM, OATM, BalATM, LTime, " & vbCrLf & _
          "    InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM, " & vbCrLf & _
          "    InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM, " & vbCrLf & _
          "    InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM, " & vbCrLf & _
          "    InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM " & vbCrLf & _
          ") VALUES ( " & vbCrLf & _
          "    NEWID(), source.MOBillID, source.MOBillNo, '" & strPID & "', " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, source.weight, source.weight, 0, " & vbCrLf & _
          "    0, 0, 0, 0, GETDATE(), " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0, " & vbCrLf & _
          "    0, 0, 0, 0 " & vbCrLf & _
          ");"
    Call objDS.ExecSQL(SQL)
End Sub


'==============================================================================
' 阶段 2：第一轮——按单据级精确匹配（|IN.weight - OUT.weight| <= 0.01kg）
'         一对一不重复，命中的上料单据写入 #MatchedOutBills
'         精确匹配【不写】CO_MO_WIP_MESOut（业务规则）
'==============================================================================
Private Sub meStage2_ExactMatchByBill(ByVal objDS As HHDataService.sysDataService, _
                                      ByVal strPID As String, _
                                      ByVal strBegin As String, _
                                      ByVal strEnd As String)
    Dim SQL As String

    Call objDS.ExecSQL("IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills")

    SQL = "CREATE TABLE #MatchedOutBills ( " & vbCrLf & _
          "    MOBillID    NVARCHAR(64) NOT NULL, " & vbCrLf & _
          "    OutBillID   NVARCHAR(64) NOT NULL, " & vbCrLf & _
          "    InBillID    NVARCHAR(64) NOT NULL, " & vbCrLf & _
          "    OutWeight   DECIMAL(28,8) NOT NULL, " & vbCrLf & _
          "    InWeight    DECIMAL(28,8) NOT NULL, " & vbCrLf & _
          "    PRIMARY KEY (OutBillID) " & vbCrLf & _
          ");"
    Call objDS.ExecSQL(SQL)

    ' 单据级汇总：含 RBTAG 反向扣减
    ' 同 MOBillID 内按 weight 排序后用 ROW_NUMBER 一对一配对
    SQL = "WITH OutBills AS ( " & vbCrLf & _
          "   SELECT m.billid     AS OutBillID, " & vbCrLf & _
          "          m.srcbillid  AS MOBillID, " & vbCrLf & _
          "          SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight " & vbCrLf & _
          "     FROM mes_ws_materials_out_m m " & vbCrLf & _
          "     INNER JOIN mes_ws_materials_out_i i ON m.billid = i.billid " & vbCrLf & _
          "    WHERE m.billdate BETWEEN '" & strBegin & "' AND '" & strEnd & "' " & vbCrLf & _
          "      AND ISNULL(m.bstate, 0) <> 0 " & vbCrLf & _
          "      AND ISNULL(i.deleted, 0) = 0 " & vbCrLf & _
          "      AND m.srcbillid IS NOT NULL " & vbCrLf & _
          "    GROUP BY m.billid, m.srcbillid " & vbCrLf & _
          "), " & vbCrLf & _
          "InBills AS ( " & vbCrLf & _
          "   SELECT m.billid    AS InBillID, " & vbCrLf & _
          "          i.MOBILLID  AS MOBillID, " & vbCrLf & _
          "          SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight " & vbCrLf & _
          "     FROM mes_ws_materials_in_m m " & vbCrLf & _
          "     INNER JOIN mes_ws_materials_in_i i ON m.billid = i.billid " & vbCrLf & _
          "    WHERE m.billdate BETWEEN '" & strBegin & "' AND '" & strEnd & "' " & vbCrLf & _
          "      AND ISNULL(m.bstate, 0) <> 0 " & vbCrLf & _
          "      AND ISNULL(i.deleted, 0) = 0 " & vbCrLf & _
          "      AND i.MOBILLID IS NOT NULL " & vbCrLf & _
          "    GROUP BY m.billid, i.MOBILLID " & vbCrLf & _
          "), " & vbCrLf & _
          "OutRanked AS ( " & vbCrLf & _
          "   SELECT *, ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY weight, OutBillID) AS rn " & vbCrLf & _
          "     FROM OutBills " & vbCrLf & _
          "), " & vbCrLf & _
          "InRanked AS ( " & vbCrLf & _
          "   SELECT *, ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY weight, InBillID) AS rn " & vbCrLf & _
          "     FROM InBills " & vbCrLf & _
          ") " & vbCrLf & _
          "INSERT INTO #MatchedOutBills (MOBillID, OutBillID, InBillID, OutWeight, InWeight) " & vbCrLf & _
          "SELECT o.MOBillID, o.OutBillID, i.InBillID, o.weight, i.weight " & vbCrLf & _
          "  FROM OutRanked o " & vbCrLf & _
          "  INNER JOIN InRanked i " & vbCrLf & _
          "     ON o.MOBillID = i.MOBillID " & vbCrLf & _
          "    AND o.rn       = i.rn " & vbCrLf & _
          " WHERE ABS(o.weight - i.weight) <= " & EPS_EXACT_MATCH & ";"
    Call objDS.ExecSQL(SQL)
End Sub


'==============================================================================
' 阶段 3：剩余按累计核销 + 1.05 末笔系数 + SrcTag 区分
'         一次性集合化写入 CO_MO_WIP_MESOut
'         同时回写 CO_MO_WIP.OWeight、BalWeight
'------------------------------------------------------------------------------
'  设：S = SUM(剩余上料 oweight)  （= 待核销总量 - InitWeight，仅本期未匹配部分）
'      T = 待核销总量 = InitWeight + S
'      N = 需核销量   = 本期未被精确匹配的收货量（可由 GWeight 推导，见下）
'
'  注：阶段 2 精确匹配的"上料"和"收货"按单据级一对一抵消，
'      因此从 GWeight 中扣除"已匹配收货量" = SUM(InBills.weight)
'
'  排序：RBTAG DESC（先取退/补料）→ billdate → acctime
'  累计：cum = SUM(oweight) OVER (ORDER BY ...)
'
'  分配规则：
'    若 N <= 0  ⇒ 不核销（全部上料留作下期）
'    若 T >= N  ⇒ 末笔加成（× 1.05）
'        - 找到第一条满足 cum >= N 的明细 = 末笔
'        - 末笔之前的明细：UseWeight = oweight，SrcTag = 1
'        - 末笔本身：UseWeight = (N - prevCum) × 1.05，SrcTag = 2
'        - 末笔之后的明细：不写
'    若 T <  N  ⇒ 待核销总量不足（含 InitWeight 也覆盖不了 N）
'        - 全部剩余上料明细均核销：UseWeight = oweight
'        - 最后一条 SrcTag = 3（末笔无加成），其余 SrcTag = 1
'        - 此时 OWeight = T，BalWeight = InitWeight + IWeight - T = 0
'==============================================================================
Private Sub meStage3_CumulativeWriteOff(ByVal objDS As HHDataService.sysDataService, _
                                        ByVal strPID As String, _
                                        ByVal strBegin As String, _
                                        ByVal strEnd As String)
    Dim SQL As String

    ' 一次性 INSERT 所有 MO 的核销明细
    SQL = "WITH " & vbCrLf & _
          "RemainOut AS ( " & vbCrLf & _
          "   /* 剩余未被精确匹配的上料明细（明细级 i.id） */ " & vbCrLf & _
          "   SELECT m.billid, m.srcbillid AS MOBillID, m.billdate, m.billno, m.acctime, " & vbCrLf & _
          "          ISNULL(m.rbtag, 0) AS RBTAG, i.id AS OutItmID, " & vbCrLf & _
          "          CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0) AS oweight, " & vbCrLf & _
          "          CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.qty,0)    AS oqty " & vbCrLf & _
          "     FROM mes_ws_materials_out_i i " & vbCrLf & _
          "     INNER JOIN mes_ws_materials_out_m m ON m.billid = i.billid " & vbCrLf & _
          "     LEFT  JOIN #MatchedOutBills mb     ON mb.OutBillID = m.billid " & vbCrLf & _
          "    WHERE m.billdate BETWEEN '" & strBegin & "' AND '" & strEnd & "' " & vbCrLf & _
          "      AND ISNULL(m.bstate, 0) <> 0 " & vbCrLf & _
          "      AND ISNULL(i.deleted, 0) = 0 " & vbCrLf & _
          "      AND m.srcbillid IS NOT NULL " & vbCrLf & _
          "      AND mb.OutBillID IS NULL                       /* 排除已精确匹配 */ " & vbCrLf & _
          "), " & vbCrLf & _
          "MatchedInTotal AS ( " & vbCrLf & _
          "   /* 已被精确匹配的收货量（按 MO 汇总） */ " & vbCrLf & _
          "   SELECT MOBillID, SUM(InWeight) AS matched_in " & vbCrLf & _
          "     FROM #MatchedOutBills " & vbCrLf & _
          "    GROUP BY MOBillID " & vbCrLf & _
          "), " & vbCrLf & _
          "Demand AS ( " & vbCrLf & _
          "   /* 每个 MO 的需核销量 N，及待核销总量 T */ " & vbCrLf & _
          "   SELECT w.MOBillID, " & vbCrLf & _
          "          ISNULL(w.GWeight, 0) - ISNULL(mi.matched_in, 0)        AS N_NeedWriteOff, " & vbCrLf & _
          "          ISNULL(w.InitWeight, 0)                                AS Init, " & vbCrLf & _
          "          (SELECT ISNULL(SUM(oweight),0) FROM RemainOut r WHERE r.MOBillID = w.MOBillID) AS S_Remain " & vbCrLf & _
          "     FROM CO_MO_WIP w " & vbCrLf & _
          "     LEFT JOIN MatchedInTotal mi ON mi.MOBillID = w.MOBillID " & vbCrLf & _
          "    WHERE w.PID = '" & strPID & "' " & vbCrLf & _
          "), " & vbCrLf & _
          "Sorted AS ( " & vbCrLf & _
          "   /* 排序 + 累计 */ " & vbCrLf & _
          "   SELECT r.*, " & vbCrLf & _
          "          ROW_NUMBER() OVER (PARTITION BY r.MOBillID " & vbCrLf & _
          "                             ORDER BY r.RBTAG DESC, r.billdate, r.acctime, r.billid, r.OutItmID) AS rn, " & vbCrLf & _
          "          SUM(r.oweight) OVER (PARTITION BY r.MOBillID " & vbCrLf & _
          "                               ORDER BY r.RBTAG DESC, r.billdate, r.acctime, r.billid, r.OutItmID " & vbCrLf & _
          "                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS cum " & vbCrLf & _
          "     FROM RemainOut r " & vbCrLf & _
          "), " & vbCrLf & _
          "Joined AS ( " & vbCrLf & _
          "   SELECT s.*, d.N_NeedWriteOff, d.Init, d.S_Remain, " & vbCrLf & _
          "          (d.Init + d.S_Remain)               AS T_Total, " & vbCrLf & _
          "          (s.cum - s.oweight)                 AS prev_cum, " & vbCrLf & _
          "          MAX(s.rn) OVER (PARTITION BY s.MOBillID) AS max_rn " & vbCrLf & _
          "     FROM Sorted s " & vbCrLf & _
          "     INNER JOIN Demand d ON d.MOBillID = s.MOBillID " & vbCrLf & _
          "    WHERE d.N_NeedWriteOff > 0 " & vbCrLf & _
          "), " & vbCrLf & _
          "Picked AS ( " & vbCrLf & _
          "   /* 每个 MO 选中要写入 MESOut 的明细，并计算 UseWeight、SrcTag */ " & vbCrLf & _
          "   SELECT j.*, " & vbCrLf & _
          "          CASE " & vbCrLf & _
          "             WHEN j.T_Total >= j.N_NeedWriteOff THEN " & vbCrLf & _
          "                  /* 待核销总量足够覆盖收货：按需核销 + 末笔加成 */ " & vbCrLf & _
          "                  CASE " & vbCrLf & _
          "                     WHEN j.cum < j.N_NeedWriteOff THEN j.oweight                /* 末笔之前 */ " & vbCrLf & _
          "                     WHEN j.prev_cum < j.N_NeedWriteOff THEN " & vbCrLf & _
          "                          (j.N_NeedWriteOff - j.prev_cum) * " & CStr(COEF_LAST_BOOST) & "   /* 末笔加成 */ " & vbCrLf & _
          "                     ELSE NULL                                                   /* 末笔之后丢弃 */ " & vbCrLf & _
          "                  END " & vbCrLf & _
          "             ELSE " & vbCrLf & _
          "                  /* 总量不足：剩余上料全部核销，无加成 */ " & vbCrLf & _
          "                  j.oweight " & vbCrLf & _
          "          END AS UseWeight, " & vbCrLf & _
          "          CASE " & vbCrLf & _
          "             WHEN j.T_Total >= j.N_NeedWriteOff THEN " & vbCrLf & _
          "                  CASE " & vbCrLf & _
          "                     WHEN j.cum < j.N_NeedWriteOff THEN " & CStr(SRC_NORMAL) & " " & vbCrLf & _
          "                     WHEN j.prev_cum < j.N_NeedWriteOff THEN " & CStr(SRC_LAST_BOOSTED) & " " & vbCrLf & _
          "                     ELSE NULL " & vbCrLf & _
          "                  END " & vbCrLf & _
          "             ELSE " & vbCrLf & _
          "                  CASE WHEN j.rn = j.max_rn THEN " & CStr(SRC_LAST_PLAIN) & " ELSE " & CStr(SRC_NORMAL) & " END " & vbCrLf & _
          "          END AS SrcTag " & vbCrLf & _
          "     FROM Joined j " & vbCrLf & _
          ") " & vbCrLf & _
          "INSERT INTO CO_MO_WIP_MESOut ( " & vbCrLf & _
          "    id, MOBillID, PID, srcoutbillid, srcoutitmid, " & vbCrLf & _
          "    OutWeight, OutQTY, UseQTY, UseWeight, ltime, SrcTag " & vbCrLf & _
          ") " & vbCrLf & _
          "SELECT NEWID(), p.MOBillID, '" & strPID & "', p.billid, p.OutItmID, " & vbCrLf & _
          "       p.oweight, p.oqty, 0, p.UseWeight, GETDATE(), p.SrcTag " & vbCrLf & _
          "  FROM Picked p " & vbCrLf & _
          " WHERE p.SrcTag IS NOT NULL " & vbCrLf & _
          "   AND p.UseWeight IS NOT NULL;"
    Call objDS.ExecSQL(SQL)

    ' 回写 CO_MO_WIP.OWeight、BalWeight
    '   OWeight  = SUM(UseWeight)            （本次核销总量）
    '   BalWeight = InitWeight + IWeight - OWeight   （剩余待核销）
    SQL = "WITH AGG AS ( " & vbCrLf & _
          "   SELECT MOBillID, SUM(ISNULL(UseWeight,0)) AS sumUse " & vbCrLf & _
          "     FROM CO_MO_WIP_MESOut " & vbCrLf & _
          "    WHERE PID = '" & strPID & "' " & vbCrLf & _
          "    GROUP BY MOBillID " & vbCrLf & _
          ") " & vbCrLf & _
          "UPDATE w SET " & vbCrLf & _
          "    w.OWeight   = ISNULL(a.sumUse, 0), " & vbCrLf & _
          "    w.BalWeight = ISNULL(w.InitWeight, 0) + ISNULL(w.IWeight, 0) - ISNULL(a.sumUse, 0), " & vbCrLf & _
          "    w.LTime     = GETDATE() " & vbCrLf & _
          "  FROM CO_MO_WIP w " & vbCrLf & _
          "  LEFT JOIN AGG a ON a.MOBillID = w.MOBillID " & vbCrLf & _
          " WHERE w.PID = '" & strPID & "';"
    Call objDS.ExecSQL(SQL)
End Sub

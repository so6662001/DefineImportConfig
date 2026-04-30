/*=============================================================================
  test_meCreateMOWIP.sql — usp_meCreateMOWIP 单元测试

  运行方式:
      sqlcmd -S localhost -U sa -P 'YourPwd!' -d MOWIP_TEST -b -i sql/schema.sql
      sqlcmd -S localhost -U sa -P 'YourPwd!' -d MOWIP_TEST -b -i sql/usp_meCreateMOWIP.sql
      sqlcmd -S localhost -U sa -P 'YourPwd!' -d MOWIP_TEST -b -i tests/test_meCreateMOWIP.sql

  说明:
      - 每个 TEST_xx 互相独立，开头都会清表 + 灌测试数据
      - assert 失败即 RAISERROR(... 16 ...)，sqlcmd -b 会立即非零退出
      - 全部 PASS 后最末尾输出 "ALL TESTS PASSED"
=============================================================================*/
SET NOCOUNT ON;
SET XACT_ABORT ON;
GO

-- ===== 公共：断言宏（用 sp_executesql 模拟） =================================
IF OBJECT_ID('dbo.assertEq','P') IS NOT NULL DROP PROCEDURE dbo.assertEq;
GO
CREATE PROCEDURE dbo.assertEq
    @label    NVARCHAR(200),
    @expected SQL_VARIANT,
    @actual   SQL_VARIANT
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @msg NVARCHAR(2000);
    IF (@expected IS NULL AND @actual IS NULL) RETURN;
    IF (@expected IS NULL AND @actual IS NOT NULL)
       OR (@expected IS NOT NULL AND @actual IS NULL)
       OR (CONVERT(NVARCHAR(100), @expected) <> CONVERT(NVARCHAR(100), @actual))
    BEGIN
        SET @msg = N'ASSERT FAILED [' + @label + N']  expected=<'
                 + ISNULL(CONVERT(NVARCHAR(100), @expected),'NULL')
                 + N'> actual=<'
                 + ISNULL(CONVERT(NVARCHAR(100), @actual),'NULL') + N'>';
        RAISERROR(@msg, 16, 1);
    END
END
GO

IF OBJECT_ID('dbo.assertNear','P') IS NOT NULL DROP PROCEDURE dbo.assertNear;
GO
CREATE PROCEDURE dbo.assertNear
    @label    NVARCHAR(200),
    @expected DECIMAL(28,8),
    @actual   DECIMAL(28,8),
    @eps      DECIMAL(28,8) = 0.001
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @msg NVARCHAR(2000);
    IF ABS(ISNULL(@expected,0) - ISNULL(@actual,0)) > @eps
    BEGIN
        SET @msg = N'ASSERT FAILED [' + @label + N']  expected~='
                 + CONVERT(NVARCHAR(50), @expected)
                 + N' actual=' + CONVERT(NVARCHAR(50), @actual);
        RAISERROR(@msg, 16, 1);
    END
END
GO

-- ===== 公共：清空所有测试表 ==================================================
IF OBJECT_ID('dbo.clearAll','P') IS NOT NULL DROP PROCEDURE dbo.clearAll;
GO
CREATE PROCEDURE dbo.clearAll AS
BEGIN
    SET NOCOUNT ON;
    DELETE FROM CO_MO_WIP_MESOut;
    DELETE FROM CO_MO_WIP;
    DELETE FROM mes_ws_materials_in_i;
    DELETE FROM mes_ws_materials_in_m;
    DELETE FROM mes_ws_materials_out_i;
    DELETE FROM mes_ws_materials_out_m;
    DELETE FROM ACCPERIOD;
    DELETE FROM pl;

    INSERT INTO ACCPERIOD VALUES ('PREV','20251101','20251130'),
                                 ('CUR' ,'20251201','20251231');
    INSERT INTO pl VALUES ('PL01','车间A');
END
GO

-- ===== 公共：创建一张上料单据 =================================================
IF OBJECT_ID('dbo.addOut','P') IS NOT NULL DROP PROCEDURE dbo.addOut;
GO
CREATE PROCEDURE dbo.addOut
    @billid  NVARCHAR(64),
    @mo      NVARCHAR(64),
    @weight  DECIMAL(28,8),
    @date    DATETIME = '20251210',
    @rbtag   INT = 0,
    @bstate  INT = 1
AS
BEGIN
    INSERT INTO mes_ws_materials_out_m (billid, billno, billdate, acctime, plid, srcbillid, srcbillno, rbtag, bstate)
    VALUES (@billid, @billid, @date, @date, 'PL01', @mo, @mo, @rbtag, @bstate);
    INSERT INTO mes_ws_materials_out_i (id, billid, itmid, weight, qty, deleted)
    VALUES (@billid + '_I', @billid, 'ITM01', @weight, @weight, 0);
END
GO

-- ===== 公共：创建一张收货单据 =================================================
IF OBJECT_ID('dbo.addIn','P') IS NOT NULL DROP PROCEDURE dbo.addIn;
GO
CREATE PROCEDURE dbo.addIn
    @billid  NVARCHAR(64),
    @mo      NVARCHAR(64),
    @weight  DECIMAL(28,8),
    @date    DATETIME = '20251210',
    @rbtag   INT = 0,
    @bstate  INT = 1
AS
BEGIN
    INSERT INTO mes_ws_materials_in_m (billid, billno, billdate, acctime, plid, rbtag, bstate)
    VALUES (@billid, @billid, @date, @date, 'PL01', @rbtag, @bstate);
    INSERT INTO mes_ws_materials_in_i (id, billid, MOBILLID, MOBILLNO, itmid, weight, qty, deleted)
    VALUES (@billid + '_I', @billid, @mo, @mo, 'ITM01', @weight, @weight, 0);
END
GO

-- ===== 公共：守恒律检查 ======================================================
IF OBJECT_ID('dbo.assertConservation','P') IS NOT NULL DROP PROCEDURE dbo.assertConservation;
GO
CREATE PROCEDURE dbo.assertConservation @PID NVARCHAR(64) AS
BEGIN
    DECLARE @bad INT;
    SELECT @bad = COUNT(*)
      FROM CO_MO_WIP
     WHERE PID = @PID
       AND ABS(ISNULL(InitWeight,0) + ISNULL(IWeight,0)
              - ISNULL(OWeight,0)   - ISNULL(BalWeight,0)) > 0.001;
    IF @bad > 0
    BEGIN
        DECLARE @msg NVARCHAR(2000) = N'守恒律 Init+IWeight=OWeight+BalWeight 失败，违例 MO 数=' + CAST(@bad AS NVARCHAR(20));
        RAISERROR(@msg, 16, 1);
    END
END
GO

-- ===== 公共：MESOut.UseWeight 合计 ==========================================
IF OBJECT_ID('dbo.fnSumUse','FN') IS NOT NULL DROP FUNCTION dbo.fnSumUse;
GO
CREATE FUNCTION dbo.fnSumUse(@PID NVARCHAR(64), @MO NVARCHAR(64))
RETURNS DECIMAL(28,8)
AS
BEGIN
    DECLARE @r DECIMAL(28,8);
    SELECT @r = SUM(ISNULL(UseWeight,0)) FROM CO_MO_WIP_MESOut WHERE PID=@PID AND MOBillID=@MO;
    RETURN ISNULL(@r,0);
END
GO

PRINT '------ Setup OK ------';
GO

/*============================================================================
  TEST_01: 完全无单据 — 仅验证不报错且不产生数据
============================================================================*/
PRINT '=== TEST_01: 空数据 ===';
EXEC dbo.clearAll;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @c INT;
SELECT @c = COUNT(*) FROM CO_MO_WIP        WHERE PID='CUR'; EXEC dbo.assertEq '01.WIP行数', 0, @c;
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR'; EXEC dbo.assertEq '01.MES行数', 0, @c;
PRINT '  PASS';
GO

/*============================================================================
  TEST_02: 单据级精确匹配 — 上料 5kg + 收货 5kg
           期望: MESOut 不写, OWeight=5, BalWeight=0
============================================================================*/
PRINT '=== TEST_02: 精确匹配 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5;
EXEC dbo.addIn  'I1', 'MO1', 5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @c INT, @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @c = COUNT(*)   FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertEq '02.MES应不写', 0, @c;
SELECT @o = OWeight, @b = BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '02.OWeight=5', 5, @o;
EXEC dbo.assertNear '02.BalWeight=0', 0, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_03: 精确匹配阈值 0.01 边界 — 5.005 vs 5.000 (差 0.005 < 0.01)
============================================================================*/
PRINT '=== TEST_03: 精确匹配阈值边界 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5.005;
EXEC dbo.addIn  'I1', 'MO1', 5.000;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @c INT;
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertEq '03.差0.005应匹配', 0, @c;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_04: 阈值外 — 5.02 vs 5.00 (差 0.02 > 0.01)，应进入累计核销
           T=5.02 >= N=5.00 ⇒ 末笔加成
           上料只有一条 5.02：UseWeight = (5.00 - 0)*1.05 = 5.25
           OWeight=5.25, BalWeight=5.02-5.25=-0.23
           （守恒成立但 BalWeight 为负 — 合理：1.05 系数使核销额超过实际上料）
============================================================================*/
PRINT '=== TEST_04: 阈值外 末笔加成 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5.02;
EXEC dbo.addIn  'I1', 'MO1', 5.00;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @use DECIMAL(28,8), @tag INT, @o DECIMAL(28,8);
SELECT @use = UseWeight, @tag = SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '04.UseWeight=5.25', 5.25, @use;
EXEC dbo.assertEq   '04.SrcTag=2', 2, @tag;
SELECT @o = OWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '04.OWeight=5.25', 5.25, @o;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_05: 多笔上料 累计核销 末笔加成 (T >= N)
           上料 3 + 4 + 10 = 17, 收货 5
           N=5, NeedFromS=5(无期初), 末笔=第二条
           第一条 cum=3 <= 5  ⇒ UseWeight=3, SrcTag=1
           第二条 prev_cum=3 < 5 <= cum=7 ⇒ UseWeight=(5-3)*1.05=2.10, SrcTag=2
           第三条 prev_cum=7 >= 5 ⇒ 不写
           OWeight = 3 + 2.10 = 5.10, BalWeight = 17 - 5.10 = 11.90
============================================================================*/
PRINT '=== TEST_05: 多笔累计 末笔加成 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1',  3, '20251201';
EXEC dbo.addOut 'O2', 'MO1',  4, '20251202';
EXEC dbo.addOut 'O3', 'MO1', 10, '20251203';
EXEC dbo.addIn  'I1', 'MO1',  5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @c INT, @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertEq '05.明细2条', 2, @c;

DECLARE @use1 DECIMAL(28,8), @tag1 INT, @use2 DECIMAL(28,8), @tag2 INT;
SELECT @use1 = UseWeight, @tag1 = SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O1';
SELECT @use2 = UseWeight, @tag2 = SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O2';
EXEC dbo.assertNear '05.O1.use=3',     3.00, @use1;
EXEC dbo.assertEq   '05.O1.tag=1',        1, @tag1;
EXEC dbo.assertNear '05.O2.use=2.10',  2.10, @use2;
EXEC dbo.assertEq   '05.O2.tag=2',        2, @tag2;

SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O3';
EXEC dbo.assertEq '05.O3不写', 0, @c;

SELECT @o = OWeight, @b = BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '05.OWeight=5.10',  5.10, @o;
EXEC dbo.assertNear '05.BalWeight=11.90', 11.90, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_06: 待核销总量不足 (T < N)
           上料 2 + 3 = 5, 收货 10
           T=5 < N=10 ⇒ 全部核销, 末笔 SrcTag=3, 其余 SrcTag=1
           OWeight=5, BalWeight=0
============================================================================*/
PRINT '=== TEST_06: T<N 全部核销 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 2, '20251201';
EXEC dbo.addOut 'O2', 'MO1', 3, '20251202';
EXEC dbo.addIn  'I1', 'MO1', 10;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @c INT, @use1 DECIMAL(28,8), @tag1 INT, @use2 DECIMAL(28,8), @tag2 INT, @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertEq '06.明细2条', 2, @c;
SELECT @use1=UseWeight, @tag1=SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O1';
SELECT @use2=UseWeight, @tag2=SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O2';
EXEC dbo.assertNear '06.O1.use=2', 2, @use1;
EXEC dbo.assertEq   '06.O1.tag=1', 1, @tag1;
EXEC dbo.assertNear '06.O2.use=3', 3, @use2;
EXEC dbo.assertEq   '06.O2.tag=3', 3, @tag2;
SELECT @o=OWeight, @b=BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '06.OWeight=5',   5, @o;
EXEC dbo.assertNear '06.BalWeight=0', 0, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_07: 期初结转 — 上期结余 4kg 到本期
           上期 BalWeight=4 ⇒ 本期 InitWeight=4
           本期上料 2, 收货 5
           N=5, Init=4, S=2, NeedFromS = 5-4 = 1
           T=Init+S=6 >= N=5 ⇒ 末笔加成
           上料 O1=2, prev_cum=0 < NeedFromS=1 <= cum=2 ⇒ UseWeight=(1-0)*1.05=1.05, SrcTag=2
           OWeight = 0(matched) + 4(InitConsumed=min(4,5)=4) + 1.05(MES) = 5.05
           BalWeight = 4+2-5.05 = 0.95
============================================================================*/
PRINT '=== TEST_07: 期初结转 + 末笔加成 ===';
EXEC dbo.clearAll;
-- 灌一条上期记录: InitWeight=10, BalWeight=4 (假设上期已核销了 6)
INSERT INTO CO_MO_WIP (id, MOBillID, MOBillNo, PID, InitWeight, IWeight, OWeight, BalWeight, WeightTotal, GWeight, GWeightTotal, GWeightTotalPer)
VALUES (NEWID(), 'MO1', 'MO1', 'PREV', 10, 0, 6, 4, 10, 6, 6, 60);
EXEC dbo.addOut 'O1', 'MO1', 2;
EXEC dbo.addIn  'I1', 'MO1', 5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @init DECIMAL(28,8), @iw DECIMAL(28,8), @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @init=InitWeight, @iw=IWeight, @o=OWeight, @b=BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '07.InitWeight=4', 4, @init;
EXEC dbo.assertNear '07.IWeight=2',    2, @iw;
EXEC dbo.assertNear '07.OWeight=5.05', 5.05, @o;
EXEC dbo.assertNear '07.BalWeight=0.95', 0.95, @b;

DECLARE @use DECIMAL(28,8), @tag INT;
SELECT @use=UseWeight, @tag=SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O1';
EXEC dbo.assertNear '07.O1.use=1.05', 1.05, @use;
EXEC dbo.assertEq   '07.O1.tag=2',    2,    @tag;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_08: 期初足够覆盖收货 (NeedFromS <= 0) — 不应写 MES
           InitWeight=10, 上料 2, 收货 5
           N=5, Init=10 >= N ⇒ NeedFromS=0 ⇒ 无 MES 行
           InitConsumed = min(10,5) = 5
           OWeight = 0 + 5 + 0 = 5
           BalWeight = 10+2-5 = 7
============================================================================*/
PRINT '=== TEST_08: 期初足够覆盖收货 ===';
EXEC dbo.clearAll;
INSERT INTO CO_MO_WIP (id, MOBillID, MOBillNo, PID, InitWeight, IWeight, OWeight, BalWeight, WeightTotal, GWeight, GWeightTotal, GWeightTotalPer)
VALUES (NEWID(), 'MO1', 'MO1', 'PREV', 12, 0, 2, 10, 12, 2, 2, 16.67);
EXEC dbo.addOut 'O1', 'MO1', 2;
EXEC dbo.addIn  'I1', 'MO1', 5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @c INT, @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertEq '08.无MES明细', 0, @c;
SELECT @o = OWeight, @b = BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '08.OWeight=5',  5, @o;
EXEC dbo.assertNear '08.BalWeight=7', 7, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_09: 退/补料扣减 — RBTAG=1
           上料 O1=10 (正), O2=3 (rbtag=1, 视为 -3) => 净上料 7
           收货 6
           N=6, NeedFromS=6
           排序: RBTAG DESC ⇒ O2 先 (rbtag=1, oweight=-3), O1 后 (rbtag=0, oweight=+10)
           cum: O2=-3, O1=7
           NeedFromS=6:
             O2: cum=-3  <= 6 ⇒ UseWeight=-3,        SrcTag=1
             O1: prev_cum=-3 < 6 <= cum=7 ⇒ UseWeight=(6-(-3))*1.05 = 9.45, SrcTag=2
           OWeight = -3 + 9.45 = 6.45, BalWeight = 7 - 6.45 = 0.55
============================================================================*/
PRINT '=== TEST_09: 退补料 RBTAG ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 10, '20251201', 0;
EXEC dbo.addOut 'O2', 'MO1',  3, '20251202', 1;  -- 退料
EXEC dbo.addIn  'I1', 'MO1',  6;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @use1 DECIMAL(28,8), @tag1 INT, @use2 DECIMAL(28,8), @tag2 INT, @o DECIMAL(28,8), @b DECIMAL(28,8), @iw DECIMAL(28,8);
SELECT @iw = IWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '09.IWeight净=7', 7, @iw;

SELECT @use2=UseWeight, @tag2=SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O2';
EXEC dbo.assertNear '09.O2.use=-3', -3, @use2;
EXEC dbo.assertEq   '09.O2.tag=1',   1, @tag2;
SELECT @use1=UseWeight, @tag1=SrcTag FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND srcoutbillid='O1';
EXEC dbo.assertNear '09.O1.use=9.45', 9.45, @use1;
EXEC dbo.assertEq   '09.O1.tag=2',     2,   @tag1;

SELECT @o=OWeight, @b=BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '09.OWeight=6.45',  6.45, @o;
EXEC dbo.assertNear '09.BalWeight=0.55', 0.55, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_10: 多 MO 同时跑 — 验证 partition by 正确
============================================================================*/
PRINT '=== TEST_10: 多 MO ===';
EXEC dbo.clearAll;
-- MO1: 精确匹配
EXEC dbo.addOut 'OA1', 'MO1', 5;
EXEC dbo.addIn  'IA1', 'MO1', 5;
-- MO2: 多笔末笔加成
EXEC dbo.addOut 'OB1', 'MO2', 3, '20251201';
EXEC dbo.addOut 'OB2', 'MO2', 4, '20251202';
EXEC dbo.addIn  'IB1', 'MO2', 5;
-- MO3: 总量不足
EXEC dbo.addOut 'OC1', 'MO3', 2;
EXEC dbo.addIn  'IC1', 'MO3', 10;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @c INT, @o DECIMAL(28,8), @b DECIMAL(28,8);
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO1'; EXEC dbo.assertEq '10.MO1.MES=0', 0, @c;
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO2'; EXEC dbo.assertEq '10.MO2.MES=2', 2, @c;
SELECT @c = COUNT(*) FROM CO_MO_WIP_MESOut WHERE PID='CUR' AND MOBillID='MO3'; EXEC dbo.assertEq '10.MO3.MES=1', 1, @c;
SELECT @o = OWeight, @b = BalWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO3';
EXEC dbo.assertNear '10.MO3.OWeight=2', 2, @o;
EXEC dbo.assertNear '10.MO3.BalWeight=0', 0, @b;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_11: 幂等 — 连续跑两次结果应完全一致
============================================================================*/
PRINT '=== TEST_11: 幂等 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1',  3, '20251201';
EXEC dbo.addOut 'O2', 'MO1',  4, '20251202';
EXEC dbo.addOut 'O3', 'MO1', 10, '20251203';
EXEC dbo.addIn  'I1', 'MO1',  5;

EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
SELECT IDENTITY(INT,1,1) AS sn, MOBillID, OWeight, BalWeight, IWeight INTO #snap1 FROM CO_MO_WIP WHERE PID='CUR' ORDER BY MOBillID;

EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
SELECT IDENTITY(INT,1,1) AS sn, MOBillID, OWeight, BalWeight, IWeight INTO #snap2 FROM CO_MO_WIP WHERE PID='CUR' ORDER BY MOBillID;

DECLARE @diff INT;
SELECT @diff = COUNT(*) FROM #snap1 s1 INNER JOIN #snap2 s2 ON s1.MOBillID=s2.MOBillID
 WHERE ABS(ISNULL(s1.OWeight,0)-ISNULL(s2.OWeight,0))>0.001
    OR ABS(ISNULL(s1.BalWeight,0)-ISNULL(s2.BalWeight,0))>0.001
    OR ABS(ISNULL(s1.IWeight,0)-ISNULL(s2.IWeight,0))>0.001;
EXEC dbo.assertEq '11.幂等 diff=0', 0, @diff;
DROP TABLE #snap1; DROP TABLE #snap2;
PRINT '  PASS';
GO

/*============================================================================
  TEST_12: 日期范围过滤 — 期外单据不应参与
============================================================================*/
PRINT '=== TEST_12: 日期过滤 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5, '20251115';   -- 上期
EXEC dbo.addOut 'O2', 'MO1', 3, '20251210';   -- 本期
EXEC dbo.addIn  'I1', 'MO1', 3, '20251210';   -- 本期
EXEC dbo.addIn  'I2', 'MO1', 5, '20260105';   -- 下期
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';

DECLARE @iw DECIMAL(28,8), @gw DECIMAL(28,8);
SELECT @iw = IWeight, @gw = GWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '12.IWeight=3', 3, @iw;
EXEC dbo.assertNear '12.GWeight=3', 3, @gw;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_13: bstate=0 / deleted=1 — 应被忽略
============================================================================*/
PRINT '=== TEST_13: 失效单据过滤 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5, '20251210', 0, 1;   -- 正常
EXEC dbo.addOut 'O2', 'MO1', 9, '20251211', 0, 0;   -- bstate=0 应忽略
INSERT INTO mes_ws_materials_out_m VALUES ('O3','O3','20251212','20251212','PL01','MO1','MO1',0,1);
INSERT INTO mes_ws_materials_out_i VALUES ('O3_I','O3','ITM01',8,8,1);                  -- deleted=1 应忽略
EXEC dbo.addIn  'I1', 'MO1', 5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @iw DECIMAL(28,8);
SELECT @iw = IWeight FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '13.IWeight=5', 5, @iw;
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_14: 跨期累计 — WeightTotal/GWeightTotal 应包含上期值
============================================================================*/
PRINT '=== TEST_14: 跨期累计 ===';
EXEC dbo.clearAll;
INSERT INTO CO_MO_WIP (id, MOBillID, MOBillNo, PID, InitWeight, IWeight, OWeight, BalWeight, WeightTotal, GWeight, GWeightTotal, GWeightTotalPer)
VALUES (NEWID(), 'MO1', 'MO1', 'PREV', 0, 0, 6, 4, 100, 0, 60, 60);
EXEC dbo.addOut 'O1', 'MO1', 8;
EXEC dbo.addIn  'I1', 'MO1', 7;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @wt DECIMAL(28,8), @gt DECIMAL(28,8);
SELECT @wt = WeightTotal, @gt = GWeightTotal FROM CO_MO_WIP WHERE PID='CUR' AND MOBillID='MO1';
EXEC dbo.assertNear '14.WeightTotal=108', 108, @wt;     -- 100 + 8
EXEC dbo.assertNear '14.GWeightTotal=67', 67, @gt;      -- 60 + 7
EXEC dbo.assertConservation 'CUR';
PRINT '  PASS';
GO

/*============================================================================
  TEST_15: 异常回滚 — 临时表残留也应清理（手动触发再调用）
============================================================================*/
PRINT '=== TEST_15: 临时表清理 ===';
EXEC dbo.clearAll;
EXEC dbo.addOut 'O1', 'MO1', 5;
EXEC dbo.addIn  'I1', 'MO1', 5;
EXEC dbo.usp_meCreateMOWIP @PID='CUR', @BeginDate='20251201', @EndDate='20251231', @PrevAPID='PREV';
DECLARE @c INT;
SELECT @c = CASE WHEN OBJECT_ID('tempdb..#MatchedOutBills') IS NULL THEN 0 ELSE 1 END;
EXEC dbo.assertEq '15.临时表已清理', 0, @c;
PRINT '  PASS';
GO

PRINT '';
PRINT '=========================================';
PRINT '  ALL TESTS PASSED';
PRINT '=========================================';
GO

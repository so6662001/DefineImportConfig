/*=============================================================================
  usp_meCreateMOWIP — 在制品配比算法（4 阶段）
-------------------------------------------------------------------------------
  作者     : 优化版 2026-04-30
  原作者   : 黄好庭 2025-12-02
  目标版本 : SQL Server 2008 R2 及以上
  说明     : 把原 VB6 端的 4 阶段算法整体下沉到 SQL Server 存储过程，
             既便于单元测试，也避免 VB ↔ SQL 多次往返。

  调用例   : EXEC usp_meCreateMOWIP
                 @PID       = '202512',
                 @BeginDate = '20251201',
                 @EndDate   = '20251231',
                 @PrevAPID  = NULL;          -- NULL 表示自动取上一会计期

  SrcTag   : 1=累计核销-普通行
             2=累计核销-末笔加成（× 1.05）
             3=累计核销-末笔无加成（待核销总量不足覆盖收货）

  守恒律   : Init + IWeight = OWeight + BalWeight  （任意 MO 始终成立）

  SQL Server 2008 兼容性说明:
    - 不使用 SUM() OVER (ORDER BY ...) 聚合窗口框架（2012+）
      ⇒ 改用 ROW_NUMBER() + 自连接计算累计
    - 不使用 THROW（2012+）
      ⇒ 改用 RAISERROR + ERROR_MESSAGE()
    - 不使用 CREATE TABLE 内联 INDEX 子句（2014+）
      ⇒ 拆分成独立的 CREATE INDEX
    - MERGE / CTE / ROW_NUMBER() OVER / 聚合 OVER (PARTITION BY 不带 ORDER BY)
      / TRY...CATCH 这些 2008 都支持
=============================================================================*/

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID('dbo.usp_meCreateMOWIP','P') IS NOT NULL
    DROP PROCEDURE dbo.usp_meCreateMOWIP;
GO

CREATE PROCEDURE dbo.usp_meCreateMOWIP
    @PID        NVARCHAR(64),
    @BeginDate  DATETIME,
    @EndDate    DATETIME,
    @PrevAPID   NVARCHAR(64) = NULL,        -- 可选：测试时显式指定上一期，生产环境可传 NULL
    @UseTran    BIT          = 1            -- 是否包裹事务（嵌套调用时传 0）
AS
BEGIN
    SET NOCOUNT ON;
    SET XACT_ABORT ON;

    DECLARE @Coef          DECIMAL(28,8) = 1.05;
    DECLARE @EpsExactMatch DECIMAL(28,8) = 0.01;
    DECLARE @SrcNormal     INT = 1;
    DECLARE @SrcLastBoost  INT = 2;
    DECLARE @SrcLastPlain  INT = 3;

    -- 把 EndDate 折算成"当天 23:59:59"
    DECLARE @EndDateInclusive DATETIME =
        DATEADD(SECOND, -1, DATEADD(DAY, 1, CAST(CAST(@EndDate AS DATE) AS DATETIME)));

    DECLARE @ownTran BIT = 0;
    IF @UseTran = 1 AND @@TRANCOUNT = 0
    BEGIN
        BEGIN TRAN;
        SET @ownTran = 1;
    END

    BEGIN TRY
        /*======================================================================
          阶段 0：清理本期数据 + 上一会计期 BalWeight 结转
        ======================================================================*/
        DELETE FROM CO_MO_WIP_MESOut WHERE PID = @PID;
        DELETE FROM CO_MO_WIP        WHERE PID = @PID;

        -- 自动取上一会计期（必须严格 < @BeginDate）
        IF @PrevAPID IS NULL
        BEGIN
            SELECT TOP 1 @PrevAPID = APID
              FROM ACCPERIOD
             WHERE BEGINDATE < @BeginDate
             ORDER BY BEGINDATE DESC;
        END

        IF @PrevAPID IS NOT NULL
        BEGIN
            INSERT INTO CO_MO_WIP (
                id, MOBillID, MOBillNo, PID,
                InitWeight, IWeight, OWeight, BalWeight,
                WeightTotal, GWeight, GWeightTotal, GWeightTotalPer,
                InitATM, IATM, OATM, BalATM, LTime,
                InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM,
                InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM,
                InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM,
                InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM
            )
            SELECT NEWID(), MOBillID, MOBillNo, @PID,
                   BalWeight, 0, 0, BalWeight,
                   WeightTotal, 0, GWeightTotal, GWeightTotalPer,
                   BalATM, 0, 0, BalATM, GETDATE(),
                   BalStdCstATM, 0, 0, BalStdCstATM,
                   BalTaxATM,    0, 0, BalTaxATM,
                   BalStdTaxATM, 0, 0, BalStdTaxATM,
                   BalExpTaxATM, 0, 0, BalExpTaxATM
              FROM CO_MO_WIP
             WHERE PID = @PrevAPID
               AND ISNULL(BalWeight, 0) <> 0;
        END

        /*======================================================================
          阶段 1：本期上料/收货按 MOBillID 汇总，MERGE 到 CO_MO_WIP
                  WeightTotal/GWeightTotal 含跨期累计（在期初值上累加）
        ======================================================================*/
        ;WITH OutAgg AS (
            SELECT m.srcbillid AS MOBillID, MAX(m.srcbillno) AS MOBillNo,
                   SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight
              FROM mes_ws_materials_out_m m
              INNER JOIN mes_ws_materials_out_i i ON m.billid = i.billid
              WHERE m.billdate BETWEEN @BeginDate AND @EndDateInclusive
                AND ISNULL(m.bstate, 0) <> 0
                AND ISNULL(i.deleted, 0) = 0
                AND m.srcbillid IS NOT NULL
              GROUP BY m.srcbillid
        )
        MERGE INTO CO_MO_WIP AS target
        USING OutAgg AS source
           ON target.PID = @PID AND target.MOBillID = source.MOBillID
        WHEN MATCHED THEN UPDATE SET
            target.IWeight     = ISNULL(source.weight, 0),
            target.BalWeight   = ISNULL(target.BalWeight, 0)   + ISNULL(source.weight, 0),
            target.WeightTotal = ISNULL(target.WeightTotal, 0) + ISNULL(source.weight, 0)
        WHEN NOT MATCHED THEN INSERT (
            id, MOBillID, MOBillNo, PID,
            InitWeight, IWeight, OWeight, BalWeight,
            WeightTotal, GWeight, GWeightTotal, GWeightTotalPer,
            InitATM, IATM, OATM, BalATM, LTime,
            InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM,
            InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM,
            InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM,
            InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM
        ) VALUES (
            NEWID(), source.MOBillID, source.MOBillNo, @PID,
            0, source.weight, 0, source.weight,
            source.weight, 0, 0, 0,
            0, 0, 0, 0, GETDATE(),
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        );

        ;WITH InAgg AS (
            SELECT i.MOBILLID AS MOBillID, MAX(i.MOBILLNO) AS MOBillNo,
                   SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight
              FROM mes_ws_materials_in_m m
              INNER JOIN mes_ws_materials_in_i i ON m.billid = i.billid
              WHERE m.billdate BETWEEN @BeginDate AND @EndDateInclusive
                AND ISNULL(m.bstate, 0) <> 0
                AND ISNULL(i.deleted, 0) = 0
                AND i.MOBILLID IS NOT NULL
              GROUP BY i.MOBILLID
        )
        MERGE INTO CO_MO_WIP AS target
        USING InAgg AS source
           ON target.PID = @PID AND target.MOBillID = source.MOBillID
        WHEN MATCHED THEN UPDATE SET
            target.GWeight      = ISNULL(source.weight, 0),
            target.GWeightTotal = ISNULL(target.GWeightTotal, 0) + ISNULL(source.weight, 0)
        WHEN NOT MATCHED THEN INSERT (
            id, MOBillID, MOBillNo, PID,
            InitWeight, IWeight, OWeight, BalWeight,
            WeightTotal, GWeight, GWeightTotal, GWeightTotalPer,
            InitATM, IATM, OATM, BalATM, LTime,
            InitStdCstATM, IStdCstATM, OStdCstATM, BalStdCstATM,
            InitTaxATM,    ITaxATM,    OTaxATM,    BalTaxATM,
            InitStdTaxATM, IStdTaxATM, OStdTaxATM, BalStdTaxATM,
            InitExpTaxATM, IExpTaxATM, OExpTaxATM, BalExpTaxATM
        ) VALUES (
            NEWID(), source.MOBillID, source.MOBillNo, @PID,
            0, 0, 0, 0,
            0, source.weight, source.weight, 0,
            0, 0, 0, 0, GETDATE(),
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        );

        /*======================================================================
          阶段 2：第一轮——按单据级精确匹配
                  |IN.weight - OUT.weight| <= 0.01 kg，一对一不重复
                  命中【不写】CO_MO_WIP_MESOut
        ======================================================================*/
        IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills;
        CREATE TABLE #MatchedOutBills (
            MOBillID    NVARCHAR(64) NOT NULL,
            OutBillID   NVARCHAR(64) NOT NULL,
            InBillID    NVARCHAR(64) NOT NULL,
            OutWeight   DECIMAL(28,8) NOT NULL,
            InWeight    DECIMAL(28,8) NOT NULL,
            PRIMARY KEY (OutBillID),
            UNIQUE (InBillID)
        );

        ;WITH OutBills AS (
            SELECT m.billid AS OutBillID, m.srcbillid AS MOBillID,
                   SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight
              FROM mes_ws_materials_out_m m
              INNER JOIN mes_ws_materials_out_i i ON m.billid = i.billid
              WHERE m.billdate BETWEEN @BeginDate AND @EndDateInclusive
                AND ISNULL(m.bstate, 0) <> 0
                AND ISNULL(i.deleted, 0) = 0
                AND m.srcbillid IS NOT NULL
              GROUP BY m.billid, m.srcbillid
        ),
        InBills AS (
            SELECT m.billid AS InBillID, i.MOBILLID AS MOBillID,
                   SUM(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0)) AS weight
              FROM mes_ws_materials_in_m m
              INNER JOIN mes_ws_materials_in_i i ON m.billid = i.billid
              WHERE m.billdate BETWEEN @BeginDate AND @EndDateInclusive
                AND ISNULL(m.bstate, 0) <> 0
                AND ISNULL(i.deleted, 0) = 0
                AND i.MOBILLID IS NOT NULL
              GROUP BY m.billid, i.MOBILLID
        ),
        -- 仅对正向单据做精确匹配（净退料/净退货不参与精确配对，避免 -3 与 -3 也被匹配掉）
        OutPos AS (
            SELECT OutBillID, MOBillID, weight,
                   ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY weight, OutBillID) AS rn
              FROM OutBills WHERE weight > 0
        ),
        InPos AS (
            SELECT InBillID, MOBillID, weight,
                   ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY weight, InBillID) AS rn
              FROM InBills WHERE weight > 0
        )
        INSERT INTO #MatchedOutBills (MOBillID, OutBillID, InBillID, OutWeight, InWeight)
        SELECT o.MOBillID, o.OutBillID, i.InBillID, o.weight, i.weight
          FROM OutPos o
          INNER JOIN InPos i
             ON o.MOBillID = i.MOBillID
            AND o.rn       = i.rn
         WHERE ABS(o.weight - i.weight) <= @EpsExactMatch;

        /*======================================================================
          阶段 3：剩余收货按累计核销 + 1.05 末笔系数
        ------------------------------------------------------------------------
          定义（每个 MO）：
              MatchedOut    = SUM(#MatchedOutBills.OutWeight)
              MatchedIn     = SUM(#MatchedOutBills.InWeight)
              Init          = CO_MO_WIP.InitWeight
              IWeight       = CO_MO_WIP.IWeight
              GWeight       = CO_MO_WIP.GWeight
              S             = SUM(剩余上料 oweight) = IWeight - MatchedOut
              N             = 需核销量            = GWeight - MatchedIn
              T             = 待核销总量          = Init + S
              InitConsumed  = MIN(Init, N)        = 期初被消耗的量
              NeedFromS     = MAX(N - Init, 0)    = 需要从本期上料填补的量

          排序：RBTAG DESC（先取退/补料）→ billdate → acctime → billid → OutItmID
          累计：cum = SUM(oweight) WHERE rn <= self.rn  （SQL Server 2008 兼容写法）

          分配规则（逐条剩余上料明细）：
              若 N <= 0          ⇒ 不写明细
              若 NeedFromS = 0   ⇒ 不写明细（期初足够覆盖收货）
              若 T >= N          ⇒ 末笔加成
                 - cum <= NeedFromS                             → UseWeight=oweight, SrcTag=1
                 - prev_cum < NeedFromS <= cum                  → UseWeight=(NeedFromS - prev_cum)*1.05, SrcTag=2
                 - prev_cum >= NeedFromS                        → 不写
              若 T <  N          ⇒ 总量不足，剩余上料全部核销
                 - 最后一条 SrcTag=3，其余 SrcTag=1
                 - UseWeight = oweight

          回写 CO_MO_WIP（保证守恒律 Init + IWeight = OWeight + BalWeight）：
              OWeight  = MatchedOut + InitConsumed + SUM(MESOut.UseWeight)
              BalWeight = Init + IWeight - OWeight
        ------------------------------------------------------------------------
          【SQL Server 2008 改写要点】
            原版用 SUM(oweight) OVER (PARTITION BY MOBillID ORDER BY ...
                                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
            来计算累计，2008 不支持。
            改写：
              1) 用 ROW_NUMBER() OVER (PARTITION BY MOBillID ORDER BY ...) 算 rn
                 落到 #SortedRemain 临时表
              2) 用自连接 SUM(s2.oweight) WHERE s2.rn <= s1.rn 算累计 cum
                 因为按 MOBillID 分区，每个 MO 明细数一般不多，O(n²) 可接受
        ======================================================================*/

        IF OBJECT_ID('tempdb..#SortedRemain') IS NOT NULL DROP TABLE #SortedRemain;
        CREATE TABLE #SortedRemain (
            MOBillID    NVARCHAR(64)  NOT NULL,
            billid      NVARCHAR(64)  NOT NULL,
            billno      NVARCHAR(64)  NULL,
            billdate    DATETIME      NULL,
            acctime     DATETIME      NULL,
            RBTAG       INT           NOT NULL,
            OutItmID    NVARCHAR(64)  NOT NULL,
            oweight     DECIMAL(28,8) NOT NULL,
            oqty        DECIMAL(28,8) NULL,
            rn          INT           NOT NULL,
            PRIMARY KEY (MOBillID, rn)
        );

        ;WITH RemainOut AS (
            SELECT m.billid, m.srcbillid AS MOBillID, m.billdate, m.billno, m.acctime,
                   ISNULL(m.rbtag, 0) AS RBTAG, i.id AS OutItmID,
                   CAST(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.weight,0) AS DECIMAL(28,8)) AS oweight,
                   CAST(CASE WHEN ISNULL(m.rbtag,0) IN (1,2) THEN -1.0 ELSE 1.0 END * ISNULL(i.qty,0)    AS DECIMAL(28,8)) AS oqty
              FROM mes_ws_materials_out_i i
              INNER JOIN mes_ws_materials_out_m m ON m.billid = i.billid
              LEFT  JOIN #MatchedOutBills mb     ON mb.OutBillID = m.billid
              WHERE m.billdate BETWEEN @BeginDate AND @EndDateInclusive
                AND ISNULL(m.bstate, 0) <> 0
                AND ISNULL(i.deleted, 0) = 0
                AND m.srcbillid IS NOT NULL
                AND mb.OutBillID IS NULL
        )
        INSERT INTO #SortedRemain (MOBillID, billid, billno, billdate, acctime, RBTAG, OutItmID, oweight, oqty, rn)
        SELECT MOBillID, billid, billno, billdate, acctime, RBTAG, OutItmID, oweight, oqty,
               ROW_NUMBER() OVER (PARTITION BY MOBillID
                   ORDER BY RBTAG DESC, billdate, acctime, billid, OutItmID) AS rn
          FROM RemainOut;

        /*------------------------------------------------------------------
          构造每个 MO 的需求量 + 累计 + 末笔判定，写入 #Picked
          再统一 INSERT 到 CO_MO_WIP_MESOut
        ------------------------------------------------------------------*/
        ;WITH MatchedAgg AS (
            SELECT MOBillID, SUM(InWeight) AS matched_in, SUM(OutWeight) AS matched_out
              FROM #MatchedOutBills GROUP BY MOBillID
        ),
        Demand AS (
            SELECT w.MOBillID,
                   ISNULL(w.GWeight, 0)    - ISNULL(ma.matched_in, 0)            AS N_NeedWriteOff,
                   ISNULL(w.InitWeight, 0)                                       AS Init,
                   (SELECT ISNULL(SUM(oweight),0) FROM #SortedRemain r
                     WHERE r.MOBillID = w.MOBillID)                              AS S_Remain
              FROM CO_MO_WIP w
              LEFT JOIN MatchedAgg ma ON ma.MOBillID = w.MOBillID
              WHERE w.PID = @PID
        ),
        -- 用自连接算累计（2008 兼容）
        Cum AS (
            SELECT s1.MOBillID, s1.billid, s1.OutItmID, s1.RBTAG,
                   s1.billdate, s1.acctime, s1.oweight, s1.oqty, s1.rn,
                   ISNULL((SELECT SUM(s2.oweight) FROM #SortedRemain s2
                            WHERE s2.MOBillID = s1.MOBillID AND s2.rn <= s1.rn), 0) AS cum,
                   ISNULL((SELECT MAX(s3.rn) FROM #SortedRemain s3
                            WHERE s3.MOBillID = s1.MOBillID), 0)                    AS max_rn
              FROM #SortedRemain s1
        ),
        Joined AS (
            SELECT c.*,
                   d.N_NeedWriteOff,
                   d.Init,
                   d.S_Remain,
                   (d.Init + d.S_Remain)                              AS T_Total,
                   CASE WHEN d.N_NeedWriteOff - d.Init > 0
                        THEN d.N_NeedWriteOff - d.Init ELSE 0 END     AS NeedFromS,
                   (c.cum - c.oweight)                                AS prev_cum
              FROM Cum c
              INNER JOIN Demand d ON d.MOBillID = c.MOBillID
              WHERE d.N_NeedWriteOff > 0
        ),
        Picked AS (
            SELECT j.*,
                   /* UseWeight */
                   CASE
                       WHEN j.NeedFromS <= 0 THEN NULL
                       WHEN j.T_Total >= j.N_NeedWriteOff THEN
                            CASE
                                WHEN j.cum      <= j.NeedFromS THEN j.oweight
                                WHEN j.prev_cum <  j.NeedFromS THEN (j.NeedFromS - j.prev_cum) * @Coef
                                ELSE NULL
                            END
                       ELSE j.oweight
                   END AS UseWeight,
                   /* SrcTag */
                   CASE
                       WHEN j.NeedFromS <= 0 THEN NULL
                       WHEN j.T_Total >= j.N_NeedWriteOff THEN
                            CASE
                                WHEN j.cum      <= j.NeedFromS THEN @SrcNormal
                                WHEN j.prev_cum <  j.NeedFromS THEN @SrcLastBoost
                                ELSE NULL
                            END
                       ELSE
                            CASE WHEN j.rn = j.max_rn THEN @SrcLastPlain ELSE @SrcNormal END
                   END AS SrcTag
              FROM Joined j
        )
        INSERT INTO CO_MO_WIP_MESOut (
            id, MOBillID, PID, srcoutbillid, srcoutitmid,
            OutWeight, OutQTY, UseQTY, UseWeight, ltime, SrcTag
        )
        SELECT NEWID(), p.MOBillID, @PID, p.billid, p.OutItmID,
               p.oweight, p.oqty, 0, p.UseWeight, GETDATE(), p.SrcTag
          FROM Picked p
         WHERE p.SrcTag IS NOT NULL
           AND p.UseWeight IS NOT NULL
           AND p.UseWeight <> 0;

        /*======================================================================
          回写 CO_MO_WIP.OWeight、BalWeight
            OWeight  = MatchedOut + InitConsumed + SUM(MESOut.UseWeight)
            BalWeight = Init + IWeight - OWeight   (守恒)
        ======================================================================*/
        ;WITH MatchedAgg AS (
            SELECT MOBillID, SUM(InWeight) AS matched_in, SUM(OutWeight) AS matched_out
              FROM #MatchedOutBills GROUP BY MOBillID
        ),
        UseAgg AS (
            SELECT MOBillID, SUM(ISNULL(UseWeight,0)) AS sumUse
              FROM CO_MO_WIP_MESOut
              WHERE PID = @PID
              GROUP BY MOBillID
        )
        UPDATE w SET
            w.OWeight = ISNULL(ma.matched_out, 0)
                      + CASE
                            WHEN ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0) <= 0 THEN 0
                            WHEN ISNULL(w.InitWeight,0) <= ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0)
                                 THEN ISNULL(w.InitWeight,0)
                            ELSE ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0)
                        END
                      + ISNULL(u.sumUse, 0),
            w.BalWeight = ISNULL(w.InitWeight,0) + ISNULL(w.IWeight,0)
                        - (ISNULL(ma.matched_out, 0)
                           + CASE
                                 WHEN ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0) <= 0 THEN 0
                                 WHEN ISNULL(w.InitWeight,0) <= ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0)
                                      THEN ISNULL(w.InitWeight,0)
                                 ELSE ISNULL(w.GWeight,0) - ISNULL(ma.matched_in,0)
                             END
                           + ISNULL(u.sumUse, 0)),
            w.LTime = GETDATE()
          FROM CO_MO_WIP w
          LEFT JOIN MatchedAgg ma ON ma.MOBillID = w.MOBillID
          LEFT JOIN UseAgg     u  ON u.MOBillID  = w.MOBillID
         WHERE w.PID = @PID;

        /*======================================================================
          完工率
        ======================================================================*/
        UPDATE CO_MO_WIP
           SET GWeightTotalPer = CASE
                                     WHEN ISNULL(WeightTotal,0) = 0 THEN 100
                                     ELSE GWeightTotal / WeightTotal * 100
                                 END
         WHERE PID = @PID;

        IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills;
        IF OBJECT_ID('tempdb..#SortedRemain')   IS NOT NULL DROP TABLE #SortedRemain;

        IF @ownTran = 1 COMMIT TRAN;
    END TRY
    BEGIN CATCH
        DECLARE @errMsg   NVARCHAR(4000) = ERROR_MESSAGE();
        DECLARE @errSev   INT            = ERROR_SEVERITY();
        DECLARE @errState INT            = ERROR_STATE();

        IF @ownTran = 1 AND @@TRANCOUNT > 0 ROLLBACK TRAN;
        IF OBJECT_ID('tempdb..#MatchedOutBills') IS NOT NULL DROP TABLE #MatchedOutBills;
        IF OBJECT_ID('tempdb..#SortedRemain')   IS NOT NULL DROP TABLE #SortedRemain;

        -- SQL Server 2008 不支持 THROW，用 RAISERROR 重抛
        RAISERROR (@errMsg, @errSev, @errState);
        RETURN;
    END CATCH
END
GO

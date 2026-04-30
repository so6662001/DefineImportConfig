/*=============================================================================
  schema.sql — 在制品配比算法相关表结构
  目的：脱离生产库，独立创建测试库及全部相关表
       （字段类型与生产库一致，无关字段简化为可空）
=============================================================================*/

SET NOCOUNT ON;
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- 删表（按依赖反向）
IF OBJECT_ID('CO_MO_WIP_MESOut','U')          IS NOT NULL DROP TABLE CO_MO_WIP_MESOut;
IF OBJECT_ID('CO_MO_WIP','U')                 IS NOT NULL DROP TABLE CO_MO_WIP;
IF OBJECT_ID('mes_ws_materials_in_i','U')     IS NOT NULL DROP TABLE mes_ws_materials_in_i;
IF OBJECT_ID('mes_ws_materials_in_m','U')     IS NOT NULL DROP TABLE mes_ws_materials_in_m;
IF OBJECT_ID('mes_ws_materials_out_i','U')    IS NOT NULL DROP TABLE mes_ws_materials_out_i;
IF OBJECT_ID('mes_ws_materials_out_m','U')    IS NOT NULL DROP TABLE mes_ws_materials_out_m;
IF OBJECT_ID('pl','U')                        IS NOT NULL DROP TABLE pl;
IF OBJECT_ID('ACCPERIOD','U')                 IS NOT NULL DROP TABLE ACCPERIOD;
GO

CREATE TABLE ACCPERIOD (
    APID        NVARCHAR(64) NOT NULL PRIMARY KEY,
    BEGINDATE   DATETIME     NOT NULL,
    ENDDATE     DATETIME     NULL
);
GO

CREATE TABLE pl (
    plid        NVARCHAR(64) NOT NULL PRIMARY KEY,
    plname      NVARCHAR(128) NULL
);
GO

-- 上料主表
CREATE TABLE mes_ws_materials_out_m (
    billid      NVARCHAR(64) NOT NULL PRIMARY KEY,
    billno      NVARCHAR(64) NULL,
    billdate    DATETIME     NOT NULL,
    acctime     DATETIME     NULL,
    plid        NVARCHAR(64) NULL,
    srcbillid   NVARCHAR(64) NULL,         -- 对应生产订单 MOBillID
    srcbillno   NVARCHAR(64) NULL,
    rbtag       INT          NULL,         -- 1/2 = 退/补料（反向扣减）
    bstate      INT          NULL          -- 0=未生效
);
GO

-- 上料明细表
CREATE TABLE mes_ws_materials_out_i (
    id          NVARCHAR(64) NOT NULL PRIMARY KEY,
    billid      NVARCHAR(64) NOT NULL,
    itmid       NVARCHAR(64) NULL,
    weight      DECIMAL(28,8) NULL,
    qty         DECIMAL(28,8) NULL,
    deleted     INT          NULL          -- 1=逻辑删除
);
GO

-- 收货主表
CREATE TABLE mes_ws_materials_in_m (
    billid      NVARCHAR(64) NOT NULL PRIMARY KEY,
    billno      NVARCHAR(64) NULL,
    billdate    DATETIME     NOT NULL,
    acctime     DATETIME     NULL,
    plid        NVARCHAR(64) NULL,
    rbtag       INT          NULL,
    bstate      INT          NULL
);
GO

-- 收货明细表
CREATE TABLE mes_ws_materials_in_i (
    id          NVARCHAR(64) NOT NULL PRIMARY KEY,
    billid      NVARCHAR(64) NOT NULL,
    MOBILLID    NVARCHAR(64) NULL,
    MOBILLNO    NVARCHAR(64) NULL,
    itmid       NVARCHAR(64) NULL,
    weight      DECIMAL(28,8) NULL,
    qty         DECIMAL(28,8) NULL,
    deleted     INT          NULL
);
GO

-- 在制品账（每个 MO 一行）
CREATE TABLE CO_MO_WIP (
    id              NVARCHAR(64)  NOT NULL PRIMARY KEY,
    MOBillID        NVARCHAR(64)  NOT NULL,
    MOBillNo        NVARCHAR(64)  NULL,
    PID             NVARCHAR(64)  NOT NULL,
    InitWeight      DECIMAL(28,8) NULL,
    IWeight         DECIMAL(28,8) NULL,
    OWeight         DECIMAL(28,8) NULL,
    BalWeight       DECIMAL(28,8) NULL,
    WeightTotal     DECIMAL(28,8) NULL,
    GWeight         DECIMAL(28,8) NULL,
    GWeightTotal    DECIMAL(28,8) NULL,
    GWeightTotalPer DECIMAL(28,8) NULL,
    InitATM         DECIMAL(28,8) NULL,
    IATM            DECIMAL(28,8) NULL,
    OATM            DECIMAL(28,8) NULL,
    BalATM          DECIMAL(28,8) NULL,
    LTime           DATETIME      NULL,
    InitStdCstATM   DECIMAL(28,8) NULL, IStdCstATM    DECIMAL(28,8) NULL,
    OStdCstATM      DECIMAL(28,8) NULL, BalStdCstATM  DECIMAL(28,8) NULL,
    InitTaxATM      DECIMAL(28,8) NULL, ITaxATM       DECIMAL(28,8) NULL,
    OTaxATM         DECIMAL(28,8) NULL, BalTaxATM     DECIMAL(28,8) NULL,
    InitStdTaxATM   DECIMAL(28,8) NULL, IStdTaxATM    DECIMAL(28,8) NULL,
    OStdTaxATM      DECIMAL(28,8) NULL, BalStdTaxATM  DECIMAL(28,8) NULL,
    InitExpTaxATM   DECIMAL(28,8) NULL, IExpTaxATM    DECIMAL(28,8) NULL,
    OExpTaxATM      DECIMAL(28,8) NULL, BalExpTaxATM  DECIMAL(28,8) NULL
);
GO
CREATE INDEX IX_CO_MO_WIP_PID_MOBillID ON CO_MO_WIP (PID, MOBillID);
GO

-- 用料明细
CREATE TABLE CO_MO_WIP_MESOut (
    id              NVARCHAR(64)  NOT NULL PRIMARY KEY,
    MOBillID        NVARCHAR(64)  NOT NULL,
    PID             NVARCHAR(64)  NOT NULL,
    srcoutbillid    NVARCHAR(64)  NULL,
    srcoutitmid     NVARCHAR(64)  NULL,
    OutWeight       DECIMAL(28,8) NULL,
    OutQTY          DECIMAL(28,8) NULL,
    UseQTY          DECIMAL(28,8) NULL,
    UseWeight       DECIMAL(28,8) NULL,
    ltime           DATETIME      NULL,
    SrcTag          INT           NULL    -- 1=普通核销 2=末笔加成 3=末笔无加成
);
GO
CREATE INDEX IX_MESOut_PID_MO ON CO_MO_WIP_MESOut (PID, MOBillID);
GO

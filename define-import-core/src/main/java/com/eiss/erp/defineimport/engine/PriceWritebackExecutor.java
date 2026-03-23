package com.eiss.erp.defineimport.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eiss.erp.defineimport.mapper.ImportInventoryDataMapper;
import com.eiss.erp.defineimport.mapper.ImportRecordMapper;
import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import com.eiss.erp.defineimport.model.entity.ImportInventoryData;
import com.eiss.erp.defineimport.model.entity.ImportRecord;
import com.eiss.erp.defineimport.model.enums.ImportStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-batch price writeback: match parsed price rows to persisted inventory rows and update prices.
 */
@Component
public class PriceWritebackExecutor {

    public static final String WRITE_UPDATE_EMPTY_ONLY = "UPDATE_EMPTY_ONLY";
    public static final String WRITE_UPDATE_ALL = "UPDATE_ALL";
    public static final String WRITE_UPDATE_IF_CHANGED = "UPDATE_IF_CHANGED";

    /** Cross-batch price source marker */
    public static final int PRICE_SOURCE_CROSS_BATCH = 1;

    private static final int BATCH_SIZE = 500;
    private static final String SPEC_SEPARATOR = "*";

    @Autowired
    private ImportInventoryDataMapper inventoryDataMapper;
    @Autowired
    private ImportRecordMapper recordMapper;

    /**
     * Execute price writeback: match price rows to inventory data and update prices.
     *
     * @param priceRows                parsed price data
     * @param inventoryRecordId        target inventory record ID (nullable - auto-find if null)
     * @param batchNo                  batch number (nullable)
     * @param supplierId               supplier ID (for auto-finding)
     * @param matchFields              fields for matching
     * @param wallThicknessMatchMode   0=exact, 1=range
     * @param specRangeMatchMode       0=no, 1=split+range
     * @param specRangeConfig          config for spec range
     * @param writebackMode            UPDATE_EMPTY_ONLY / UPDATE_ALL / UPDATE_IF_CHANGED
     * @param priceRecordId            optional price import record id stored on updated rows
     */
    @Transactional(rollbackFor = Exception.class)
    public WritebackResult execute(List<ParsedRowDto> priceRows,
                                   Long inventoryRecordId,
                                   String batchNo,
                                   Long supplierId,
                                   List<String> matchFields,
                                   int wallThicknessMatchMode,
                                   int specRangeMatchMode,
                                   SpecRangeConfig specRangeConfig,
                                   String writebackMode,
                                   Long priceRecordId) {
        List<ExcelImportError> unmatchedDetails = new ArrayList<>();
        if (priceRows == null) {
            priceRows = List.of();
        }
        int totalPriceRows = priceRows.size();

        if (matchFields == null || matchFields.isEmpty()) {
            matchFields = List.of("category", "spec", "origin", "material");
        }

        ImportRecord targetRecord = resolveInventoryRecord(inventoryRecordId, batchNo, supplierId);
        if (targetRecord == null) {
            for (ParsedRowDto p : priceRows) {
                unmatchedDetails.add(buildUnmatchedError(p, "未找到可回写的库存导入记录"));
            }
            return new WritebackResult(totalPriceRows, 0, totalPriceRows, 0, 0, unmatchedDetails);
        }

        List<ImportInventoryData> inventoryRows = inventoryDataMapper.selectList(
                new LambdaQueryWrapper<ImportInventoryData>()
                        .eq(ImportInventoryData::getRecordId, targetRecord.getId()));

        if (inventoryRows.isEmpty()) {
            for (ParsedRowDto p : priceRows) {
                unmatchedDetails.add(buildUnmatchedError(p, "库存导入记录下无明细数据"));
            }
            return new WritebackResult(totalPriceRows, 0, totalPriceRows, 0, 0, unmatchedDetails);
        }

        List<ParsedRowDto> invParsed = new ArrayList<>(inventoryRows.size());
        List<Long> invIds = new ArrayList<>(inventoryRows.size());
        BigDecimal[] origPrice = new BigDecimal[inventoryRows.size()];

        for (int i = 0; i < inventoryRows.size(); i++) {
            ImportInventoryData d = inventoryRows.get(i);
            invIds.add(d.getId());
            ParsedRowDto pr = toParsedRow(d);
            origPrice[i] = d.getPrice();
            pr.setPrice(null);
            invParsed.add(pr);
        }

        List<ExcelImportError> matchErrors = PriceMatcher.match(
                invParsed, priceRows,
                matchFields, wallThicknessMatchMode, specRangeMatchMode, specRangeConfig, SPEC_SEPARATOR);

        BigDecimal[] matchedPrice = new BigDecimal[invParsed.size()];
        for (int i = 0; i < invParsed.size(); i++) {
            matchedPrice[i] = invParsed.get(i).getPrice();
        }
        for (int i = 0; i < invParsed.size(); i++) {
            if (matchedPrice[i] == null) {
                invParsed.get(i).setPrice(origPrice[i]);
            }
        }

        int matchedPriceRows = totalPriceRows - matchErrors.size();
        unmatchedDetails.addAll(matchErrors);

        int updatedRows = 0;
        int skippedRows = 0;
        List<ImportInventoryData> pending = new ArrayList<>();

        for (int i = 0; i < invParsed.size(); i++) {
            BigDecimal newP = matchedPrice[i];
            if (newP == null) {
                continue;
            }
            BigDecimal oldP = origPrice[i];
            if (!shouldUpdate(writebackMode, oldP, newP)) {
                skippedRows++;
                continue;
            }

            ImportInventoryData upd = new ImportInventoryData();
            upd.setId(invIds.get(i));
            upd.setPrice(newP);
            upd.setPriceSource(PRICE_SOURCE_CROSS_BATCH);
            upd.setPriceRecordId(priceRecordId);
            upd.setPriceUpdatedAt(LocalDateTime.now());
            pending.add(upd);
            updatedRows++;

            if (pending.size() >= BATCH_SIZE) {
                flushUpdates(pending);
                pending.clear();
            }
        }

        if (!pending.isEmpty()) {
            flushUpdates(pending);
        }

        return new WritebackResult(totalPriceRows, matchedPriceRows, matchErrors.size(),
                updatedRows, skippedRows, unmatchedDetails);
    }

    /**
     * Same as {@link #execute(List, Long, String, Long, List, int, int, SpecRangeConfig, String, Long)}
     * with no price record id.
     */
    @Transactional(rollbackFor = Exception.class)
    public WritebackResult execute(List<ParsedRowDto> priceRows,
                                   Long inventoryRecordId,
                                   String batchNo,
                                   Long supplierId,
                                   List<String> matchFields,
                                   int wallThicknessMatchMode,
                                   int specRangeMatchMode,
                                   SpecRangeConfig specRangeConfig,
                                   String writebackMode) {
        return execute(priceRows, inventoryRecordId, batchNo, supplierId, matchFields,
                wallThicknessMatchMode, specRangeMatchMode, specRangeConfig, writebackMode, null);
    }

    private void flushUpdates(List<ImportInventoryData> batch) {
        for (ImportInventoryData u : batch) {
            inventoryDataMapper.updateById(u);
        }
    }

    private static boolean shouldUpdate(String writebackMode, BigDecimal oldP, BigDecimal newP) {
        if (newP == null) {
            return false;
        }
        if (WRITE_UPDATE_EMPTY_ONLY.equals(writebackMode)) {
            return oldP == null;
        }
        if (WRITE_UPDATE_ALL.equals(writebackMode)) {
            return true;
        }
        if (WRITE_UPDATE_IF_CHANGED.equals(writebackMode)) {
            return oldP == null || oldP.compareTo(newP) != 0;
        }
        return false;
    }

    private static ParsedRowDto toParsedRow(ImportInventoryData d) {
        ParsedRowDto p = new ParsedRowDto();
        p.setRowIndex(d.getSourceRow() != null ? d.getSourceRow() : 0);
        p.setSheetName(d.getSourceSheet());
        p.setCategory(d.getCategory());
        p.setSpec(d.getSpec());
        p.setOrigin(d.getOrigin());
        p.setMaterial(d.getMaterial());
        p.setRemark(d.getRemark());
        p.setPrice(d.getPrice());
        return p;
    }

    private ImportRecord resolveInventoryRecord(Long inventoryRecordId, String batchNo, Long supplierId) {
        if (inventoryRecordId != null) {
            return recordMapper.selectById(inventoryRecordId);
        }
        if (batchNo != null && !batchNo.isBlank()) {
            return recordMapper.selectOne(new LambdaQueryWrapper<ImportRecord>()
                    .eq(ImportRecord::getBatchNo, batchNo.trim())
                    .eq(ImportRecord::getImportStatus, ImportStatusEnum.CONFIRMED.getCode())
                    .orderByDesc(ImportRecord::getCreateTime)
                    .last("LIMIT 1"));
        }
        if (supplierId != null) {
            return recordMapper.selectOne(new LambdaQueryWrapper<ImportRecord>()
                    .eq(ImportRecord::getSupplierId, supplierId)
                    .eq(ImportRecord::getImportStatus, ImportStatusEnum.CONFIRMED.getCode())
                    .orderByDesc(ImportRecord::getCreateTime)
                    .last("LIMIT 1"));
        }
        return null;
    }

    private static ExcelImportError buildUnmatchedError(ParsedRowDto priceRow, String msg) {
        return ExcelImportError.builder()
                .sheetName(priceRow.getSheetName())
                .rowIndex(priceRow.getRowIndex())
                .fieldName("价格回写")
                .errorMsg(msg)
                .errorLevel("WARNING")
                .errorType("MATCH_FAIL")
                .build();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WritebackResult {
        private int totalPriceRows;
        private int matchedRows;
        private int unmatchedRows;
        private int updatedRows;
        private int skippedRows;
        private List<ExcelImportError> unmatchedDetails;
    }
}

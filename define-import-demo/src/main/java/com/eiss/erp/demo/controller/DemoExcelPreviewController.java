package com.eiss.erp.demo.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.alibaba.excel.read.metadata.ReadSheet;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/excel-preview")
public class DemoExcelPreviewController {

    private final Map<String, Path> uploadedFiles = new ConcurrentHashMap<>();

    @PostMapping("/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            String fileId = UUID.randomUUID().toString().replace("-", "");
            Path tempFile = Files.createTempFile("excel-preview-", ".xlsx");
            file.transferTo(tempFile.toFile());
            uploadedFiles.put(fileId, tempFile);

            List<ReadSheet> sheetList = EasyExcel.read(tempFile.toFile()).build().excelExecutor().sheetList();
            List<Map<String, Object>> sheetInfos = new ArrayList<>();
            for (ReadSheet rs : sheetList) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sheetNo", rs.getSheetNo());
                info.put("sheetName", rs.getSheetName());
                sheetInfos.add(info);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileId", fileId);
            result.put("fileName", file.getOriginalFilename());
            result.put("fileSize", file.getSize());
            result.put("sheets", sheetInfos);
            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("上传文件失败: " + e.getMessage());
        }
    }

    @GetMapping("/{fileId}/sheet/{sheetIndex}")
    public Result<?> getSheetData(@PathVariable String fileId,
                                   @PathVariable Integer sheetIndex,
                                   @RequestParam(defaultValue = "0") Integer headerRow,
                                   @RequestParam(defaultValue = "100") Integer maxRows) {
        try {
            Path filePath = uploadedFiles.get(fileId);
            if (filePath == null || !Files.exists(filePath)) {
                return Result.fail("文件不存在或已过期, 请重新上传");
            }

            List<Map<Integer, String>> rows = new ArrayList<>();
            Map<Integer, String>[] headerRef = new Map[]{null};

            EasyExcel.read(filePath.toFile(), new PageReadListener<Map<Integer, String>>(dataList -> {
                rows.addAll(dataList);
            })).sheet(sheetIndex).headRowNumber(headerRow + 1).doRead();

            List<Map<Integer, String>> headerRows = new ArrayList<>();
            try (var reader = EasyExcel.read(filePath.toFile()).build()) {
                List<ReadSheet> sheetList = reader.excelExecutor().sheetList();
            }

            int limit = Math.min(rows.size(), maxRows);
            List<Map<Integer, String>> limitedRows = rows.subList(0, limit);

            Set<Integer> allCols = new TreeSet<>();
            for (Map<Integer, String> row : limitedRows) {
                allCols.addAll(row.keySet());
            }

            List<Map<String, Object>> headers = new ArrayList<>();
            for (Integer col : allCols) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("index", col);
                h.put("label", com.eiss.erp.defineimport.util.CellRefUtil.toRef(0, col).replaceAll("\\d+", ""));
                headers.add(h);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("headers", headers);
            result.put("rows", limitedRows);
            result.put("totalRows", rows.size());
            result.put("sheetIndex", sheetIndex);
            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("读取Sheet数据失败: " + e.getMessage());
        }
    }
}

package com.eiss.erp.demo.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.alibaba.excel.read.metadata.ReadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/excel-preview")
public class DemoExcelPreviewController {

    private static final Logger log = LoggerFactory.getLogger(DemoExcelPreviewController.class);

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_UPLOADED_ENTRIES = 100;

    private final Map<String, FileEntry> uploadedFiles = new ConcurrentHashMap<>();

    private static final class FileEntry {
        private final Path path;
        private final LocalDateTime uploadedAt;

        FileEntry(Path path, LocalDateTime uploadedAt) {
            this.path = path;
            this.uploadedAt = uploadedAt;
        }

        Path getPath() {
            return path;
        }

        LocalDateTime getUploadedAt() {
            return uploadedAt;
        }
    }

    @Scheduled(fixedRate = 1800000)
    public void cleanupUploadedFiles() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        Iterator<Map.Entry<String, FileEntry>> it = uploadedFiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FileEntry> e = it.next();
            if (e.getValue().getUploadedAt().isBefore(cutoff)) {
                try {
                    Files.deleteIfExists(e.getValue().getPath());
                } catch (IOException ex) {
                    log.warn("删除过期预览临时文件失败: {}", e.getValue().getPath(), ex);
                }
                it.remove();
            }
        }
    }

    @PostMapping("/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            validateExcelFile(file);
            if (uploadedFiles.size() > MAX_UPLOADED_ENTRIES) {
                return Result.fail("上传队列已满，请稍后再试或等待系统自动清理");
            }

            String fileId = UUID.randomUUID().toString().replace("-", "");
            Path tempFile = Files.createTempFile("excel-preview-", ".xlsx");
            try {
                file.transferTo(tempFile.toFile());
            } catch (Exception ex) {
                Files.deleteIfExists(tempFile);
                throw ex;
            }
            uploadedFiles.put(fileId, new FileEntry(tempFile, LocalDateTime.now()));

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
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            log.error("上传文件失败", e);
            return Result.fail("上传文件失败，请稍后重试");
        }
    }

    @GetMapping("/{fileId}/sheet/{sheetIndex}")
    public Result<?> getSheetData(@PathVariable String fileId,
                                   @PathVariable Integer sheetIndex,
                                   @RequestParam(defaultValue = "0") Integer headerRow,
                                   @RequestParam(defaultValue = "100") Integer maxRows) {
        try {
            FileEntry entry = uploadedFiles.get(fileId);
            Path filePath = entry != null ? entry.getPath() : null;
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
            log.error("读取Sheet数据失败", e);
            return Result.fail("读取Sheet数据失败，请稍后重试");
        }
    }

    private void validateExcelFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件大小不能超过50MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("文件名无效，请上传 .xlsx 或 .xls 格式的 Excel 文件");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new IllegalArgumentException("仅支持 .xlsx 或 .xls 格式的 Excel 文件");
        }
    }
}

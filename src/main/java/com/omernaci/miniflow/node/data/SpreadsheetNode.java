package com.omernaci.miniflow.node.data;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class SpreadsheetNode implements NodeExecutor {

    static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int EXCEL_MAX_TEXT = 32_767;

    private final ObjectMapper mapper;

    public SpreadsheetNode(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "spreadsheet";
    }

    @Override
    public String label() {
        return "CSV / Excel";
    }

    @Override
    public Map<String, Object> defaultParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rows", "{{ input.rows }}");
        p.put("format", "xlsx");
        p.put("fileName", "report-{{ today }}");
        p.put("sheetName", "Report");
        return p;
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.text("rows").mandatory().placeholder("{{ input.rows }}"),
                ParamField.select("format", "xlsx", "csv").mandatory(),
                ParamField.text("fileName").mandatory(),
                ParamField.text("sheetName").when("format", "xlsx"),
                ParamField.list("columns"),
                ParamField.text("delimiter").when("format", "csv").placeholder(";"));
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        String format = Params.str(params, "format", "xlsx").trim().toLowerCase();
        if (!format.equals("xlsx") && !format.equals("csv")) {
            throw new MiniFlowException("error.param.oneOf", "format", "xlsx, csv");
        }
        Table table = toTable(params.get("rows"), params.get("columns"));

        byte[] bytes = format.equals("csv")
                ? csv(table, Params.str(params, "delimiter", ","), Params.bool(params, "bom", true))
                : xlsx(table, Params.str(params, "sheetName", "Report"));

        String name = Params.str(params, "fileName", "report");
        if (!name.toLowerCase().endsWith("." + format)) {
            name = name + "." + format;
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", name);
        file.put("mimeType", format.equals("csv") ? "text/csv" : XLSX_MIME);
        file.put("size", bytes.length);
        file.put("data", Base64.getEncoder().encodeToString(bytes));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file);
        out.put("rowCount", table.rows().size());
        out.put("columns", table.header());
        return NodeResult.of(out);
    }

    record Table(List<String> header, List<List<Object>> rows) {
    }

    @SuppressWarnings("unchecked")
    Table toTable(Object rowsParam, Object columnsParam) {
        List<Object> items;
        if (rowsParam == null || (rowsParam instanceof String s && s.isBlank())) {
            items = List.of();
        } else if (rowsParam instanceof Map<?, ?> single) {
            items = List.of(single);
        } else if (rowsParam instanceof Collection<?> list) {
            items = new ArrayList<>(list);
        } else {
            throw new MiniFlowException("error.spreadsheet.rows", rowsParam.getClass().getSimpleName());
        }

        List<String> columns = new ArrayList<>();
        if (columnsParam instanceof Collection<?> cols) {
            cols.forEach(c -> columns.add(String.valueOf(c)));
        } else if (columnsParam instanceof String s && !s.isBlank()) {
            for (String c : s.split(",")) {
                columns.add(c.trim());
            }
        }

        boolean objects = !items.isEmpty() && items.get(0) instanceof Map<?, ?>;
        if (objects && columns.isEmpty()) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    m.keySet().forEach(k -> keys.add(String.valueOf(k)));
                }
            }
            columns.addAll(keys);
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m) {
                List<Object> row = new ArrayList<>();
                for (String c : columns) {
                    row.add(((Map<String, Object>) m).get(c));
                }
                rows.add(row);
            } else if (item instanceof Collection<?> values) {
                rows.add(new ArrayList<>(values));
            } else {
                rows.add(List.of(item == null ? "" : item));
            }
        }
        return new Table(columns, rows);
    }

    byte[] csv(Table table, String delimiter, boolean bom) {
        StringBuilder sb = new StringBuilder();
        if (bom) {
            sb.append('﻿');
        }
        if (!table.header().isEmpty()) {
            appendCsvLine(sb, new ArrayList<>(table.header()), delimiter);
        }
        for (List<Object> row : table.rows()) {
            appendCsvLine(sb, row, delimiter);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvLine(StringBuilder sb, List<?> values, String delimiter) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            String v = text(values.get(i));
            boolean quote = v.contains(delimiter) || v.contains("\"") || v.contains("\n") || v.contains("\r");
            sb.append(quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
        }
        sb.append("\r\n");
    }

    byte[] xlsx(Table table, String sheetName) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(WorkbookUtil.createSafeSheetName(sheetName));
            CreationHelper helper = wb.getCreationHelper();

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("dd.mm.yyyy hh:mm"));

            int r = 0;
            int width = table.header().size();
            if (!table.header().isEmpty()) {
                Row header = sheet.createRow(r++);
                for (int c = 0; c < table.header().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(table.header().get(c));
                    cell.setCellStyle(headerStyle);
                }
                sheet.createFreezePane(0, 1);
            }
            for (List<Object> values : table.rows()) {
                Row row = sheet.createRow(r++);
                width = Math.max(width, values.size());
                for (int c = 0; c < values.size(); c++) {
                    Object v = values.get(c);
                    Cell cell = row.createCell(c);
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v instanceof Boolean b) {
                        cell.setCellValue(b);
                    } else if (v instanceof Date d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateStyle);
                    } else if (v != null) {
                        String t = text(v);
                        cell.setCellValue(t.length() > EXCEL_MAX_TEXT ? t.substring(0, EXCEL_MAX_TEXT) : t);
                    }
                }
            }
            for (int c = 0; c < Math.min(width, 50); c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Throwable ignored) {
                    sheet.setColumnWidth(c, 18 * 256);
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new MiniFlowException(e, "error.spreadsheet.write", e.getMessage());
        }
    }

    private String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Map<?, ?> || v instanceof Collection<?>) {
            try {
                return mapper.writeValueAsString(v);
            } catch (Exception e) {
                return String.valueOf(v);
            }
        }
        return String.valueOf(v);
    }
}

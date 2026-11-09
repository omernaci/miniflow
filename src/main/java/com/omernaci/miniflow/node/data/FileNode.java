package com.omernaci.miniflow.node.data;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lists and reads documents (PDF, Word, text) inside the folder set by {@code miniflow.files-dir}.
 * Paths outside that folder are rejected; the node never writes or deletes files.
 */
@Component
public class FileNode implements NodeExecutor {

    public static final String TYPE = "file";
    public static final int DEFAULT_MAX_CHARS = 20_000;
    public static final int MAX_CHARS = 200_000;
    public static final int MAX_FILES = 1000;
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    static final Set<String> TEXT_TYPES = Set.of("txt", "md", "markdown", "csv", "json", "html", "htm", "xml", "yml",
            "yaml", "log");
    private static final Pattern PAGES = Pattern.compile("\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?");

    private final String filesDir;

    public FileNode(@Value("${miniflow.files-dir:}") String filesDir) {
        this.filesDir = filesDir == null ? "" : filesDir.trim();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String label() {
        return "File";
    }

    @Override
    public String description() {
        return "Lists files or reads the text of a PDF, Word or text file in the MINIFLOW_FILES_DIR folder.";
    }

    @Override
    public Map<String, Object> defaultParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("operation", "list");
        p.put("path", "");
        p.put("pattern", "*.pdf");
        p.put("recursive", false);
        p.put("maxChars", DEFAULT_MAX_CHARS);
        return p;
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.select("operation", "list", "read").mandatory(),
                ParamField.text("path").placeholder("notes/chapter-1.pdf"),
                ParamField.text("pattern").placeholder("*.pdf").when("operation", "list"),
                ParamField.bool("recursive").when("operation", "list"),
                ParamField.text("pages").placeholder("1-5").when("operation", "read"),
                ParamField.number("maxChars").when("operation", "read"));
    }

    @Override
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Path base = baseDir();
        String op = Params.str(params, "operation", "list").trim();
        return switch (op) {
            case "list" -> NodeResult.of(list(base, params));
            case "read" -> NodeResult.of(read(base, params));
            default -> throw new MiniFlowException("error.param.oneOf", "operation", "list, read");
        };
    }

    private Path baseDir() {
        if (filesDir.isEmpty()) {
            throw new MiniFlowException("error.file.disabled");
        }
        try {
            Path base = Paths.get(filesDir).toAbsolutePath().normalize();
            if (!Files.isDirectory(base)) {
                throw new MiniFlowException("error.file.notDirectory", filesDir);
            }
            return base.toRealPath();
        } catch (IOException e) {
            throw new MiniFlowException("error.file.notDirectory", filesDir);
        }
    }

    /** Resolves a relative path inside the base folder, also after following symlinks. */
    Path resolve(Path base, String relative) {
        String rel = relative == null ? "" : relative.trim().replace('\\', '/');
        if (rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) {
            throw new MiniFlowException("error.file.outside", relative);
        }
        Path p = base.resolve(rel).normalize();
        if (!p.startsWith(base)) {
            throw new MiniFlowException("error.file.outside", relative);
        }
        if (!Files.exists(p)) {
            throw new MiniFlowException("error.file.notFound", rel);
        }
        try {
            Path real = p.toRealPath();
            if (!real.startsWith(base)) {
                throw new MiniFlowException("error.file.outside", relative);
            }
            return real;
        } catch (IOException e) {
            throw new MiniFlowException("error.file.notFound", rel);
        }
    }

    private Map<String, Object> list(Path base, Map<String, Object> params) {
        Path dir = resolve(base, Params.str(params, "path"));
        if (!Files.isDirectory(dir)) {
            throw new MiniFlowException("error.file.notDirectory", Params.str(params, "path"));
        }
        String pattern = Params.str(params, "pattern", "*");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(
                "glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern.trim()));
        boolean recursive = Params.bool(params, "recursive", false);

        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, recursive ? Integer.MAX_VALUE : 1)) {
            List<Path> paths = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> !hidden(dir, f))
                    .filter(f -> matcher.matches(f.getFileName()))
                    .sorted()
                    .limit(MAX_FILES + 1L)
                    .toList();
            if (paths.size() > MAX_FILES) {
                throw new MiniFlowException("error.file.tooMany", MAX_FILES);
            }
            for (Path f : paths) {
                files.add(info(base, f));
            }
        } catch (IOException e) {
            throw new MiniFlowException(e, "error.file.read", Params.str(params, "path"), e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("count", files.size());
        return out;
    }

    private Map<String, Object> read(Path base, Map<String, Object> params) {
        String path = Params.required(params, "path");
        Path file = resolve(base, path);
        if (!Files.isRegularFile(file)) {
            throw new MiniFlowException("error.file.notFile", path);
        }
        int maxChars = Params.integer(params, "maxChars", DEFAULT_MAX_CHARS);
        if (maxChars < 1 || maxChars > MAX_CHARS) {
            throw new MiniFlowException("error.file.maxCharsRange", MAX_CHARS);
        }
        Map<String, Object> out = info(base, file);
        long size = (Long) out.get("size");
        if (size > MAX_FILE_SIZE) {
            throw new MiniFlowException("error.file.tooLarge", path, MAX_FILE_SIZE / 1024 / 1024);
        }
        String ext = (String) out.get("extension");
        String text;
        Integer pages = null;
        try {
            if ("pdf".equals(ext)) {
                try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                    pages = doc.getNumberOfPages();
                    PDFTextStripper stripper = new PDFTextStripper();
                    int[] range = pageRange(Params.str(params, "pages"), pages);
                    stripper.setStartPage(range[0]);
                    stripper.setEndPage(range[1]);
                    text = stripper.getText(doc);
                }
            } else if ("docx".equals(ext)) {
                try (InputStream in = Files.newInputStream(file);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(new XWPFDocument(in))) {
                    text = extractor.getText();
                }
            } else if (TEXT_TYPES.contains(ext)) {
                text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } else {
                throw new MiniFlowException("error.file.unsupported", ext.isEmpty() ? path : ext);
            }
        } catch (MiniFlowException e) {
            throw e;
        } catch (Exception e) {
            throw new MiniFlowException(e, "error.file.read", path, e.getMessage());
        }
        text = clean(text);
        out.put("pages", pages);
        out.put("chars", text.length());
        out.put("truncated", text.length() > maxChars);
        out.put("text", text.length() > maxChars ? text.substring(0, maxChars) : text);
        return out;
    }

    /** Parses "3" or "2-5" into a 1-based page range limited to the document; blank means all pages. */
    static int[] pageRange(String pages, int total) {
        if (pages == null || pages.isBlank()) {
            return new int[]{1, Math.max(total, 1)};
        }
        Matcher m = PAGES.matcher(pages);
        if (!m.matches()) {
            throw new MiniFlowException("error.file.pages", pages);
        }
        int from = Integer.parseInt(m.group(1));
        int to = m.group(2) == null ? from : Integer.parseInt(m.group(2));
        if (from < 1 || to < from || from > total) {
            throw new MiniFlowException("error.file.pages", pages);
        }
        return new int[]{from, Math.min(to, total)};
    }

    private static String clean(String text) {
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        return t.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t\\x0B\\f]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static boolean hidden(Path dir, Path file) {
        for (Path part : dir.relativize(file)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> info(Path base, Path file) {
        Map<String, Object> m = new LinkedHashMap<>();
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        m.put("name", name);
        m.put("path", base.relativize(file).toString().replace('\\', '/'));
        m.put("extension", dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT));
        try {
            m.put("size", Files.size(file));
            m.put("modified", Files.getLastModifiedTime(file).toInstant().atZone(ZoneId.systemDefault())
                    .toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString());
        } catch (IOException e) {
            throw new MiniFlowException(e, "error.file.read", name, e.getMessage());
        }
        return m;
    }
}

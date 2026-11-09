package com.omernaci.miniflow;

import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.engine.ExecutionContext.StepLog;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.node.data.FileNode;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FileNodeTest {

    static final Path DIR;
    static final Path OUTSIDE;

    static {
        try {
            DIR = Files.createTempDirectory("miniflow-files");
            OUTSIDE = Files.createTempDirectory("miniflow-outside");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void filesDir(DynamicPropertyRegistry registry) {
        registry.add("miniflow.files-dir", DIR::toString);
    }

    @Autowired
    WorkflowEngine engine;

    @BeforeAll
    static void createFiles() throws IOException {
        Files.createDirectories(DIR.resolve("notes/deep"));
        Files.writeString(DIR.resolve("notes/a-intro.md"), "# Intro\r\n\r\n\r\n\r\nSpring Boot basics   \r\n");
        Files.writeString(DIR.resolve("notes/deep/c-extra.txt"), "x".repeat(50));
        Files.writeString(DIR.resolve("notes/.hidden.md"), "secret");
        Files.writeString(DIR.resolve("notes/image.png"), "not really a png");
        Files.writeString(OUTSIDE.resolve("secret.txt"), "outside");

        try (PDDocument doc = new PDDocument()) {
            for (String text : List.of("Page one: dependency injection", "Page two: beans", "Page three: profiles")) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(DIR.resolve("notes/b-chapter.pdf").toFile());
        }
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(DIR.resolve("notes/d-word.docx"))) {
            doc.createParagraph().createRun().setText("Word paragraph about transactions");
            doc.createParagraph().createRun().setText("Second paragraph");
            doc.write(out);
        }
    }

    private StepLog file(Map<String, Object> params) {
        Workflow wf = new Workflow("file-test", "file", List.of(
                new NodeDef("start", "trigger", Map.of()),
                new NodeDef("doc", FileNode.TYPE, params)
        ), List.of(new Connection("start", "doc", null)));
        ExecutionContext ctx = engine.run(wf, Map.of());
        return ctx.steps().get(1);
    }

    private Map<String, Object> ok(Map<String, Object> params) {
        StepLog step = file(params);
        assertThat(step.error()).isNull();
        return step.output();
    }

    private Map<String, Object> read(String path, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>(Map.of("operation", "read", "path", path));
        params.putAll(extra);
        return ok(params);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsMatchingFilesSortedAndSkipsHiddenOnes() {
        Map<String, Object> out = ok(Map.of("operation", "list", "path", "notes", "pattern", "*.{pdf,md,docx}"));
        List<Map<String, Object>> files = (List<Map<String, Object>>) out.get("files");
        assertThat(files).extracting(f -> f.get("path"))
                .containsExactly("notes/a-intro.md", "notes/b-chapter.pdf", "notes/d-word.docx");
        assertThat(out.get("count")).isEqualTo(3);
        assertThat(files.get(1)).containsEntry("name", "b-chapter.pdf").containsEntry("extension", "pdf")
                .containsKeys("size", "modified");

        Map<String, Object> all = ok(Map.of("operation", "list", "pattern", "*", "recursive", true));
        assertThat((List<Map<String, Object>>) all.get("files")).extracting(f -> f.get("path"))
                .contains("notes/deep/c-extra.txt", "notes/image.png").doesNotContain("notes/.hidden.md");
        assertThat(ok(Map.of("operation", "list", "path", "notes")).get("count")).isEqualTo(4);
    }

    @Test
    void readsPdfPagesWordAndText() {
        Map<String, Object> pdf = read("notes/b-chapter.pdf", Map.of());
        assertThat(pdf.get("pages")).isEqualTo(3);
        assertThat((String) pdf.get("text")).contains("dependency injection").contains("profiles");

        String pageTwo = (String) read("notes/b-chapter.pdf", Map.of("pages", "2")).get("text");
        assertThat(pageTwo).contains("beans").doesNotContain("profiles").doesNotContain("injection");
        assertThat((String) read("notes/b-chapter.pdf", Map.of("pages", "2-9")).get("text"))
                .contains("beans").contains("profiles").doesNotContain("injection");

        assertThat((String) read("notes/d-word.docx", Map.of()).get("text"))
                .contains("Word paragraph about transactions").contains("Second paragraph");

        Map<String, Object> md = read("notes/a-intro.md", Map.of());
        assertThat(md.get("text")).isEqualTo("# Intro\n\nSpring Boot basics");
        assertThat(md.get("pages")).isNull();
        assertThat(md.get("truncated")).isEqualTo(false);
    }

    @Test
    void cutsLongText() {
        Map<String, Object> out = read("notes/deep/c-extra.txt", Map.of("maxChars", 10));
        assertThat(out.get("text")).isEqualTo("x".repeat(10));
        assertThat(out.get("chars")).isEqualTo(50);
        assertThat(out.get("truncated")).isEqualTo(true);
    }

    @Test
    void staysInsideTheFilesFolder() throws IOException {
        assertThat(file(Map.of("operation", "read", "path", "../" + OUTSIDE.getFileName() + "/secret.txt")).error())
                .contains("outside");
        assertThat(file(Map.of("operation", "read", "path", OUTSIDE.resolve("secret.txt").toString())).error())
                .contains("outside");
        assertThat(file(Map.of("operation", "list", "path", "..")).error()).contains("outside");

        Path link = DIR.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, OUTSIDE.resolve("secret.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        try {
            assertThat(file(Map.of("operation", "read", "path", "link.txt")).error()).contains("outside");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void reportsBadInput() {
        assertThat(file(Map.of("operation", "read", "path", "notes/missing.pdf")).error()).contains("notes/missing.pdf");
        assertThat(file(Map.of("operation", "read", "path", "notes/image.png")).error()).contains("png");
        assertThat(file(Map.of("operation", "read", "path", "notes")).error()).contains("notes");
        assertThat(file(Map.of("operation", "read", "path", "notes/b-chapter.pdf", "pages", "5")).error()).contains("5");
        assertThat(file(Map.of("operation", "read", "path", "notes/b-chapter.pdf", "pages", "x")).error()).contains("x");
        assertThat(file(Map.of("operation", "read", "path", "notes/a-intro.md", "maxChars", 0)).error())
                .contains("maxChars");
        assertThat(file(Map.of("operation", "delete", "path", "notes")).error()).contains("operation");
    }

    @Test
    void isDisabledWithoutAFolder() {
        assertThatThrownBy(() -> new FileNode("").execute(Map.of("operation", "list"), Map.of(), null))
                .hasMessageContaining("MINIFLOW_FILES_DIR");
        assertThatThrownBy(() -> new FileNode(DIR.resolve("nope").toString())
                .execute(Map.of("operation", "list"), Map.of(), null))
                .hasMessageContaining("nope");
    }
}

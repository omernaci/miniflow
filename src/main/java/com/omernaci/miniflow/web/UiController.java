package com.omernaci.miniflow.web;

import com.omernaci.miniflow.common.i18n.Messages;
import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.dashboard.DashboardService;
import com.omernaci.miniflow.engine.WorkflowEngine;
import com.omernaci.miniflow.execution.ExecutionStore;
import com.omernaci.miniflow.node.NodeTypeInfo;
import com.omernaci.miniflow.queue.QueueListenerManager;
import com.omernaci.miniflow.scheduler.CronScheduler;
import com.omernaci.miniflow.variable.VariableService;
import com.omernaci.miniflow.web.view.PageView;
import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Controller
public class UiController {

    private final WorkflowService workflows;
    private final ExecutionStore executions;
    private final WorkflowEngine engine;
    private final CronScheduler scheduler;
    private final ObjectWriter jsonWriter;
    private final CredentialService credentials;
    private final QueueListenerManager listeners;
    private final DashboardService dashboard;
    private final VariableService variables;

    public UiController(WorkflowService workflows, ExecutionStore executions,
                        WorkflowEngine engine, CronScheduler scheduler, ObjectMapper mapper,
                        CredentialService credentials, QueueListenerManager listeners,
                        DashboardService dashboard, VariableService variables) {
        this.dashboard = dashboard;
        this.variables = variables;
        this.credentials = credentials;
        this.listeners = listeners;
        this.workflows = workflows;
        this.executions = executions;
        this.engine = engine;
        this.scheduler = scheduler;
        this.jsonWriter = mapper.writerWithDefaultPrettyPrinter();
    }

    @PostMapping("/workflows/import")
    public String importWorkflows(@RequestParam String json,
                                  @RequestParam(defaultValue = "false") boolean overwrite,
                                  RedirectAttributes ra) {
        try {
            List<Workflow> imported = workflows.importJson(json, overwrite);
            ra.addFlashAttribute("success", Messages.get("import.success", imported.size(),
                    String.join(", ", imported.stream().map(Workflow::id).toList())));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            ra.addFlashAttribute("importJson", json);
        }
        return "redirect:/";
    }

    @GetMapping("/workflows/{id}/export")
    public ResponseEntity<String> export(@PathVariable String id) {
        Workflow wf = workflows.find(id)
                .orElseThrow(() -> notFound("error.notFound.workflow", id));
        return jsonFile(id + ".json", jsonWriter.writeValueAsString(wf));
    }

    @GetMapping("/workflows/export")
    public ResponseEntity<String> exportAll() {
        List<Workflow> all = workflows.all().stream().sorted(Comparator.comparing(Workflow::id)).toList();
        return jsonFile("miniflow-workflows.json", jsonWriter.writeValueAsString(all));
    }

    private String listenSummary(Workflow wf) {
        return listeners.summary(wf.id()).orElseGet(() -> wf.listenConfig()
                .map(c -> c.get("type") + " · " + firstNonBlank(c.get("queue"), c.get("topic"), c.get("list"), c.get("channel")))
                .orElse(null));
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }

    private static ResponseStatusException notFound(String code, Object arg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, Messages.get(code, arg));
    }

    private static ResponseEntity<String> jsonFile(String filename, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    public record Row(Workflow wf, String webhook, String cron, LocalDateTime nextRun,
                      String listen, String listenError) {
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String range, Model model) {
        model.addAttribute("d", dashboard.build(range));
        return "dashboard";
    }

    @GetMapping("/")
    public String index(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String state,
                        @RequestParam(required = false) String trigger,
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "10") int size,
                        Model model) {
        List<Workflow> all = workflows.all();
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = all.stream()
                .filter(wf -> query.isEmpty()
                        || wf.id().toLowerCase(Locale.ROOT).contains(query)
                        || (wf.name() != null && wf.name().toLowerCase(Locale.ROOT).contains(query)))
                .filter(wf -> state == null || state.isBlank()
                        || ("active".equals(state) == wf.active()))
                .filter(wf -> matchesTrigger(wf, trigger))
                .sorted(Comparator.comparing(Workflow::name, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(wf -> new Row(wf, wf.webhookPath().orElse(null), wf.cron().orElse(null),
                        scheduler.nextRun(wf.id()).orElse(null),
                        listenSummary(wf), listeners.error(wf.id()).orElse(null)))
                .toList();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", q);
        params.put("state", state);
        params.put("trigger", trigger);
        model.addAttribute("pg", PageView.slice(rows, page, PageView.normalizeSize(size, 10), "/", params));
        model.addAttribute("q", q);
        model.addAttribute("state", state);
        model.addAttribute("trigger", trigger);
        model.addAttribute("filtered", !query.isEmpty() || (state != null && !state.isBlank())
                || (trigger != null && !trigger.isBlank()));
        model.addAttribute("totalCount", all.size());
        model.addAttribute("activeCount", all.stream().filter(Workflow::active).count());
        return "index";
    }

    private static boolean matchesTrigger(Workflow wf, String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return true;
        }
        return switch (trigger) {
            case "webhook" -> wf.webhookPath().isPresent();
            case "schedule" -> wf.cron().isPresent();
            case "queue" -> wf.listenConfig().isPresent();
            case "manual" -> wf.webhookPath().isEmpty() && wf.cron().isEmpty() && wf.listenConfig().isEmpty();
            default -> true;
        };
    }

    @PostMapping("/workflows")
    public String create(@RequestParam String id, @RequestParam String name, RedirectAttributes ra) {
        String cleanId = id.trim();
        if (!cleanId.matches("[a-z0-9-]+")) {
            ra.addFlashAttribute("error", Messages.get("error.workflow.createIdInvalid"));
            return "redirect:/";
        }
        if (workflows.find(cleanId).isPresent()) {
            ra.addFlashAttribute("error", Messages.get("error.workflow.exists", cleanId));
            return "redirect:/";
        }
        try {
            workflows.save(new Workflow(cleanId, name.trim(), false,
                    List.of(new Workflow.NodeDef("start", "trigger", Map.of("path", cleanId, "cron", ""), 80, 200)),
                    List.of()));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
        return "redirect:/workflows/" + cleanId + "/edit";
    }

    @PostMapping("/workflows/{id}/active")
    public String toggleActive(@PathVariable String id, @RequestParam boolean active, RedirectAttributes ra) {
        try {
            workflows.setActive(id, active)
                    .orElseThrow(() -> notFound("error.notFound.workflow", id));
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @GetMapping("/workflows/{id}/edit")
    public String editor(@PathVariable String id, Model model) {
        Workflow wf = workflows.find(id)
                .orElseThrow(() -> notFound("error.notFound.workflow", id));
        model.addAttribute("workflow", wf);
        model.addAttribute("nodeTypes", engine.nodeTypes().stream().map(NodeTypeInfo::localized).toList());
        model.addAttribute("credentials", credentials.list().stream()
                .map(c -> Map.of("name", c.name(), "type", c.type())).toList());
        model.addAttribute("vars", new TreeMap<>(variables.all()));
        model.addAttribute("otherWorkflows", workflows.all().stream()
                .filter(other -> !other.id().equals(wf.id()))
                .sorted(Comparator.comparing(other -> other.name() == null ? other.id() : other.name()))
                .toList());
        return "editor";
    }

    @PostMapping("/workflows/bulk")
    public String bulk(@RequestParam(required = false) List<String> ids, @RequestParam String action,
                       @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        String back = "redirect:" + safeReturn(returnTo);
        List<String> selected = ids == null ? List.of() : ids.stream().filter(i -> i != null && !i.isBlank()).distinct().toList();
        if (selected.isEmpty()) {
            ra.addFlashAttribute("error", Messages.get("bulk.none"));
            return back;
        }
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : selected) {
            try {
                boolean ok = switch (action) {
                    case "activate" -> workflows.setActive(id, true).isPresent();
                    case "deactivate" -> workflows.setActive(id, false).isPresent();
                    case "delete" -> workflows.delete(id);
                    default -> throw new IllegalArgumentException(Messages.get("bulk.unknown", action));
                };
                (ok ? done : failed).add(id);
            } catch (IllegalArgumentException e) {
                failed.add(id + " (" + e.getMessage() + ")");
            }
        }
        if (!done.isEmpty()) {
            ra.addFlashAttribute("success", Messages.get("bulk.done." + action, done.size()));
        }
        if (!failed.isEmpty()) {
            ra.addFlashAttribute("error", Messages.get("bulk.failed", String.join(", ", failed)));
        }
        return back;
    }

    @PostMapping("/workflows/export-selected")
    public ResponseEntity<String> exportSelected(@RequestParam(required = false) List<String> ids) {
        List<Workflow> selected = (ids == null ? List.<String>of() : ids).stream()
                .distinct()
                .map(workflows::find)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Workflow::id))
                .toList();
        return jsonFile("miniflow-workflows-selected.json", jsonWriter.writeValueAsString(selected));
    }

    private static String safeReturn(String returnTo) {
        if (returnTo == null || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.contains("\\")
                || returnTo.contains("\n") || returnTo.contains("\r")) {
            return "/";
        }
        return returnTo;
    }

    @PostMapping("/workflows/{id}/delete")
    public String delete(@PathVariable String id) {
        workflows.delete(id);
        return "redirect:/";
    }

    @GetMapping("/executions")
    public String executions(@RequestParam(required = false) String workflow,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String mode,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "25") int size,
                             Model model) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("workflow", workflow);
        params.put("status", status);
        params.put("mode", mode);
        int pageSize = PageView.normalizeSize(size, 25);
        model.addAttribute("pg", PageView.of(executions.page(workflow, status, mode, page, pageSize), "/executions", params));
        model.addAttribute("filter", workflow);
        model.addAttribute("status", status);
        model.addAttribute("mode", mode);
        model.addAttribute("workflowOptions", workflows.all().stream()
                .sorted(Comparator.comparing(Workflow::name, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList());
        return "executions";
    }

    @GetMapping("/executions/{id}")
    public String execution(@PathVariable String id, Model model) {
        model.addAttribute("ex", executions.find(id)
                .orElseThrow(() -> notFound("error.notFound.execution", id)));
        return "execution";
    }
}

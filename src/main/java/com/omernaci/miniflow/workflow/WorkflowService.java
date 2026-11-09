package com.omernaci.miniflow.workflow;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.config.MiniFlowProperties;
import com.omernaci.miniflow.execution.ExecutionRepository;
import com.omernaci.miniflow.node.core.SeenKeyRepository;
import com.omernaci.miniflow.scheduler.CronSupport;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository repository;
    private final ExecutionRepository executionRepository;
    private final SeenKeyRepository seenKeyRepository;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final MiniFlowProperties props;

    public WorkflowService(WorkflowRepository repository, ExecutionRepository executionRepository,
                         SeenKeyRepository seenKeyRepository,
                         ObjectMapper mapper, ApplicationEventPublisher events, MiniFlowProperties props) {
        this.repository = repository;
        this.executionRepository = executionRepository;
        this.seenKeyRepository = seenKeyRepository;
        this.mapper = mapper;
        this.events = events;
        this.props = props;
    }

    record Definition(List<Workflow.NodeDef> nodes, List<Workflow.Connection> connections) {
    }

    @PostConstruct
    void seedExamples() throws IOException {
        if (!props.seedExamples() || repository.count() > 0) {
            return;
        }
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(props.seedLocation());
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                Workflow wf = mapper.readValue(in, Workflow.class);
                save(wf);
                log.info("Seeded example workflow {} ({})", wf.id(), r.getFilename());
            }
        }
    }

    @Transactional
    public Workflow save(Workflow wf) {
        validate(wf);
        WorkflowEntity entity = repository.findById(wf.id()).orElseGet(() -> new WorkflowEntity(wf.id()));
        entity.setName(wf.name() == null || wf.name().isBlank() ? wf.id() : wf.name().trim());
        entity.setActive(wf.active());
        entity.setDefinitionJson(toJson(new Definition(wf.nodes(), wf.connections())));
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
        events.publishEvent(new WorkflowChangedEvent(wf.id()));
        return toModel(entity);
    }

    @Transactional
    public Optional<Workflow> setActive(String id, boolean active) {
        return find(id).map(wf -> save(wf.withActive(active)));
    }

    @Transactional(readOnly = true)
    public Optional<Workflow> find(String id) {
        return repository.findById(id).map(this::toModel);
    }

    @Transactional(readOnly = true)
    public List<Workflow> all() {
        return repository.findAll().stream().map(this::toModel).toList();
    }

    @Transactional(readOnly = true)
    public List<Workflow> active() {
        return repository.findByActiveTrue().stream().map(this::toModel).toList();
    }

    @Transactional
    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        executionRepository.deleteByWorkflowId(id);
        seenKeyRepository.deleteByWorkflowId(id);
        events.publishEvent(new WorkflowChangedEvent(id));
        return true;
    }

    @Transactional
    public List<Workflow> importJson(String json, boolean overwrite) {
        if (json == null || json.isBlank()) {
            throw new MiniFlowException("error.import.empty");
        }
        List<Workflow> list;
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || node.isMissingNode() || node.isNull()) {
                throw new MiniFlowException("error.import.empty");
            }
            list = node.isArray()
                    ? mapper.convertValue(node, new TypeReference<List<Workflow>>() { })
                    : List.of(mapper.treeToValue(node, Workflow.class));
        } catch (MiniFlowException e) {
            throw e;
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MiniFlowException("error.import.invalidJson", e.getMessage());
        }
        if (list.isEmpty()) {
            throw new MiniFlowException("error.import.none");
        }

        Set<String> ids = new HashSet<>();
        for (Workflow wf : list) {
            if (wf == null || wf.id() == null || wf.id().isBlank()) {
                throw new MiniFlowException("error.import.missingId");
            }
            if (!ids.add(wf.id())) {
                throw new MiniFlowException("error.import.duplicateId", wf.id());
            }
            if (!overwrite && repository.existsById(wf.id())) {
                throw new MiniFlowException("error.import.exists", wf.id());
            }
        }
        List<Workflow> saved = new ArrayList<>();
        for (Workflow wf : list) {
            try {
                saved.add(save(wf));
            } catch (IllegalArgumentException e) {
                throw new MiniFlowException(e, "error.import.item", wf.id(), e.getMessage());
            }
        }
        log.info("Imported {} workflow(s): {}", saved.size(), ids);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Workflow> findByWebhookPath(String path) {
        return all().stream()
                .filter(wf -> wf.webhookPath().map(path::equals).orElse(false))
                .findFirst();
    }

    private void validate(Workflow wf) {
        if (wf.id() == null || wf.id().isBlank()) {
            throw new MiniFlowException("error.workflow.idRequired");
        }
        if (!wf.id().matches("[A-Za-z0-9_-]{1,100}")) {
            throw new MiniFlowException("error.workflow.idInvalid", wf.id());
        }
        long distinct = wf.nodes().stream().map(Workflow.NodeDef::id).distinct().count();
        if (distinct != wf.nodes().size()) {
            throw new MiniFlowException("error.workflow.duplicateNodeIds");
        }
        long triggers = wf.nodes().stream().filter(n -> "trigger".equals(n.type())).count();
        if (triggers > 1) {
            throw new MiniFlowException("error.workflow.multipleTriggers");
        }
        for (Workflow.NodeDef node : wf.nodes()) {
            if (node.retry() != null && !node.retry().isValid()) {
                throw new MiniFlowException("error.workflow.invalidRetry", node.id(), RetryPolicy.MAX_TIMES,
                        RetryPolicy.MAX_DELAY_MS);
            }
        }
        wf.cron().ifPresent(expr -> {
            try {
                CronExpression.parse(CronSupport.normalize(expr));
            } catch (IllegalArgumentException e) {
                throw new MiniFlowException("error.workflow.invalidCron", expr, e.getMessage());
            }
        });
        wf.listenConfig().ifPresent(listen -> {
            String type = String.valueOf(listen.get("type")).trim().toLowerCase();
            String needed = switch (type) {
                case "rabbitmq" -> "queue";
                case "kafka" -> "topic";
                case "redis" -> listen.get("list") != null && !String.valueOf(listen.get("list")).isBlank() ? "list" : "channel";
                default -> throw new MiniFlowException("error.listen.type", type);
            };
            for (String key : new String[]{"credential", needed}) {
                Object v = listen.get(key);
                if (v == null || String.valueOf(v).isBlank()) {
                    throw new MiniFlowException("error.listen.missing", key);
                }
            }
        });
        wf.webhookPath().ifPresent(path -> {
            if (!path.matches("[A-Za-z0-9_-]+")) {
                throw new MiniFlowException("error.workflow.invalidPath", path);
            }
            findByWebhookPath(path)
                    .filter(other -> !other.id().equals(wf.id()))
                    .ifPresent(other -> {
                        throw new MiniFlowException("error.workflow.pathInUse", path, other.id());
                    });
        });
    }

    private Workflow toModel(WorkflowEntity e) {
        try {
            Definition d = mapper.readValue(e.getDefinitionJson(), Definition.class);
            return new Workflow(e.getId(), e.getName(), e.isActive(), d.nodes(), d.connections());
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not read workflow definition: " + e.getId(), ex);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

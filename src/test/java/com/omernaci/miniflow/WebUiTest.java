package com.omernaci.miniflow;

import com.jayway.jsonpath.JsonPath;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WebUiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowService workflows;

    @BeforeEach
    void orderFlow() {
        TestWorkflows.ensureOrderFlow(workflows);
    }

    @Test
    void indexListsWorkflows() throws Exception {
        mvc.perform(get("/").param("q", "order"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Order processing")))
                .andExpect(content().string(containsString("/webhook/orders")));
    }

    @Test
    void indexFiltersAndPaginates() throws Exception {
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/workflows").param("id", String.format("page-test-%02d", i))
                    .param("name", String.format("Paging test %02d", i)));
        }
        mvc.perform(get("/").param("q", "paging test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Paging test 01")))
                .andExpect(content().string(containsString("Paging test 10")))
                .andExpect(content().string(Matchers.not(containsString("Paging test 11"))))
                .andExpect(content().string(containsString("1–10 of 12")))
                .andExpect(content().string(containsString("/?q=paging%20test&amp;page=2&amp;size=10")));

        mvc.perform(get("/").param("q", "paging test").param("page", "2"))
                .andExpect(content().string(containsString("Paging test 11")))
                .andExpect(content().string(containsString("11–12 of 12")))
                .andExpect(content().string(Matchers.not(containsString("Paging test 01"))));

        mvc.perform(get("/").param("q", "paging test").param("page", "99").param("size", "7"))
                .andExpect(content().string(containsString("11–12 of 12")));

        mvc.perform(get("/").param("q", "paging test").param("state", "active"))
                .andExpect(content().string(containsString("No workflows match the filters.")));
        mvc.perform(get("/").param("q", "paging test").param("trigger", "webhook").param("size", "25"))
                .andExpect(content().string(containsString("Paging test 12")));
        mvc.perform(get("/").param("q", "paging test").param("trigger", "queue"))
                .andExpect(content().string(containsString("No workflows match the filters.")));

        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/workflows/" + String.format("page-test-%02d", i) + "/delete"));
        }
    }

    @Test
    void bulkActionsOnSelectedWorkflows() throws Exception {
        for (String id : new String[]{"bulk-a", "bulk-b", "bulk-c"}) {
            mvc.perform(post("/workflows").param("id", id).param("name", "Bulk " + id));
        }
        mvc.perform(post("/workflows/bulk").param("action", "activate").param("ids", "bulk-a", "bulk-b")
                        .param("returnTo", "/?q=bulk&size=10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?q=bulk&size=10"))
                .andExpect(flash().attribute("success", "2 workflow(s) activated."));
        mvc.perform(get("/api/workflows/bulk-a")).andExpect(jsonPath("$.active").value(true));
        mvc.perform(get("/api/workflows/bulk-c")).andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/workflows/export-selected").param("ids", "bulk-c", "bulk-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("bulk-a"));

        mvc.perform(post("/workflows/bulk").param("action", "deactivate").param("ids", "bulk-a")
                        .param("returnTo", "//evil.example.com"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/workflows/bulk").param("action", "activate"))
                .andExpect(flash().attribute("error", "Select at least one workflow."));

        mvc.perform(get("/").param("q", "bulk"))
                .andExpect(content().string(containsString("class=\"row-check\"")))
                .andExpect(content().string(containsString("id=\"bulk-form\"")));
        mvc.perform(post("/workflows/bulk").param("action", "delete").param("ids", "bulk-a", "bulk-b", "bulk-c", "yok-boyle"))
                .andExpect(flash().attribute("success", "3 workflow(s) deleted."))
                .andExpect(flash().attribute("error", containsString("yok-boyle")));
        mvc.perform(get("/api/workflows/bulk-b")).andExpect(status().isNotFound());
    }

    @Test
    void executionsAreFilteredAndPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/workflows/order-flow/run").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"orderId\": " + i + ", \"customer\": \"Pager\", \"amount\": 10}"));
        }
        mvc.perform(get("/executions").param("workflow", "order-flow").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Order processing")))
                .andExpect(content().string(containsString("Per page")));
        mvc.perform(get("/executions").param("workflow", "order-flow").param("status", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No executions yet")));
    }

    @Test
    void dashboardShowsKpisChartAndLists() throws Exception {
        mvc.perform(post("/api/workflows/order-flow/run").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": 1, \"customer\": \"Dash\", \"amount\": 10}"));
        for (String range : new String[]{"24h", "7d", "14d", "nonsense"}) {
            mvc.perform(get("/dashboard").param("range", range))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Executions over time")))
                    .andExpect(content().string(containsString("class=\"bar-col\"")))
                    .andExpect(content().string(containsString("Most active workflows")))
                    .andExpect(content().string(containsString("Upcoming scheduled runs")));
        }
        mvc.perform(get("/dashboard").param("lang", "tr"))
                .andExpect(content().string(containsString("Zamana göre çalıştırmalar")));
    }

    @Test
    void editorRendersWithWorkflowJson() throws Exception {
        mvc.perform(get("/workflows/order-flow/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"drawflow\"")))
                .andExpect(content().string(containsString("nodeTypes:")))
                .andExpect(content().string(containsString("\"outputs\":[\"true\",\"false\"]")));

        mvc.perform(get("/workflows/order-flow/edit"))
                .andExpect(content().string(containsString("\"name\":\"condition\",\"kind\":\"text\",\"required\":true")))
                .andExpect(content().string(containsString("\"label\":\"Webhook path\"")))
                .andExpect(content().string(containsString("id=\"np-form\"")));
        mvc.perform(get("/workflows/order-flow/edit").param("lang", "tr"))
                .andExpect(content().string(containsString("\"label\":\"Webhook yolu\"")));
    }

    @Test
    void runIsRecordedAndShownInExecutions() throws Exception {
        String body = mvc.perform(post("/api/workflows/order-flow/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": 5, \"customer\": \"Zeynep\", \"amount\": 2000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.steps[3].nodeId").value("big"))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/executions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(id)));

        mvc.perform(get("/executions/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Zeynep")));
    }

    @Test
    void createWorkflowFromForm() throws Exception {
        mvc.perform(post("/workflows").param("id", "yeni-akis").param("name", "Yeni Akış"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/workflows/yeni-akis/edit"));

        mvc.perform(get("/api/workflows/yeni-akis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].type").value("trigger"));
    }

    @Test
    void duplicateNodeIdsAreRejected() throws Exception {
        mvc.perform(put("/api/workflows/bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"nodes\":[{\"id\":\"a\",\"type\":\"trigger\"},{\"id\":\"a\",\"type\":\"log\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}

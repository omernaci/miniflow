package com.omernaci.miniflow;

import com.omernaci.miniflow.workflow.Workflow;
import com.omernaci.miniflow.workflow.Workflow.Connection;
import com.omernaci.miniflow.workflow.Workflow.NodeDef;
import com.omernaci.miniflow.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RespondNodeTest {

    @Autowired WorkflowService store;
    @Autowired MockMvc mvc;

    @Test
    void respondNodeSetsStatusHeadersAndJsonBody() throws Exception {
        store.save(new Workflow("resp-json", "Respond JSON", true, List.of(
                new NodeDef("s", "trigger", Map.of("path", "resp-json")),
                new NodeDef("reply", "respond", Map.of(
                        "status", 201,
                        "headers", Map.of("Location", "/orders/{{ input.id }}"),
                        "body", "{{ input }}"))
        ), List.of(new Connection("s", "reply", null))));

        mvc.perform(post("/webhook/resp-json").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":42,\"name\":\"Ada\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/orders/42"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.executionId").doesNotExist());

        store.delete("resp-json");
    }

    @Test
    void respondNodeCanReturnPlainText() throws Exception {
        store.save(new Workflow("resp-text", "Respond text", true, List.of(
                new NodeDef("s", "trigger", Map.of("path", "resp-text")),
                new NodeDef("reply", "respond", Map.of("status", 200, "body", "pong"))
        ), List.of(new Connection("s", "reply", null))));

        mvc.perform(post("/webhook/resp-text").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));

        store.delete("resp-text");
    }

    @Test
    void withoutRespondNodeTheWebhookStillReturnsTheLastOutput() throws Exception {
        store.save(new Workflow("resp-default", "Default", true, List.of(
                new NodeDef("s", "trigger", Map.of("path", "resp-default")),
                new NodeDef("set", "set", Map.of("keepInput", false, "values", Map.of("hello", "{{ input.name }}")))
        ), List.of(new Connection("s", "set", null))));

        mvc.perform(post("/webhook/resp-default").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.hello").value("Ada"));

        store.delete("resp-default");
    }

    @Test
    void lastSuccessfulRespondNodeWins() throws Exception {
        store.save(new Workflow("resp-chain", "Chain", true, List.of(
                new NodeDef("s", "trigger", Map.of("path", "resp-chain")),
                new NodeDef("first", "respond", Map.of("status", 202, "body", "first")),
                new NodeDef("second", "respond", Map.of("status", 418, "body", "second"))
        ), List.of(new Connection("s", "first", null), new Connection("first", "second", null))));

        mvc.perform(post("/webhook/resp-chain").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(418))
                .andExpect(content().string(containsString("second")));

        store.delete("resp-chain");
    }
}

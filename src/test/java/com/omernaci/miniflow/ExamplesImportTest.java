package com.omernaci.miniflow;

import com.omernaci.miniflow.workflow.Workflow;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExamplesImportTest {

    @Autowired
    WorkflowService store;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void importExamples() throws Exception {
        String json = Files.readString(Path.of("examples/basics.json"), StandardCharsets.UTF_8);
        List<Workflow> imported = store.importJson(json, true);
        assertThat(imported).hasSize(8);
    }

    @Test
    void allExamplesAreImportedWithSchedulesInactive() {
        assertThat(store.find("crypto-price-alert").orElseThrow().active()).isFalse();
        assertThat(store.find("morning-briefing").orElseThrow().cron()).contains("0 8 * * MON-FRI");
        assertThat(store.find("cart-calculator").orElseThrow().webhookPath()).contains("cart");
    }

    @Test
    void importWithoutOverwriteFailsAndChangesNothing() {
        assertThatThrownBy(() -> store.importJson(
                "[{\"id\":\"tamamen-yeni\",\"name\":\"x\",\"nodes\":[]},{\"id\":\"fx-rate-api\",\"name\":\"y\",\"nodes\":[]}]",
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fx-rate-api");
        assertThat(store.find("tamamen-yeni")).isEmpty();
    }

    @Test
    void cartFlowCalculatesCouponShippingAndVat() throws Exception {
        mvc.perform(post("/webhook/cart").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Headphones\",\"price\":450,\"qty\":2,\"coupon\":\"save10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotal").value(900))
                .andExpect(jsonPath("$.data.discount").value(90.0))
                .andExpect(jsonPath("$.data.shipping").value(0))
                .andExpect(jsonPath("$.data.total").value(810.0))
                .andExpect(jsonPath("$.data.vatIncluded").value(135.0));

        mvc.perform(post("/webhook/cart").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Pen\",\"price\":25,\"qty\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discount").value(0))
                .andExpect(jsonPath("$.data.shipping").value(49.9))
                .andExpect(jsonPath("$.data.total").value(149.9));

        mvc.perform(post("/webhook/cart").contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.error").exists());
    }

    @Test
    void offlineBranchesOfOtherWebhookExamples() throws Exception {
        mvc.perform(post("/webhook/ticket").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"subject\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false));

        mvc.perform(post("/webhook/github").contentType(MediaType.APPLICATION_JSON).content("{\"zen\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ignored").exists());

        mvc.perform(get("/webhook/fx-rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.error").value(containsString("from and to are required")));
    }

    @Test
    void importFormAndExportEndpoints() throws Exception {
        mvc.perform(post("/workflows/import")
                        .param("json", "{\"id\":\"form-ile\",\"name\":\"Formdan\",\"nodes\":[{\"id\":\"s\",\"type\":\"trigger\"}]}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", startsWith("1 workflow(s) imported")));

        mvc.perform(post("/workflows/import").param("json", "bozuk json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        mvc.perform(get("/workflows/cart-calculator/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("cart-calculator.json")))
                .andExpect(jsonPath("$.id").value("cart-calculator"));

        mvc.perform(get("/workflows/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(8)));

        mvc.perform(post("/api/workflows/import").param("overwrite", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(Path.of("examples/workflows/fx-rate-api.json"), StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
    }
}

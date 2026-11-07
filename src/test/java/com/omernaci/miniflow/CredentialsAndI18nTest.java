package com.omernaci.miniflow;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.config.MiniFlowProperties;
import com.omernaci.miniflow.credential.CredentialCrypto;
import com.omernaci.miniflow.credential.CredentialRepository;
import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.workflow.WorkflowService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CredentialsAndI18nTest {

    @Autowired
    CredentialService credentials;

    @Autowired
    CredentialRepository repository;

    @Autowired
    CredentialCrypto crypto;

    @Autowired
    MockMvc mvc;

    @Autowired
    WorkflowService workflows;

    @BeforeEach
    void orderFlow() {
        TestWorkflows.ensureOrderFlow(workflows);
    }

    @Test
    void secretsAreEncryptedAndKeptWhenLeftBlank() {
        credentials.save(null, "ai-sifreli", "ai", Map.of("provider", "openai", "apiKey", "sk-COK-GIZLI", "model", "m1"));

        String stored = repository.findByName("ai-sifreli").orElseThrow().getDataEncrypted();
        assertThat(stored).startsWith("v1:").doesNotContain("COK-GIZLI");
        assertThat(credentials.require("ai-sifreli", "ai")).containsEntry("apiKey", "sk-COK-GIZLI");

        credentials.save("ai-sifreli", "ai-yeni-ad", "ai", Map.of("provider", "openai", "apiKey", "", "model", "m2"));
        assertThat(credentials.require("ai-yeni-ad", "ai"))
                .containsEntry("apiKey", "sk-COK-GIZLI").containsEntry("model", "m2");
        assertThat(credentials.publicValues("ai-yeni-ad")).containsEntry("apiKey", "").containsEntry("model", "m2");
        assertThat(credentials.secretsPresent("ai-yeni-ad")).containsEntry("apiKey", true);
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> credentials.save(null, "bosluklu ad", "slack", Map.of("webhookUrl", "x")))
                .isInstanceOf(MiniFlowException.class);
        assertThatThrownBy(() -> credentials.save(null, "eksik", "smtp", Map.of("host", "h", "port", "587")))
                .isInstanceOf(MiniFlowException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> credentials.save(null, "yanlis-secim", "ai",
                Map.of("provider", "baska", "model", "m"))).hasMessageContaining("provider");
        credentials.save(null, "tekil", "slack", Map.of("webhookUrl", "http://a"));
        assertThatThrownBy(() -> credentials.save(null, "tekil", "slack", Map.of("webhookUrl", "http://b")))
                .hasMessageContaining("tekil");
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String encrypted = crypto.encrypt("{\"a\":\"b\"}");
        CredentialCrypto other = new CredentialCrypto(new MiniFlowProperties(Map.of(), 14, true, "tr", "baska-anahtar", "classpath:workflows/*.json"));
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(MiniFlowException.class);
        assertThat(crypto.decrypt(encrypted)).isEqualTo("{\"a\":\"b\"}");
    }

    @Test
    void credentialPagesNeverShowSecrets() throws Exception {
        mvc.perform(post("/credentials")
                        .param("name", "ui-slack").param("type", "slack")
                        .param("f_webhookUrl", "https://hooks.slack.com/services/GIZLI-ADRES"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/credentials"));

        mvc.perform(get("/credentials"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ui-slack")))
                .andExpect(content().string(not(containsString("GIZLI-ADRES"))));

        mvc.perform(get("/credentials/ui-slack/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("GIZLI-ADRES"))));

        mvc.perform(get("/credentials/new").param("type", "smtp"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("f_host")));

        mvc.perform(post("/credentials").param("name", "ui-smtp").param("type", "smtp").param("f_host", "h"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("flash error")));

        mvc.perform(post("/credentials/ui-slack/delete"))
                .andExpect(status().is3xxRedirection());
        assertThat(repository.findByName("ui-slack")).isEmpty();
    }

    @Test
    void credentialEncryptedWithAnotherKeyCanBeReEntered() throws Exception {
        credentials.save(null, "eski-anahtar", "slack", Map.of("webhookUrl", "https://hooks.slack.com/services/x"));
        var entity = repository.findByName("eski-anahtar").orElseThrow();
        entity.setDataEncrypted("v1:" + Base64.getEncoder().encodeToString(new byte[64]));
        repository.save(entity);

        assertThat(credentials.isReadable("eski-anahtar")).isFalse();
        assertThat(credentials.unreadable()).contains("eski-anahtar");
        mvc.perform(get("/credentials"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unreadable")));
        mvc.perform(get("/credentials/eski-anahtar/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("different encryption key")));

        assertThatThrownBy(() -> credentials.save("eski-anahtar", "eski-anahtar", "slack", Map.of("webhookUrl", "")))
                .isInstanceOf(IllegalArgumentException.class);
        credentials.save("eski-anahtar", "eski-anahtar", "slack", Map.of("webhookUrl", "https://hooks.slack.com/services/y"));
        assertThat(credentials.isReadable("eski-anahtar")).isTrue();
        assertThat(credentials.require("eski-anahtar", "slack")).containsEntry("webhookUrl", "https://hooks.slack.com/services/y");
        credentials.delete("eski-anahtar");
    }

    @Test
    void englishByDefaultTurkishWithLangParamAndCookie() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("+ New workflow")))
                .andExpect(content().string(containsString("lang=\"en\"")));

        mvc.perform(get("/").param("lang", "tr"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("miniflow-lang", "tr"))
                .andExpect(content().string(containsString("+ Yeni akış")))
                .andExpect(content().string(containsString("lang=\"tr\"")));

        Cookie tr = new Cookie("miniflow-lang", "tr");
        mvc.perform(get("/credentials").cookie(tr))
                .andExpect(content().string(containsString("Kimlik bilgileri")));
        mvc.perform(get("/workflows/order-flow/edit").cookie(tr))
                .andExpect(content().string(containsString("Test et")))
                .andExpect(content().string(containsString("HTTP İsteği")));
        mvc.perform(get("/workflows/order-flow/edit"))
                .andExpect(content().string(containsString("Test run")))
                .andExpect(content().string(containsString("HTTP Request")));
    }

    @Test
    void apiErrorsFollowSelectedLanguage() throws Exception {
        String bad = "{\"name\":\"x\",\"nodes\":[{\"id\":\"s\",\"type\":\"trigger\",\"params\":{\"cron\":\"her gün\"}}]}";
        mvc.perform(put("/api/workflows/dil-test").contentType(MediaType.APPLICATION_JSON).content(bad)
                        .cookie(new Cookie("miniflow-lang", "tr")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Geçersiz cron ifadesi")));
        mvc.perform(put("/api/workflows/dil-test").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Invalid cron expression")));
    }

    @Test
    void bothLanguageFilesHaveTheSameKeys() throws Exception {
        Properties en = load("/messages.properties");
        Properties tr = load("/messages_tr.properties");
        assertThat(tr.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
        assertThat(en.size()).isGreaterThan(200);
    }

    private static Properties load(String path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = CredentialsAndI18nTest.class.getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}

package com.omernaci.miniflow.node.notify;

import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.node.ParamField;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TeamsNode extends ChatWebhookNode {

    public TeamsNode(CredentialService credentials, RestClient.Builder builder) {
        super(credentials, builder);
    }

    @Override
    public String type() {
        return "teams";
    }

    @Override
    public String label() {
        return "Microsoft Teams";
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.credential(),
                ParamField.textarea("text").mandatory(),
                ParamField.json("card"));
    }

    @Override
    protected Map<String, Object> body(String text, Map<String, Object> params) {
        if (params.get("card") instanceof Map<?, ?> card && !card.isEmpty()) {
            Map<String, Object> attachment = new LinkedHashMap<>();
            attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
            attachment.put("content", card);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "message");
            body.put("attachments", List.of(attachment));
            return body;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        return body;
    }
}

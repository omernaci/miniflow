package com.omernaci.miniflow.node.notify;

import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DiscordNode extends ChatWebhookNode {

    public DiscordNode(CredentialService credentials, RestClient.Builder builder) {
        super(credentials, builder);
    }

    @Override
    public String type() {
        return "discord";
    }

    @Override
    public String label() {
        return "Discord";
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.credential(),
                ParamField.textarea("text").mandatory(),
                ParamField.text("username"));
    }

    @Override
    protected Map<String, Object> body(String text, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", text);
        String username = Params.str(params, "username", "");
        if (!username.isBlank()) {
            body.put("username", username);
        }
        return body;
    }
}

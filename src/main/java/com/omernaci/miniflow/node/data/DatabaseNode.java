package com.omernaci.miniflow.node.data;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.credential.CredentialService;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.node.NodeExecutor;
import com.omernaci.miniflow.node.NodeResult;
import com.omernaci.miniflow.node.ParamField;
import com.omernaci.miniflow.node.support.Params;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DatabaseNode implements NodeExecutor {

    private static final Pattern READ_QUERY =
            Pattern.compile("^\\s*(select|with|show|values|explain|describe|desc)\\b", Pattern.CASE_INSENSITIVE);

    private final CredentialService credentials;

    public DatabaseNode(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public String type() {
        return "database";
    }

    @Override
    public String label() {
        return "Database (SQL)";
    }

    @Override
    public List<String> credentialTypes() {
        return List.of("database");
    }

    @Override
    public Map<String, Object> defaultParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("credential", "");
        p.put("query", "SELECT * FROM orders WHERE status = :status");
        p.put("params", Map.of("status", "{{ input.status }}"));
        p.put("maxRows", 100);
        return p;
    }

    @Override
    public List<ParamField> fields() {
        return List.of(
                ParamField.credential(),
                ParamField.code("query").mandatory().placeholder("SELECT * FROM orders WHERE status = :status"),
                ParamField.keyValue("params"),
                ParamField.number("maxRows"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Map<String, String> c = credentials.require(Params.str(params, "credential"), "database");
        String sql = Params.required(params, "query");
        Map<String, Object> sqlParams = params.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        DriverManagerDataSource ds = new DriverManagerDataSource(c.get("jdbcUrl"),
                c.getOrDefault("username", ""), c.getOrDefault("password", ""));
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        jdbc.getJdbcTemplate().setMaxRows(Params.integer(params, "maxRows", 1000));
        jdbc.getJdbcTemplate().setQueryTimeout(60);

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (READ_QUERY.matcher(sql).find()) {
                List<Map<String, Object>> rows = jdbc.queryForList(sql, sqlParams).stream()
                        .map(DatabaseNode::lowercaseKeys)
                        .toList();
                out.put("rows", rows);
                out.put("count", rows.size());
                out.put("first", rows.isEmpty() ? null : rows.get(0));
            } else {
                out.put("updated", jdbc.update(sql, sqlParams));
            }
        } catch (DataAccessException e) {
            Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
            throw new MiniFlowException(e, "error.database", root.getMessage());
        }
        return NodeResult.of(out);
    }

    private static Map<String, Object> lowercaseKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }
}

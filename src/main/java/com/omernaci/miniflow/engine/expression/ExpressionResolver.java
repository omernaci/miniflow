package com.omernaci.miniflow.engine.expression;

import com.omernaci.miniflow.common.exception.MiniFlowException;
import com.omernaci.miniflow.engine.ExecutionContext;
import com.omernaci.miniflow.variable.VariableService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{ ... }}} expressions in node parameters with SpEL.
 *
 * <p>Root variables: {@code input}, {@code trigger}, {@code nodes}, {@code vars}, {@code now},
 * {@code today}, {@code nowMillis} and every node id as a shortcut ({@code first-alert} becomes
 * {@code firstAlert}). Missing properties evaluate to {@code null}. A value that is a single
 * expression keeps its type; otherwise the result is a string.
 *
 * <p>Evaluation uses {@link SimpleEvaluationContext}:
 * type references, constructors, bean references, static methods and reflection are not available.
 */
@Component
public class ExpressionResolver {

    private static final Pattern EXPR = Pattern.compile("\\{\\{(.+?)}}", Pattern.DOTALL);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final VariableService variables;

    public ExpressionResolver(VariableService variables) {
        this.variables = variables;
    }

    private static final Set<String> RESERVED = Set.of("input", "trigger", "nodes", "vars", "loop", "now", "today", "nowMillis",
            "true", "false", "null", "and", "or", "not", "div", "mod", "eq", "ne", "lt", "le", "gt", "ge", "new", "T");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static String alias(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String a = nodeId;
        if (!IDENTIFIER.matcher(a).matches()) {
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char ch : nodeId.toCharArray()) {
                if (ch == '-' || ch == ' ' || ch == '.') {
                    upper = sb.length() > 0;
                } else {
                    sb.append(upper ? Character.toUpperCase(ch) : ch);
                    upper = false;
                }
            }
            a = sb.toString();
        }
        return IDENTIFIER.matcher(a).matches() && !RESERVED.contains(a) && !ExpressionFunctions.NAMES.contains(a)
                ? a : null;
    }

    public Map<String, Object> resolve(Map<String, Object> params, Map<String, Object> input, ExecutionContext ctx) {
        Map<String, Object> root = new ExpressionFunctions.Scope();
        ctx.nodeOutputs().forEach((id, output) -> {
            String a = alias(id);
            if (a != null) {
                root.putIfAbsent(a, output);
            }
        });
        root.put("input", input == null ? Map.of() : input);
        root.put("trigger", ctx.trigger());
        root.put("nodes", ctx.nodeOutputs());
        root.put("vars", variables.all());
        root.put("now", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        root.put("today", LocalDate.now().toString());
        root.put("nowMillis", System.currentTimeMillis());
        root.put("loop", ctx.loop());

        EvaluationContext evalCtx = SimpleEvaluationContext
                .forPropertyAccessors(new LenientMapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
                .withMethodResolvers(new ExpressionFunctions(), new SafeMethodResolver())
                .withRootObject(root)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) resolveValue(params, evalCtx);
        return resolved;
    }

    public Map<String, Object> preview(Map<String, Object> params, Map<String, Object> input,
                                       Map<String, Object> trigger, Map<String, Object> nodes) {
        ExecutionContext ctx = new ExecutionContext(null, "manual", trigger);
        if (nodes != null) {
            ctx.nodeOutputs().putAll(nodes);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> errors = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((key, value) -> {
                try {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put(key, value);
                    values.put(key, resolve(one, input, ctx).get(key));
                } catch (RuntimeException e) {
                    errors.put(key, e.getMessage());
                }
            });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("errors", errors);
        return out;
    }

    private Object resolveValue(Object value, EvaluationContext evalCtx) {
        if (value instanceof String s) {
            return resolveString(s, evalCtx);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolveValue(v, evalCtx)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(resolveValue(v, evalCtx)));
            return out;
        }
        return value;
    }

    private Object resolveString(String s, EvaluationContext evalCtx) {
        String t = s.trim();
        if (t.startsWith("{{") && t.endsWith("}}") && t.indexOf("}}") == t.length() - 2 && t.length() > 4) {
            return evaluate(t.substring(2, t.length() - 2), evalCtx);
        }
        Matcher m = EXPR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = evaluate(m.group(1), evalCtx);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object evaluate(String expression, EvaluationContext evalCtx) {
        try {
            return parser.parseExpression(expression.trim()).getValue(evalCtx);
        } catch (Exception e) {
            throw new MiniFlowException(e, "error.expression", "{{" + expression + "}}", e.getMessage());
        }
    }

    static final class LenientMapAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[]{Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map;
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue)
                throws AccessException {
            throw new AccessException("Assignment is not allowed in expressions");
        }
    }

    static final class SafeMethodResolver extends ReflectiveMethodResolver {

        private static final Set<String> ALWAYS_BLOCKED = Set.of("getClass", "wait", "notify", "notifyAll");

        private static final Set<String> MUTATORS = Set.of(
                "put", "putAll", "putIfAbsent", "remove", "clear", "add", "addAll",
                "removeAll", "retainAll", "removeIf", "replaceAll", "set", "compute", "merge");

        @Override
        public MethodExecutor resolve(EvaluationContext context, Object target, String name,
                                      List<TypeDescriptor> argumentTypes) throws AccessException {
            if (target == null || target instanceof Class<?> || !isDataType(target) || isBlocked(target.getClass(), name)) {
                return null;
            }
            return super.resolve(context, target, name, argumentTypes);
        }

        @Override
        protected Method[] getMethods(Class<?> type) {
            return Arrays.stream(super.getMethods(type))
                    .filter(m -> !Modifier.isStatic(m.getModifiers()))
                    .filter(m -> !isBlocked(type, m.getName()))
                    .toArray(Method[]::new);
        }

        private static boolean isBlocked(Class<?> type, String name) {
            if (ALWAYS_BLOCKED.contains(name)) {
                return true;
            }
            boolean mutable = Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
            return mutable && MUTATORS.contains(name);
        }

        private static boolean isDataType(Object target) {
            return target instanceof CharSequence || target instanceof Number || target instanceof Boolean
                    || target instanceof Character || target instanceof Map<?, ?> || target instanceof Collection<?>;
        }
    }
}

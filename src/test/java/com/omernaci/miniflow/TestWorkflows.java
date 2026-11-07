package com.omernaci.miniflow;

import com.omernaci.miniflow.workflow.WorkflowService;

final class TestWorkflows {

    static final String ORDER_FLOW = """
            {
              "id": "order-flow",
              "name": "Order processing",
              "active": true,
              "nodes": [
                {"id": "start", "type": "trigger", "x": 40, "y": 200,
                 "params": {"path": "orders", "sample": {"orderId": 42, "customer": "Alice", "amount": 1500}}},
                {"id": "calc", "type": "set", "x": 300, "y": 200,
                 "params": {"values": {"total": "{{ input.amount * 1.20 }}",
                                       "label": "Order #{{ input.orderId }} - {{ input.customer }}"}}},
                {"id": "check", "type": "if", "x": 560, "y": 200, "params": {"condition": "{{ input.total > 1000 }}"}},
                {"id": "big", "type": "log", "x": 840, "y": 90,
                 "params": {"message": "LARGE order: {{ input.label }}, total {{ input.total }}"}},
                {"id": "small", "type": "log", "x": 840, "y": 320,
                 "params": {"message": "Regular order: {{ input.label }}, total {{ input.total }}"}},
                {"id": "done", "type": "set", "x": 1110, "y": 200,
                 "params": {"keepInput": false,
                            "values": {"orderId": "{{ trigger.orderId }}",
                                       "priority": "{{ nodes['check'].total > 1000 ? 'HIGH' : 'NORMAL' }}"}}}
              ],
              "connections": [
                {"from": "start", "to": "calc"},
                {"from": "calc", "to": "check"},
                {"from": "check", "to": "big", "branch": "true"},
                {"from": "check", "to": "small", "branch": "false"},
                {"from": "big", "to": "done"},
                {"from": "small", "to": "done"}
              ]
            }
            """;

    private TestWorkflows() {
    }

    static void ensureOrderFlow(WorkflowService workflows) {
        if (workflows.find("order-flow").isEmpty()) {
            workflows.importJson(ORDER_FLOW, false);
        }
    }
}
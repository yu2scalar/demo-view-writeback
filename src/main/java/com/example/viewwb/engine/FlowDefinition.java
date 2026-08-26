package com.example.viewwb.engine;

import com.example.viewwb.exception.CustomException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 更新モジュールのフロー定義。GUI(Drawflow エディタ)が出力する flow_json と 1:1。
 * ノード種別: start / read / var / compare / branch / update / abort / commit
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowDefinition(
        String moduleName,
        List<Node> nodes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(
            int id,
            String type,
            Map<String, Object> params,
            /** 遷移先: 通常ノードは {"next":[id]}、分岐は {"true":[id],"false":[id]} */
            Map<String, List<Integer>> next) {

        public String param(String name) {
            Object value = params == null ? null : params.get(name);
            return value == null ? null : String.valueOf(value);
        }

        public String requiredParam(String name) {
            String value = param(name);
            if (value == null || value.isBlank()) {
                throw new CustomException("Flow node " + id + " (" + type
                        + "): parameter '" + name + "' is required", 400);
            }
            return value;
        }
    }

    public Node startNode() {
        return nodes.stream().filter(n -> "start".equals(n.type())).findFirst()
                .orElseThrow(() -> new CustomException("Flow has no start node", 400));
    }

    public Node node(int id) {
        return nodes.stream().filter(n -> n.id() == id).findFirst()
                .orElseThrow(() -> new CustomException("Flow references unknown node id " + id, 400));
    }
}

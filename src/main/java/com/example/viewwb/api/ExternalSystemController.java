package com.example.viewwb.api;

import com.example.viewwb.dto.ApiResponse;
import com.example.viewwb.service.ExternalSystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部システム模擬コンソール(plan-009)の API。
 * 変更リクエスト inbox の閲覧と、許可(生テーブル更新 + SUCCEEDED 返送)/
 * 却下(REJECTED 返送)を提供する。
 */
@RestController
@RequestMapping("/api/external")
public class ExternalSystemController {

    private final ExternalSystemService service;

    public ExternalSystemController(ExternalSystemService service) {
        this.service = service;
    }

    @GetMapping("/{namespace}/inbox")
    public ApiResponse<List<Map<String, Object>>> inbox(@PathVariable String namespace) {
        return ApiResponse.success(service.listInbox(namespace));
    }

    @PostMapping("/{namespace}/inbox/{eventId}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String namespace,
                                                    @PathVariable String eventId) {
        return ApiResponse.success(service.approve(namespace, eventId));
    }

    @PostMapping("/{namespace}/inbox/{eventId}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable String namespace,
                                                   @PathVariable String eventId) {
        return ApiResponse.success(service.reject(namespace, eventId));
    }
}

package com.example.viewwb.api;

import com.example.viewwb.dto.ApiResponse;
import com.example.viewwb.service.AdminSetupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminSetupService setupService;

    public AdminController(AdminSetupService setupService) {
        this.setupService = setupService;
    }

    /** viewmgr / views namespace とメタテーブルを作成(冪等) */
    @PostMapping("/setup")
    public ApiResponse<Map<String, Object>> setup() {
        return ApiResponse.success(setupService.setup(), "Setup completed");
    }
}

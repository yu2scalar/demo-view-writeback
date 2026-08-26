package com.example.viewwb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // plan-009: viewmgr.re_inbox の自動ポーリング(InboxApplyService)
public class DemoViewWritebackApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoViewWritebackApplication.class, args);
    }
}

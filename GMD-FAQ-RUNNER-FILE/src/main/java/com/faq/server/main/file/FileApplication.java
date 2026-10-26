package com.gmd.faq.server.main.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 描述：技能文件同步服务启动类
 * <p>
 * 极简 Spring Boot 应用，负责从 console 节点同步 skill 文件到本地（runtime 节点）。
 * 不对外暴露 HTTP 接口，仅作为 HTTP 客户端和 Redis 订阅者运行。
 *
 **/
@SpringBootApplication
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}

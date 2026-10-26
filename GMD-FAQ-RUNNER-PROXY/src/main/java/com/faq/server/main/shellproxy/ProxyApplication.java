package com.gmd.faq.server.main.shellproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 描述：Shell执行代理启动入口
 * <p>
 * 极简Spring Boot应用，仅提供HTTP Shell命令代理执行能力。
 * 无数据库、缓存、消息队列等外部依赖。
 *
 **/
@SpringBootApplication
public class ProxyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyApplication.class, args);
    }
}

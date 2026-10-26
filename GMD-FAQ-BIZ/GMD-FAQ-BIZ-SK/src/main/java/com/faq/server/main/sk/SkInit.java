package com.gmd.faq.server.main.sk;

import com.gmd.faq.server.main.common.config.auth.AuthInterceptor;
import com.gmd.faq.server.main.common.consts.TableConst;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 描述：初始化API Key
 *
 **/
@Slf4j
@Component
@RequiredArgsConstructor
public class SkInit implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            getAllSkIDs().forEach(AuthInterceptor::addSkId);
            log.info("API Key 初始化完成");
        } catch (Exception e) {
            log.error("API Key 初始化失败", e);
            throw new RuntimeException(e);
        }
    }

    private List<Long> getAllSkIDs() {
        return jdbcTemplate.query(
                "SELECT id FROM " + TableConst.SECRET_KEY + " WHERE enabled = 1",
                (rs, rowNum) -> rs.getLong("id"));
    }
}

package com.gmd.faq.server.main.console.skill;

import com.gmd.faq.server.main.common.consts.TableConst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 启动时生成内部服务鉴权 token
 * 每次启动删除旧 token，插入新的 UUID token
 *
 **/
@Service
public class InitTokenScript implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitTokenScript.class);

    private final JdbcTemplate jdbcTemplate;

    public InitTokenScript(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    @Override
    public void run(ApplicationArguments args) {
        String newToken = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("DELETE FROM " + TableConst.SKILL_TOKEN);
        jdbcTemplate.update("INSERT INTO " + TableConst.SKILL_TOKEN + " (skill_token) VALUES (?)", newToken);
        log.info("已生成新的内部服务鉴权 token");
    }
}

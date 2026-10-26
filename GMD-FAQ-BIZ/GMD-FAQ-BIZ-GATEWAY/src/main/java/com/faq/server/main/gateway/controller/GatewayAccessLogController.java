package com.gmd.faq.server.main.gateway.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.gateway.entity.GatewayAccessLog;
import com.gmd.faq.server.main.gateway.service.GatewayAccessLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：网关访问日志控制器
 *
 **/
@RestController
@RequestMapping("/gateway/access-log")
@RequiredArgsConstructor
public class GatewayAccessLogController {
    private final GatewayAccessLogService accessLogService;

    @GetMapping("/page")
    public R<IPage<GatewayAccessLog>> page(GatewayAccessLog query, PageParams pageParams) {
        return R.data(accessLogService.pageLogs(query, pageParams));
    }

    @GetMapping("/{id}")
    public R<GatewayAccessLog> get(@PathVariable("id") Long id) {
        return R.data(accessLogService.logDetail(id));
    }

    @DeleteMapping
    @PermNeed({"apiservice:app:remove"})
    public R<?> delete(@RequestBody List<Long> ids) {
        return R.data(accessLogService.deleteLogs(ids));
    }
}
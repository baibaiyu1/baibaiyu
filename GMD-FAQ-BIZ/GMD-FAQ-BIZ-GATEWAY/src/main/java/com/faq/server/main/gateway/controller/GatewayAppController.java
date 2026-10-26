package com.gmd.faq.server.main.gateway.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.gateway.entity.GatewayApp;
import com.gmd.faq.server.main.gateway.service.GatewayAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：网关应用管理控制器
 *
 **/
@RestController
@RequestMapping("/gateway/app")
@RequiredArgsConstructor
public class GatewayAppController {
    private final GatewayAppService gatewayAppService;

    @PostMapping
    @PermNeed({"apiservice:app:add"})
    public R<?> add(@RequestBody GatewayApp app) {
        return R.data(gatewayAppService.saveApp(app));
    }

    @PutMapping
    @PermNeed({"apiservice:app:edit"})
    public R<?> update(@RequestBody GatewayApp app) {
        return R.data(gatewayAppService.updateApp(app));
    }

    @DeleteMapping
    @PermNeed({"apiservice:app:remove"})
    public R<?> delete(@RequestBody List<Long> ids) {
        return R.data(gatewayAppService.deleteApps(ids));
    }

    @GetMapping("/page")
    public R<IPage<GatewayApp>> page(GatewayApp query, PageParams pageParams) {
        return R.data(gatewayAppService.page(MP.getPage(pageParams), MP.getQueryWrapper(query)));
    }

    @GetMapping
    public R<List<GatewayApp>> list() {
        return R.data(gatewayAppService.lambdaQuery().orderByDesc(GatewayApp::getCreatedAt).list());
    }

    @GetMapping("/{id}")
    public R<GatewayApp> get(@PathVariable("id") Long id) {
        return R.data(gatewayAppService.getById(id));
    }

    @PutMapping("/{id}/online/{v}")
    @PermNeed({"apiservice:app:edit"})
    public R<?> online(@PathVariable("id") Long id, @PathVariable("v") Integer v) {
        return R.data(gatewayAppService.updateOnline(id, v));
    }
}
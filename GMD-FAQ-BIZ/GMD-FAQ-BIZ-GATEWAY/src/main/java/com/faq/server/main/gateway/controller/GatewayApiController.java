package com.gmd.faq.server.main.gateway.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.gateway.entity.GatewayApi;
import com.gmd.faq.server.main.gateway.service.GatewayApiService;
import com.gmd.faq.server.main.gateway.vo.GatewayApiVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：网关API管理控制器
 *
 **/
@RestController
@RequestMapping("/gateway/api")
@RequiredArgsConstructor
public class GatewayApiController {
    private final GatewayApiService gatewayApiService;

    @PostMapping
    @PermNeed({"apiservice:app:add"})
    public R<?> add(@RequestBody GatewayApiVO vo) {
        return R.data(gatewayApiService.saveApi(vo));
    }

    @PutMapping
    @PermNeed({"apiservice:app:edit"})
    public R<?> update(@RequestBody GatewayApiVO vo) {
        return R.data(gatewayApiService.updateApi(vo));
    }

    @DeleteMapping
    @PermNeed({"apiservice:app:remove"})
    public R<?> delete(@RequestBody List<Long> ids) {
        return R.data(gatewayApiService.deleteApis(ids));
    }

    @GetMapping("/page")
    public R<IPage<GatewayApiVO>> page(GatewayApi query, PageParams pageParams) {
        return R.data(gatewayApiService.pageVO(query, pageParams));
    }

    @GetMapping("/{id}")
    public R<GatewayApiVO> get(@PathVariable("id") Long id) {
        return R.data(gatewayApiService.detail(id));
    }

    @PutMapping("/{id}/online/{v}")
    @PermNeed({"apiservice:app:edit"})
    public R<?> online(@PathVariable("id") Long id, @PathVariable("v") Integer v) {
        return R.data(gatewayApiService.updateOnline(id, v));
    }

    @GetMapping("/categories")
    public R<List<String>> categories() {
        return R.data(gatewayApiService.categories());
    }

    @GetMapping("/brief")
    public R<List<GatewayApiVO>> brief() {
        return R.data(gatewayApiService.listBrief());
    }
}
package com.gmd.faq.server.main.console.params;

import com.gmd.faq.server.main.common.entity.Params;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.params.service.ParamsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：系统参数
 *
 **/
@RestController
@RequestMapping("/params")
public class ParamsController {
    private final ParamsService paramsService;

    public ParamsController(ParamsService paramsService) {
        this.paramsService = paramsService;
    }

    @GetMapping(value = "/page", name = "分页")
    public R<?> page(Params params, PageParams pageParams) {
        return R.data(paramsService.page(MP.getPage(pageParams), MP.getQueryWrapper(params)));
    }

    @PermNeed({"system:params:add"})
    @PostMapping(value = "/add", name = "新增")
    public R<?> add(@RequestBody Params params) {
        return R.data(paramsService.saveV2(params));
    }

    @PermNeed({"system:params:edit"})
    @PostMapping(value = "/update", name = "编辑")
    public R<?> update(@RequestBody Params params) {
        return R.data(paramsService.updateByIdV2(params));
    }

    @PermNeed({"system:params:remove"})
    @PostMapping(value = "/delete", name = "删除")
    public R<?> delete(@RequestBody List<Long> ids) {
        return R.data(paramsService.removeBatchByIds(ids));
    }

    @GetMapping(value = "{id}", name = "根据ID唯一获取")
    public R<?> selectOne(@PathVariable("id") Long id) {
        return R.data(paramsService.getById(id));
    }

    @GetMapping(value = "/fetch-value-by-key", name = "根据key获取value")
    public R<?> fetchValueByKey(@RequestParam("key") String key) {
        return R.data(paramsService.fetchValueByKey(key));
    }

}
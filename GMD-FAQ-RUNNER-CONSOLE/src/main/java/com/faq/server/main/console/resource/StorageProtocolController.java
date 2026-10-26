package com.gmd.faq.server.main.console.resource;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.gmd.faq.server.main.common.entity.StorageProtocol;
import com.gmd.faq.server.main.common.mp.support.MP;
import com.gmd.faq.server.main.common.mp.support.PageParams;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.resource.service.StorageProtocolService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：文件存储协议配置
 *
 **/
@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
public class StorageProtocolController {
    private final StorageProtocolService storageProtocolService;

    @PermNeed({"system:storage:add"})
    @PostMapping(value = "/add", name = "新增")
    public R<Boolean> add(@RequestBody StorageProtocol body) {
        return R.data(storageProtocolService.saveV2(body));
    }

    @PermNeed({"system:storage:edit"})
    @PostMapping(value = "/update", name = "编辑")
    public R<Boolean> update(@RequestBody StorageProtocol body) {
        return R.data(storageProtocolService.updateV2(body));
    }

    @PermNeed({"system:storage:remove"})
    @PostMapping(value = "/delete", name = "删除")
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(storageProtocolService.removeBatchByIds(ids));
    }

    @GetMapping(value = "/page", name = "分页")
    public R<IPage<StorageProtocol>> page(StorageProtocol storageProtocol, PageParams pageParams) {
        return R.data(storageProtocolService.page(MP.getPage(pageParams), MP.getQueryWrapper(storageProtocol)));
    }

    @GetMapping(value = "/selectOne", name = "根据ID唯一获取")
    public R<StorageProtocol> selectOne(@RequestParam("id") Long id) {
        return R.data(storageProtocolService.getById(id));
    }

    @PermNeed({"system:storage:edit"})
    @GetMapping(value = "/validSuccess", name = "设置有效")
    public R<Boolean> validSuccess(@RequestParam("id") Long id) {
        return R.data(storageProtocolService.validSuccess(id));
    }

    @PermNeed({"system:storage:edit"})
    @PostMapping(value = "/updateProtocol", name = "更新协议配置")
    public R<Boolean> updateProtocol(@RequestBody StorageProtocol body) {
        UpdateWrapper<StorageProtocol> uw = new UpdateWrapper<>();
        uw.eq("id", body.getId());
        uw.set("protocol_config", body.getProtocolConfig());
        return R.data(storageProtocolService.update(uw));
    }
}
package com.gmd.faq.server.main.console.sk;

import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.SecretKeyVo;
import com.gmd.faq.server.main.sk.service.SecretKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 描述：访问秘钥Controller
 *
 **/
@RestController
@RequestMapping("/sk")
@RequiredArgsConstructor
public class SecretKeyController {

    private final SecretKeyService secretKeyService;

    /**
     * 查询全部秘钥列表（value已脱敏）
     */
    @GetMapping("/list")
    public R<List<SecretKeyVo>> list() {
        return R.data(secretKeyService.listAll());
    }

    /**
     * 新增秘钥
     */
    @PermNeed({"system:apikey:add"})
    @PostMapping
    public R<SecretKeyVo> create(@RequestBody SecretKeyVo vo) {
        return R.data(secretKeyService.create(vo));
    }

    /**
     * 更新秘钥（仅允许更新名称）
     */
    @PermNeed({"system:apikey:edit"})
    @PutMapping
    public R<Boolean> update(@RequestBody SecretKeyVo vo) {
        return R.data(secretKeyService.updateName(vo));
    }

    /**
     * 删除秘钥
     */
    @PermNeed({"system:apikey:remove"})
    @DeleteMapping
    public R<Boolean> delete(@RequestBody List<Long> ids) {
        return R.data(secretKeyService.delete(ids));
    }
}
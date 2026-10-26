package com.gmd.faq.server.main.params.core.impl;

import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.params.core.ParamsCore;
import org.springframework.stereotype.Component;

/**
 * 描述：技能包文件允许入库的扩展名白名单（逗号分隔）
 *
 **/
@Component
public class SkillFileAllowedExtensionsParams implements ParamsCore {
    @Override
    public String checkAndFormatValue(String value) {
        if (FuncUtils.isEmpty(value)) {
            throw new RuntimeException("value值不可为空");
        }
        return value.trim();
    }

    @Override
    public String getDefaultValue() {
        return "md,py,sh,js,ts,json,yaml,yml,xml,txt,java,cs,go,rs,rb,php,sql,html,css,scss,less,cfg,conf,toml";
    }

    @Override
    public void register(ParamsAdapter adapter) {
        adapter.register("SKILL_FILE_ALLOWED_EXTENSIONS", new SkillFileAllowedExtensionsParams());
    }
}

package com.gmd.faq.server.main.params.core.impl;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.params.core.ParamsCore;
import org.springframework.stereotype.Component;

/**
 * 描述：token存活时长
 *
 **/
@Component
public class RefreshTokenTTLParams implements ParamsCore {
    @Override
    public String checkAndFormatValue(String value) {
        if(FuncUtils.isEmpty(value)) {
            throw new RuntimeException("value值不可为空");
        }
        String trim = value.trim();
        try {
            Long.parseLong(trim);
        } catch (NumberFormatException e) {
            throw new RuntimeException("系统参数REFRESH_TOKEN_TTL不合法",e);
        }
        return trim;
    }

    @Override
    public String getDefaultValue() {
        return String.valueOf(SysConst.REFRESH_TOKEN_TTL);
    }

    @Override
    public void register(ParamsAdapter adapter) {
        adapter.register("REFRESH_TOKEN_TTL", new RefreshTokenTTLParams());
    }
}

package com.gmd.faq.server.main.params.core.impl;

import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.params.core.ParamsCore;
import org.springframework.stereotype.Component;

/**
 * 描述：允许上传的文件类型
 *
 **/
@Component
public class AllowImageUploadFile implements ParamsCore {
    @Override
    public String checkAndFormatValue(String value) {
        if(FuncUtils.isEmpty(value)) {
            throw new RuntimeException("value值不可为空");
        }
        String trim = value.trim();
        trim = trim.replace(" ", ",");
        trim = trim.replace("，", ",");
        trim = trim.replace("、", ",");
        trim = trim.replace(";", ",");
        trim = trim.replace("；", ",");
        return trim;
    }

    @Override
    public String getDefaultValue() {
        return SysConst.ALLOW_IMAGE_FILE_TYPE;
    }

    @Override
    public void register(ParamsAdapter adapter) {
        adapter.register("ALLOW_IMAGE_FILE_TYPE", new AllowImageUploadFile());
    }
}

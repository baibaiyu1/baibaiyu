package com.gmd.faq.server.main.params.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.cluster.core.MessagePublisher;
import com.gmd.faq.server.main.common.consts.RedisChannelTopic;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.entity.Params;
import com.gmd.faq.server.main.common.message.ParamChangeMessage;
import com.gmd.faq.server.main.common.util.FuncUtils;
import com.gmd.faq.server.main.common.util.JsonUtils;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.params.mapper.ParamsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 描述：系统参数
 *
 **/
@Slf4j
@Service
public class ParamsServiceImpl extends ServiceImpl<ParamsMapper, Params> implements ParamsService {
    private final ParamsAdapter paramsAdapter;
    private final MessagePublisher messagePublisher;

    public ParamsServiceImpl(ParamsAdapter paramsAdapter, MessagePublisher messagePublisher) {
        this.paramsAdapter = paramsAdapter;
        this.messagePublisher = messagePublisher;
    }

    @Override
    public String fetchValueByKey(String key) {
        return paramsAdapter.getValue(key);
    }

    @Override
    public boolean saveV2(Params params) {
        if(FuncUtils.isEmpty(params.getParamValue())) {
            throw new RuntimeException("value值不可为空");
        }

        List<Params> list = lambdaQuery()
                .eq(Params::getParamKey, params.getParamKey())
                .list();
        if (list == null || list.isEmpty()) {
            boolean result = paramsAdapter.saveParams(params) > 0;
            // 发布参数变更广播
            publishParamChange(params.getParamKey());
            return result;
        } else {
            throw new RuntimeException("Key已存在");
        }
    }


    @Override
    public boolean updateByIdV2(Params params) {
        if(FuncUtils.isEmpty(params.getParamValue())) {
            throw new RuntimeException("value值不可为空");
        }

        boolean result = paramsAdapter.updateParams(params) > 0;
        // 发布参数变更广播
        publishParamChange(params.getParamKey());
        return result;
    }

    /**
     * 通过 Redis 广播参数变更通知，各模块订阅者按 paramKey 自行过滤处理
     */
    private void publishParamChange(String paramKey) {
        try {
            ParamChangeMessage message = ParamChangeMessage.create(
                    SysConst.CURRENT_NODE_ID, paramKey);
            String json = JsonUtils.toJsonStr(message);
            if (json != null) {
                messagePublisher.publish(RedisChannelTopic.PARAM_CHANGE_CHANNEL, json);
                log.debug("发布参数变更广播 - paramKey: {}", paramKey);
            }
        } catch (Exception e) {
            log.error("发布参数变更广播失败", e);
        }
    }
}

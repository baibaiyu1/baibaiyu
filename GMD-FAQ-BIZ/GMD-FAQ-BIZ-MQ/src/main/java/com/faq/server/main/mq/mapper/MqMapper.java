package com.gmd.faq.server.main.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.mq.entity.Mq;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：消息队列表ROM
 *
 **/
@Mapper
public interface MqMapper extends BaseMapper<Mq> {
}

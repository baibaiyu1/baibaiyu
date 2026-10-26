package com.gmd.faq.server.main.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.AttachChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：附件表分片记录表
 *
 **/
@Mapper
public interface AttachChunkMapper extends BaseMapper<AttachChunk> {
}

package com.gmd.faq.server.main.resource.visibility.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.ResourceAccessGrant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceAccessGrantMapper extends BaseMapper<ResourceAccessGrant> {
}

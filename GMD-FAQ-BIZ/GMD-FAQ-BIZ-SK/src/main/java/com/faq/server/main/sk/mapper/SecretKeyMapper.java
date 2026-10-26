package com.gmd.faq.server.main.sk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.SecretKey;
import org.apache.ibatis.annotations.Mapper;

/**
 * 描述：访问秘钥数据访问层
 *
 **/
@Mapper
public interface SecretKeyMapper extends BaseMapper<SecretKey> {
}

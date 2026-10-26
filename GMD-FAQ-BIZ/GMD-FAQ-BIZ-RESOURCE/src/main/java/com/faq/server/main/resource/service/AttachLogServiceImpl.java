package com.gmd.faq.server.main.resource.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.entity.AttachLog;
import com.gmd.faq.server.main.resource.mapper.AttachLogMapper;
import org.springframework.stereotype.Service;

/**
 * 描述：附件操作日志
 *
 **/
@Service
public class AttachLogServiceImpl extends ServiceImpl<AttachLogMapper, AttachLog> implements AttachLogService {
}

package com.gmd.faq.server.main.engine.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG文档Mapper
 *
 */
@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {
}

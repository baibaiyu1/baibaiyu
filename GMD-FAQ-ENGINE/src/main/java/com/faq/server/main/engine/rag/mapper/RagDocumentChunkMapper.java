package com.gmd.faq.server.main.engine.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gmd.faq.server.main.common.entity.RagDocumentChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG文档分块Mapper
 *
 */
@Mapper
public interface RagDocumentChunkMapper extends BaseMapper<RagDocumentChunk> {
}

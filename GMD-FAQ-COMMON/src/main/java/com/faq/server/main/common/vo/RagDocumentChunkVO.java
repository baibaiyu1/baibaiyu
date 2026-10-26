package com.gmd.faq.server.main.common.vo;

import com.gmd.faq.server.main.common.entity.RagDocumentChunk;
import lombok.Getter;
import lombok.Setter;

/**
 * 描述：RagDocumentChunk VO
 *
 **/
@Getter
@Setter
public class RagDocumentChunkVO extends RagDocumentChunk {
    private double score = 0;
}

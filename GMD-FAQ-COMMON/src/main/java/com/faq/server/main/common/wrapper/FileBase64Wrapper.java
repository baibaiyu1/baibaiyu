package com.gmd.faq.server.main.common.wrapper;

import com.gmd.faq.server.main.common.enums.ModelType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 描述：FileBase64 包装类
 *
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileBase64Wrapper {
    String base64;
    String mediaType;
    ModelType modelType;
}

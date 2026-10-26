package com.gmd.faq.server.main.node.serialize.serializer;

import com.gmd.faq.server.main.node.serialize.Config;
import com.gmd.faq.server.main.node.serialize.Serializer;

import java.util.Base64;

/**
 * 描述：base64序列化器
 *
 **/
public class Base64Serializer implements Serializer {
    @Override
    public String serialize(Object data, Config config) throws Exception {
        if (data instanceof String) {
            return Base64.getEncoder().encodeToString(((String) data).getBytes());
        } else if (data instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) data);
        } else {
            String json = new JsonSerializer().serialize(data, config);
            return Base64.getEncoder().encodeToString(json.getBytes());
        }
    }
}

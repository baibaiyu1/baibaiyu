package com.gmd.faq.server.main.node.unserialize.unserializer;

import com.gmd.faq.server.main.node.unserialize.Config;
import com.gmd.faq.server.main.node.unserialize.UnSerializer;

import java.util.Base64;

/**
 * 描述：base64反序列化器
 *
 **/
public class Base64UnSerializer implements UnSerializer {
    @Override
    public Object unserialize(String data, Config config) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(data);
        return new String(decoded, config.getEncoding());
    }
}

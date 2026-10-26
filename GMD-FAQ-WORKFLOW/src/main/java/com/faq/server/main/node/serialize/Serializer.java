package com.gmd.faq.server.main.node.serialize;

/**
 * 描述：序列化接口
 *
 **/
public interface Serializer {
    String serialize(Object data, Config config) throws Exception;
}

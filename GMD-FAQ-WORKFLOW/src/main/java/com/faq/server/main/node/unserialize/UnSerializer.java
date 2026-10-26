package com.gmd.faq.server.main.node.unserialize;

/**
 * 描述：序列化接口
 *
 **/
public interface UnSerializer {
    Object unserialize(String data, Config config) throws Exception;
}

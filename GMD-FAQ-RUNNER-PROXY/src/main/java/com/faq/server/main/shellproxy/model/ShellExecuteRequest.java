package com.gmd.faq.server.main.shellproxy.model;

/**
 * 描述：Shell执行请求
 *
 **/
public class ShellExecuteRequest {

    /** Shell命令（必填） */
    private String command;

    /** 超时时间（秒），不填使用默认值 */
    private Integer timeout;

    /** 输出字符集，默认UTF-8 */
    private String charset;

    /** 工作目录 */
    private String workingDirectory;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }
}

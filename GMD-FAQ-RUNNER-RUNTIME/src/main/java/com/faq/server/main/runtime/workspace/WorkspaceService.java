package com.gmd.faq.server.main.runtime.workspace;

import com.gmd.faq.server.main.common.dto.WorkspaceFileWriteDTO;
import com.gmd.faq.server.main.common.vo.WorkspaceCapacityVO;
import com.gmd.faq.server.main.common.vo.WorkspaceFileNode;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;

/**
 * 描述：工作空间文件管理 Service
 *
 **/
public interface WorkspaceService {

    /**
     * 以文本形式写入（覆盖）工作空间中的文件
     *
     * <p>供右栏「文章编写模式」保存使用。与 multipart 上传的区别是可做并发冲突检测。
     *
     * @param dto 写入参数
     */
    void writeFileContent(WorkspaceFileWriteDTO dto);

    /**
     * 上传单个文件到工作空间
     *
     * @param sessionId 会话ID
     * @param file      上传的文件
     * @return 文件在工作空间中的相对路径
     */
    String uploadFile(String sessionId, MultipartFile file);

    /**
     * 上传多个文件到工作空间
     *
     * @param sessionId 会话ID
     * @param files     上传的文件数组
     * @return 各文件在工作空间中的相对路径列表
     */
    List<String> uploadFiles(String sessionId, MultipartFile[] files);

    /**
     * 上传压缩包并自动解压到工作空间
     *
     * @param sessionId 会话ID
     * @param file      压缩包文件
     * @return 解压后的文件相对路径列表
     */
    List<String> uploadAndExtractArchive(String sessionId, MultipartFile file);

    /**
     * 获取工作空间文件树
     *
     * @param sessionId 会话ID
     * @return 文件树根节点列表
     */
    List<WorkspaceFileNode> listFiles(String sessionId);

    /**
     * 下载工作空间中的单个文件
     *
     * @param sessionId    会话ID
     * @param fileName     文件全名
     * @param outputStream 输出流
     */
    void downloadFile(String sessionId, String fileName, OutputStream outputStream);

    /**
     * 下载工作空间中的多个文件（打包成ZIP）
     *
     * @param sessionId    会话ID
     * @param filePaths    文件相对路径列表
     * @param outputStream 输出流
     */
    void downloadFiles(String sessionId, List<String> filePaths, OutputStream outputStream);

    /**
     * 下载整个工作空间（打包成ZIP）
     *
     * @param sessionId    会话ID
     * @param outputStream 输出流
     */
    void downloadWorkspace(String sessionId, OutputStream outputStream);

    /**
     * 删除工作空间中的单个文件或目录
     *
     * @param sessionId 会话ID
     * @param filePath  文件在工作空间中的相对路径
     */
    void deleteFile(String sessionId, String filePath);

    /**
     * 清空工作空间下的所有文件（保留工作空间根目录）
     *
     * @param sessionId 会话ID
     */
    void clearWorkspace(String sessionId);

    /**
     * 获取工作空间容量信息（已用空间/最大容量）
     *
     * @param sessionId 会话ID
     * @return 容量信息
     */
    WorkspaceCapacityVO getCapacity(String sessionId);

    /**
     * 检查工作空间是否存在（仅检查目录是否存在，不会创建）
     *
     * @param sessionId 会话ID
     * @return 是否存在
     */
    boolean workspaceExists(String sessionId);
}

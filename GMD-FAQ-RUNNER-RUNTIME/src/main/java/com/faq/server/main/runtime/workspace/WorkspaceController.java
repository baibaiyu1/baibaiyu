package com.gmd.faq.server.main.runtime.workspace;

import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.config.auth.ChatKeyAccess;
import com.gmd.faq.server.main.common.config.auth.SkAccess;
import com.gmd.faq.server.main.common.dto.WorkspaceFileWriteDTO;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.vo.WorkspaceCapacityVO;
import com.gmd.faq.server.main.common.vo.WorkspaceFileNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 描述：智能体工作空间文件管理 Controller
 *
 **/
@RestController
@RequestMapping("/runtime/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final SessionAccessGuard sessionAccessGuard;
    private final ObjectProvider<WorkspaceSyncService> workspaceSyncService;

    /**
     * 上传单个文件到工作空间
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/upload")
    public R<String> upload(@RequestParam("sessionId") String sessionId,
                            @RequestParam("file") MultipartFile file) {
        checkWrite(sessionId);
        String path = workspaceService.uploadFile(sessionId, file);
        enqueueChange(sessionId);
        return R.data(path, "上传成功");
    }

    /**
     * 上传多个文件到工作空间
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/upload/batch")
    public R<List<String>> uploadBatch(@RequestParam("sessionId") String sessionId,
                                       @RequestParam("files") MultipartFile[] files) {
        checkWrite(sessionId);
        List<String> paths = workspaceService.uploadFiles(sessionId, files);
        enqueueChange(sessionId);
        return R.data(paths, "批量上传成功");
    }

    /**
     * 上传压缩包并自动解压到工作空间
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/upload/archive")
    public R<List<String>> uploadArchive(@RequestParam("sessionId") String sessionId,
                                          @RequestParam("file") MultipartFile file) {
        checkWrite(sessionId);
        List<String> paths = workspaceService.uploadAndExtractArchive(sessionId, file);
        enqueueChange(sessionId);
        return R.data(paths, "压缩包解压成功");
    }

    /**
     * 获取工作空间文件树
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/files")
    public R<List<WorkspaceFileNode>> listFiles(@RequestParam("sessionId") String sessionId) {
        checkRead(sessionId);
        List<WorkspaceFileNode> nodes = workspaceService.listFiles(sessionId);
        return R.data(nodes);
    }

    /**
     * 以文本形式写入（覆盖）工作空间中的文件
     *
     * <p>供右栏「文章编写模式」保存使用。传 expectedContent 时做并发冲突检测：
     * 编辑期间智能体若改过同一文件，这里会拒绝覆盖而不是静默冲掉对方的内容。
     */
    @SkAccess
    @ChatKeyAccess
    @PutMapping("/file")
    public R<Void> writeFile(@RequestBody WorkspaceFileWriteDTO dto) {
        checkWrite(dto == null ? null : dto.getSessionId());
        workspaceService.writeFileContent(dto);
        enqueueChange(dto.getSessionId());
        return R.success("保存成功");
    }

    /**
     * 下载工作空间中的单个文件
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/download")
    public void download(@RequestParam("sessionId") String sessionId,
                         @RequestParam("fileName") String fileName,
                         HttpServletResponse response) {
        checkRead(sessionId);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        setDownloadHeader(response, fileName);

        try (OutputStream outputStream = response.getOutputStream()) {
            workspaceService.downloadFile(sessionId, fileName, outputStream);
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败", e);
        }
    }

    /**
     * 下载工作空间中的多个文件（打包成ZIP）
     */
    @SkAccess
    @ChatKeyAccess
    @PostMapping("/download/batch")
    public void downloadBatch(@RequestParam("sessionId") String sessionId,
                              @RequestBody List<String> filePaths,
                              HttpServletResponse response) {
        checkRead(sessionId);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        setDownloadHeader(response, "batch-download.zip");

        try (OutputStream outputStream = response.getOutputStream()) {
            workspaceService.downloadFiles(sessionId, filePaths, outputStream);
        } catch (Exception e) {
            throw new RuntimeException("批量下载失败", e);
        }
    }

    /**
     * 下载整个工作空间（打包成ZIP）
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/download/all")
    public void downloadAll(@RequestParam("sessionId") String sessionId,
                            HttpServletResponse response) {
        checkRead(sessionId);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        setDownloadHeader(response, "workspace-" + sessionId + ".zip");

        try (OutputStream outputStream = response.getOutputStream()) {
            workspaceService.downloadWorkspace(sessionId, outputStream);
        } catch (Exception e) {
            throw new RuntimeException("工作空间下载失败", e);
        }
    }

    /**
     * 删除工作空间中的单个文件
     */
    @SkAccess
    @ChatKeyAccess
    @DeleteMapping("/file")
    public R<Void> deleteFile(@RequestParam("sessionId") String sessionId,
                              @RequestParam("filePath") String filePath) {
        checkWrite(sessionId);
        workspaceService.deleteFile(sessionId, filePath);
        enqueueChange(sessionId);
        return R.success("删除成功");
    }

    /**
     * 清空工作空间下的所有文件
     */
    @SkAccess
    @ChatKeyAccess
    @DeleteMapping("/clear")
    public R<Void> clearWorkspace(@RequestParam("sessionId") String sessionId) {
        checkWrite(sessionId);
        workspaceService.clearWorkspace(sessionId);
        WorkspaceSyncService sync = workspaceSyncService.getIfAvailable();
        if (sync != null) {
            sync.enqueueClear(TenantUtils.getCurrentTenantCode(), sessionId);
        }
        return R.success("清空成功");
    }

    /**
     * 获取工作空间容量信息
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/capacity")
    public R<WorkspaceCapacityVO> getCapacity(@RequestParam("sessionId") String sessionId) {
        checkRead(sessionId);
        WorkspaceCapacityVO capacity = workspaceService.getCapacity(sessionId);
        return R.data(capacity);
    }

    /**
     * 检查工作空间是否存在（仅检查目录存在性，不会自动创建）
     */
    @SkAccess
    @ChatKeyAccess
    @GetMapping("/exists")
    public R<Boolean> exists(@RequestParam("sessionId") String sessionId) {
        checkRead(sessionId);
        boolean exists = workspaceService.workspaceExists(sessionId);
        return R.data(exists);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 设置文件下载响应头
     */
    private void setDownloadHeader(HttpServletResponse response, String fileName) {
        try {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        } catch (Exception e) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment;filename=download");
        }
    }

    private void checkRead(String sessionId) {
        sessionAccessGuard.requireRead(parseSessionId(sessionId));
    }

    private void checkWrite(String sessionId) {
        sessionAccessGuard.requireActiveWrite(parseSessionId(sessionId));
    }

    private Long parseSessionId(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9]+")) {
            throw new IllegalArgumentException("无效的会话ID");
        }
        try {
            Long id = Long.valueOf(sessionId);
            if (id <= 0L || !id.toString().equals(sessionId)) {
                throw new IllegalArgumentException("无效的会话ID");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的会话ID", e);
        }
    }

    private void enqueueChange(String sessionId) {
        WorkspaceSyncService sync = workspaceSyncService.getIfAvailable();
        if (sync != null) {
            sync.enqueue(TenantUtils.getCurrentTenantCode(), sessionId);
        }
    }
}

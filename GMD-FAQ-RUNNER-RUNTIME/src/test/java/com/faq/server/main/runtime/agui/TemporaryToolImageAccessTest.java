package com.gmd.faq.server.main.runtime.agui;

import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.agent.service.SessionAccessGuard;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.util.TenantUtils;
import com.gmd.faq.server.main.common.util.UserUtils;
import com.gmd.faq.server.main.engine.tool.ToolResultImageSupport;
import com.gmd.faq.server.main.engine.tool.ToolResultPresentation;
import com.gmd.faq.server.main.resource.visibility.service.ResourceVisibilityService;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.spring.boot.agui.mvc.AguiMvcController;
import io.agentscope.spring.boot.agui.mvc.AguiRestController;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class TemporaryToolImageAccessTest {
    @Test void onlyRegisteredOwnerCanReadGeneratedImagesWithinTheTemporaryThread() throws Exception {
        Path base = Files.createTempDirectory("temporary-tool-image-");
        try (var users = Mockito.mockStatic(UserUtils.class);
             var tenants = Mockito.mockStatic(TenantUtils.class);
             var paths = Mockito.mockStatic(SysConst.class)) {
            UserDetail user = Mockito.mock(UserDetail.class);
            Mockito.when(user.getAuthType()).thenReturn("ACCOUNT");
            users.when(UserUtils::getUserDetail).thenReturn(user);
            users.when(UserUtils::getId).thenReturn(1L);
            users.when(UserUtils::getTenantId).thenReturn(1L);
            tenants.when(TenantUtils::getCurrentTenantCode).thenReturn("test");
            paths.when(() -> SysConst.getWorkspacePath("test")).thenReturn(base.toString());
            var controller = new AguiRestController(Mockito.mock(AguiMvcController.class),
                    Mockito.mock(AgentDefinitionService.class), Mockito.mock(ResourceVisibilityService.class),
                    Mockito.mock(SessionAccessGuard.class), "/runtime/agui", true);
            ReflectionTestUtils.invokeMethod(controller, "registerTemporaryThread", "thread_test", 1L);
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            ToolResultBlock result = new ToolResultImageSupport().normalize(ToolResultBlock.of("call", "screenshot",
                    new ImageBlock(new Base64Source("image/png", Base64.getEncoder().encodeToString(bytes.toByteArray())))),
                    base.resolve("thread_test"));
            String file = ToolResultPresentation.from(result).images().getFirst().filePath();
            var response = controller.temporaryToolImage("thread_test", file);
            assertArrayEquals(bytes.toByteArray(), response.getBody());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertThrows(IllegalArgumentException.class, () -> controller.temporaryToolImage("123", file));
            assertThrows(IllegalArgumentException.class, () -> controller.temporaryToolImage("thread_test", "../outside.png"));
            assertThrows(IllegalStateException.class, () -> controller.temporaryToolImage("thread_unknown", file));
            users.when(UserUtils::getId).thenReturn(2L);
            assertThrows(IllegalStateException.class, () -> controller.temporaryToolImage("thread_test", file));
            users.when(UserUtils::getId).thenReturn(1L);
            users.when(UserUtils::getTenantId).thenReturn(2L);
            assertThrows(IllegalStateException.class, () -> controller.temporaryToolImage("thread_test", file));
        } finally {
            try (var files = Files.walk(base)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}

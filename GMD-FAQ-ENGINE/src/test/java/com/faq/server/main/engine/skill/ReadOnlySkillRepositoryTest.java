package com.gmd.faq.server.main.engine.skill;

import com.gmd.faq.server.main.common.entity.*;
import com.gmd.faq.server.main.common.enums.SkillFileType;
import com.gmd.faq.server.main.common.enums.ToolType;
import com.gmd.faq.server.main.engine.agent.AgentExecutionRole;
import com.gmd.faq.server.main.engine.agui.*;
import com.gmd.faq.server.main.skill.service.*;
import com.gmd.faq.server.main.tool.service.ToolService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.skill.*;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlySkillRepositoryTest {
    private final SkillPackageService packages = mock(SkillPackageService.class);
    private final SkillFileService files = mock(SkillFileService.class);
    private final AgentSkillPackageService bindings = mock(AgentSkillPackageService.class);
    private final SkillToolService toolBindings = mock(SkillToolService.class);
    private final ToolService tools = mock(ToolService.class);
    private final SkillBoxFactory factory = new SkillBoxFactory(packages, files, bindings, toolBindings, tools);
    private final AgentDefinition definition = new AgentDefinition();
    private final AgentContext owner = new AgentContext();

    @BeforeEach void setup() {
        definition.setId(10L); definition.setTenantId(1L);
        owner.setTenantId(1L); owner.setThreadId("20"); owner.setAgentDefinition(definition);
        AgentContext.set(owner);
        SkillPackage skill = new SkillPackage();
        skill.setId(30L); skill.setName("migration-skill"); skill.setDescription("迁移技能"); skill.setEnabled(true);
        SkillFile main = new SkillFile(); main.setFileType(SkillFileType.SKILL_MD); main.setContent("技能说明");
        SkillFile resource = new SkillFile(); resource.setFileType(SkillFileType.REFERENCES);
        resource.setFilePath("references/spec.txt"); resource.setContent("只读资源正文");
        when(bindings.getSkillPackageIds(10L)).thenReturn(List.of(30L));
        when(packages.listByIds(List.of(30L))).thenReturn(List.of(skill));
        when(files.listBySkillId(30L)).thenReturn(List.of(main, resource));
        when(toolBindings.getToolIds(30L)).thenReturn(List.of());
    }
    @AfterEach void cleanup() { AgentContext.clean(); }
    private RuntimeContext context() {
        return RuntimeContext.builder().userId("user").sessionId("20").put(AgentContext.class, owner).build();
    }

    @Test void snapshotMatchesLegacySkillsAndCannotBecomeWriteable() {
        var legacy = factory.getSkillBox(definition, new Toolkit(), null, AgentExecutionRole.ROOT);
        var repository = factory.getRepository(definition, new Toolkit(), null, AgentExecutionRole.ROOT);
        assertEquals(legacy.getAllSkillIds(), repository.getAllSkills().stream().map(AgentSkill::getSkillId).collect(java.util.stream.Collectors.toSet()));
        for (var skill : repository.getAllSkills()) {
            var old = legacy.getSkill(skill.getSkillId());
            assertEquals(old.getSkillContent(), skill.getSkillContent());
            assertEquals(old.getResources(), skill.getResources());
        }
        assertFalse(repository.isWriteable()); assertFalse(repository.getRepositoryInfo().isWritable());
        assertTrue(repository.getSkill("migration-skill").getSkillContent().contains("skillId=\"migration-skill_baibaiyu\""));
        assertThrows(UnsupportedOperationException.class, () -> repository.save(List.of(), true));
        assertThrows(UnsupportedOperationException.class, () -> repository.delete("migration-skill"));
        assertThrows(UnsupportedOperationException.class, () -> repository.setWriteable(true));
        var rc = context();
        var middleware = new ReadOnlySkillMiddleware(repository, new Toolkit(), false);
        assertEquals("base\n" + legacy.getSkillPrompt(), middleware.onSystemPrompt(null, rc, "base").block());
        assertFalse(rc.<SkillBox>get(SkillBox.class).isAutoUploadSkill());
        assertNull(rc.<SkillBox>get(SkillBox.class).getUploadDir());
    }

    @Test void actualAgentLoadsTheSameResourceThroughNativeRuntimeTool() {
        Toolkit toolkit = new Toolkit();
        var repository = factory.getRepository(definition, toolkit, null, AgentExecutionRole.ROOT);
        AgentSkill skill = repository.getSkill("migration-skill");
        AtomicInteger calls = new AtomicInteger();
        Model model = new Model() {
            public String getModelName() { return "skill-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
                assertEquals(1, schemas.stream().filter(t -> t.getName().equals("load_skill_through_path")).count());
                if (calls.getAndIncrement() == 0) return Flux.just(ChatResponse.builder().content(List.of(
                        ToolUseBlock.builder().id("load-resource").name("load_skill_through_path")
                                .input(Map.of("skillId", skill.getSkillId(), "path", "references/spec.txt"))
                                .content("{\"skillId\":\"migration-skill_baibaiyu\",\"path\":\"references/spec.txt\"}").build())).build());
                assertTrue(messages.stream().flatMap(m -> m.getContent().stream())
                        .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                        .flatMap(r -> r.getOutput().stream()).filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                        .anyMatch(t -> t.getText().contains("只读资源正文")), () -> messages.stream()
                                .flatMap(m -> m.getContent().stream()).filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                                .flatMap(r -> r.getOutput().stream()).filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                                .map(TextBlock::getText).collect(java.util.stream.Collectors.joining("\n")));
                return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("完成").build())).build());
            }
        };
        try (var agent = ReActAgent.builder().name("skill").model(model).toolkit(toolkit).maxIters(3)
                .skillRepository(repository).dynamicSkillsEnabled(false)
                .middleware(new ReadOnlySkillMiddleware(repository, toolkit, false)).build()) {
            var events = agent.streamEvents(List.of(Msg.builder().role(MsgRole.USER).textContent("加载资源").build()), context())
                    .contextWrite(c -> AgentContextPropagation.withContext(c, owner)).collectList().block(Duration.ofSeconds(10));
            assertEquals(2, calls.get());
            assertTrue(events.stream().anyMatch(e -> e instanceof ToolResultEndEvent ended && ended.getState() == ToolResultState.SUCCESS));
        }
    }

    @Test void mismatchFailsClosedAndClearsPreviousRuntimeView() {
        var repository = factory.getRepository(definition, new Toolkit(), null, AgentExecutionRole.ROOT);
        var middleware = new ReadOnlySkillMiddleware(repository, new Toolkit(), false);
        var rc = context();
        middleware.onSystemPrompt(null, rc, "").block();
        assertNotNull(rc.get(SkillBox.class));
        owner.setTenantId(2L);
        assertThrows(IllegalStateException.class, () -> middleware.onSystemPrompt(null, rc, "").block());
        assertNull(rc.get(SkillBox.class));
        owner.setTenantId(1L); AgentDefinition other = new AgentDefinition(); other.setId(99L); owner.setAgentDefinition(other);
        assertThrows(IllegalStateException.class, () -> repository.getAllSkills(rc));
    }

    @Test void delegatedSkillCannotReintroduceConfirmationToolsOrBrowserProtocols() {
        ToolConfig gated = new ToolConfig(); gated.setId(40L); gated.setToolId("ask-write");
        gated.setEnabled(true); gated.setNeedConfirm(true); gated.setToolType(ToolType.BUILTIN);
        when(toolBindings.getToolIds(30L)).thenReturn(List.of(40L));
        when(tools.listByIds(List.of(40L))).thenReturn(List.of(gated));
        Toolkit toolkit = new Toolkit();
        var repository = factory.getRepository(definition, toolkit, null, AgentExecutionRole.SUBAGENT);
        assertNull(toolkit.getTool("ask-write"));
        assertEquals(List.of("migration-skill"), repository.getAllSkillNames());
    }
}

package com.gmd.faq.server.main.account.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.gmd.faq.server.main.account.mapper.AccountMapper;
import com.gmd.faq.server.main.account.mapper.TenantMapper;
import com.gmd.faq.server.main.account.service.AccountService;
import com.gmd.faq.server.main.agent.service.AgentDefinitionService;
import com.gmd.faq.server.main.common.UserDetail;
import com.gmd.faq.server.main.common.consts.SysConst;
import com.gmd.faq.server.main.common.consts.TableConst;
import com.gmd.faq.server.main.common.dto.*;
import com.gmd.faq.server.main.common.entity.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gmd.faq.server.main.common.exception.BusinessException;
import com.gmd.faq.server.main.common.exception.NotAuthException;
import com.gmd.faq.server.main.common.util.*;
import com.gmd.faq.server.main.common.vo.AccountCredentialVO;
import com.gmd.faq.server.main.params.core.ParamsAdapter;
import com.gmd.faq.server.main.system.mapper.DepartmentMapper;
import com.gmd.faq.server.main.system.mapper.RoleAccountMapper;
import com.gmd.faq.server.main.system.service.DepartmentService;
import com.gmd.faq.server.main.system.service.MemberScopeGuard;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 账号Service实现
 * <p>
 * 系统已关闭自助注册：账号只能由组织管理员新增/删除，新增或重置后的密码由服务端随机生成，
 * 并强制用户首次登录后修改密码。
 *
 */
@Service
@RequiredArgsConstructor
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    /** 用户名长度下限，与登录页表单（`pages/Login.vue`）一致 */
    private static final int USERNAME_MIN_LENGTH = 4;
    /** 用户名长度上限，与登录页表单一致 */
    private static final int USERNAME_MAX_LENGTH = 20;
    /** 昵称长度上限，与 `gmd_faq_account.nickname varchar(10)` 一致 */
    private static final int NICKNAME_MAX_LENGTH = 10;

    private final RedisUtils redisUtils;
    private final ParamsAdapter paramsAdapter;
    private final TenantMapper tenantMapper;
    private final DepartmentMapper departmentMapper;
    private final MemberScopeGuard memberScopeGuard;
    private final DepartmentService departmentService;
    private final AgentDefinitionService agentDefinitionService;
    private final RoleAccountMapper roleAccountMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public LoginResponse login(LoginRequest request) {
        if (FuncUtils.isEmpty(request.getUsername()) || FuncUtils.isEmpty(request.getPassword())) {
            throw new RuntimeException("用户名和密码不能为空");
        }

        // 查询用户（支持用户名或邮箱登录）
        Account account = this.lambdaQuery()
                .and(wrapper -> wrapper
                        .eq(Account::getUsername, request.getUsername())
                        .or()
                        .eq(Account::getEmail, request.getUsername())
                )
                .one();

        if (account == null) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 检查用户是否被禁用
        if (Boolean.FALSE.equals(account.getEnabled())) {
            throw new RuntimeException("账号已被禁用，请联系管理员");
        }

        // 验证密码
        String salt = account.getId().toString();
        if (!passwordMatches(request.getPassword(), salt, account.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 「部门即组织」后不再需要选择组织：一次登录直接进入，组织归属完全由 account.dept_id 决定。
        // 未归属部门（dept_id 为空）的账号同样可以登录，只是数据范围退化为「仅本人」。
        Tenant tenant = resolvePlatformTenant();

        account.setLastTenantId(tenant.getId());
        this.updateById(account);
        TenantUtils.setCurrentTenant(tenant.getId(), tenant.getCode());
        return generateTokenResponse(account, tenant);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountCredentialVO createAccount(AccountCreateRequest request) {
        // 参数校验：username / nickname 的长度口径必须与登录页表单及建表语句一致，
        // 否则会建出「登录框输不进去」或「写库直接报 Data too long」的账号
        // （见 db_init.sql：username varchar(40)、nickname varchar(10)；登录页限制用户名 4-20）。
        if (FuncUtils.isEmpty(request.getUsername())) {
            throw new RuntimeException("用户名不能为空");
        }
        String username = request.getUsername().trim();
        if (username.length() < USERNAME_MIN_LENGTH || username.length() > USERNAME_MAX_LENGTH) {
            throw new RuntimeException("用户名长度为" + USERNAME_MIN_LENGTH + "-" + USERNAME_MAX_LENGTH + "个字符");
        }

        String nickname = FuncUtils.isEmpty(request.getNickname()) ? "" : request.getNickname().trim();
        if (FuncUtils.isEmpty(nickname)) {
            throw new RuntimeException("昵称不能为空");
        }
        if (nickname.length() > NICKNAME_MAX_LENGTH) {
            throw new RuntimeException("昵称长度不能超过" + NICKNAME_MAX_LENGTH + "个字符");
        }

        String email = FuncUtils.isEmpty(request.getEmail()) ? null : request.getEmail().trim();

        // 组织归属：由请求里的部门决定（「部门即组织」）。允许留空——无组织的账号仍可登录，
        // 只是数据范围退化为「仅本人」，后续可由管理员在「用户列表」里分配组织。
        Long deptId = request.getDeptId();
        if (deptId != null && deptId == 0L) {
            deptId = null;
        }
        if (deptId != null && departmentMapper.selectById(deptId) == null) {
            throw new RuntimeException("部门（组织）不存在：" + deptId);
        }
        // 越权防护：只有可见范围覆盖目标组织的管理员才能把账号建到该组织
        // （仅有 system:user:add 的部门管理员不能把账号建到别人的组织，也不能建「无组织」账号）
        if (!memberScopeGuard.canManageDepartment(deptId)) {
            throw new RuntimeException(deptId == null
                    ? "无权创建不归属组织的账号"
                    : "无权在该组织下创建账号");
        }

        // 检查用户名是否已存在
        if (this.lambdaQuery().eq(Account::getUsername, username).exists()) {
            throw new RuntimeException("用户名已存在");
        }

        // 检查邮箱是否已存在（邮箱可选，填写时才校验）
        if (email != null && this.lambdaQuery().eq(Account::getEmail, email).exists()) {
            throw new RuntimeException("邮箱已被使用");
        }

        // 密码：管理员可指定，留空则随机生成（明文只在本次响应里返回一次）
        boolean generated = FuncUtils.isEmpty(request.getPassword());
        String rawPassword = generated ? CryptoUtils.randomPassword() : null;

        // 创建账号
        Account account = new Account();
        account.setNickname(nickname);
        account.setEmail(email);
        account.setUsername(username);
        account.setEnabled(true);
        account.setDeptId(deptId);
        // 先保存以获取ID（密码以用户ID作为盐值）
        this.save(account);

        account.setPassword(toStoredPassword(request.getPassword(), rawPassword, account.getId()));
        // 首次登录必须修改密码
        account.setMustChangePassword(true);
        // 记录登录所属租户，便于登录时直接进入（多组织已下线，这里取平台组织）
        Tenant tenant = resolvePlatformTenant();
        account.setLastTenantId(tenant.getId());
        this.updateById(account);

        return AccountCredentialVO.builder()
                .id(account.getId())
                .username(account.getUsername())
                .nickname(account.getNickname())
                .password(rawPassword)
                .generated(generated)
                .mustChangePassword(true)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAccounts(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("请选择要删除的用户");
        }

        // 去重，避免同一ID重复处理
        Set<Long> targetIds = new HashSet<>(ids);
        Long currentAccountId = UserUtils.getId();

        // 校验：不能删除自己 / 内置管理员
        // 「部门即组织」后不再有「组织拥有者」概念，组织负责人通过 gmd_faq_department_leader
        // （多选关联表）表达，删除前会把该账号从所有组织的负责人名单里摘掉。
        List<Account> accounts = this.listByIds(targetIds);
        if (accounts.size() != targetIds.size()) {
            throw new RuntimeException("部分用户不存在");
        }
        for (Account account : accounts) {
            if (Objects.equals(account.getId(), currentAccountId)) {
                throw new RuntimeException("不能删除当前登录账号");
            }
            if (Objects.equals(account.getId(), SysConst.ADMIN_ACCOUNT_ID)) {
                throw new RuntimeException("内置管理员账号不可删除");
            }
        }

        // 清理角色绑定与「组织负责人」身份，再删除账号本身
        roleAccountMapper.delete(Wrappers.<RoleAccount>lambdaQuery()
                .in(RoleAccount::getAccountId, targetIds));
        for (Long targetId : targetIds) {
            departmentService.removeLeaderAccount(targetId);
        }

        return this.removeByIds(targetIds);
    }

    @Override
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        if (FuncUtils.isEmpty(request.getRefreshToken())) {
            throw new RuntimeException("refreshToken不能为空");
        }

        try {
            // 解析refreshToken
            Claims claims = TokenUtils.parseToken(request.getRefreshToken());
            String userId = claims.getId();

            // 查询用户
            Account account = this.getById(userId);
            if (account == null) {
                throw new RuntimeException("用户不存在");
            }

            // 检查用户是否被禁用
            if (Boolean.FALSE.equals(account.getEnabled())) {
                throw new RuntimeException("账号已被禁用，请联系管理员");
            }

            // 保留原 token 中的租户上下文（多组织已下线，缺失时回落到平台组织）
            Long tenantId = null;
            String tenantCode = null;
            try {
                UserDetail oldDetail = JsonUtils.parse(claims.getSubject(), UserDetail.class);
                if (oldDetail != null && oldDetail.getTenantId() != null) {
                    tenantId = oldDetail.getTenantId();
                    tenantCode = oldDetail.getTenantCode();
                }
            } catch (Exception ignored) {}

            Tenant tenant = tenantId == null ? resolvePlatformTenant() : tenantMapper.selectById(tenantId);
            if (tenant == null) {
                tenant = resolvePlatformTenant();
            }

            // 生成新的token（保留租户上下文）
            return generateTokenResponse(account, tenant);
        } catch (Exception e) {
            throw new NotAuthException("refreshToken无效或已过期");
        }
    }

    @Override
    public void logout() {
        try {
            redisUtils.delete(RedisKeyBuilder.globalKey("login:" + TokenUtils.getToken()));
        } catch (Exception e) {
            throw new RuntimeException("退出登录失败");
        }
    }

    @Override
    public LoginResponse selectTenant(SelectTenantRequest request) {
        // 「部门即组织」后多组织已下线：组织归属由 account.dept_id 决定，不再有「切换/选择组织」的动作。
        // 这里保留接口是为了兼容旧前端与旧 token 刷新，行为等价于「重新签发当前登录态的 token」。
        Long accountId = UserUtils.getId();
        Account account = this.getById(accountId);
        if (account == null) {
            throw new RuntimeException("账号不存在");
        }

        Tenant tenant = resolvePlatformTenant();
        account.setLastTenantId(tenant.getId());
        this.updateById(account);

        LoginResponse response = generateTokenResponse(account, tenant);

        // 新 token 生成成功后再删除旧 token
        try {
            redisUtils.delete(RedisKeyBuilder.globalKey("login:" + TokenUtils.getToken()));
        } catch (Exception ignored) {}

        return response;
    }

    @Override
    public boolean changePassword(ChangePasswordRequest request) {
        if (FuncUtils.isEmpty(request.getNewPassword())) {
            throw new RuntimeException("新密码不能为空");
        }

        // 获取当前用户ID
        Long userId = UserUtils.getId();
        Account account = this.getById(userId);
        if (account == null) {
            throw new RuntimeException("用户不存在");
        }

        String salt = account.getId().toString();

        if (FuncUtils.isEmpty(request.getOldPassword())) {
            // 首次登录强制改密场景：用户刚刚用管理员分配的密码登录过，
            // 再要求他重复输入一遍没有意义，因此这里允许省略旧密码。
            // 但仅限「待改密」状态；正常账号仍然必须校验旧密码，
            // 避免会话被盗后可以静默改掉密码。
            if (!Boolean.TRUE.equals(account.getMustChangePassword())) {
                throw new RuntimeException("旧密码不能为空");
            }
        } else if (!passwordMatches(request.getOldPassword(), salt, account.getPassword())) {
            // 验证旧密码
            throw new RuntimeException("旧密码错误");
        }

        // 加密新密码
        String encryptedNewPassword = CryptoUtils.md5(request.getNewPassword(), salt);
        account.setPassword(encryptedNewPassword);
        // 用户已完成改密，「首次登录必须修改密码」的标记随之解除
        account.setMustChangePassword(false);

        return this.updateById(account);
    }

    @Override
    public boolean updateProfile(UpdateProfileRequest request) {
        // 获取当前用户ID
        Long userId = UserUtils.getId();
        Account account = this.getById(userId);
        if (account == null) {
            throw new RuntimeException("用户不存在");
        }

        // 更新昵称
        if (!FuncUtils.isEmpty(request.getNickname())) {
            account.setNickname(request.getNickname());
        }

        // 更新邮箱
        if (!FuncUtils.isEmpty(request.getEmail())) {
            // 检查邮箱是否已被其他用户使用
            if (this.lambdaQuery()
                    .eq(Account::getEmail, request.getEmail())
                    .ne(Account::getId, userId)
                    .exists()) {
                throw new RuntimeException("邮箱已被其他用户使用");
            }
            account.setEmail(request.getEmail());
        }

        account.setRememberLastTenant(request.getRememberLastTenant());

        return this.updateById(account);
    }

    @Override
    public boolean toggleEnabled(Long id, Boolean enabled) {
        Account account = this.getById(id);

        if (account == null) {
            throw new RuntimeException("用户不存在");
        }

        if (Objects.equals(account.getId(), SysConst.ADMIN_ACCOUNT_ID)) {
            throw new RuntimeException("管理员账号不可操作");
        }

        account.setEnabled(enabled);
        return this.updateById(account);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountCredentialVO resetPassword(Long id, String newPassword) {
        Account account = this.getById(id);
        if (account == null) {
            throw new RuntimeException("用户不存在");
        }

        // 「部门即组织」后「本组织成员」由 account.dept_id 表达，不再查 account_tenant；
        // 有 system:user:resetPwd 权限即可重置，具体可见范围由用户列表的数据范围控制。
        boolean generated = FuncUtils.isEmpty(newPassword);
        String rawPassword = generated ? CryptoUtils.randomPassword() : null;

        // 加密新密码（使用用户ID作为盐值）
        account.setPassword(toStoredPassword(newPassword, rawPassword, account.getId()));
        // 重置后同样要求用户下次登录时修改密码
        account.setMustChangePassword(true);
        this.updateById(account);

        return AccountCredentialVO.builder()
                .id(account.getId())
                .username(account.getUsername())
                .nickname(account.getNickname())
                .password(rawPassword)
                .generated(generated)
                .mustChangePassword(true)
                .build();
    }

    /**
     * 计算入库的密码摘要
     * <p>
     * 全站约定：客户端传输的 password 字段始终是「明文密码的 MD5」（见 Login.vue / MyAccount.vue 的
     * {@code md5(password)}），服务端在此基础上加盐存储，即 {@code md5(salt || clientMd5)} ——
     * 内置 admin 账号的种子数据正是这一口径（md5("1111111111111111111" + md5("Admin@123.com"))）。
     * <p>
     * 登录时 {@link #passwordMatches} 用「客户端传来的摘要」按同一口径比对，因此：
     * <ul>
     *   <li>管理员指定密码：{@code clientMd5} 由前端算好，直接加盐；</li>
     *   <li>服务端随机生成密码：没有客户端参与，需要先补一次 MD5 才能与登录口径对齐，</li>
     * </ul>
     * 否则随机密码只在「服务端返回的明文」与「入库摘要」之间差了一层 MD5，用户永远登录不上。
     *
     * @param clientMd5   客户端传入的密码摘要（可为空，为空表示由服务端随机生成）
     * @param rawPassword 服务端随机生成的明文密码（仅当 clientMd5 为空时有值）
     * @param accountId   账号ID，作为盐值
     * @return 入库的密码摘要
     */
    private String toStoredPassword(String clientMd5, String rawPassword, Long accountId) {
        String effectiveClientMd5 = FuncUtils.isEmpty(clientMd5)
                ? CryptoUtils.md5(rawPassword)
                : clientMd5;
        return CryptoUtils.md5(effectiveClientMd5, accountId.toString());
    }

    @Override
    public LoginResponse chatKeyToken(String chatKey) {
        if (FuncUtils.isEmpty(chatKey)) {
            return null;
        }

        // 使用JdbcTemplate绕过MyBatis-Plus租户自动过滤
        List<AgentChatKey> chatKeys = jdbcTemplate.query(
                "SELECT agent_code, chat_key, tenant_id FROM " + TableConst.AGENT_CHAT_KEY + " WHERE chat_key = ?",
                (rs, rowNum) -> {
                    AgentChatKey ack = new AgentChatKey();
                    ack.setAgentCode(rs.getString("agent_code"));
                    ack.setChatKey(rs.getString("chat_key"));
                    ack.setTenantId(rs.getLong("tenant_id"));
                    return ack;
                },
                chatKey
        );

        AgentChatKey agentChatKey = chatKeys.isEmpty() ? null : chatKeys.getFirst();
        if (agentChatKey == null) {
            return null;
        }

        // 设置租户
        boolean hasNotCurrentTenant = TenantUtils.getCurrentTenantId() == null;
        if (hasNotCurrentTenant) {
            TenantUtils.setCurrentTenant(agentChatKey.getTenantId(), null);
        }

        try {
            String agentCode = agentChatKey.getAgentCode();
            AgentDefinition agent = agentDefinitionService.lambdaQuery()
                    .eq(AgentDefinition::getAgentCode, agentCode)
                    .one();
            if (agent == null || !Objects.equals(agent.getTenantId(), agentChatKey.getTenantId())
                    || !Boolean.TRUE.equals(agent.getEnabled())
                    || !Boolean.TRUE.equals(agent.getPublished())
                    || !Boolean.TRUE.equals(agent.getExternalShareEnabled())) {
                return null;
            }

            Tenant tenant = tenantMapper.selectById(agent.getTenantId());
            if (tenant == null) {
                return null;
            }

            UserDetail userDetail = UserDetail.builder()
                    .id(IdWorker.getId())
                    .name(agent.getName())
                    .username(agent.getAgentCode())
                    .tenantId(tenant.getId())
                    .tenantCode(tenant.getCode())
                    .authType(UserUtils.AUTH_CHAT_KEY)
                    .boundAgentId(agent.getId())
                    .boundAgentCode(agent.getAgentCode())
                    .chatKeyId(chatKey)
                    .build();
            long visitorTtl = TimeUnit.HOURS.toMillis(24);
            String token = TokenUtils.createToken(chatKey, userDetail, visitorTtl);

            redisUtils.setEx(RedisKeyBuilder.globalKey("chatkey:" + chatKey),
                    agent.getAgentCode(), 7, TimeUnit.DAYS);
            redisUtils.setEx(RedisKeyBuilder.globalKey("login:" + token),
                    JsonUtils.toJsonStr(userDetail), visitorTtl, TimeUnit.MILLISECONDS);

            return LoginResponse.builder()
                    .accessToken(token)
                    .accessTokenTTL(System.currentTimeMillis() + visitorTtl)
                    .refreshToken(token)
                    .refreshTokenTTL(System.currentTimeMillis() + visitorTtl)
                    .userDetail(userDetail)
                    .build();
        } finally {
            if (hasNotCurrentTenant) {
                TenantUtils.clear();
            }
        }
    }

    /**
     * 解析平台组织
     * <p>
     * 「部门即组织」后 {@code gmd_faq_tenant} 退化为内部实现细节：业务表仍带 {@code tenant_id}，
     * 租户拦截器需要它注入隔离条件。本部署即一个平台组织，取启用状态下的第一条即可。
     *
     * @return 平台组织，缺失时抛业务异常
     */
    private Tenant resolvePlatformTenant() {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getEnabled, true)
                .orderByAsc(Tenant::getId)
                .last("LIMIT 1"));
        if (tenant == null) {
            tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                    .orderByAsc(Tenant::getId)
                    .last("LIMIT 1"));
        }
        if (tenant == null) {
            throw new RuntimeException("平台组织未初始化，请联系管理员");
        }
        return tenant;
    }

    /**
     * 生成Token响应
     *
     * @param account 账号信息
     * @param tenant  平台组织（{@code gmd_faq_tenant}，内部实现细节）
     * @return 登录响应
     */
    private LoginResponse generateTokenResponse(Account account, Tenant tenant) {
        String userId = account.getId().toString();
        Long tenantId = tenant.getId();
        String tenantCode = tenant.getCode();

        // 「部门即组织」：单条组织信息，仅用于兼容前端旧字段，不再提供「选择/切换组织」列表
        List<UserDetail.TenantInfo> tenantInfoList = new ArrayList<>();
        tenantInfoList.add(new UserDetail.TenantInfo(
                tenant.getId(), tenant.getCode(), tenant.getName(), null));

        // 构建UserDetail
        UserDetail.UserDetailBuilder builder = UserDetail.builder()
                .id(account.getId())
                .username(account.getUsername())
                .name(account.getNickname())
                .email(account.getEmail())
                .tenantId(tenantId)
                .tenantCode(tenantCode)
                .tenantName(tenant.getName())
                .deptId(account.getDeptId())
                .tenants(tenantInfoList);

        UserDetail userDetail = builder.build();

        // 倘若当前没有租户上下文，设置完之后需要在 finally 中清除
        boolean hasNotCurrentTenant = TenantUtils.getCurrentTenantId() == null;
        try {
            // 设置当前租户
            TenantUtils.setCurrentTenant(tenantId, tenantCode);

            // 生成 accessToken 和 refreshToken
            long accessTokenTtl = Long.parseLong(paramsAdapter.getValue("ACCESS_TOKEN_TTL"));
            String accessToken = TokenUtils.createToken(userId, userDetail, accessTokenTtl);
            long refreshTokenTtl = Long.parseLong(paramsAdapter.getValue("REFRESH_TOKEN_TTL"));
            String refreshToken = TokenUtils.createToken(userId, userDetail, refreshTokenTtl);

            // 存储到Redis
            String userDetailStr = JsonUtils.toJsonStr(userDetail);
            redisUtils.setEx(RedisKeyBuilder.globalKey("login:" + accessToken), userDetailStr, accessTokenTtl, TimeUnit.MILLISECONDS);

            // 返回登录响应
            long currentTime = System.currentTimeMillis();
            return LoginResponse.builder()
                    .accessToken(accessToken)
                    .accessTokenTTL(currentTime + accessTokenTtl)
                    .refreshToken(refreshToken)
                    .refreshTokenTTL(currentTime + refreshTokenTtl)
                    .userDetail(userDetail)
                    .mustChangePassword(Boolean.TRUE.equals(account.getMustChangePassword()))
                    .build();
        } finally {
            if (hasNotCurrentTenant) {
                TenantUtils.clear();
            }
        }
    }

    /**
     * 密码匹配验证
     */
    private boolean passwordMatches(String requestPassword, String salt, String storedPassword) {
        if (FuncUtils.isEmpty(requestPassword) || FuncUtils.isEmpty(storedPassword)) {
            return false;
        }

        if (CryptoUtils.md5(requestPassword, salt).equals(storedPassword)) {
            return true;
        }

        String clientMd5Password = CryptoUtils.md5(requestPassword);
        if (CryptoUtils.md5(clientMd5Password, salt).equals(storedPassword)) {
            return true;
        }

        // Compatibility for older seed data that stored only the client-side MD5 value.
        return clientMd5Password.equals(storedPassword)
                || (isMd5Hex(requestPassword) && requestPassword.equals(storedPassword));
    }

    private boolean isMd5Hex(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}

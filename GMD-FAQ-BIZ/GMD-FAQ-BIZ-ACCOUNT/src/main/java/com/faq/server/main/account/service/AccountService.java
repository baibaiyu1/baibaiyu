package com.gmd.faq.server.main.account.service;

import com.gmd.faq.server.main.common.dto.*;
import com.gmd.faq.server.main.common.entity.Account;
import com.gmd.faq.server.main.common.vo.AccountCredentialVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 账号Service
 *
 */
public interface AccountService extends IService<Account> {

    /**
     * 用户登录
     *
     * @param request 登录请求
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 管理员新增用户（系统已关闭自助注册，账号只能由拥有 system:user:add 的管理员创建）
     * <p>
     * 「部门即组织」后组织归属由请求体里的 {@code deptId} 决定（允许留空 = 无组织账号）；
     * 密码留空时由服务端随机生成并在返回值中一次性给出，且被标记为「首次登录必须修改密码」。
     *
     * @param request 新增用户请求
     * @return 新账号及其初始密码
     */
    AccountCredentialVO createAccount(AccountCreateRequest request);

    /**
     * 管理员删除用户
     * <p>
     * 删除账号同时清理其全部角色绑定与「部门负责人」引用。
     * 仅把用户移出组织（保留账号）请用 {@code PUT /system/user/{id}/department} 不传 deptId。
     *
     * @param ids 账号ID列表
     * @return 是否成功
     */
    boolean deleteAccounts(List<Long> ids);

    /**
     * 刷新Token
     *
     * @param request 刷新请求
     * @return 登录响应
     */
    LoginResponse refreshToken(RefreshTokenRequest request);

    /**
     * 用户退出
     */
    void logout();

    /**
     * 修改密码（用户自助，首次登录强制改密也走这里）
     *
     * @param request 修改密码请求
     * @return 是否成功
     */
    boolean changePassword(ChangePasswordRequest request);

    /**
     * 修改个人信息
     *
     * @param request 修改个人信息请求
     * @return 是否成功
     */
    boolean updateProfile(UpdateProfileRequest request);

    /**
     * 禁用/激活用户
     *
     * @param id 用户ID
     * @param enabled 是否启用
     * @return 是否成功
     */
    boolean toggleEnabled(Long id, Boolean enabled);

    /**
     * 管理员重置用户密码
     * <p>
     * 新密码留空时由服务端随机生成并一次性返回，且标记「下次登录必须修改密码」。
     *
     * @param id 用户ID
     * @param newPassword 新密码（可选）
     * @return 新密码信息
     */
    AccountCredentialVO resetPassword(Long id, String newPassword);

    /**
     * 通过ChatKey换取Token
     *
     * @param chatKey 对话Key
     * @return token，如果验证失败返回null
     */
    LoginResponse chatKeyToken(String chatKey);

    /**
     * 选择租户（多租户登录后选择进入的租户）
     *
     * @param request 租户选择请求
     * @return 登录响应（含租户上下文的 token）
     */
    LoginResponse selectTenant(SelectTenantRequest request);
}

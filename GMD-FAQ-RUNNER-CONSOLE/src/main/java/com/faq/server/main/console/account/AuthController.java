package com.gmd.faq.server.main.console.account;

import com.gmd.faq.server.main.account.service.AccountService;
import com.gmd.faq.server.main.common.config.auth.PassAuth;
import com.gmd.faq.server.main.common.dto.*;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.util.UserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 认证Controller
 *
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;

    /**
     * 用户登录
     */
    @PassAuth
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResponse res = accountService.login(request);
        return R.data(res, "登录成功");
    }

    /**
     * 刷新Token
     */
    @PassAuth
    @PostMapping("/refresh-token")
    public R<LoginResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        LoginResponse res = accountService.refreshToken(request);
        return R.data(res, "刷新成功");
    }

    /**
     * 用户退出
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        accountService.logout();
        return R.success("退出成功");
    }

    /**
     * 当前登录账号在当前租户下的权限点（单轨权限）
     * <p>
     * 前端登录/切换租户后拉取一次并缓存，用于 v-permission 指令与 isAdmin/isReadOnly 推导；
     * 后端本身不依赖它做鉴权（每个请求都会重新解析）。
     */
    @GetMapping("/perms")
    public R<List<String>> perms() {
        return R.data(UserUtils.getPerms());
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public R<Boolean> changePassword(@RequestBody ChangePasswordRequest request) {
        boolean result = accountService.changePassword(request);
        return R.data(result);
    }

    /**
     * 修改个人信息
     */
    @PostMapping("/update-profile")
    public R<Boolean> updateProfile(@RequestBody UpdateProfileRequest request) {
        boolean result = accountService.updateProfile(request);
        return R.data(result);
    }

    /**
     * 通过ChatKey换取Token
     */
    @PassAuth
    @PostMapping("/chat-key-token/{chatKey}")
    public R<LoginResponse> chatKeyToken(@PathVariable("chatKey") String chatKey) {
        return R.data(accountService.chatKeyToken(chatKey));
    }

    /**
     * 选择租户（多租户登录后选择进入的租户）
     */
    @PostMapping("/select-tenant")
    public R<LoginResponse> selectTenant(@RequestBody SelectTenantRequest request) {
        LoginResponse res = accountService.selectTenant(request);
        return R.data(res, "租户选择成功");
    }
}
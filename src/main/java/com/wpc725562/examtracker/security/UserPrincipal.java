package com.wpc725562.examtracker.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 当前登录用户 —— 认证成功后放进 {@code SecurityContext} 的那个对象。
 *
 * <p>只有两个字段，因为**它只回答「你是谁」**。判断「你能不能做这件事」用的是
 * 数据归属（{@code userId} 是否匹配），那在 Service 层用查询条件表达，
 * 不在这里堆权限字符串。本项目是单用户维度的数据隔离，
 * 不需要 RBAC，硬套 {@code ROLE_ADMIN} 只会让权限模型变复杂而没有实际收益。
 */
public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final String username;

    public UserPrincipal(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /**
     * 永远返回 null。
     *
     * <p>密码校验发生在**登录那一刻**（{@code AuthService} 里用 {@code PasswordEncoder.matches}），
     * 之后所有请求都靠 JWT 签名认证，不需要再拿密码。
     * 把这个字段留空是有意的：既然用不到，就不要让明文密码有机会流进 SecurityContext。
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}

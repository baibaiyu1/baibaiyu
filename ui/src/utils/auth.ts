import setting from "@/config/setting";
import cache from "@/utils/cache";
import Cookies from 'js-cookie';
import {type AccountVO, type LoginResponse, type TokenType} from "@/types";

const TokenKey = setting.systemName + '-' + setting.accessToken;
const refreshTokenKey = setting.systemName + '-' + setting.refreshToken;
const userKey = setting.systemName + '-' + setting.user;
const ACCESS_TOKEN_COOKIE_KEY = 'gmd-faq-access-token';
const REFRESH_TOKEN_COOKIE_KEY = 'gmd-faq-refresh-token';
const shareSessionKey = setting.systemName + '-share-session';

function currentShareKey(): string | null {
  if (typeof window === 'undefined') return null;
  const match = /^#\/communication\/([^/?]+)/.exec(window.location.hash);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

function isShareRoute(): boolean {
  return currentShareKey() !== null;
}

function getShareSession(chatKey: string): LoginResponse | null {
  try {
    const raw = sessionStorage.getItem(shareSessionKey);
    if (!raw) return null;
    const session = JSON.parse(raw) as { chatKey: string; data: LoginResponse };
    if (session.chatKey !== chatKey || !session.data?.accessToken ||
        Number(session.data.accessTokenTTL) <= Date.now()) return null;
    return session.data;
  } catch {
    return null;
  }
}

function setShareSession(chatKey: string, data: LoginResponse): void {
  sessionStorage.setItem(shareSessionKey, JSON.stringify({ chatKey, data }));
}

function removeShareSession(): void {
  sessionStorage.removeItem(shareSessionKey);
}

/**
 * 获取token
 * @returns token
 */
function getToken(): string {
  const shareKey = currentShareKey();
  if (shareKey !== null) return getShareSession(shareKey)?.accessToken || '';
  const token = cache.local.getJSON(TokenKey, { value: null, ttl: 0 })
  if(token.value) {
    if(token.ttl === '-1') {
      return token.value
    }
    const currentTime = new Date().getTime()
    if(currentTime < Number(token.ttl)) {
      return token.value
    }
  }
  return '';
}

/**
 * 设置token
 * @param token token
 */
function setToken(token: TokenType) {
  Cookies.set(ACCESS_TOKEN_COOKIE_KEY, token.value, {
    secure: setting.cookieSecure,
    sameSite: setting.cookieSameSite,
    expires: 7, // 7天过期，关闭浏览器不消失
    path: '/'    // 全站有效
  })
  cache.local.setJSON(TokenKey, token);
}

/**
 * 清除token
 */
function removeToken() {
  Cookies.remove(ACCESS_TOKEN_COOKIE_KEY)
  cache.local.remove(TokenKey);
}

/**
 * 获取refreshToken
 * @returns token
 */
function getRefreshToken(): string {
  if (isShareRoute()) return '';
  const refreshToken = cache.local.getJSON(refreshTokenKey, { value: null, ttl: 0 });
  if(refreshToken.value) {
    if(refreshToken.ttl === -1) {
      return refreshToken.value
    }
    const currentTime = new Date().getTime()
    if(currentTime < refreshToken.ttl) {
      return refreshToken.value
    }
  }
  return '';
}

/**
 * 设置refreshToken
 * @param refreshToken
 */
function setRefreshToken(refreshToken: TokenType) {
  Cookies.set(REFRESH_TOKEN_COOKIE_KEY, refreshToken.value, {
    secure: setting.cookieSecure,
    sameSite: setting.cookieSameSite,
    expires: 7, // 7天过期，关闭浏览器不消失
    path: '/'    // 全站有效
  })
  cache.local.setJSON(refreshTokenKey, refreshToken);
}

/**
 * 清除refreshToken
 */
function removeRefreshToken() {
  Cookies.remove(REFRESH_TOKEN_COOKIE_KEY)
  cache.local.remove(refreshTokenKey);
}

/**
 * 获取User
 *
 * 兼容历史缓存：早期版本可能把 user 存成「JSON 字符串」（双重编码），
 * 这里统一返回对象，避免调用方拿到字符串后出现 `userInfo.id` 取不到、或
 * 透传给后端时反序列化失败。
 */
function getUser(): AccountVO {
  const raw = cache.local.getJSON(userKey)
  if (typeof raw === 'string') {
    try {
      return JSON.parse(raw) as AccountVO
    } catch {
      return raw as unknown as AccountVO
    }
  }
  return raw as AccountVO
}

/**
 * 设置User
 */
function setUser(userInfo: AccountVO) {
  cache.local.setJSON(userKey, userInfo);
}

/**
 * 清除User
 */
function removeUser() {
  cache.local.remove(userKey);
}

export {
  isShareRoute,
  getShareSession,
  setShareSession,
  removeShareSession,
  getToken,
  setToken,
  removeToken,
  getRefreshToken,
  setRefreshToken,
  removeRefreshToken,
  getUser,
  setUser,
  removeUser
};


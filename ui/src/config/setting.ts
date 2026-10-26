import { BRAND_KEY } from './brand'

export default {
  // 内部标识（cookie / 本地存储 key 前缀），不参与界面展示，保持稳定以免影响已登录用户的本地数据
  systemName: BRAND_KEY,
  repeatReqInterval: 500, // 重复请求时间间隔（毫秒）
  tokenHeader: "Authorization",
  refreshTokenRequest: "Is_Refresh_Token_Request",
  accessToken: "accessToken",
  refreshToken: "refreshToken",
  user: "user",
  cookieSecure: false,
  cookieSameSite: 'lax' as "lax" | "strict" | "Strict" | "Lax" | "none" | "None",
  whiteList: [
    "/login"
  ],
};

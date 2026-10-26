/**
 * 智能体编排工作台 —— 智能体头像
 *
 * 本仓 `AgentDefinition` 没有图标字段，工作台统一使用内置头像；
 * 侧栏名片、预览欢迎区与助手消息共用同一张图，保证「左侧头像点出来的就是当前智能体」。
 *
 */
import chatBotAvatar from '@/assets/avatar/chat-bot.png'

/** 智能体默认头像 */
export const AGENT_AVATAR = chatBotAvatar

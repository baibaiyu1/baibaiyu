<template>
  <div class="mermaid-skeleton">
    <div class="mermaid-skeleton-inner">
      <!-- 标题区 -->
      <div class="mermaid-skeleton-header">
        <span class="mermaid-skeleton-dot"></span>
        <span class="mermaid-skeleton-title">正在生成图表...</span>
      </div>
      <!-- 模拟流程图骨架 -->
      <div class="mermaid-skeleton-diagram">
        <!-- 节点 -->
        <div
          v-for="n in 5"
          :key="'node-' + n"
          class="mermaid-skeleton-node"
          :class="'mermaid-skeleton-node--' + n"
          :style="{ animationDelay: `${n * 0.12}s` }"
        >
          <div class="mermaid-skeleton-node-bar"></div>
        </div>
        <!-- 连线 -->
        <svg class="mermaid-skeleton-lines" viewBox="0 0 100 100" preserveAspectRatio="none">
          <line x1="50" y1="18" x2="28" y2="48" class="line-1" />
          <line x1="50" y1="18" x2="50" y2="48" class="line-2" />
          <line x1="50" y1="18" x2="72" y2="48" class="line-3" />
          <line x1="28" y1="60" x2="50" y2="82" class="line-4" />
          <line x1="50" y1="60" x2="50" y2="82" class="line-5" />
          <line x1="72" y1="60" x2="50" y2="82" class="line-6" />
        </svg>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Mermaid 流式加载占位组件
 *
 * 代码块尚未完整输出时展示流程图骨架屏动画，
 * 包含模拟节点和连线的脉动效果。
 */
</script>

<style scoped>
.mermaid-skeleton {
  margin: 12px 0;
  border-radius: 14px;
  overflow: hidden;
  position: relative;
  background: #fafbfc;
  border: 1px solid #e0e3e8;
}


@keyframes mermaidBorderFlow {
  0% { background-position: 200% 0; }
  50% { background-position: 0% 0; }
  100% { background-position: 200% 0; }
}

.mermaid-skeleton-inner {
  position: relative;
  padding: 18px 20px 24px;
  z-index: 1;
}

/* 标题区域 */
.mermaid-skeleton-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
}

.mermaid-skeleton-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c4cad4;
  animation: mermaidDotPulse 1.4s ease-in-out infinite;
}

@keyframes mermaidDotPulse {
  0%, 100% { opacity: 0.3; transform: scale(0.85); }
  50% { opacity: 0.9; transform: scale(1.1); }
}

.mermaid-skeleton-title {
  font-size: 13px;
  font-weight: 500;
  color: #9ca3af;
  animation: mermaidTitleFade 1.4s ease-in-out infinite;
}

@keyframes mermaidTitleFade {
  0%, 100% { opacity: 0.45; }
  50% { opacity: 0.85; }
}

/* 流程图骨架区 */
.mermaid-skeleton-diagram {
  position: relative;
  height: 220px;
  border-radius: 10px;
}

/* SVG 连线层 */
.mermaid-skeleton-lines {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.mermaid-skeleton-lines line {
  stroke: #d1d5db;
  stroke-width: 0.8;
  stroke-dasharray: 4 3;
  animation: mermaidLineDash 2s linear infinite;
}

.mermaid-skeleton-lines .line-1 { animation-delay: 0s; }
.mermaid-skeleton-lines .line-2 { animation-delay: 0.3s; }
.mermaid-skeleton-lines .line-3 { animation-delay: 0.6s; }
.mermaid-skeleton-lines .line-4 { animation-delay: 0.9s; }
.mermaid-skeleton-lines .line-5 { animation-delay: 1.2s; }
.mermaid-skeleton-lines .line-6 { animation-delay: 1.5s; }

@keyframes mermaidLineDash {
  to { stroke-dashoffset: -14; }
}

/* 骨架节点 */
.mermaid-skeleton-node {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: linear-gradient(135deg, #eef2ff 0%, #f4f6fd 50%, #eef2ff 100%);
  border: 1px solid #e4e8f5;
  animation: mermaidNodeFloat 3s ease-in-out infinite;
}

.mermaid-skeleton-node-bar {
  width: 70%;
  height: 10px;
  border-radius: 5px;
  background: linear-gradient(90deg, #dde3f9 25%, #c7d2f5 50%, #dde3f9 75%);
  background-size: 200% 100%;
  animation: mermaidShimmer 2s ease-in-out infinite;
}

/* 节点位置 */
.mermaid-skeleton-node--1 {
  top: 6%;
  left: 50%;
  width: 44%;
  height: 28px;
  transform: translateX(-50%);
}

.mermaid-skeleton-node--2 {
  top: 42%;
  left: 6%;
  width: 36%;
  height: 28px;
}

.mermaid-skeleton-node--3 {
  top: 42%;
  left: 50%;
  width: 40%;
  height: 28px;
  transform: translateX(-50%);
}

.mermaid-skeleton-node--4 {
  top: 42%;
  right: 6%;
  width: 36%;
  height: 28px;
}

.mermaid-skeleton-node--5 {
  bottom: 6%;
  left: 50%;
  width: 44%;
  height: 28px;
  transform: translateX(-50%);
}

@keyframes mermaidNodeFloat {
  0%, 100% { transform: translateX(-50%) translateY(0); }
  50% { transform: translateX(-50%) translateY(-4px); }
}

/* 左右两侧节点需要单独处理动画，因为没有 translateX(-50%) */
.mermaid-skeleton-node--2,
.mermaid-skeleton-node--4 {
  animation-name: mermaidNodeFloatSide;
}

@keyframes mermaidNodeFloatSide {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}

@keyframes mermaidShimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>

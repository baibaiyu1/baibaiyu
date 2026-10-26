<template>
  <div class="uip-skeleton">
    <div class="uip-skeleton-inner">
      <div class="uip-skeleton-header">
        <span class="uip-skeleton-dot"></span>
        <span class="uip-skeleton-title">正在准备交互组件...</span>
      </div>
      <div class="uip-skeleton-body">
        <div
          v-for="n in 3"
          :key="n"
          class="uip-skeleton-row"
          :style="{ animationDelay: `${0.3 + n * 0.15}s` }"
        >
          <div class="uip-skeleton-label"></div>
          <div class="uip-skeleton-field"></div>
        </div>
        <div class="uip-skeleton-btn"></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * UIP 交互组件流式加载占位卡片
 * JSON 不完整时展示优雅的骨架屏动画
 */
</script>

<style scoped>
.uip-skeleton {
  margin: 12px 0;
  border-radius: 12px;
  overflow: hidden;
  position: relative;
  background: #fafbfc;
  border: 1px solid #e0e3e8;
}

/* 旋转渐变边框 — 使用静态渐变 + shimmer 伪元素实现跨浏览器兼容 */
.uip-skeleton::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: 12px;
  padding: 1px;
  background: linear-gradient(
    120deg,
    #e8ecf1 0%,
    #c4cad4 25%,
    #e8ecf1 40%,
    #fafbfc 60%,
    #e8ecf1 75%,
    #c4cad4 100%
  );
  background-size: 300% 100%;
  -webkit-mask: linear-gradient(#fff 0 0) content-box, linear-gradient(#fff 0 0);
  -webkit-mask-composite: xor;
  mask-composite: exclude;
  opacity: 0.5;
  animation: uipBorderShimmer 3s ease-in-out infinite;
}

@keyframes uipBorderShimmer {
  0% { background-position: 100% 0; }
  50% { background-position: 0% 0; }
  100% { background-position: 100% 0; }
}

.uip-skeleton-inner {
  position: relative;
  padding: 18px 20px;
  z-index: 1;
}

/* 标题区域 */
.uip-skeleton-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
}

.uip-skeleton-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c4cad4;
  animation: pulse 1.5s ease-in-out infinite;
}

.uip-skeleton-title {
  font-size: 13px;
  color: #a0a4ab;
  animation: pulseText 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.75; }
}

@keyframes pulseText {
  0%, 100% { opacity: 0.5; }
  50% { opacity: 0.9; }
}

/* 骨架行 */
.uip-skeleton-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.uip-skeleton-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: fadeSlideIn 0.5s ease both;
}

@keyframes fadeSlideIn {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.uip-skeleton-label {
  height: 12px;
  width: 32%;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef0f4 25%, #e4e7ed 50%, #eef0f4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.8s ease-in-out infinite;
}

.uip-skeleton-field {
  height: 32px;
  width: 100%;
  border-radius: 6px;
  background: linear-gradient(90deg, #f2f4f7 25%, #e8eaef 50%, #f2f4f7 75%);
  background-size: 200% 100%;
  animation: shimmer 1.8s ease-in-out infinite;
  animation-delay: 0.1s;
}

.uip-skeleton-btn {
  height: 32px;
  width: 72px;
  margin-top: 8px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef0f4 25%, #dee2ea 50%, #eef0f4 75%);
  background-size: 200% 100%;
  animation: shimmer 1.8s ease-in-out infinite;
}

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>

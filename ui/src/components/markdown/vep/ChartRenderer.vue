<template>
  <div class="vep-chart">
    <!-- 标题 -->
    <div v-if="item.title" class="vep-chart-title">{{ item.title }}</div>
    <!-- 洞察 -->
    <div v-if="item.insight" class="vep-chart-insight">{{ item.insight }}</div>
    <!-- 图表区域 -->
    <div class="vep-chart-body">
      <VChart :option="chartOption" autoresize class="vep-chart-canvas" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart, PieChart, RadarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  RadarComponent
} from 'echarts/components'
import type { ChartItem, ChartData, ChartSeries } from './types'

use([
  CanvasRenderer,
  LineChart, BarChart, PieChart, RadarChart,
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, RadarComponent
])

/** 现代灵动配色 */
const COLORS = [
  '#6C9BD2', '#5DBE9B', '#F0986D', '#A78BFA', '#F6C244',
  '#4ECDC4', '#FF6B8A', '#8EC9FF', '#B8A9E8', '#F9A66C'
]

/** 配色渐变浅色 */
const COLOR_LIGHTS = [
  'rgba(108,155,210,0.25)', 'rgba(93,190,155,0.25)', 'rgba(240,152,109,0.25)',
  'rgba(167,139,250,0.25)', 'rgba(246,194,68,0.25)', 'rgba(78,205,196,0.25)',
  'rgba(255,107,138,0.25)', 'rgba(142,201,255,0.25)', 'rgba(184,169,232,0.25)',
  'rgba(249,166,108,0.25)'
]

const props = defineProps<{ item: ChartItem }>()

/** VEP 简化数据 -> ECharts Option */
const chartOption = computed(() => buildEChartsOption(props.item.data))

/**
 * 构建全局共用样式：柔和坐标轴、卡片式提示框、轻盈图例
 */
function buildBaseOption() {
  return {
    color: COLORS,
    animationDuration: 800,
    animationEasing: 'cubicOut' as const,
    tooltip: {
      backgroundColor: '#fff',
      borderColor: '#e8ecf1',
      borderWidth: 1,
      borderRadius: 8,
      padding: [10, 14],
      textStyle: { color: '#333', fontSize: 13 },
      extraCssText: 'box-shadow: 0 4px 16px rgba(0,0,0,0.08);'
    },
    legend: {
      bottom: 0,
      type: 'scroll',
      icon: 'roundRect',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#666', fontSize: 12 },
      pageTextStyle: { color: '#999' }
    }
  }
}

/**
 * 构建 ECharts 线性渐变对象（plain object 格式，无需导入 graphic 模块）
 */
function linearGradient(colorTop: string, colorBottom: string) {
  return {
    type: 'linear',
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: colorTop },
      { offset: 1, color: colorBottom }
    ]
  }
}

/**
 * 构建柔和坐标轴配置
 */
function buildAxisStyle(chartType: string, xAxis: string[] | undefined, yAxisLabel?: string) {
  return {
    grid: { left: '3%', right: '4%', bottom: '14%', top: '6%', containLabel: true },
    xAxis: {
      type: 'category',
      data: xAxis || [],
      boundaryGap: chartType === 'bar',
      axisLine: { lineStyle: { color: '#e0e5ec' } },
      axisTick: { show: false },
      axisLabel: { color: '#888', fontSize: 11 },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: yAxisLabel || '',
      nameTextStyle: { color: '#999', fontSize: 11 },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#aaa', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } }
    }
  }
}

/**
 * 将 VEP ChartData 转为 ECharts Option
 */
function buildEChartsOption(data: ChartData) {
  const { chartType, xAxis, series, yAxisLabel } = data
  const base = buildBaseOption()

  // 饼图
  if (chartType === 'pie') {
    return Object.assign({}, base, {
      tooltip: Object.assign({}, base.tooltip, { trigger: 'item', formatter: '{b}: {c} ({d}%)' }),
      series: series.map(s => ({
        type: 'pie',
        name: s.name,
        radius: ['40%', '68%'],
        center: ['50%', '46%'],
        data: s.data as Array<{ name: string; value: number }>,
        itemStyle: {
          borderRadius: 6,
          borderColor: '#fff',
          borderWidth: 2
        },
        emphasis: {
          scaleSize: 8,
          itemStyle: { shadowBlur: 16, shadowColor: 'rgba(0,0,0,0.12)' }
        },
        label: {
          show: true,
          color: '#666',
          fontSize: 11,
          formatter: '{b}\n{d}%'
        },
        labelLine: { lineStyle: { color: '#ddd' } }
      }))
    })
  }

  // 雷达图
  if (chartType === 'radar') {
    const resolvedXAxis = xAxis && xAxis.length > 0
      ? xAxis
      : extractRadarIndicatorNames(series)
    const maxVal = getMaxFromSeries(series)
    const indicator = resolvedXAxis.map(name => ({ name, max: maxVal }))
    return Object.assign({}, base, {
      tooltip: Object.assign({}, base.tooltip, { trigger: 'item' }),
      radar: {
        indicator,
        center: ['50%', '48%'],
        radius: '62%',
        axisName: { color: '#777', fontSize: 11, borderRadius: 3, padding: [2, 5] },
        axisLine: { lineStyle: { color: '#e8ecf1' } },
        splitLine: { lineStyle: { color: '#f0f2f5' } },
        splitArea: {
          areaStyle: { color: ['rgba(108,155,210,0.04)', 'rgba(108,155,210,0.02)'] }
        }
      },
      series: series.map((s, i) => ({
        type: 'radar',
        name: s.name,
        data: [{ name: s.name, value: extractRadarValues(s.data) }],
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2, color: COLORS[i % COLORS.length] },
        areaStyle: { color: COLOR_LIGHTS[i % COLOR_LIGHTS.length] },
        itemStyle: { color: COLORS[i % COLORS.length] }
      }))
    })
  }

  // 折线图 / 柱状图 / 面积图
  const actualType = chartType === 'area' ? 'line' : chartType
  const axisStyle = buildAxisStyle(chartType, xAxis, yAxisLabel)
  return Object.assign({}, base, {
    tooltip: Object.assign({}, base.tooltip, { trigger: 'axis' }),
    ...axisStyle,
    series: series.map((s, i) => {
      const colorIdx = i % COLORS.length
      const item: Record<string, unknown> = {
        name: s.name,
        type: actualType,
        data: s.data as number[],
        smooth: true,
        symbol: chartType === 'bar' ? 'none' : 'circle',
        symbolSize: chartType !== 'bar' ? 6 : undefined,
        itemStyle: {
          color: COLORS[colorIdx],
          ...(chartType === 'bar' ? { borderRadius: [6, 6, 0, 0] } : {})
        },
        lineStyle: chartType !== 'bar' ? { width: 2.5, color: COLORS[colorIdx] } : undefined
      }
      // 面积图：纵向渐变填充
      if (chartType === 'area') {
        item.areaStyle = {
          color: linearGradient(COLORS[colorIdx] || '#6C9BD2', 'rgba(255,255,255,0.02)'),
          opacity: 0.3
        }
      }
      return item
    })
  })
}

/**
 * 获取雷达图指标最大值
 */
function getMaxFromSeries(series: ChartData['series']): number {
  let max = 0
  for (const s of series) {
    if (Array.isArray(s.data) && s.data.length > 0) {
      const first = s.data[0]
      if (typeof first === 'number') {
        for (const v of s.data as number[]) {
          if (v > max) max = v
        }
      } else if (first && typeof first === 'object' && 'value' in first) {
        for (const item of s.data as Array<{ name: string; value: number }>) {
          if (item.value > max) max = item.value
        }
      }
    }
  }
  // 向上取整到合适的值
  return max > 0 ? Math.ceil(max * 1.1) : 100
}

/**
 * 雷达图：从系列数据中提取指标名称
 * 当 xAxis 未提供时，优先从 {name, value} 对象数组中提取 name，
 * 若为纯数字数组则生成默认名称 "维度1"、"维度2"...
 */
function extractRadarIndicatorNames(series: ChartData['series']): string[] {
  if (series.length === 0) return []
  const firstSeries = series[0]
  if (!firstSeries || !Array.isArray(firstSeries.data) || firstSeries.data.length === 0) return []
  const firstItem = firstSeries.data[0]
  // {name, value} 对象格式：提取 name 字段
  if (firstItem && typeof firstItem === 'object' && 'name' in firstItem) {
    return (firstSeries.data as Array<{ name: string; value: number }>).map(item => item.name)
  }
  // 纯数字格式：生成默认维度名
  return firstSeries.data.map((_, i) => `维度${i + 1}`)
}

/**
 * 雷达图：从系列数据中提取数值数组
 * 支持纯数字数组和 {name, value} 对象数组两种格式
 */
function extractRadarValues(data: ChartSeries['data']): number[] {
  if (!Array.isArray(data) || data.length === 0) return []
  const first = data[0]
  if (typeof first === 'number') {
    return data as number[]
  }
  if (first && typeof first === 'object' && 'value' in first) {
    return (data as Array<{ name: string; value: number }>).map(item => item.value)
  }
  return []
}
</script>

<style scoped>
.vep-chart {
  margin: 10px 0;
  padding: 16px 20px;
  background: #fafbfc;
  border: 1px solid #e8ecf1;
  border-radius: 10px;
}

.vep-chart-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a1a2e;
  margin-bottom: 4px;
}

.vep-chart-insight {
  font-size: 13px;
  color: #8c8c8c;
  font-style: italic;
  margin-bottom: 12px;
  line-height: 1.5;
}

.vep-chart-body {
  width: 100%;
}

.vep-chart-canvas {
  width: 100%;
  height: 320px;
}
</style>

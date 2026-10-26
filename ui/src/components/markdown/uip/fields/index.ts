/**
 * 表单字段渲染组件注册表
 *
 * 映射 APIP 字段 type → Vue 组件。
 * 未知 type 查找失败时由调用方降级为 <a-input>。
 */
import type { Component } from 'vue'
import TextField from './TextField.vue'
import TextareaField from './TextareaField.vue'
import NumberField from './NumberField.vue'
import SelectField from './SelectField.vue'
import RadioField from './RadioField.vue'
import CheckboxField from './CheckboxField.vue'
import SwitchField from './SwitchField.vue'
import DateField from './DateField.vue'
import DateTimeField from './DateTimeField.vue'
import EmailField from './EmailField.vue'
import TelField from './TelField.vue'

export const fieldRenderers: Record<string, Component> = {
  text: TextField,
  textarea: TextareaField,
  number: NumberField,
  select: SelectField,
  radio: RadioField,
  checkbox: CheckboxField,
  'checkbox-group': CheckboxField,
  switch: SwitchField,
  date: DateField,
  datetime: DateTimeField,
  email: EmailField,
  tel: TelField,
}

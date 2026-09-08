/// <reference types="vite/client" />

// 说明：@wangeditor/editor 自带 TS 类型声明（types 字段有效），可直接使用。
// @wangeditor/editor-for-vue 的 package.json exports 未映射 types 路径，
// bundler 模式下 TS 无法解析其类型（构建报 TS7016），故在此补充模块声明。
// 声明内容依据包内 dist/src/components/*.vue.d.ts 的实际 props 定义。
declare module '@wangeditor/editor-for-vue' {
  import type { DefineComponent } from 'vue'
  import type { IDomEditor, IEditorConfig, IToolbarConfig } from '@wangeditor/editor'

  // 富文本编辑器组件：v-model 绑定 html，onCreated / onDestroyed 事件回传编辑器实例
  export const Editor: DefineComponent<{
    mode?: string
    defaultConfig?: Partial<IEditorConfig>
    modelValue?: string
    'onUpdate:modelValue'?: (value: string) => void
    onOnCreated?: (editor: IDomEditor) => void
    onOnDestroyed?: (editor: IDomEditor) => void
  }>

  // 工具栏组件：传入编辑器实例渲染菜单
  export const Toolbar: DefineComponent<{
    editor?: IDomEditor
    mode?: string
    defaultConfig?: Partial<IToolbarConfig>
  }>
}

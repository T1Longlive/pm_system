<script setup lang="ts">
// 根组件：仅承载路由出口
</script>

<template>
  <router-view />
</template>

<style>
/* 全局基础样式：页面铺满视口，去除默认边距 */
html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
}
</style>

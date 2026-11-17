# Course state: 测开导向 Linux + 自动化测试
**Learner:** 命令行新手；有云服务器；目标 = 测开（测试开发），应对面试；会审核 AI 方案
**Source:** topic mode — 测开面试导向课程（原运维向10章已压缩重构）

## Course map（3 部分 · 11 章）
- Part A Linux 底座（测开必需）
- Part B 测试核心（笔试面试主战场）
- Part C 工程化与面试（项目经历）

## Chapters
- [x] L1. 文件即一切：Linux 世界观与目录结构 — mastered: FHS、路径、一切皆文件 | weak: /var vs /tmp（已纠正）
- [x] L2. Shell 思维：管道、重定向与命令组合 — mastered: 管道流水线、> vs >>、du -sh | weak: 中文引号、/proc 与 du 场景混淆
- [ ] L3. 权限与用户模型 ← current, 已出题未批改
- [ ] L4. 进程、日志与网络速览（查服务/看日志/接口连通）
- [ ] T1. 测试理论与用例设计（等价类/边界值/场景法）
- [ ] T2. Python + pytest 自动化基础
- [ ] T3. 接口自动化实战（请求/断言/接口关联）
- [ ] T4. SQL 造数验数 + mock 与测试报告
- [ ] T5. 框架分层设计（PO / 数据驱动 / 失败重跑）
- [ ] E1. Docker 与测试环境搭建
- [ ] E2. CI/CD 流水线（Jenkins / GitLab CI）
- [ ] E3. 性能测试入门（TPS / RT / Locust 或 JMeter）
- [ ] E4. 测开面试真题与项目包装

## Concept table
| Concept | Status | Evidence | Next review |
|---|---|---|---|
| 一切皆文件（含虚拟文件） | practicing | 练习4缺 /proc/pid 例子 | L4 |
| FHS 目录职责 | mastered | 练习1全对 | T4 造数路径 |
| /var vs /tmp | review-needed | 概念检查曾错，近迁移已对 | L3 开头抽查 |
| 绝对/相对路径 | mastered | 练习3全对 | — |
| 配置与程序分离 | mastered | 练习2准确 | — |
| 管道组合 | mastered | 练习1思路对 | T3 接口自动化复用 |
| > vs >> | mastered | 练习3雷找对 | E2 CI 配置注意 |
| du -sh / du -ah | practicing | 现场讲解过，练习2数据源曾混淆 | L4 |
| 权限位 rwx / 644·755·600 | introduced | 第3章已讲未练习 | L3 批改 |

## Review queue
- /var vs /tmp — L1 概念检查错 — 近迁移已对，L3 开头再抽查
- du 的数据来源（统计文件 vs /proc 进程） — L2 练习2混淆 — L4 复测
- 一切皆文件的虚拟文件例子 — L1 不完整 — L4 复测

## Error log
- L1: 目录职责凭直觉（把日志归到 /tmp），未按 var=variable 推导
- L2: 磁盘占用场景误用 /proc 概念；中文弯引号复制习惯需改

# 开发入口

继续开发前请依次阅读：

1. 根目录 `README.md`
2. `PROJECT_STATE.md`
3. `ARCHITECTURE.md`
4. `DECISIONS.md`
5. `TODO.md`
6. `KNOWN_ISSUES.md`
7. `BUILD.md`
8. `SESSION_HANDOFF.md`

修改数据语义时同时更新 `API.md`；修改数据库表时必须增加 Room Migration 并验证从 v1、v2 升级。不要将缺失数据渲染为 0，不要把电池侧估算功率描述为充电器输出或协议功率。

每次交付前至少运行 `testDebugUnitTest`、`lintRelease` 和 `assembleRelease`，并更新项目状态、变更记录、待办及本次交接。


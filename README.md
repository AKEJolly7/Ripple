# 观澜（Ripple）

> 观水有术，必观其澜。——《孟子》
> **观其澜，知其源 —— Watch the waves, find the stones.**

事件（ChatGPT、B100、DeepSeek）是"投石"，行情是"波澜"，观澜就是在找那颗石头。

一句话简介：**确定性回测 + LLM 定点归因的双任务分析系统**
- 把 NVDA 五年行情拐点与 AI 行业事件对齐成可交互、可溯源的单文件 K 线 HTML。
- 输出黄金 vs 比特币避险/抗通胀比较的 Excel 回测底稿、PPT 决策框架与 Word 策略报告。

## 环境要求

- **JDK 17+**（本仓库约定 `JAVA_HOME` 指向它；默认 java 是 8 的机器见常见问题）
- **Maven 3.9+**
- 网络：可访问新浪财经 / Binance / Hacker News Algolia（Yahoo 主源在大陆 IP 下自动降级，见常见问题）
- 可选：`DEEPSEEK_API_KEY` 环境变量（LLM 事件归因；不设则走规则对齐，全流程照常）

## 一键运行

```bash
mvn -q clean package && java -jar target/ripple-0.1.0.jar run-all
```

Windows（`run.cmd`）：

```cmd
run.cmd
```

产物路径：

| 产物 | 路径 | 说明 |
|---|---|---|
| NVDA 事件归因交互报告 | `output/nvda-events.html` | 双击即开（ECharts 与数据全内嵌，断网可用），三标的可切换 |
| 黄金/比特币回测底稿 | `artifacts/gold-btc-backtest.xlsx` | 5 sheet，数值可与 `work/` 原始 JSON 交叉核对 |
| 资产配置决策框架 | `artifacts/gold-btc-framework.pptx` | 5 页决策框架 |
| 策略报告 | `artifacts/gold-btc-strategy.docx` | 结论先行 + 口径 + 风险 + 附录 |
| 中间检查点（溯源凭据） | `work/*.json` | 行情/分析/事件/对齐的原始数据，断点续跑复用 |

分步 CLI（可选）：
- `DataFetchMain`（抓数）
- `AnalysisMain`（技术分析）
- `AgentMain`（编排对齐，支持 `--no-llm` `--verify`）
- `RenderMain`（HTML，支持 `--serve` 预览）
- `BuildArtifactsMain`（三件套，`--gold-weight` 可配）

## 常见问题

**1. Yahoo 报 429/403？**
Yahoo v8 对大陆 IP 返回 403（地域封锁），429 为限速。系统已内置自动降级链：Yahoo →（BTC 走 Binance，其余走新浪财经），无需处理；日志中的 `切换兜底数据源` 属正常。有海外网络时自动回到 Yahoo。

**2. 没有 DEEPSEEK_API_KEY 能跑吗？**
能。`run-all` 与所有 CLI 默认在无 key 时自动降级为规则对齐（关键词+时间窗+词典打分），产物结构与流程完全一致，仅归因推理为规则生成。设 key 后建议显式 `--no-llm` 对比两种模式。

**3. LLM 归因和规则对齐（降级）有什么区别？**
两条路径互为镜像，同一证据集下各司其职（完整对照表见 DESIGN.md 关键取舍 6）：

| | LLM 归因 | 规则对齐（降级） |
|---|---|---|
| 判定 | 语义因果判断（"这新闻是不是当日行情的合理动因"） | 词典命中 × 日期接近 × 动量加权 |
| 归因尺度 | 宁缺毋滥（实测 NVDA 26 归因 / 22 缺失） | 尽量归因（48/48） |
| 推理 | 自然语言因果链 | 模板句 |
| 复现性/成本 | 不可复现，48 次调用 ≈1 分钟 | 毫秒级、零成本、可单测 |
| 落盘标记 | `mode=llm` | `mode=rule`（降级发生时如实标记） |

LLM 失败有三层防御：schema 校验丢弃编造条目 → 单拐点降级"事件缺失" → 整体不可用时上层降级规则对齐（已用无效 key 实测）。LLM 调用按拐点分片（每次 1 拐点 + 预筛 top-8 候选），不会超出模型上下文与输出上限。

**4. 构建报"非法字符/文本块"错误？**
默认 `java`/`mvn` 指向 JDK 8。执行：
```bash
export JAVA_HOME=<JDK17 路径> MAVEN_SKIP_RC=1
```
MAVEN_SKIP_RC 跳过 ~/.mavenrc 的旧 JAVA_HOME（本机开发环境：`JAVA_HOME=~/tools/jdk-17/Contents/Home`）

**5. HN Algolia 偶发 429/500？**
已内置指数退避重试 + 窗口级二次重试，偶发失败自动恢复；个别窗口持续失败会记为"事件缺失"而非中断。

**6. 重复运行会重新抓数据吗？**
不会。`work/` 检查点存在即复用（断点续跑，二次运行 0 网络请求）；删除 `work/` 后重跑即全量重建。

## 更多文档

- [CLAUDE.md](CLAUDE.md) —— 本仓 5 条约定
- [DESIGN.md](DESIGN.md) —— 架构与关键取舍
- [MILESTONES.md](MILESTONES.md) —— R0-R9 里程碑与验收记录
- [DEVLOG.md](DEVLOG.md) —— 逐轮开发日志（AI 工具、参与环节、人工判断）

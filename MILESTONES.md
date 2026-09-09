# 观澜（Ripple）里程碑 —— R0-R10

> 状态总览：**R0-R8 全部完成；R9/R10 为追加轮（LLM 真实路径实测 / 产物版式修复与从零回归）**。每条保留实际执行的范围、关键变更与最终验收结论；
> 过程细节（输入提示词、问题修复三列表、方法论）见 DEVLOG.md，架构与题目核验 checklist 见 DESIGN.md。

- [x] **R0 骨架与治理（0.25h）**
  设计方案获批（四层架构 / 数据源选型 / 五项关键取舍）；Maven 骨架（Java 17 + langchain4j 1.19.0 + POI 5.5.1 + Jackson 2.22.2）；治理文件四件（CLAUDE.md 5 条约定 / 本文件 / DEVLOG.md / .gitignore）；ECharts 6.1.0 本地入库（SHA-256 `b66b25ae…0fd0`）。
  环境：JDK 17 装至 `~/tools/jdk-17`（Adoptium tar.gz 免 sudo）；构建统一 `JAVA_HOME=<JDK17> MAVEN_SKIP_RC=1`（跳过 ~/.mavenrc 的 zulu-8）。
  验收：`mvn -q compile` 通过，治理文件与 echarts.min.js 齐备。

- [x] **R1 行情数据通道（计划 0.5h，实际 1.5h 含数据源灾备）**
  domain（Ohlcv/NewsItem/MarketEvent+ImpactRating）+ dataprovider（HttpTransport 抽象 / 指数退避+抖动 / 错误五分类 / Yahoo 主源 + 新浪·拆股表前复权 + Binance + HN Algolia）+ DataFetchMain CLI，落盘 work/（含 source 溯源字段）。
  关键变更：Yahoo v8 对大陆 IP 403，经用户确认采用「Yahoo 主 + 新浪（美股/ETF）/Binance（BTC）兜底」自动降级；目录按用户规格定为 work/（原计划 output/cache）。
  review 修订：ImpactRating 改英文常量（中文经 label() 输出）；UA 完整 Chrome 形态；退避加 8s 上限与 ±25% 抖动。
  验收：15 用例全绿（全 mock）；NVDA 1254 根、拆股前后 120.888→121.79 连续；GLD/BTC 兜底链路验证通过。

- [x] **R2 技术分析技能（0.75h，确定性不调 LLM）**
  analysis 包：AnalysisConfig（13 阈值 properties 化，-D 可覆盖）+ Indicators 纯函数（MA/RSI/波动率/放量）+ PivotDetector 四类信号（局部极值 n=5 / MA20×60 交叉 / zig-zag 趋势段 / ±5% 单日）+ AnalysisMain。
  说明：原计划的 R2 资讯检索（GDELT 兜底）未单列——事件检索通道 R1 已含 HackerNewsProvider，本轮按用户规格实现技术分析。
  验收：25 用例全绿；三锚点（ChatGPT +8.21% / GTC -5.55% / DeepSeek -16.97% 放量）默认阈值全部命中，人工对照合理。

- [x] **R3 Agent 编排层（1h）**
  agent 包三层：tools（RippleTools + work/ 检查点）、subagent（MarketData/NewsEvent/CorrelationAnalyst/Render 占位，各自 AiServices + system prompt + tool 门面）、orchestrator（默认顺序循环 + agent-as-tool 的 --orchestrate 模式）；LlmModels（DeepSeek OpenAI 兼容，key 只读 env）、LlmAligner（schema 校验：URL 必属证据集合）、RuleBasedAligner（--no-llm）。
  review 修订：子 agent 工具边界从 prompt 约束升级为 tool 门面强制（MarketData/News/AlignmentTools）；--no-llm 经空目录+清 key 端到端复验独立可跑。
  验收：28 用例全绿；NVDA 全流程 24 窗口 1016 条候选 → 对齐 20 条每条含 URL；断点续跑 0 网络请求。

- [x] **R4 关联逻辑强化与可溯源（1h）**
  EventMark 必带七字段（标题/日期/URL/摘要/评级/置信度/一句话推理）+ correlation 分档（≥0.65 强 / ≥0.40 相关 / <0.40 弱）+ MissingEvent（事件缺失只列候选禁编造）+ alignments.json schemaVersion=2 + --verify 溯源抽查模式。
  关键修复：词典硬约束（无主题命中封顶 0.39）；窗口内 URL 去重与同日拐点去重；HN 500 窗口级二次重试；缺失阈值 -D 可调。
  验收：30 用例全绿；归因 24 条/缺失 0；URL 抽查 3/3 实开且内容一致（CNBC/Investors/Verge）；必带字段零缺失。

- [x] **R5 可交互可溯源 K 线 HTML（1.25h）**
  render 包 + templates/report.html + static/echarts.min.js：candlestick + MA + 成交量副图 + dataZoom、三标的下拉切换、事件标记三件套（散点/markLine/markArea）、tooltip 含来源链接（新窗口）、溯源面板按评级筛选、CSP 与 `</` 转义防注入、单文件断网可开；可选 PreviewServer。
  review 修订：footer 移除生成时间；全仓包结构类型单一化（domain/enums、agent/api、agent/tools、analysis/model、dataprovider/api，清理 6 个空目录）。
  验收：31 用例全绿；三标的 116 条归因单文件 1.7MB、0 外链资源；PreviewServer 冒烟通过。

- [x] **任务 1 完整回归（追加）**
  发现并修复 GTC/B100 锚点缺口：拐点选择改「每季度显著性 Top-3 + 全局 48」时间全覆盖策略。
  验收：吻合率 **95.8%**（±3 交易日，验收线 70%）；三锚点 3/3；URL 抽查 3/3；断点续跑 0 网络请求。副作用如实记录：GLD 缺失标注增至 28（缺失机制本身是 R4 需求）。

- [x] **R6 黄金 vs 比特币三件套（1.5h）**
  artifacts 包：PortfolioStats（commons-math3：CAGR/波动/回撤/夏普/Pearson 相关/权重组合）+ ChartPng（Java2D 静态图，POI 图表弱的取舍已注释）+ ExcelWriter（5 sheet 含 Sources）+ PptxWriter（5 页）+ DocxWriter（结论先行）；BuildArtifactsMain 一条命令（--gold-weight 可配）。
  关键修复：相关矩阵转置 bug；PPT 表格图片锚点重叠；SPY 折线因 HashMap 迭代序被画成金色（review 确认"图表非空白占位"时像素级核验发现）。
  验收：指标独立复算 3/3 逐位一致（GLD CAGR 0.192168 / BTC 回撤 0.766293 / 相关 0.139446）；三件套 zip+XML 良构并在 Office 打开。

- [x] **R7 工程收尾（1h）**
  README 重写（一键 run-all + run.cmd + 5 条 FAQ）；DESIGN.md（四层架构 ASCII / 职责表 / 五项取舍 / 溯源设计）；DEVLOG 补 AI 参与总览；补 5 个测试类 17 用例（固化历轮缺陷）；GateIoProvider 二级兜底（Binance 网络波动，999 天分段修复 Gate.io 400）；runAll 一键入口（单标的失败不中断）。
  验收：46 用例全绿；`mvn -q clean package` 从零通过；**run-all 从零（删 target/work/output/artifacts）真实网络完整跑通**——三标的归因 48/19+29 缺失/48、HTML 1.7MB 三锚点全命中、三件套产出，退出码 0。

- [x] **R8 安全审查 + 演示彩排（0.5h，只查不改）**
  密钥全仓审查（src/pom/md/target/work/jar 内 class 零泄漏）；前端安全（0 外链/CSP/URL 白名单/noopener/注入转义）；健壮性三路径（429 退避 / LLM 90s 超时降级 / --no-llm 离线实测）；简洁性（11 包类型单一、依赖无环、死代码 2 处记录）；demo.md 3 分钟脚本；DESIGN.md 附检查者视角 checklist。
  验收：checklist **21/21 全绿**；断网渲染实测可用（0 网络请求）；唯一改动为 .gitignore 补 `*.local.properties`。

- [x] **R9 LLM 真实路径端到端验证与修复（追加轮）**
  动因：DEEPSEEK_API_KEY 配置到位后实测 LLM 归因路径，暴露 4 个阻断缺陷（5 个 agent 接口缺 @UserMessage / 工具参数名 arg0 / 整标的输入超模型上下文与输出上限 / ObjectMapper 缺 JavaTimeModule）；用户 review 发现 javadoc 承诺"解析失败由上层降级规则对齐"从未实现。
  关键变更：LlmAligner 重构为单拐点分片（alignOne：1 拐点 + 规则分预筛 top-8 候选，证据随用户消息下发）；散文包裹 JSON 提取兜底；丢弃/空返回回填"事件缺失"（拐点守恒 48）；RippleOrchestrator 补上层降级（LLM 归因为空 → 规则对齐，mode 如实落盘）；pom 补 maven.compiler.parameters；测试 46 → 48。
  验收：真实 key `mode=llm` 26 归因 + 22 缺失 = 48，URL 26/26 溯源、三锚点归因正确；无效 key 整体降级实测（48 次 401 → `mode=rule` 48 归因，退出码 0）；HTML 重渲染。双路径差异分析记入 DESIGN.md 取舍 6；遗留：--orchestrate 路径待分片改造后实测。

- [x] **R10 产物版式修复 + 从零全链路回归（追加轮，2026-09-09）**
  版式修复（用户实开产物发现）：PPT 组合页表格列宽显式分配（默认 100pt/列不受 anchor 约束致溢出压图）+ 图片右移；Excel 表头填充 RGB 误传调色板索引截断成 indexed=-4364 黑底——改 XSSFColor。新增 PptxLayoutTest/ExcelStyleTest（TestSnapshots 共享），48 → 50 用例。
  验收：`mvn -q clean verify` 全绿；从零 run-all（带 key）退出码 0——NVDA `mode=llm` 29+19=48 守恒、URL 溯源 96/96、三锚点命中、吻合率 96.6%；双路径同证据集对比印证取舍 6（LLM 29 ⊆ 规则 48，R3 经典误标场景 LLM 拒绝归因）。

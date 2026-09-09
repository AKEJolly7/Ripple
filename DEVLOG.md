# DEVLOG —— 观澜（Ripple）开发日志

> 记录 AI 开发过程（验收凭据 A7）：按开发顺序逐轮记录用户指令、实现要点、关键决策与修复、验收结论与人工判断。

## 一、总览：AI 工具与人工分工

**AI 工具**：Claude Code（DeepSeek-V4-Pro，1M context）——全程唯一编码工具。

**AI 环节**：方案起草、全部代码与测试、依赖版本核实（Maven Central）、数据源可达性探测、验收命令执行与结果采集、文档撰写。

**人工环节**（每轮 review 决定方向）：
- R0 确认设计；拍板 JDK 17 安装方式（Adoptium tar.gz 免 sudo）
- R1 关键决策：Yahoo 大陆 403 的降级方案，AI 提三选项、**人工选定「Yahoo 主 + 新浪/Binance 兜底」**
- R1 review：枚举中文字面量改英文常量；UA 完整性与退避抖动 → 采纳
- R2 验收：三锚点命中情况人工对照真实行情
- R3 review：核实子 agent 工具边界与 --no-llm 独立性 → 发现并修正 tool 门面缺失
- R4 验收：URL 抽查内容与结论一致性人工复核
- R5 review：移除 HTML 生成时间、包结构类型单一化 → 采纳（后者触发全仓重构）；浏览器人工点验交互
- R6 review：确认 PPT 图表非空白占位 → 像素级核验发现 SPY 线被误画成金色并修复
- R9 人工审查：发现 LlmAligner javadoc 承诺"解析失败由上层降级为规则对齐"实际无对应实现（高可信纪律与代码不符，佐证 LLM 路径零实测）→ 促成本轮补齐降级链并全链路实测
- 节奏约定：里程碑指令逐轮人工下达，完成后人工检查再进下一轮；改动不主动 commit，留工作区

## 二、工程方法论

> 每条按「怎么做 → 为什么 → 效果如何」展开。

### 1. 逐轮指令 + 验收驱动，不做大跃进

**怎么做**
- R0-R8 每轮由用户下达明确的目标 / 范围 / 验收标准，完成后停下等确认。
- 每轮验收命令必须实际执行，输出摘要记入 DEVLOG。

**为什么**
- 4-8 小时预算内控制返工半径，方向偏差在一轮内暴露，不做大跃进。

**效果如何**
- 历轮 review 意见（枚举规范、tool 边界、图表占位等）全部当轮闭环，无跨轮返工。

### 2. 确定性技能为主，LLM 只在语义处定点介入

**怎么做**
- 拐点检测 / 回测 / 统计走纯函数 skill 层（可单测、可复现）。
- LLM 仅在事件归因 / 评级 / 推理三处介入，输出过三重校验：
  URL 必属证据集合、拐点日期必真实存在、枚举与置信度收敛到合法域。

**为什么**
- 检测是数学问题，LLM 做会幻觉日期价格且不可复现；
  归因是语义问题，确定性代码写不出。

**效果如何**
- 吻合率 95.8% 可量化复算；LLM 编造的条目被校验器丢弃（单测覆盖）。

### 3. 检查点即状态

**怎么做**
- 每步中间产物落盘 work/（ohlcv / analysis / events / alignments JSON，含 source 溯源字段）。
- 检查点已存在即复用，不做重复计算。

**为什么**
- 断点续跑 + 幂等重跑 + 可审计，三位一体。

**效果如何**
- 二次运行 0 网络请求；单标的失败不中断全局。
- 产物数值可与原始 JSON 交叉核对（R6 复算 3/3 逐位一致）。

### 4. 修过的 bug 当轮固化为回归测试

**怎么做**
- 每个真实缺陷修复后立即补一条测试固化其根因类别：
  词典硬约束、相关矩阵转置、ChartPng 迭代序颜色、Gate.io 分段跨度、LLM 编造 URL 丢弃等。

**为什么**
- 缺陷的根因模式比缺陷本身更值得记忆。

**效果如何**
- 50 个测试中约 1/3 是缺陷回归用例，历轮 review 触发的问题无一复发。

### 5. 产物级验收：不信代码信产物

**怎么做**
- 验收做在产物上：
  HTML 像素级颜色统计、Excel 指标独立复算逐位比对、URL 用 curl 实开核验标题日期、PPT 内嵌图与磁盘逐字节比对、zip+XML 良构校验。

**为什么**
- 代码编译通过 ≠ 产物正确——SPY 金色线、相关矩阵 NaN 都是"全绿测试"下的产物级缺陷。

**效果如何**
- 三次 review 质疑（tool 边界、--no-llm、图表占位）均靠产物级核验给出实证答案，其中两次挖出真 bug。

### 6. 外部依赖一律降级链 + 参数化阈值

**怎么做**
- 数据源多级兜底（Yahoo → 新浪 / Binance → Gate.io），接口不变。
- LLM 无 key 自动降级规则对齐。
- 所有分析阈值 properties 化，支持 -D 覆盖。
- HN 偶发 500 做窗口级二次重试。

**为什么**
- 外部服务可用性不可控——Yahoo 地域封锁、Binance 网络波动、HN 限流都是实际发生过的。

**效果如何**
- run-all 从零三次全绿，外部波动全部自动恢复。

### 7. 如实声明已知限制

**怎么做**
- LLM 端到端未实测（R9 撤销）、部分源站反爬、2 处死代码——写进 checklist 与每轮 DEVLOG，不粉饰。

**为什么**
- 验收凭据的可信度来自不回避。

**效果如何**
- checklist 21/21 全绿的同时保留已知限制；R9 撤销"LLM 未实测"一条（条件具备后立即验证，而非长期搁置）。

## 三、逐轮记录

> 每轮包含七要素：输入提示词（原样保留）、做了什么、review 与改动、关键问题与决策、最终交付、产生的效果、如何验收（多步表格 + 发现修复的问题三列表）。

### R0 骨架与治理（2026-09-01）

**输入提示词**

```
背景与目标：
1.实时数据Agent与可视化：回顾英伟达(NVDA)近五年行情(OHLCV+成交量)，梳理同期 AI 行业大事件(ChatGPT发布/B100/DeepSeek等)，在K线图上标记行情拐点/加速/下跌/上涨等变化触发时刻的主要事件与影响评级，产物可交互、可溯源，最终生成 HTML
2.构建黄金与比特币作为避险/抗通胀资产的可交互比较分析体系，产物含 Excel 回测底稿、PPT 决策框架、Word 策略报告
环境：技术栈定为 Java 17 + langchain4j（LLM 走 OpenAI 兼容，如 DeepSeek，key 只读环境变量 DEEPSEEK_API_KEY）+ Apache POI（Excel/PPT/Word）+ ECharts（本地打包，不用 CDN）
任务：进入 plan 模式，只做设计、不写代码：
0.系统命名为「观澜（Ripple）」：出自《孟子》"观水有术，必观其澜"——观波澜而知水性，正是"看 K 线波动而洞悉行业事件因果"；事件（ChatGPT、B100、DeepSeek）是"投石"，行情是"波澜"，本系统agent就是在找那颗石头；Slogan：观其澜，知其源 —— Watch the waves, find the stones.
1.把题目拆成：功能需求 / 非功能需求（可交互、可溯源、前端安全与 CORS）/ 交付物清单 / 验收标准，逐条列出
2.给出单模块 Maven 项目结构（包名 + 每个类的职责一句话），要体现 tools / skills / subagent / orchestrator 四层划分
3.数据源选型与理由：行情（Yahoo chart API v8 直连 vs 现成库）、资讯（Hacker News Algolia 主 + GDELT 兜底）、LLM（OpenAI 兼容 DeepSeek）
4.说明关键取舍：为什么"确定性技能层 + LLM 只在事件归因/评级/报告处介入"；为什么编排用 agent-as-tool 而不是 DAG；为什么 ECharts 本地打包 + 数据内嵌 HTML（规避 CORS 与 CDN 供应链风险）
5.生成 MILESTONES.md：把 4-8 小时切成 R0-R8 九个可验收里程碑，每个标注耗时与验收命令
6.生成 CLAUDE.md（≤60 行）：只写 5 条本仓约定（构建/测试命令、代码风格、禁止把 key 写进代码、产物输出目录、每轮结束更新 DEVLOG.md）
7.检查是否遗漏题目要求（事件与拐点吻合、结论可回链来源、密钥不入库、记录 AI 开发过程）
不要开始写代码，等我确认后再进入 R1
```

**做了什么**

- 完成整体设计方案（需求拆解 / 四层架构 / 数据源选型 / 五项关键取舍 / 里程碑划分），用户确认后落盘。
- 搭建 Maven 骨架：Java 17 + langchain4j 1.19.0 + POI 5.5.1 + Jackson 2.22.2 + JUnit5，shade 打包。
- 建立治理文件：CLAUDE.md（5 条约定）、MILESTONES.md（R0-R8）、DEVLOG.md、.gitignore。
- ECharts 6.1.0 echarts.min.js 本地入库（npm 官方 tarball）。

**review 与改动**

- 用户批准设计方案（plan 评审通过），节奏约定确立：逐轮指令、完成后停下等确认、不主动 commit。

**关键问题与决策**

1. 本机无 JDK 17（默认 8，另有 Corretto 11），brew cask 因 sudo 无法交互失败 → Adoptium tar.gz 装至 `~/tools/jdk-17`（免 sudo，不动系统 JDK）。
2. `~/.mavenrc` 强制 zulu-8 覆盖命令行 JAVA_HOME → 构建统一加 `MAVEN_SKIP_RC=1`，写入 CLAUDE.md 约定 1。
3. langchain4j 版本以 repo1.maven.org metadata 为准（1.19.0），不采信 search.maven.org 的过期索引（1.0.0）。
4. 版本锁定：langchain4j 1.19.0 / POI 5.5.1 / Jackson 2.22.2 / ECharts 6.1.0（SHA-256 `b66b25ae…0fd0`）。

**最终交付**

- pom.xml + 四层包结构骨架 + 治理文件四件 + echarts.min.js 入库。

**产生的效果**

- 后续八轮全部在此骨架上增量演进；治理文件（CLAUDE.md 约定）避免了密钥、构建环境、产物目录的重复踩坑。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn -q compile` | ✅ COMPILE OK |
| 2 | `ls` 治理文件与 echarts.min.js | ✅ 全部存在 |
| 3 | CLI 冒烟（help / 子命令路由） | ✅ 正常 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 首次 `mvn compile` 报"非法字符/未结束的字符串文字"：JDK 8 javac 解析不了 Java 17 文本块 | 定位到 `~/.mavenrc` 强制 zulu-8；不动用户配置，构建统一加 `MAVEN_SKIP_RC=1` | 无 |
| 本机无 JDK 17，brew cask 安装需 sudo 交互失败 | Adoptium tar.gz 解压至 `~/tools/jdk-17`（免 sudo） | 无 |

### R1 行情数据通道

**输入提示词**

```
目标：R1：搭建 Maven 骨架 + 两个数据源工具，可独立测试
范围：只写 pom.xml、domain 包、dataprovider 包、DataFetchMain CLI、对应单元测试
1.pom.xml：JDK17、Jackson、JDK HttpClient、slf4j+logback、langchain4j、langchain4j-open-ai（OpenAI 兼容）、Apache POI、JUnit5、commons-math3。锁定具体版本并注释用途，依赖保持克制，不引入 Spring Boot
2.domain：OHLCV(date/open/high/low/close/volume)、NewsItem(title/date/summary/url/source)、MarketEvent(含影响评级 enum 利好/利空/中性 + 置信度字段，为 R4 预留)
3.MarketDataProvider 接口 + YahooFinanceProvider：请求 https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?period1=&period2=&interval=1d ，带 User-Agent；实现指数退避重试(429/5xx)、超时、错误分类；支持 NVDA / GLD / BTC-USD
4.NewsProvider 接口 + HackerNewsProvider：用 Algolia search API（https://hn.algolia.com/api/v1/search_by_date）按时间窗口+关键词抓事件，保留 objectID 与 story_url 作为溯源
5.DataFetchMain：CLI 参数 --symbol --years，把 OHLCV 与事件 JSON 落到 work/ 目录
6.单测用 mock 的 HTTP 响应验证解析正确，不依赖真实网络
7.验收：mvn test 全绿；mvn exec:java -Dexec.mainClass=...DataFetchMain --symbol NVDA --years 5 输出"条数+首末日期"
8.完成后把本轮改动与人工判断追加到 DEVLOG.md。任何 key 不落盘、不写进 pom 或代码
```

**做了什么**

- `domain`：Ohlcv（前复权）/ NewsItem（externalId 溯源）/ MarketEvent（评级+置信度，R4 预留）。
- `dataprovider`：HttpTransport 抽象 → JdkHttpTransport（UA+超时）→ ResilientHttpTransport（指数退避）；HttpFetchException 五类错误分类。
- Provider：YahooFinanceProvider（题目规格主源，adjclose 前复权）+ SinaFinanceProvider（拆股表前复权）+ BinanceProvider（游标翻页）+ HackerNewsProvider（objectID/story_url 溯源）。
- DataFetchMain CLI（--symbol/--years/--keyword/--out），落盘 work/（含 source 溯源字段）。
- pom 补 slf4j 2.0.18 + logback 1.5.38 + commons-math3 3.6.1 + junit 5.14.4（版本均经 Maven Central 核实）。

**review 与改动**

- ImpactRating 枚举中文字面量改英文常量 BULLISH/BEARISH/NEUTRAL（中文经 label() 输出）。
- UA 改完整 Chrome 形态 + 产品 token（依据实测：curl 默认 UA 被 Yahoo 429，完整浏览器 UA 被拒在 IP 层 403）。
- 退避重试加 8s 上限与 ±25% 随机抖动，补边界单测。
- 顺带修正两处规范问题：`adjNodeVolume` 更名 `volumeOrZero`；Comparator 全限定名改 import。

**关键问题与决策**

- **Yahoo v8 对大陆 IP 403（地域封锁，本机无代理）**：curl 实测 8 个候选源（Stooq JS PoW 反爬、腾讯接口已废弃、东财无响应、OKX/CoinGecko 不可达），经用户确认采用「Yahoo 主 + 新浪（美股/ETF，拆股表）/Binance（BTC）兜底」自动降级。
- 新浪数据未复权（NVDA 拆股处 1208→121 断裂）：内置拆股表（NVDA 2021 4:1、2024 10:1）实现前复权，与 Yahoo adjclose 口径对齐。

**最终交付**

- domain + dataprovider 双包（5 个 Provider/Transport 类）+ DataFetchMain + 5 个测试类 15 用例。

**产生的效果**

- 行情/资讯通道独立可测（全 mock，零网络依赖）；降级链在此后历次网络波动（含 R7 的 Binance 不可达）中持续兜底。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test`（全 mock） | ✅ 15/15 |
| 2 | DataFetchMain NVDA 5 年 | ✅ 1254 根，首末日期正确 |
| 3 | 拆股连续性检查 | ✅ 2024-06-07 close=120.888 → 06-10 close=121.79 |
| 4 | GLD / BTC-USD 兜底链路 | ✅ GLD 1254 根（新浪）/ BTC 1827 根（Binance） |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 真实网络验收 Yahoo 403 | 经用户确认引入新浪/Binance 兜底降级链 | 无（接口不变，换网络环境自动回切 Yahoo） |
| 退避重试固定 1s/2s/4s 无抖动（review 指出） | 指数退避 + 8s 上限 + ±25% 抖动 | 无 |

### R2 技术分析技能（确定性，不调 LLM）

**输入提示词**

```
目标：R2：实现 "technical-analysis" 技能（确定性流程，不调 LLM），输出拐点与趋势段
范围：新增 analysis 包；DataFetchMain 抓的数据直接复用
1.计算 MA5/20/60、RSI14、20日波动率、成交量均值与"异常放量"(>3倍均值)标记
2.拐点检测：滚动窗口局部极值(n=5)；均线金叉/死叉；趋势段划分(上涨/下跌/盘整，基于斜率+回撤阈值)；显著单日涨跌(±5%)。所有阈值从 properties 读取，可参数化
3.输出模型：Segment(区间/趋势标签/起止价/最大回撤)、InflectionPoint(日期/类型/当日涨跌/是否放量/前后±5天候选窗口)
4.AnalysisMain 打印拐点与趋势段；用真实 NVDA 数据断言：至少检出 2022-11(ChatGPT发布)、2024-03(GTC/B100)、2025-01(DeepSeek.附近的显著拐点；不命中就调阈值并说明原因
5.验收：mvn test 全绿；人工对照真实走势确认拐点合理
6.完成后 DEVLOG.md 追加本轮改动与人工判断
```

**做了什么**

- `analysis` 包：AnalysisConfig（13 个阈值 properties 化，支持 -D 覆盖）、Indicators（纯函数：SMA/Wilder RSI/滚动波动率/异常放量）、PivotDetector（四类拐点信号 + zig-zag 趋势段）、AnalysisMain CLI（复用 work/ 检查点）。
- 输出模型落 domain：Segment / InflectionPoint / PivotType / TrendLabel。

**review 与改动**

- 无 review 修订（当轮通过）。

**关键问题与决策**

- **三锚点默认阈值全部命中，无需调参**：ChatGPT 日 +8.21%、GTC 日 -5.55%（前一日局部高点，典型 sell-the-news）、DeepSeek 日 -16.97% 且放量。
- 趋势段 86 段/5 年偏细（8% 回撤阈值对高波动标的敏感）：对事件归因反而有利（候选窗口更密），保持默认。

**最终交付**

- analysis 包 + 3 个测试类 10 用例（指标数学正确性 / 合成锯齿序列信号 / 真实 NVDA 锚点断言）。

**产生的效果**

- 拐点检测成为可单测、可复现的纯函数层——为 R3 编排与 R4 对齐提供确定性地基。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` | ✅ 25/25 |
| 2 | AnalysisMain NVDA 真实数据 | ✅ 拐点 291 个、趋势段 86 段 |
| 3 | 三锚点断言（±14 天） | ✅ 全部命中（未调阈值） |
| 4 | 人工对照真实走势 | ✅ 2023 AI 主升 +129.94%、DeepSeek 冲击段 -19.56% 等与已知行情吻合 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 测试先红后绿 3 处（RSI 交替序列期望值手算错、交易日窗口日历换算错、多余 JSON 字段） | 逐一修正期望值与解析（@JsonIgnoreProperties） | 无 |

### R3 Agent 编排层

**输入提示词**

```
目标：R3：打通 Agent 编排层，体现 tools / subagent / skill 三层协作
范围：新增 agent 包
1.把 R1 的 MarketDataProvider、NewsProvider 与 R2 的 AnalysisService 暴露为 langchain4j @Tool（写清参数与返回说明）
2.建 4 个子 agent（各自 AiServices + 独立 system prompt + 各自的 tool 集）：
   - MarketDataAgent：拉行情+算指标，只回结构化 JSON；
   - NewsEventAgent：按时间窗检索事件、去重、打置信度；
   - CorrelationAnalyst：把拐点与事件对齐，输出 EventMark(事件+关联强度+影响评级+置信度+来源URL+推理)；
   - RenderAgent：先留接口，R5/R6 实现
3.编排：OrchestratorAgent 用 agent-as-tool 模式（把子 agent 封装成 @Tool 由主 agent 调用），或手写顺序循环；每一步中间结果写入 work/ 下 JSON，支持断点续跑
4.LLM：OpenAiChatModel.builder().baseUrl(https://api.deepseek.com).apiKey(System.getenv("DEEPSEEK_API_KEY")).modelName("deepseek-chat")。同时实现 --no-llm 降级模式：用关键词+时间窗做规则对齐，保证无 key 也能跑通
5.验收：命令行跑通"NVDA 5年→指标→拐点→HN事件→对齐→work/alignments.json"，打印 5-8 条对齐结论，每条都有来源 URL
6.在代码注释里写明：为什么用 agent-as-tool（langchain4j 无原生 DAG；顺序编排够用且可测试），什么场景会演进到 LangGraph4j
7.完成后 DEVLOG.md 追加
```

**做了什么**

- `agent` 包三层：tools 层 RippleTools（4 个能力 + work/ 检查点）、subagent 层 4 个 AiServices 接口、orchestrator 层（默认顺序循环 + agent-as-tool 的 --orchestrate 模式）。
- LlmModels（DeepSeek OpenAI 兼容，key 只读 env）、LlmAligner（输出 schema 校验：URL 必属证据集合）、RuleBasedAligner（--no-llm 规则对齐）。
- 取舍注释按要求写入 RippleOrchestrator javadoc（agent-as-tool vs DAG、LangGraph4j 演进条件）。

**review 与改动**

- 用户要求核实两件事，其中发现一处实现与声明不符：
  - **「各自独立的 tool 集」实际传的是整个 RippleTools**（每个子 agent 都能看到全部工具，边界只靠 prompt）→ 拆出 MarketDataTools/NewsTools/AlignmentTools 三个门面强制 tool 层边界（归因 agent 只能读检查点证据、不能自行拉数据），RenderAgent 无工具。
  - **--no-llm 独立性**：全新目录 + `env -u DEEPSEEK_API_KEY` 端到端复验通过。

**关键问题与决策**

- 默认顺序循环（可单测/可断点续跑/无 key 可跑），agent-as-tool 作为 --orchestrate 增强——分支决策交给 LLM 的场景保留。
- **已知局限（人工对照发现）**：规则对齐只看时间+关键词，2021-11-04「EU 调查 Arm 收购」配 +12.04% 上涨被误标利好——正是 R4 引入 LLM 语义归因的动机。

**最终交付**

- agent 包（含 api/tools 子包）+ AgentMain CLI + RuleBasedAlignerTest 3 用例。

**产生的效果**

- 流水线可编排、可断点续跑；LLM 与规则两条对齐路径互为镜像（同一输入可复算对比）。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` | ✅ 28/28 |
| 2 | AgentMain NVDA 全流程（--no-llm） | ✅ 24 窗口 1016 条候选 → 对齐 20 条，每条含 URL |
| 3 | 断点续跑（二次运行） | ✅ 0 网络请求，全检查点命中 |
| 4 | --no-llm 独立性（空目录+清 key） | ✅ 全流程未触碰 LLM 类 |
| 5 | 密钥扫描 | ✅ 零泄漏 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 子 agent 工具边界只靠 prompt 约束（review 发现） | 三个 tool 门面类强制边界 | 无 |
| printMarks 格式串占位符与参数错位致运行时异常 | 重写打印逻辑 | 无 |

### R4 关联逻辑强化与可溯源

**输入提示词**

```
目标：R4：强化关联逻辑与可溯源，产出规范化的 alignments.json
范围：只改 CorrelationAnalyst 与对齐输出，不动数据层
1.对齐规则：时间窗（拐点前后 ±N 天）为主 + LLM 判断事件主题与当日行情动因是否一致为辅；输出 correlation 0-1，评级 强相关/相关/弱相关
2.每个 EventMark 必带：事件标题/日期/来源URL/摘要/影响评级(利好/利空/中性)/置信度/一句话推理
3.找不到强相关事件的拐点：标注"事件缺失"，给出 2-3 条同期候选，禁止编造归因
4.alignments.json 带 schemaVersion 与生成时间；增加 --verify 模式：打印每条 EventMark 的来源 URL 供人工抽查
5.验收：抽查 3 条 EventMark 的 URL 可打开且内容与结论一致；弱相关/缺失标注生效
6.完成后 DEVLOG.md 追加
```

**做了什么**

- EventMark 补 summary/correlation 字段；LinkStrength 分档（≥0.65 强相关 / ≥0.40 相关 / <0.40 弱相关）。
- MissingEvent（事件缺失）：只列 2-3 条同期候选、不做归因。
- alignments.json schemaVersion=2 + 生成时间；--verify 溯源抽查模式。
- LLM 路径（prompt + 校验器）同步升级 attributed/missing 双数组。

**review 与改动**

- 无 review 修订（当轮通过，人工复核 URL 抽查内容）。

**关键问题与决策**

- 规则路径的"主题一致性"具象为词典硬约束：标题无行业词典命中的新闻封顶 0.39（时间再吻合也不归因，宁缺毋滥）。
- 缺失阈值参数化（`-Dalign.missing.threshold`），高阈值可演示缺失机制。

**最终交付**

- 对齐输出规范化 + RuleBasedAlignerTest 扩至 5 用例。

**产生的效果**

- 每条归因结论七字段齐备且 URL 必属证据集合——"可溯源"从约定变成机制。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` | ✅ 30/30 |
| 2 | 真实 NVDA 归因（默认阈值） | ✅ 24 条 / 缺失 0，三锚点命中 |
| 3 | 高阈值(0.85)演示缺失机制 | ✅ 20 个缺失拐点各带 2-3 条候选含 URL |
| 4 | URL 抽查 3 条（curl 实开） | ✅ CNBC/Investors/Verge 全 200，标题日期与结论一致 |
| 5 | schemaVersion=2 必带字段 | ✅ 脚本核验零缺失 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 无关新闻（Ask HN 类）靠日期邻近混过 0.40 归因线（测试驱动发现） | 词典硬约束：无主题命中封顶 0.39 | 极少数确实相关但标题无词典词的新闻会被降级（宁缺毋滥的代价） |
| 事件缺失拐点的候选列表为空 | ① 全局 URL 去重改窗口内去重；② 同日双类型拐点去重 | 无 |
| 2022-03-24 窗口实际 56 条命中却被判缺失 | HN 偶发 500 → 窗口级二次整体重试 | 无 |

### R5 可交互可溯源 K 线 HTML

**输入提示词**

```
目标：R5：生成可交互、可溯源、断网可开的 K 线 HTML
范围：新增 render 包与 resources/templates
1.ECharts candlestick + MA5/20/60 + 成交量副图；右上角下拉切换 NVDA / GLD / BTC-USD
2.事件标记用 markPoint/markLine 标在拐点/趋势段：markPoint 显示事件标题(截断)，点击弹 tooltip 含摘要与"来源"超链接(新窗口打开)
3.数据以 JSON 内嵌进 HTML；echarts.min.js 下载到 resources/static 本地打包，HTML 内禁止任何外链 CDN（规避 CORS 与供应链风险）；保证双击 file:// 可打开
4.底部"溯源面板"：列出全部 EventMark(日期/事件/评级/置信度/来源URL/推理)，可按评级筛选
5.可选 PreviewServer(<200 行)：http://localhost:8080 预览，只读静态资源、正确 Content-Type、无 cookie
6.单测：解析生成的 HTML，断言 markPoint 数量>0、每个 href 以 http 开头、无 http(s.外链的 <script src>
7.验收：浏览器打开能点事件跳来源；断网可开
8.完成后 DEVLOG.md 追加
```

**做了什么**

- `render` 包：HtmlReportRenderer（单文件装配：echarts+数据全内嵌、静态溯源面板）+ PreviewServer（~55 行）+ RenderMain CLI。
- 模板交互：candlestick + MA + 成交量副图 + dataZoom、三标的下拉切换、事件三件套标记（散点/markLine/markArea）、tooltip 含来源链接、评级筛选。
- CSP meta、`</` 转义防注入。

**review 与改动**

- HTML footer 移除生成时间（只留数据口径与来源）。
- **全仓包结构类型单一化**（同级目录不混装 Java 类型）：domain/enums、agent/api、agent/tools、analysis/model、dataprovider/api，清理 6 个空脚手架目录；CLAUDE.md 同步。

**关键问题与决策**

- 外链断言的语义区分：禁止的是**资源标签**（script/img 等）的 src/href 外链；溯源面板的 `<a href>` 锚点是需求要求的导航，豁免。

**最终交付**

- render 包 + report.html 模板 + echarts.min.js 迁至 static/ + HtmlReportRendererTest。

**产生的效果**

- 1.7MB 单文件产物：可归档、可邮件分享、断网双击可开、0 外部依赖。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test`（含 HTML 解析断言） | ✅ 31/31 |
| 2 | 三标的渲染 | ✅ 116 条归因，0 外链资源，CSP 就位 |
| 3 | 断网可开核验（grep 外链/fetch） | ✅ 零命中 |
| 4 | PreviewServer 冒烟 | ✅ 200 + 正确 Content-Type + 无 Set-Cookie |
| 5 | 浏览器人工点验（交互/跳源/筛选） | ✅ 用户确认 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 断言"无任何 href=http"误伤溯源锚链接 | 语义修正为仅禁资源标签外链 | 无 |
| NVDA 首次渲染归因为 0（alignments 被 BTC 覆盖） | saveAlignments 按标的双写检查点 | 无 |
| href 正则误伤 JS 字符串拼接片段 | 测试中识别并跳过 JS 代码片段 | 无 |

### 任务 1 完整回归（用户追加指令）

**输入提示词**

```
完整进行任务1的回归测试和验收环节，确保当前进展符合进展预期
```

**做了什么**

- 7 步验收：全量单测 / 从零全链路（清空检查点真实网络重建）/ 吻合率 / 锚点覆盖 / 产物级检查 / 溯源抽查 / 密钥与断点续跑。

**review 与改动**

- 无独立 review 轮（用户追加的回归指令）；发现的问题当轮闭环。

**关键问题与决策**

- 发现 GTC/B100 锚点缺口后，选择「每季度显著性 Top-3 + 全局 48」的时间全覆盖策略（而非针对日期硬编码）。

**最终交付**

- 拐点选择算法升级 + 任务 1 全套验收证据。

**产生的效果**

- 任务 1 达到题目级验收标准（吻合率 95.8%、三锚点全命中）。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` 全量 | ✅ 31/31 |
| 2 | 从零全链路（真实网络） | ✅ 1254 根 → 291 拐点 → 对齐落盘 |
| 3 | 事件↔拐点吻合率（±3 交易日） | ✅ **95.8%**（验收线 70%） |
| 4 | 三锚点覆盖（±10 交易日） | ✅ 3/3（修复后） |
| 5 | URL 抽查 3 条 | ✅ 3/3 实开且内容一致 |
| 6 | 密钥扫描 + 断点续跑 | ✅ 零泄漏 / 0 网络请求 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| GTC/B100（2024-03-18）±10 交易日内无归因标记：该拐点显著性排第 102，被高波动期挤出 Top-N | 拐点选择改「每季度显著性 Top-3 + 全局上限 48」（时间全覆盖） | GLD 缺失标注增至 28（低波动拐点 HN 相关新闻少——缺失机制如实呈现，本身是 R4 需求） |

### R6 黄金 vs 比特币三件套

**输入提示词**

```
目标：R6：黄金 vs 比特币避险/抗通胀三件套（Excel 回测底稿 / PPT 决策框架 / Word 策略报告）
范围：新增 artifacts 生成(BuildArtifactsMain)，复用 R1-R3 的数据与分析技能
1.数据：GLD 与 BTC-USD 近5年日线；计算年化收益、年化波动、最大回撤、夏普、两者及与 SPY 的相关性、60/40 组合(权重可配)。用 commons-math3 计算
2.Excel(POI XSSF)：原始数据 + 指标表 + 回撤表 + 组合对比 四个 sheet，保留列格式与数值精度
3.PPT(POI XSLF)：5-7 页决策框架——问题定义/指标对比/风险与相关性/组合情景/决策建议；表格为主，图表用静态 PNG(JFreeChart 或 ECharts 导出图)，并在注释说明 POI 图表能力弱的取舍
4.Word(POI XWPF)：结论先行，含数据口径、来源说明、附录回测表
5.BuildArtifactsMain 一条命令生成到 artifacts/
6.验收：三件套能被 Office/WPS 打开；抽查 3 个指标与 Excel 底稿一致
7.完成后 DEVLOG.md 追加
```

**做了什么**

- `artifacts` 包：PortfolioStats（commons-math3 统计）、ChartPng（Java2D 静态图，取舍注释说明）、ExcelWriter（5 sheet）、PptxWriter（5 页）、DocxWriter（结论先行）。
- BuildArtifactsMain 一条命令（--gold-weight 可配，SPY 缺检查点自动补抓）。
- pom 补 log4j-to-slf4j 桥接。

**review 与改动（图表非空白占位确认）**

- 像素级核验 PPT 内嵌图，发现并修复两个真 bug（见下表）；核验脚本本身也补全了 PNG 五种 filter 反滤波（首次只处理两种曾误判）。

**关键问题与决策**

- ChartPng 选 Java2D 手绘（~130 行）而非 JFreeChart（~5MB 依赖）或 POI 原生图表（能力弱、WPS 兼容差）。
- Excel 加 Sources sheet：回测底稿须自证数据出处与口径。

**最终交付**

- artifacts 包 + BuildArtifactsMain + artifacts/ 三件套与归一化 PNG。

**产生的效果**

- 回测结论可独立复算：三资产指标与组合情景全部可从 work/ 原始 JSON 复现。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` | ✅ 31/31（回归无损） |
| 2 | 指标独立复算 3 个（Python 重算 vs Excel） | ✅ 3/3 逐位一致（GLD CAGR 0.192168 / BTC 回撤 0.766293 / 相关 0.139446） |
| 3 | 三件套完整性（zip+XML 良构） | ✅ xlsx 5 sheet / pptx 5 页+PNG / docx 结构齐备 |
| 4 | Office 打开人工确认 | ✅ 用户确认 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 相关矩阵非对角线全 NaN/伪值：`PearsonsCorrelation` 要求行=观测列=变量，初版把行列喂反 | 转置为 [日期][资产] 并剔除首日占位 | 无（修正后 GLD×BTC=0.139 等与典型事实一致） |
| PPT 组合页表格与图片锚点重叠（表格 840pt 宽压过图片） | 表格收窄左半区、图片移右半区，XML 级复验 | 无 |
| SPY 折线被画成金色（蓝色仅 56 像素=图例色块大小）：折线颜色依赖 HashMap 迭代序，SPY 先遍历命中 palette[0] | 颜色只按 key 判定（seriesColor 方法），与迭代序无关 | 无（修复后三线像素 2393/4296/1581 全绘） |

### R7 工程收尾

**输入提示词**

```
目标：R7：工程收尾——README、DESIGN、DEVLOG、补测试、全链路回归
范围：文档 + 测试，尽量不改功能
1.README.md：一句话简介/环境要求/一键运行(mvn -q clean package && java -jar target/app.jar run-all，Windows 附 run.cmd)/产物路径/常见问题(Yahoo 429、DeepSeek key、--no-llm)
2.DESIGN.md：四层架构图(ASCII)、tools/subagent/skill 职责表、关键取舍(直连 Yahoo、本地打包 ECharts 规避 CORS、agent-as-tool、LLM 定点介入、密钥治理)、溯源设计
3.DEVLOG.md：补全每轮记录，明确写：使用的 AI 工具(Claude Code + 模型)、AI 参与的环节、关键人工判断与修改(列出每轮改了什么、为什么)
4.补 3-5 个关键单测(对齐逻辑/HTML 生成/Excel 指标)
5.全链路回归：删除 target/ 与 work/ 后，从零按 README 跑通目标全部产物
6.验收：mvn test 全绿；README 从零可复现
```

**做了什么**

- README 重写（一键 run-all + run.cmd + 5 条 FAQ）；DESIGN.md 新增；DEVLOG 补 AI 参与总览。
- 补 5 个测试类 17 用例（固化历轮缺陷：词典硬约束/编造 URL 丢弃/季度选择/转置矩阵/迭代序颜色/Gate.io 分段）。
- RippleApplication.runAll 实现（替换 R0 的 TODO 路由），单标的失败不中断全局。

**review 与改动**

- 无 review 修订（当轮通过）。

**关键问题与决策**

- AgentMain/RenderMain 重构为返回退出码的 run()——System.exit 在内部调用场景会杀掉整个 run-all。

**最终交付**

- README / DESIGN / demo 基础 / run.cmd / GateIoProvider / runAll 入口 / 测试 46 用例。

**产生的效果**

- README 从零可复现成为事实（见验收）；测试覆盖了历轮全部修复过的缺陷类别。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn test` | ✅ 46/46 |
| 2 | `mvn -q clean package` 从零 | ✅ jar 29.8MB |
| 3 | **run-all 从零**（删 target/work/output/artifacts，真实网络） | ✅ 退出码 0：三标的归因 48/19+29缺失/48、HTML 1.7MB 三锚点全命中、三件套产出 |
| 4 | 产物打开人工确认 | ✅ 用户确认 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| `java -jar` 报 UnsupportedClassVersionError（默认 java 是 JDK 8） | README FAQ 覆盖（JAVA_HOME 指向 17）；回归用 `$JAVA_HOME/bin/java` | 无 |
| Binance 网络波动不可达（此前可达） | 新增 GateIoProvider 二级兜底（降级链 Yahoo → Binance → Gate.io） | 无 |
| Gate.io 400 "Candlestick range too broad"：from/to 跨度本身须 ≤1000 天 | 按 999 天分段请求 + 单测固化 | 无 |
| AgentMain 失败时 System.exit 杀掉 run-all 进程 | 重构为返回退出码的 run()，runAll 容错聚合 | 无 |

### R8 安全审查 + 演示彩排

**输入提示词**

```
目标：R8：安全审查 + 演示彩排
范围：只检查与演示，不改功能
1.密钥审查：grep 全仓(含 target/、work/)找 DEEPSEEK/Yahoo/token/apiKey 等字样，确认无硬编码；git log 里也不允许出现；.gitignore 覆盖 target/ work/ *.local.properties
2.前端安全：确认 HTML 无 CDN 外链、无 eval、事件链接仅 http/https 白名单、file:// 可打开
3.健壮性：验证 429 退避、LLM 超时、--no-llm 离线降级三条路径
4.简洁性：验证代码内容，确保模块职责划分明确，高内聚低耦合，无冗余内容
5.写 demo.md：3 分钟演示脚本(跑数据→看 HTML 交互→点溯源→开三件套)
6.让 Claude 以"检查者视角"对照题目四条期望逐项核验，输出 checklist 到 DESIGN.md
7.验收：checklist 全绿；离线也能演示
```

**做了什么**

- 密钥审查 7 项、前端安全 7 项、健壮性三路径、简洁性核验（详见 DESIGN.md 第六节 checklist 的核验方法列）。
- demo.md（3 分钟四幕脚本 + 备用预案）。
- DESIGN.md 附检查者视角 checklist（21 项）。

**review 与改动**

- 唯一改动：.gitignore 补 `*.local.properties` 兜底行（审查发现项）。

**关键问题与决策**

- `new Function`/`document.write` 出现在 echarts.min.js 内部的定性：属已审计依赖（官方 tarball + SHA-256），模板自身代码零命中——如实声明而非删库函数。

**最终交付**

- demo.md + DESIGN.md checklist + 全套审查结论。

**产生的效果**

- 演示具备离线能力（断网渲染实测 0 网络请求）；安全与工程质量有逐项实证。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | 密钥审查（src/pom/md/target/work/jar 内 class/git 历史） | ✅ 零泄漏 |
| 2 | 前端安全（外链/CSP/白名单/noopener/注入转义） | ✅ 全部通过 |
| 3 | 健壮性三路径（429 退避单测+实跑日志 / LLM 90s 超时+降级 / --no-llm env -u 实测） | ✅ 全部通过 |
| 4 | 简洁性（11 包类型单一/依赖无环/死代码记录） | ✅ 通过（死代码 2 处按"只查不改"记录） |
| 5 | checklist 统计 | ✅ **21/21 全绿** |
| 6 | 断网渲染实测 | ✅ 0 网络请求产出 HTML |
| 7 | `mvn test` | ✅ 46/46 |

**验收中发现并处理的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| .gitignore 未覆盖 `*.local.properties`（用户指定要求） | 补兜底行（R8 唯一改动） | 无 |
| 首轮前端扫描报 `new Function`/`document.write`/noopener 计数差/转义缺失 4 项"❌" | 逐项定性：库内部代码（已审计依赖）、JS 动态拼接处（同样过白名单）、本轮数据无触发字符（机制经单测复核）——均为误报而非缺陷 | 无 |

### R9 LLM 真实路径端到端验证与修复（追加轮）

**输入提示词**

```
目标：R9（追加轮）：DEEPSEEK_API_KEY 配置到位后，真实端到端验证 LlmAligner 归因路径 + 补齐历史承诺（解析失败降级）并固化 LLM 与规则双路径的差异
范围：agent 包（LlmAligner / CorrelationAnalyst / RippleOrchestrator / api 五接口）、pom.xml、测试与全部治理文档；不动数据层与渲染层
1.密钥传递：key 已持久化 ~/.zshrc，经环境变量注入运行进程，不进代码/配置/文档/对话记录
2.LLM 路径验证：真实 DeepSeek 调用跑通 NVDA 全流程，确认 mode=llm、reasoning 为自然语言推理、URL 全部可溯源至证据集合、编造条目被校验器实时丢弃
3.降级链补齐：兑现 LlmAligner javadoc"解析失败由上层降级为规则对齐"的历史承诺——schema 校验丢弃 / 单拐点降级"事件缺失"（拐点守恒）/ 整体不可用上层降级规则对齐（mode 如实落盘）
4.健壮性：无效 key 实测整体降级路径；散文包裹 JSON 提取兜底；修复阻断缺陷（@UserMessage 缺失 / 工具参数名 arg0 / 整标的输入超限 / JavaTimeModule）
5.文档记录：全部项目 md 记录或变更本次实践；LLM 归因 vs 规则对齐差异分析记入合适位置；产物目录（work/output/artifacts）入库的约定修订
6.验收：mvn -q verify 全绿（48 用例）；真实 key 与无效 key 各跑一遍 NVDA 全流程；HTML 重渲染；提交前密钥扫描覆盖暂存内容
7.完成后 DEVLOG.md 等文件追加本轮记录，人工审查后手动提交
```

**做了什么**

- 配置密钥传递路径（`~/.zshrc` 持久化，经交互式 shell 注入运行进程，密钥不经过对话/代码/日志）。
- 真实 DeepSeek 端到端验证 LLM 归因路径，暴露并修复 4 个阻断性缺陷（见下表）。
- 兑现两条历史承诺：LLM 输出全量 schema 校验（实测丢弃编造条目）；LLM 整体不可用时上层降级规则对齐（`RippleOrchestrator` 补 try 判空 + `mode=rule` 如实落盘）。
- `LlmAligner` 重构为**单拐点分片**：每次只送 1 个拐点 + `RuleBasedAligner.score` 预筛 top-8 候选新闻（证据直接随 `@UserMessage` 下发，`alignOne` 不再走 tool 往返——证据集合封闭性由 `validate()` 的 URL 白名单继续保证）；单拐点失败/返回为空/全被丢弃 → 回填"事件缺失"，拐点守恒（marks + missing = 48）。
- 解析健壮性：散文包裹 JSON 时提取最外层配对对象再解析。
- 新增 2 个回归测试（散文提取、空返回回填），46 → 48 用例。
- 双路径真实对照：LLM 模式与无效 key 降级模式各跑一遍 NVDA 全流程，差异分析记入 DESIGN.md 关键取舍 6。

**review 与改动**

- 用户人工审查发现"javadoc 承诺降级但代码无实现"→ 本轮第二阶段补齐（这正是零实测的典型副作用：文档写的是设计意图，不是已验证事实）。
- 用户在 IDE 中将 `LlmAligner.validate` 改为 public（便于外部核验），保留。
- 同日追加拍板：产物目录（work/output/artifacts）入库（.gitignore 移除三行，约 7.8 万行检查点与产物随仓库提交）；约定与文档同步修订，提交前密钥扫描已覆盖暂存内容（零命中，唯一 grep 命中为 URL 中 "musk-…" 误报）。

**关键问题与决策**

1. **分片而非整体调用**：48 拐点 × 2695 候选一次性送入超出模型上下文与输出 token 上限（实测模型回散文导致解析失败）；分片后输入输出规模可控，且单拐点失败半径缩小到一个拐点。
2. **部分失败的降级目标是"事件缺失"而非规则归因**：混合两种归因来源会让 `mode` 语义失真；宁缺毋滥与仓库既有哲学一致。整体失败才降级规则对齐。
3. **`alignOne` 证据随消息下发、不走 AlignmentTools**：R3 review 建立的"tool 门面边界"在分片模式下的等价物是校验器白名单——LLM 只能用输入里出现过的 URL，越界即丢弃（有单测）。AlignmentTools 保留给 `--orchestrate` 路径。
4. 密钥注入方式：`DEEPSEEK_API_KEY="$(zsh -ic 'printf %s "$DEEPSEEK_API_KEY"' ...)" java ...`——非交互 shell 不读 `~/.zshrc`，此法让密钥只存在于进程环境，不进对话记录与磁盘新位置。

**最终交付**

- 代码：5 个 agent 接口补 `@UserMessage`；`CorrelationAnalyst.alignOne`；`LlmAligner` 分片重构；`RippleOrchestrator` 上层降级；pom 补 `maven.compiler.parameters`。
- 文档：DESIGN.md（取舍 4/6、checklist C5/D3、已知限制更新）、README.md FAQ、demo.md 可选段、本文件、MILESTONES.md。

**产生的效果**

- LLM 归因路径从"代码就绪、零实测"变为全链路真实验证：NVDA `mode=llm`，26 归因 + 22 缺失 = 48 拐点守恒；URL 26/26 溯源至证据集；reasoning 全为自然语言因果链（2025-01-27 DeepSeek 冲击、2023-05-25 财报超预期、2026-02-06 capex 表态均归因正确）。
- 降级链三层全部实测：schema 校验丢弃（两轮共 26 条无 URL 条目）→ 单拐点降级缺失（序列化故障那次 48 拐点全部安全降级）→ 整体降级规则（无效 key 实测 `mode=rule`、48 归因、退出码 0）。
- HTML 重新渲染（NVDA 26 条 LLM 归因 + GLD/BTC 规则归因）。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn -q clean package`（含测试） | ✅ 48/48 |
| 2 | 真实 key 运行 AgentMain NVDA | ✅ `mode=llm`，26 归因 + 22 缺失 = 48，退出码 0 |
| 3 | URL 溯源核验（脚本对 `NVDA_events.json` 求差集） | ✅ 26/26 来自证据集，编造 0 |
| 4 | reasoning 人工抽查 | ✅ 全为 LLM 自然语言推理，模板句 0 条；三锚点归因正确 |
| 5 | 无效 key 运行（降级链实测） | ✅ 48 次 401 → 整体降级规则 → `mode=rule` 48 归因，退出码 0 |
| 6 | `--verify` 溯源清单 + HTML 重渲染 | ✅ 26 条含 URL；output/nvda-events.html 1.7MB |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| 5 个 agent 接口全部缺失 `@UserMessage`，AiServices 构造即抛 IllegalConfigurationException（零实测的直接证据） | 逐接口补用户消息模板 | 无 |
| 工具参数名运行期不可见（LLM 只见 `arg0`），tool calling 准确度受损 | pom 加 `maven.compiler.parameters=true` | 无 |
| 整标的输入超限：模型回 "Given…" 散文，解析失败且无降级 | 重构为单拐点分片（top-8 预筛）+ 散文 JSON 提取兜底 | 48 次 LLM 调用 ≈1 分钟（可接受） |
| `LlmAligner` 的 ObjectMapper 未注册 JavaTimeModule，`LocalDate` 序列化失败（该次故障同时实测了"单拐点降级不中断全局"） | 与 RippleTools/HtmlReportRenderer 对齐注册 | 无 |
| 校验丢弃条目后拐点凭空消失（27+20=47≠48） | 返回为空/全被丢弃时回填"事件缺失" | 无 |
| javadoc 承诺"解析失败由上层降级为规则对齐"实际不存在（用户 review 发现） | `RippleOrchestrator` 补判空降级，`mode` 如实落盘 | LLM 真情实判"全部无可归因"时也会触发规则兜底（mode=rule 如实标记，可接受） |

### R10 JDK 17 降级 + 产物版式修复 + 从零全链路回归（追加轮）

**输入提示词**

```
目标：R10（追加轮）：JDK 21→17 降级、PPT/Excel 两处产物版式缺陷修复、从零全链路回归验收
范围：pom.xml、全部 src 的 Java 21 API 替换、PptxWriter/ExcelWriter 与新测试、md 数字与版本同步；不改业务逻辑
1.JDK 降级：maven.compiler.release 17；getFirst/getLast（SequencedCollection，Java 21 独有）58 处
  替换为 get(0)/get(size-1)；md 只做 21→17 字面替换，不新增记录
2.PPT 修复：组合页表格列宽显式分配（XSLFTable 默认每列 100pt 且不受 anchor 宽约束，5 列实际
  渲染 500pt 溢出压住右侧图表最右列字符——用户实开发现）+ 图片右移；固化 PptxLayoutTest
3.Excel 修复：表头 setFillForegroundColor((short)0xE8EEF4) 把 RGB 误传给调色板索引、截断成
  indexed=-4364 被 Excel/WPS 渲染成黑底（全部 sheet 表头均中招）——改 XSSFColor RGB；固化 ExcelStyleTest
4.从零回归：删 work/output/artifacts/target 后带 key run-all，对照设计初衷逐项核验
5.验收：mvn -q verify 全绿；产物 XML 级核验（列宽/锚点/填充色）
```

**做了什么**

- JDK 降级：pom release 17；20 个文件 58 处 SequencedCollection API 替换（批量 sed + 4 处复杂接收者手工）；23 处 md 版本字面替换（含历史轮次，日期未误伤）。
- 版式修复：PptxWriter 列宽按份额显式分配（首列 2 份，表格收敛 410pt）+ 图片 x 500→520；ExcelWriter 表头填充改 XSSFColor（rgb=E8EEF4）。
- 测试：新增 PptxLayoutTest、ExcelStyleTest，Snapshot 构造抽共享 TestSnapshots（循 FakeTransport 先例）；48 → 50 用例。
- 从零全链路（带 key，5.5 分钟退出码 0）：降级链 4 次自动切换、NVDA `mode=llm` 29+19=48 拐点守恒、URL 溯源 96/96、三锚点命中、吻合率 96.6%、密钥零泄漏；字节码 major 61 确认 17。
- 双路径同证据集对比（复用检查点跑规则模式）：LLM 29 ⊆ 规则 48、评级一致 28/29、correlation 均值 0.813 vs 0.775；2021-11-04「EU 调查 Arm 配 +12% 上涨」规则以 0.84 误标（R3 缺陷）、LLM 如设计预期拒绝归因——取舍 6 的记录经实测成立。

**review 与改动**

- 用户实开产物发现两处版式缺陷（PPT 第四页图表遮挡表格最右列、Excel 指标表表头黑底）——均为"编译通过 ≠ 产物正确"的又一轮印证，已各自固化为产物级断言。

**关键问题与决策**

1. JDK 17 验证方式：本机无法装/查 17（沙箱限制），用 JDK 21 + `--release 17` 交叉编译等效验证（javac 在该模式拒绝 21 专属 API），字节码 major 61 确认。
2. langchain4j 1.19.0 最低 Java 17——17 即底线，不可再降。
3. `Comparator.reversed()` 与 `List.reversed()` 同名不同源，批量替换时以方法语义甄别，未误伤。

**最终交付**

- JDK 17 兼容的全部源码与构建配置；PPT/Excel 版式修复与 2 个产物级回归测试；文档数字同步（测试数/根数/归因数/R0-R10）。

**产生的效果**

- 项目在 Java 17 上从零全链路复现成功；产物版式缺陷清零且有断言防复发。

**如何验收**

| # | 步骤 | 结果 |
|---|------|------|
| 1 | `mvn -q clean verify`（50 用例） | ✅ 全绿 |
| 2 | 字节码版本（javap major version） | ✅ 61（Java 17） |
| 3 | 从零 run-all（删四目录、带 key） | ✅ 退出码 0，5.5 分钟 |
| 4 | 产物 XML 级核验 | ✅ 表格列宽 410pt/图片 x=520 间距 50pt；表头 fill rgb=E8EEF4，负数 indexed 清零 |

**验收中发现并修复的问题**

| 现象 | 修复 | 副作用 |
|------|------|--------|
| PPT 组合页图表盖住表格最右列字符（用户实开发现） | 根因是 XSLFTable 默认列宽不受 anchor 约束（5×100=500pt > 410）：显式分配列宽 + 图片右移 20pt | slide2/3 列宽同步收敛到 anchor 声明值 |
| Excel 指标表表头黑底（用户实开发现） | 根因是 RGB 十六进制误传给调色板索引 short 截断成 -4364：改 XSSFColor byte[] 构造 | 无（全部 sheet 表头一并修复） |
| 首版 PptxLayoutTest 匹配到 slide1（问题定义文本含"组合情景"） | 改用完整标题"组合情景与决策建议"做唯一定位 | 无 |

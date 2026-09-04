# 观澜（Ripple）设计文档

> 观其澜，知其源 —— Watch the waves, find the stones.

## 〇、代码结构

```
ripple/
├── pom.xml                                # Maven：Java 21 · langchain4j 1.19.0 · POI 5.5.1 · Jackson · slf4j/logback · commons-math3 · JUnit5 · shade 打包
├── run.cmd                                # Windows 一键运行脚本
├── CLAUDE.md / README.md / DESIGN.md / MILESTONES.md / DEVLOG.md / demo.md
│
├── src/main/java/com/ripple/              # 根包：仅 CLI 入口（每个 Main 对应一条可独立运行的命令）
│   ├── RippleApplication.java             # 总入口：run-all 一键跑通（单标的失败不中断全局）
│   ├── DataFetchMain.java                 # 抓数 CLI：--symbol --years → 行情+资讯落盘 work/（含降级链）
│   ├── AnalysisMain.java                  # 技术分析 CLI：复用 work/ 检查点，打印拐点与趋势段
│   ├── AgentMain.java                     # 编排 CLI：行情→分析→事件→对齐 全流水线（--no-llm/--verify）
│   ├── RenderMain.java                    # 渲染 CLI：work/ 检查点 → 单文件 HTML（--serve 可选预览）
│   └── BuildArtifactsMain.java            # 三件套 CLI：任务 B 的 Excel/PPT/Word 一条命令（--gold-weight 可配）
│
│   ├── domain/                            # 【数据模型】纯 record，无任何业务逻辑（被所有层引用）
│   │   ├── Ohlcv.java                     #   日 K 线（前复权口径）
│   │   ├── NewsItem.java                  #   资讯（含 externalId/objectID 溯源字段）
│   │   ├── MarketEvent.java               #   NewsItem + 评级 + 置信度（R1 预留，当前未用——清理候选）
│   │   ├── InflectionPoint.java           #   行情拐点（日期/类型/涨跌/放量/±5 交易日候选窗口）
│   │   ├── Segment.java                   #   趋势段（起止日期/标签/起止价/净涨跌/最大回撤）
│   │   ├── EventCandidates.java           #   对齐输入：一个拐点 + 其窗口内的候选新闻
│   │   ├── EventMark.java                 #   对齐结论（七必带字段 + correlation + strength）
│   │   ├── MissingEvent.java              #   事件缺失标注（拐点 + 2-3 条同期候选，不做归因）
│   │   └── AlignmentOutcome.java          #   对齐结果全集（marks + missing，互斥覆盖全部拐点）
│   │   └── enums/                         #   枚举（英文常量 + label() 中文标签）
│   │       ├── ImpactRating.java          #     影响评级：BULLISH/BEARISH/NEUTRAL（利好/利空/中性）
│   │       ├── LinkStrength.java          #     关联分档：STRONG/MODERATE/WEAK + of(correlation) 分档函数
│   │       ├── PivotType.java             #     拐点类型：局部高低点/金叉死叉/显著涨跌
│   │       └── TrendLabel.java            #     趋势标签：UP/DOWN/SIDEWAYS
│   │
│   ├── dataprovider/                      # 【数据源层】原子工具，无业务判断（对应四层架构的 tools 基座）
│   │   ├── api/                           #   接口（测试注入点：mock 实现即离线单测）
│   │   │   ├── HttpTransport.java         #     HTTP 传输抽象（Response record）
│   │   │   ├── MarketDataProvider.java    #     行情源抽象：fetchDaily(symbol, start, end)
│   │   │   └── NewsProvider.java          #     资讯源抽象：search(keyword, start, end)
│   │   ├── YahooFinanceProvider.java      #   行情主源（v8 chart，adjclose 前复权）
│   │   ├── SinaFinanceProvider.java       #   行情兜底①（美股/ETF，JSONP + 内置拆股表前复权）
│   │   ├── BinanceProvider.java           #   行情兜底②（BTC，游标翻页 1000 根/页）
│   │   ├── GateIoProvider.java            #   行情兜底③（BTC 二级兜底，999 天分段请求）
│   │   ├── HackerNewsProvider.java        #   资讯源（Algolia 按时间窗，objectID/story_url 溯源）
│   │   ├── JdkHttpTransport.java          #   真实 HTTP 实现（完整浏览器 UA + 超时）
│   │   ├── ResilientHttpTransport.java    #   弹性装饰器（429/5xx/网络错误指数退避 + 8s 上限 + ±25% 抖动）
│   │   └── HttpFetchException.java        #   错误分类（RATE_LIMITED/SERVER/CLIENT/NETWORK/PARSE）
│   │
│   ├── analysis/                          # 【技能层·确定性】纯函数可单测，不调 LLM
│   │   ├── Indicators.java                #   技术指标：SMA / Wilder RSI / 滚动波动率 / 异常放量
│   │   ├── PivotDetector.java             #   拐点四类信号 + zig-zag 趋势段划分
│   │   └── model/
│   │       ├── AnalysisConfig.java        #   13 个阈值（analysis.properties + -D 覆盖）
│   │       └── AnalysisResult.java        #   分析结果（K 线 + 指标序列 + 拐点 + 趋势段）
│   │
│   ├── agent/                             # 【Agent 层】编排 + 子 agent + 对齐实现
│   │   ├── api/                           #   子 agent 接口（langchain4j AiServices，@SystemMessage 定义职责）
│   │   │   ├── MarketDataAgent.java       #     行情 agent：拉行情+算指标，只回 JSON
│   │   │   ├── NewsEventAgent.java        #     事件 agent：窗口检索+去重+相关性打分
│   │   │   ├── CorrelationAnalyst.java    #     归因 agent（LLM 核心落点）：拐点×事件对齐
│   │   │   ├── RenderAgent.java           #     渲染 agent（占位，R5/R6 由 RenderMain/BuildArtifactsMain 实现）
│   │   │   └── OrchestratorAgent.java     #     主编排 agent（--orchestrate 模式，agent-as-tool）
│   │   ├── tools/                         #   工具层（@Tool 门面 + 检查点管理）
│   │   │   ├── RippleTools.java           #     能力实现 + work/ 检查点（断点续跑核心）
│   │   │   ├── MarketDataTools.java       #     MarketDataAgent 门面（行情+分析 2 工具，边界强制）
│   │   │   ├── NewsTools.java             #     NewsEventAgent 门面（仅 HN 检索 1 工具）
│   │   │   ├── AlignmentTools.java        #     CorrelationAnalyst 门面（仅读对齐输入——证据集合封闭）
│   │   │   └── SubAgentTools.java         #     agent-as-tool：4 个子 agent 封装为 @Tool 供主编排调用
│   │   ├── RippleOrchestrator.java        #   默认顺序循环编排（含取舍注释：vs DAG / LangGraph4j 演进条件）
│   │   ├── LlmModels.java                 #   LLM 工厂（DeepSeek OpenAI 兼容，key 只读 env，90s 超时）
│   │   ├── LlmAligner.java                #   LLM 对齐 + 输出三重校验（URL 必属证据集合等，编造即丢弃）
│   │   └── RuleBasedAligner.java          #   --no-llm 规则对齐（词典硬约束 + 日期接近度 + 放量打分）
│   │
│   ├── render/                            # 【渲染层】任务 A 产物
│   │   ├── HtmlReportRenderer.java        #   单文件 HTML 装配（echarts+数据全内嵌、静态溯源面板）
│   │   └── PreviewServer.java             #   可选本地预览（127.0.0.1，只读，无 cookie）
│   │
│   └── artifacts/                         # 【产物层】任务 B 三件套
│       ├── PortfolioStats.java            #   组合统计技能（commons-math3：CAGR/波动/回撤/夏普/Pearson/组合）
│       ├── ChartPng.java                  #   静态 PNG 折线图（Java2D 手绘，取舍注释说明）
│       ├── ExcelWriter.java               #   Excel 回测底稿（XSSF，5 sheet 含 Sources）
│       ├── PptxWriter.java                #   PPT 决策框架（XSLF，5 页，表格原生 + PNG 嵌入）
│       └── DocxWriter.java                #   Word 策略报告（XWPF，结论先行）
│
├── src/main/resources/
│   ├── analysis.properties                # 13 个分析阈值（全部 -D 可覆盖）
│   ├── logback.xml                        # 日志配置（INFO → stderr）
│   ├── static/echarts.min.js              # ECharts 6.1.0 本地包（SHA-256 b66b25ae…0fd0，无 CDN）
│   └── templates/report.html              # HTML 模板（占位符装配 + 内嵌 JS 交互逻辑）
│
├── src/test/java/com/ripple/              # 17 个测试类 46 用例（全 mock HTTP，无网络依赖）
│   ├── dataprovider/                      #   FakeTransport（mock 传输）+ 6 个 Provider/退避测试
│   ├── analysis/                          #   指标数学正确性 / 合成序列信号 / 真实 NVDA 锚点断言
│   ├── agent/ + agent/tools/              #   规则对齐 / LLM 校验器 / 拐点季度选择
│   ├── render/                            #   HTML 解析断言（mark>0、href 全 http、无外链 script）
│   └── artifacts/                         #   组合统计正确性 / ChartPng 三系列颜色
│
├── work/      (gitignored)                # 检查点：ohlcv/analysis/events/alignments JSON（断点续跑 + 溯源凭据）
├── output/    (gitignored)                # 任务 A 产物：nvda-events.html（单文件，断网可开）
└── artifacts/ (gitignored)                # 任务 B 产物：xlsx/pptx/docx + 归一化 PNG
```

**阅读指引**：依赖方向单向向下（根 CLI → agent → analysis/dataprovider → domain），无环；
每个包内 Java 类型单一（api/=接口、enums/=枚举、domain 与 model/=record、其余=类）。

## 一、四层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  orchestrator（编排层）                                               │
│  RippleOrchestrator ── 顺序循环（默认）：行情→分析→事件→对齐→渲染         │
│  OrchestratorAgent ── agent-as-tool（--orchestrate，LLM 驱动分支）    │
└───────────────┬─────────────────────────────────────────────────────┘
                │ 调用
┌───────────────▼─────────────────────────────────────────────────────┐
│  agent（子 agent 层，语义决策）                                        │
│  agent/api:  MarketDataAgent / NewsEventAgent / CorrelationAnalyst  │
│              / RenderAgent / OrchestratorAgent（AiServices 接口）     │
│  agent:      LlmAligner（LLM 输出 schema 校验）                       │
│              RuleBasedAligner（--no-llm 规则对齐）  LlmModels         │
└───────────────┬─────────────────────────────────────────────────────┘
                │ 经 tool 门面（能力边界强制）调用
┌───────────────▼─────────────────────────────────────────────────────┐
│  tools + skill（工具与技能层，确定性）                                 │
│  agent/tools: RippleTools（work/ 检查点管理）                         │
│               MarketDataTools / NewsTools / AlignmentTools（门面）   │
│  analysis:    PivotDetector / Indicators（MA/RSI/波动率/放量/拐点）    │
│  artifacts:   PortfolioStats（commons-math3 回测统计）                │
└───────────────┬─────────────────────────────────────────────────────┘
                │ 数据通道
┌───────────────▼─────────────────────────────────────────────────────┐
│  dataprovider（数据源层，原子工具）                                     │
│  api:   HttpTransport / MarketDataProvider / NewsProvider（接口）     │
│  impl:  YahooFinanceProvider（主源）→ SinaFinanceProvider（美股兜底）   │
│         / BinanceProvider（BTC 兜底）；HackerNewsProvider；           │
│         ResilientHttpTransport（指数退避+抖动+限速）                   │
└─────────────────────────────────────────────────────────────────────┘
  横切：domain（record + enums）/ render（HTML/PPT/Word/Excel 产出）
        work/ 检查点 = 状态（断点续跑、幂等重跑）
```

## 二、职责表

| 层 | 组件 | 职责 | LLM? |
|---|---|---|---|
| tools | `YahooFinanceProvider` | Yahoo v8 行情（adjclose 前复权），403/网络失败时由降级链接兜底源 | 否 |
| tools | `SinaFinanceProvider` | 新浪美股/ETF 兜底（JSONP 解析 + 拆股表前复权，NVDA 2021 4:1、2024 10:1） | 否 |
| tools | `BinanceProvider` | BTC 日线兜底（游标翻页 1000 根/页） | 否 |
| tools | `HackerNewsProvider` | HN Algolia 按时间窗检索，objectID/story_url 溯源 | 否 |
| skill | `Indicators` | 纯函数指标：SMA / Wilder RSI / 滚动波动率 / 异常放量 | 否 |
| skill | `PivotDetector` | 拐点四类信号 + zig-zag 趋势段；阈值全部 properties 化（-D 可覆盖） | 否 |
| skill | `PortfolioStats` | CAGR/年化波动/最大回撤/夏普/Pearson 相关/权重组合 | 否 |
| subagent | `MarketDataAgent` | 拉行情+算指标，只回结构化 JSON（tool 门面：2 工具） | 是 |
| subagent | `NewsEventAgent` | 窗口检索、URL 去重、相关性打分（tool 门面：仅检索 1 工具） | 是 |
| subagent | `CorrelationAnalyst` | 拐点×事件对齐：correlation/评级/置信度/推理（tool 门面：仅读对齐输入 1 工具，证据集合封闭） | 是（核心落点） |
| subagent | `RenderAgent` | 渲染占位（R5/R6 由 RenderMain/BuildArtifactsMain 实现） | - |
| orchestrator | `RippleOrchestrator` | 默认顺序循环：可单测/可断点续跑/无 key 可跑 | 仅对齐步 |
| orchestrator | `OrchestratorAgent` | agent-as-tool 模式：4 个子 agent 封装为 @Tool 交给主 agent 分支调度 | 是 |

## 三、关键取舍

**1. 行情直连 Yahoo v8 而不引第三方行情库。** 免费无 key、单请求 5 年日线含复权价；Java 生态无维护良好的现成库（多为薄封装+重依赖），v8 结构稳定直连约 150 行。代价已付：大陆 IP 被 403 地域封锁 → 内置降级链（新浪/Binance），接口不变（`MarketDataProvider`），网络环境变化自动回切。

**2. ECharts 本地打包 + 数据内嵌 HTML（规避 CORS 与 CDN 供应链风险）。** `file://` 下 fetch 本地 JSON 被浏览器 CORS 拦截 → 数据内嵌 `<script>`；CDN 依赖第三方存活且存在投毒风险 → echarts.min.js 入库（SHA-256 记录）。产物为 1.7MB 单文件快照：可归档、邮件分享、审计，任何人打开看到同一份数据；`</` 转义防 script 注入，CSP meta 收紧。

**3. 编排用顺序循环 + agent-as-tool 增强，不用 DAG 引擎。** 五步线性依赖、每步产物落盘可校验——顺序循环可单测、可断点续跑、无 key 可跑（--no-llm）。langchain4j 无原生 DAG，自建需图定义/拓扑排序/状态机三套代码，对线性流程是过度设计。agent-as-tool（--orchestrate）保留给分支依赖运行时内容的场景（窗口空了换关键词、置信度低回补检索）。演进到 LangGraph4j 的触发条件：条件分支与环、并行扇出+人审中断点、步骤数增长需图结构。

**4. LLM 定点介入（归因/评级/推理），其余全部确定性。** 拐点检测是数学问题——LLM 做会幻觉日期价格且不可复现；"这条新闻是不是那次下跌的原因"是语义问题——确定性代码写不出。LLM 输出过三重校验：URL 必属检索证据集合、拐点日期必须真实存在、枚举/置信度收敛到合法域——**LLM 负责判断，不负责"知道"**。

**5. 密钥治理。** `DEEPSEEK_API_KEY` 只经 `System.getenv` 读取；不入代码/配置/测试/文档/prompt 模板（文档占位 `${DEEPSEEK_API_KEY}`）；.gitignore 覆盖 work/、output/、artifacts/、.env、*.key；每轮验收 grep 扫描全仓。

## 四、溯源设计（结论可回链来源）

```
原始 API 响应 ──落盘──> work/{symbol}_ohlcv.json（含 source 字段标注实际数据源）
HN 检索结果   ──落盘──> work/{symbol}_events.json（objectID + story_url）
对齐结论      ──落盘──> work/alignments.json（schemaVersion 2：marks + missing）
                          │
        ┌─────────────────┼──────────────────┐
        ▼                 ▼                  ▼
  HTML 溯源面板      Excel Sources sheet   Word 来源附录
  （每条 EventMark   （数据源+口径说明）    （原始 JSON 路径）
   含可点击 URL）
```

- **每条归因结论**（EventMark）必带：事件标题/日期/来源 URL/摘要/评级/置信度/一句话推理
- **事件缺失**（MissingEvent）同样可溯源：列 2-3 条同期候选 URL，明确标注"不做归因"
- **URL 硬约束**：规则与 LLM 两条对齐路径的输出 URL 都必须在检索证据集合内（LLM 编造即丢弃）
- **数值可复算**：Excel 指标与 work/ 原始 JSON 可独立交叉核对（R6 验收已做 3/3 逐位一致）
- `--verify` 模式：逐条打印 EventMark 的 URL 供人工抽查

## 五、数据口径速查

| 项 | 口径 |
|---|---|
| 价格 | 前复权（Yahoo adjclose 因子 / 新浪拆股表） |
| CAGR | 按日历时间复合（365.25 天/年） |
| 年化波动 | 日收益率标准差 × √每年期数（BTC≈365，GLD/SPY≈252，按样本密度） |
| 夏普 | CAGR / 年化波动，rf=0（简化口径，产物中注明） |
| 相关性 | 三资产日期交集的日收益率 Pearson（commons-math3） |
| 事件吻合率 | 事件日与拐点日交易日距离 ≤3 的占比（任务 1 实测 95.8%） |

---

## 六、期望核验 checklist（R8 检查者视角）

对照目标原始期望逐项核验，方法为实际执行产物级检查（非代码走查）。

### Agent 与可视化（NVDA × AI 事件）

| # | 期望 | 核验方法 | 结果 |
|---|------|---------|------|
| A1 | 近五年 OHLCV+成交量 | work/NVDA_ohlcv.json 1253 根（2021-09~2026-09），含成交量与前复权 | ✅ |
| A2 | 梳理 AI 行业大事件 | HN 检索覆盖 ChatGPT/B100/DeepSeek 锚点窗口，24 窗口 2205 条候选 | ✅ |
| A3 | K 线上标记拐点/加速/下跌/上涨 | 48 拐点四类全覆盖（BIG_UP 27/BIG_DOWN 13/局部高低点 8），散点+竖线+趋势段区块 | ✅ |
| A4 | 标记事件与影响评级 | 评级 利好/利空 全程标注（关联度/置信度附随） | ✅ |
| A5 | 可交互 | dataZoom 缩放、标的下拉切换、评级筛选、tooltip（hover/click）实测 | ✅ |
| A6 | 可溯源 | 115 条归因每条带 http(s) 来源 URL；--verify 清单；原始 JSON 检查点 | ✅ |
| A7 | 生成 HTML | output/nvda-events.html（1.7MB 单文件，file:// 直开） | ✅ |

### 黄金 vs 比特币三件套

| # | 期望 | 核验方法 | 结果 |
|---|------|---------|------|
| B1 | Excel 回测底稿 | 5 sheet（原始数据 1254 行/指标/回撤/组合/Sources），指标独立复算 3/3 逐位一致 | ✅ |
| B2 | PPT 决策框架 | 5 页（问题/指标/风险相关性/组合情景+图/建议），图表为真实 PNG（像素级核验三线全绘） | ✅ |
| B3 | Word 策略报告 | 结论先行/口径/风险提示/附录回测表齐备 | ✅ |

### 技术栈合规

| # | 期望 | 核验 | 结果 |
|---|------|------|------|
| C1 | Java 21 | maven.compiler.release=21 | ✅ |
| C2 | langchain4j | 1.19.0（langchain4j + langchain4j-open-ai） | ✅ |
| C3 | POI | poi-ooxml 5.5.1（XSSF/XSLF/XWPF 三件全用） | ✅ |
| C4 | ECharts 本地打包 | resources/static/echarts.min.js（SHA-256 记录），产物 0 外链资源 | ✅ |
| C5 | LLM OpenAI 兼容接 DeepSeek | baseUrl=https://api.deepseek.com，key 只读 System.getenv("DEEPSEEK_API_KEY") | ✅ |

### 隐含工程期望（原方案 NFR/验收标准）

| # | 期望 | 核验 | 结果 |
|---|------|------|------|
| D1 | 密钥不入库 | 全仓 grep（src/pom/md/target/work/jar 内 class）零命中；git 无提交历史；.gitignore 覆盖 target/work/*.key/.env/*.local.properties | ✅ |
| D2 | 前端安全（CORS/CDN） | 0 资源外链、CSP meta、无 fetch/XHR、JSON `</` 转义（单测）、URL 白名单（indexOf('http')===0）、target=_blank 全带 rel=noopener | ✅ |
| D3 | 无 key 可跑 | --no-llm 规则对齐全流程（env -u 实测），LLM 失败自动降级 | ✅ |
| D4 | 记录 AI 开发过程 | DEVLOG.md 逐轮记录（AI 工具/参与环节/人工判断总览 + 每轮详情） | ✅ |
| D5 | 断网可演示 | HTML 渲染只依赖 work/（实测 0 网络请求）；产物 file:// 直开 | ✅ |
| D6 | 事件与拐点吻合 | 吻合率 95.8%（±3 交易日，验收线 70%）；三锚点全命中 | ✅ |

### 已知限制（如实声明）

- LLM 归因路径（CorrelationAnalyst/--orchestrate）代码就绪、解析校验有单测，但全程在无 DEEPSEEK_API_KEY 环境开发，**端到端真实 LLM 调用未经实测**（演示用规则对齐路径）。
- 部分来源 URL（qz.com/substack 等）偶发源站反爬不可达，属外部服务问题，不影响归因结论（URL 来自检索证据集合本身有效）。
- 死代码 2 处（domain/MarketEvent 预留、RippleTools.AnalysisDoc/SUPPORTED_SYMBOLS）——按 R8"只查不改"原则记录为清理候选。

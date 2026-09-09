# 观澜（Ripple）3 分钟演示脚本

> 观其澜，知其源 —— Watch the waves, find the stones.
> 离线也可演示：全程只需 work/ 检查点与已生成产物，无需网络与 API key。

## 演示前准备（1 分钟，演示前完成）

如 java -version 已是 17 可跳过：
```bash
export JAVA_HOME=<JDK17 路径> MAVEN_SKIP_RC=1     
```

若 work/ 与产物已在（断网演示），跳过下面这行；否则从零跑一次（约 5-10 分钟）：
```bash
mvn -q clean package && java -jar target/ripple-0.1.0.jar run-all
```

检查清单：`work/NVDA_ohlcv.json` 存在、`output/nvda-events.html` 存在、`artifacts/` 三件套存在。

---

## 一、跑数据（40 秒）

一键（若检查点已在，秒级完成）：
```bash
java -jar target/ripple-0.1.0.jar run-all
```

或只演示单标的流水线：
```bash
mvn -q exec:java -Dexec.mainClass=com.ripple.AgentMain -Dexec.args="--symbol NVDA --years 5 --no-llm"
```

**口播要点**：
- "一条命令跑通 行情→指标→拐点→事件检索→对齐 全流水线；Yahoo 对大陆 IP 被 403 封锁，系统自动降级到新浪/Binance——看日志这行 `切换兜底数据源`"
- "48 个拐点全部找到相关事件；2023-05-25 财报日 +24.37%、2025-01-27 DeepSeek -16.97% 都是真实历史"
- "中间结果全部落盘 work/ 做检查点——断点续跑，第二次运行 0 网络请求"

## 二、看 HTML 交互（60 秒）

```bash
open output/nvda-events.html
```

**演示动作**（按顺序）：
1. **滚轮缩放** K 线 + 拖动底部 dataZoom 滑块——"ECharts 交互，MA5/20/60 与成交量副图"
2. 右上角**下拉切换** GLD / BTC-USD——"三标的同屏，黄金波动平缓、比特币剧烈，一眼看出资产性格"
3. 指到图上的**事件圆点**（2025-01-27 那个）——"事件按评级着色：红=利好 绿=利空，竖线是拐点日，浅色区块是趋势段"
4. **hover/点击**该日期——tooltip 弹出事件标题、摘要、评级、置信度和"来源 ↗"链接

**口播要点**：
- "注意 DevTools 的 Network 面板——**0 个外网请求**：ECharts 和数据全部内嵌，这个 1.7MB 的文件断网双击就能打开，可以邮件归档给任何人"
- "这是'断网也能演示'的证据——现在就可以把 Wi-Fi 关掉刷新"

## 三、点溯源（40 秒）

**演示动作**：
1. 在 tooltip 里点 **"来源 ↗"**——新窗口打开 investors.com 的 DeepSeek 报道原文
2. 滚动到底部**溯源面板**——"三标的归因结论（run-all 混合模式 96 条，NVDA 为 LLM 归因、GLD/BTC 为规则归因），每条带日期/评级/置信度/来源 URL/一句话推理"
3. 点筛选按钮 **"利空"**——面板过滤
4. 指一条"事件缺失"（GLD 段）——"宁缺毋滥：找不到强相关事件的拐点只列同期候选，不编造归因"

**口播要点**：
- "每条结论都能回链原文——URL 只能来自检索证据集合，LLM 编造的会被直接丢弃（校验器单测覆盖）"
- "命令行还能 `--verify` 打印全部归因条目的 URL 清单供人工抽查（NVDA LLM 模式 29 条 / 规则模式 48 条）"

## 四、开三件套（40 秒）

```bash
open artifacts/gold-btc-backtest.xlsx artifacts/gold-btc-framework.pptx artifacts/gold-btc-strategy.docx
```

**演示动作**：
1. **Excel**：切到"指标表" sheet——"GLD 夏普 1.03 vs BTC 0.19，相关只有 0.14"；切"Sources"——"数据源和口径都在里面，任何数都能和 work/ 原始 JSON 对账"
2. **PPT**：翻到"组合情景"页——"左表格右图：60/40 组合的收益回撤权衡，权重命令行可配"
3. **Word**：展示"一、结论（先行）"——"报告结论先行，口径、风险提示、附录回测表齐备"

## 五、收尾（10 秒）

> "一句话总结：**确定性技能做检测和回测，LLM 只在归因处定点介入，输出经校验可溯源**——观其澜，知其源。全部产物离线可复现、可审计。"

---

## 可选加演：LLM 归因模式（+2 分钟，需 DEEPSEEK_API_KEY 与网络）

```bash
export DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}
java -cp target/ripple-0.1.0.jar com.ripple.AgentMain --symbol NVDA --years 5
java -cp target/ripple-0.1.0.jar com.ripple.RenderMain --symbols NVDA,GLD,BTC-USD
```

**口播要点**：
- "刚才看的是规则对齐；现在换 LLM 模式——每个拐点分片调用 DeepSeek 做语义归因，约 1 分钟"
- "看 `work/NVDA_alignments.json` 的 `mode` 字段：`llm` 还是 `rule`，归因来源一目了然"
- "LLM 更'挑'：48 个拐点只归因 26 个，其余如实标'事件缺失'——宁缺毋滥；对比规则模式 48/48 全归因，两条路径互为镜像"
- "推理是自然语言因果链：2025-01-27 暴跌归因 DeepSeek 冲击高端芯片需求，语义判断是词典打分做不到的"
- "key 失效也不怕：三层降级——编造条目被校验器丢弃、单拐点失败降'缺失'、整体不可用自动回退规则对齐（无效 key 实测过）"

---

## 备用预案

| 意外 | 处置 |
|---|---|
| 外网新闻站打不开（源站反爬） | 换演示 2023-05-25 财报（investors.com/cnbc 通常可达）；或改讲 `--verify` 清单 |
| HN/Yahoo 现场抓取慢 | 不现场抓——用 work/ 检查点（断点续跑本来就是卖点） |
| 忘记 export JAVA_HOME | `java -version` 报 8 → 用 `~/tools/jdk-17/Contents/Home/bin/java` 全路径 |
| 无 key 被问 | 正是卖点：--no-llm 规则对齐全程可跑，产物结构一致 |
| LLM 演示时 DeepSeek 超时/限流 | 切回规则模式产物讲解；或讲降级日志（单拐点降缺失 → 整体降规则）本身就是演示点 |

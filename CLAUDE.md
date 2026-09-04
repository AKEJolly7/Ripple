# 观澜（Ripple）

观其澜，知其源 —— Watch the waves, find the stones.
- 任务 A：NVDA 行情 × AI 事件归因可视化（HTML）。
- 任务 B：黄金 vs 比特币避险/抗通胀比较（Excel/PPT/Word）。
- 技术栈：Java 21 · langchain4j（OpenAI 兼容 → DeepSeek）· Apache POI · ECharts 本地打包。

## 本仓约定（仅此 5 条）

1. **构建/测试**：本机默认 java/mvn 都指向 JDK 8（`~/.mavenrc` 强制 zulu-8，勿改，其他项目依赖它）。
   构建前必须：`export JAVA_HOME=~/tools/jdk-21.0.12.1+1/Contents/Home MAVEN_SKIP_RC=1`
   （JDK 21 已装在 ~/tools，MAVEN_SKIP_RC=1 让 mvn 跳过 ~/.mavenrc 的 JAVA_HOME 覆盖）；
   `mvn -q package` 构建，`mvn test` 测试，
   运行用 `mvn -q exec:java -Dexec.mainClass=com.ripple.RippleApplication -Dexec.args="<子命令> [参数] [--offline|--no-llm]"`。
2. **代码风格**：DTO 一律 record（`domain/` 只放 record，枚举在 `domain/enums/`）；包内类型单一
   （`agent/api`=接口、`agent/tools`=工具门面、`agent`=实现，`analysis/model`、`dataprovider/api` 同理）；
   外部 HTTP 必须走 `dataprovider.api.HttpTransport` 的 ResilientHttpTransport 装饰
   （统一 User-Agent/指数退避/限速）；LLM 输出必须经 schema 校验，禁止把未校验文本直接写进产物；
   提交前 `mvn -q verify` 必须绿。
3. **密钥纪律**：`DEEPSEEK_API_KEY` 只从环境变量 `System.getenv` 读取，严禁写入代码、配置、测试、
   文档、prompt 模板；文档中出现密钥位置一律写 `${DEEPSEEK_API_KEY}` 占位。
4. **产物目录**：HTML 报告输出到 `output/`，任务 B 三件套（Excel/PPT/Word）输出到 `artifacts/`，
   数据抓取中间产物输出到 `work/`，三者均已 gitignore 不入库；
   `echarts.min.js`（SHA-256: b66b25ae…0fd0）之外禁止引入任何前端外部资源。
5. **开发日志**：每个里程碑（R0-R8）完成或中止时更新 `DEVLOG.md`（时间、内容、AI 参与部分、
   问题与决策），并在 `MILESTONES.md` 勾选进度——DEVLOG 是"记录 AI 开发过程"的验收凭据。

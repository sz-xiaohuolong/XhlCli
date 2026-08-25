# Phase 00 Project Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可独立构建、测试和运行的 Java 21 XhlCLI 工程基线，并完成开源仓库所需的文档、授权、CI 与 CLI 元信息入口。

**Architecture:** Phase 00 只包含 Maven 构建和一个无业务依赖的 CLI 应用层。`Main` 负责进程边界，`CliApplication` 负责可测试的参数行为；模型、Agent 和工具从 Phase 01 起按需迁移。构建思路参考授权仓库提交 `e2b8df4` 的 `pom.xml`，但不迁移该提交中的 Agent、GLM Client 或 Tool Registry。

**Tech Stack:** Java 21、Maven Wrapper、JUnit 5、GitHub Actions、可执行 JAR

**Spec:** `PRD.md`、`docs/prd/phase-00-project-foundation.md`、`TECH_DESIGN.md`

## Global Constraints

- Java 编译使用 `maven.compiler.release=21`，不使用 Preview API。
- 根包名固定为 `com.xhlcli`；产品名固定为 `XhlCLI`。
- Phase 00 不包含模型、ReAct、Tool、HTTP、SQLite、JLine 或后期空包。
- 普通 `./mvnw test` 与 `./mvnw package` 默认执行测试。
- API Key、`.env`、`target/`、日志、索引、快照和用户状态不得提交。
- 不修改 `../paicli` 工作树；只允许用 `git -C ../paicli show e2b8df4:pom.xml` 读取构建参考。
- 不复制授权仓库 `.git` 或原提交元数据；XhlCLI 使用真实的新提交。
- 在 Task 1 开始前，仓库所有者必须提供书面授权要求的准确许可证正文及署名/NOTICE 条款；执行者逐字使用该文本，不自行改写法律条款。
- 每个 Task 结束时运行其完整验证，再提交；没有新鲜验证输出不得声明完成。

---

## File Structure

Phase 00 完成后应出现以下文件：

```text
xhlcli/
├── .github/
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml
│   │   └── feature_request.yml
│   ├── workflows/ci.yml
│   └── pull_request_template.md
├── .mvn/wrapper/                       # Maven Wrapper 生成文件
├── docs/
│   ├── engineering/source-adoption-map.md
│   ├── plans/2026-08-25-phase-00-project-foundation.md
│   └── prd/*.md
├── src/main/java/com/xhlcli/cli/
│   ├── CliApplication.java             # 可测试的 --help/--version 行为
│   └── Main.java                       # 唯一进程入口
├── src/test/java/com/xhlcli/cli/
│   └── CliApplicationTest.java
├── .env.example
├── .gitignore
├── AGENTS.md
├── CHANGELOG.md
├── CODE_OF_CONDUCT.md
├── CONTRIBUTING.md
├── LICENSE                             # 使用授权方确认的准确文本
├── NOTICE                              # 仅在授权条款要求时存在
├── PRD.md
├── README.md
├── RESEARCH.md
├── ROADMAP.md
├── SECURITY.md
├── TECH_DESIGN.md
├── mvnw
├── mvnw.cmd
└── pom.xml
```

职责约束：

- `Main.java`：读取 JAR Implementation-Version、调用 `CliApplication`、映射进程退出码。
- `CliApplication.java`：纯参数路由和文本输出，不调用 `System.exit`。
- `CliApplicationTest.java`：脱离真实终端验证输出和退出码。
- `README.md`：只陈述 Phase 00 已交付能力，不提前宣传 Agent。
- `ROADMAP.md`：从 PRD 投影阶段状态，不复制功能实现细节。
- `LICENSE` / `NOTICE`：反映书面授权和 XhlCLI 发布条件，不能凭经验猜测。

---

### Task 1: Initialize Repository Boundaries and Rights Files

**Files:**

- Create: `xhlcli/.gitignore`
- Create: `xhlcli/.env.example`
- Create: `xhlcli/LICENSE`
- Create if required by authorization: `xhlcli/NOTICE`

**Interfaces:**

- Consumes: 仓库所有者提供的准确授权与署名条款。
- Produces: 后续源代码迁移可以依赖的公开再发布边界；统一的本地文件忽略规则。

- [ ] **Step 1: Confirm the workspace is not already an initialized dirty repository**

Run:

```bash
git -C xhlcli status --short
```

Expected: 如果尚未初始化，返回“not a git repository”；如果已经初始化，先记录现有改动，禁止覆盖。

- [ ] **Step 2: Initialize XhlCLI Git history with the actual current identity and time**

Run:

```bash
git -C xhlcli init
git -C xhlcli branch -M main
git -C xhlcli status --short
```

Expected: 仓库位于 `main`，现有 PRD、Research、Tech Design、AGENTS 和计划显示为未跟踪文件。

- [ ] **Step 3: Create repository ignore rules**

Create `xhlcli/.gitignore` with exactly:

```gitignore
# Build
target/
*.class

# Secrets and local configuration
.env
.env.*
!.env.example
*.key
*.pem

# XhlCLI local state
.xhlcli/
logs/
output/

# IDE and OS
.idea/
*.iml
.vscode/
.DS_Store

# Temporary files
*.tmp
*.swp
```

- [ ] **Step 4: Create a non-secret environment template**

Create `xhlcli/.env.example` with exactly:

```dotenv
# Phase 00 does not call a model.
# Phase 01 will document the first supported provider variable here.
```

- [ ] **Step 5: Add the exact authorized license and notice**

Copy the rights holder-approved license text verbatim into `xhlcli/LICENSE`. If the authorization requires attribution or a NOTICE, copy the approved notice verbatim into `xhlcli/NOTICE`; otherwise do not create an empty NOTICE file.

Run:

```bash
test -s xhlcli/LICENSE
rg -n 'Copyright|License|许可|授权' xhlcli/LICENSE xhlcli/NOTICE 2>/dev/null
```

Expected: `LICENSE` 非空；需要 NOTICE 时能找到授权方要求的声明。不得用该检查结果推断法律充分性，内容以提供的书面文本为准。

- [ ] **Step 6: Verify no sensitive or generated file is tracked**

Run:

```bash
git -C xhlcli status --short --ignored
```

Expected: `.env`、`target/` 和 `.xhlcli/` 若存在则显示 ignored；`.env.example` 可跟踪。

- [ ] **Step 7: Commit repository boundaries**

```bash
git -C xhlcli add .gitignore .env.example LICENSE NOTICE 2>/dev/null || \
  git -C xhlcli add .gitignore .env.example LICENSE
git -C xhlcli commit -m "chore: establish repository and licensing boundaries"
```

Expected: 使用真实作者和当前时间创建首个提交；不包含源码或构建产物。

---

### Task 2: Add the Java 21 Maven Build

**Files:**

- Create: `xhlcli/pom.xml`
- Create: `xhlcli/mvnw`
- Create: `xhlcli/mvnw.cmd`
- Create: `xhlcli/.mvn/wrapper/*`

**Interfaces:**

- Consumes: Java 21 runtime.
- Produces: `./mvnw test`、`./mvnw package` 和 Main-Class 为 `com.xhlcli.cli.Main` 的 JAR 构建约定。

- [ ] **Step 1: Inspect the authorized build reference without modifying it**

Run:

```bash
git -C paicli show e2b8df4:pom.xml | sed -n '1,220p'
```

Expected: 看到早期 Maven、Jar 和 Shade 配置；只提取构建意图，不复制 `com.paicli`、Java 17 和 Phase 01 依赖。

- [ ] **Step 2: Create the Maven project descriptor**

Create `xhlcli/pom.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.xhlcli</groupId>
    <artifactId>xhlcli</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>XhlCLI</name>
    <description>Local-first terminal coding agent built with Java 21</description>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.10.2</junit.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>${maven.compiler.release}</release>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.xhlcli.cli.Main</mainClass>
                            <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Verify the POM requires Java 21 and has no Phase 01 dependencies**

Run:

```bash
rg -n 'maven.compiler.release|com\.xhlcli|junit' xhlcli/pom.xml
! rg -n 'okhttp|jackson|jline|sqlite|com\.paicli|<source>17|<target>17' xhlcli/pom.xml
```

Expected: release 21、XhlCLI 坐标和 JUnit 存在；模型与后期依赖不存在。

- [ ] **Step 4: Generate and pin Maven Wrapper**

From `xhlcli/`, run:

```bash
mvn wrapper:wrapper -Dmaven=3.9.9
./mvnw --version
```

Expected: Wrapper 文件生成，输出使用 Maven 3.9.9 和 Java 21。

- [ ] **Step 5: Verify the empty project lifecycle**

Run:

```bash
cd xhlcli
./mvnw test
./mvnw package
```

Expected: 两个命令 exit 0；此时 JAR 尚无 Main 类，因此不要运行产物。

- [ ] **Step 6: Commit build foundation**

```bash
git -C xhlcli add pom.xml mvnw mvnw.cmd .mvn
git -C xhlcli commit -m "build: add Java 21 Maven foundation"
```

---

### Task 3: Implement the Testable CLI Metadata Contract

**Files:**

- Create: `xhlcli/src/test/java/com/xhlcli/cli/CliApplicationTest.java`
- Create: `xhlcli/src/main/java/com/xhlcli/cli/CliApplication.java`
- Create: `xhlcli/src/main/java/com/xhlcli/cli/Main.java`

**Interfaces:**

- Consumes: `String[] args`、`PrintStream out`、`PrintStream err`、构建版本字符串。
- Produces: `CliApplication#run(String[]) -> int`；`Main#main(String[])` 作为进程入口。

- [ ] **Step 1: Write failing tests for no arguments, help, version, and unknown arguments**

Create `xhlcli/src/test/java/com/xhlcli/cli/CliApplicationTest.java`:

```java
package com.xhlcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CliApplicationTest {
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;
    private CliApplication application;

    @BeforeEach
    void setUp() {
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        application = new CliApplication(
                "0.1.0-test",
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsShowsProductAndNextStep() {
        assertEquals(0, application.run(new String[0]));
        assertTrue(out().contains("XhlCLI"));
        assertTrue(out().contains("--help"));
        assertEquals("", err());
    }

    @Test
    void helpShowsOnlyDeliveredPhaseZeroOptions() {
        assertEquals(0, application.run(new String[] {"--help"}));
        assertTrue(out().contains("--help"));
        assertTrue(out().contains("--version"));
        assertTrue(!out().contains("agent"));
    }

    @Test
    void versionUsesInjectedBuildVersion() {
        assertEquals(0, application.run(new String[] {"--version"}));
        assertEquals("XhlCLI 0.1.0-test\n", out());
    }

    @Test
    void unknownArgumentReturnsUsageError() {
        assertEquals(2, application.run(new String[] {"--unknown"}));
        assertTrue(err().contains("Unknown option: --unknown"));
        assertTrue(err().contains("--help"));
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail because the application class does not exist**

Run:

```bash
cd xhlcli
./mvnw -Dtest=CliApplicationTest test
```

Expected: FAIL during test compilation with `cannot find symbol: class CliApplication`.

- [ ] **Step 3: Implement the minimal CLI application**

Create `xhlcli/src/main/java/com/xhlcli/cli/CliApplication.java`:

```java
package com.xhlcli.cli;

import java.io.PrintStream;
import java.util.Objects;

public final class CliApplication {
    private final String version;
    private final PrintStream out;
    private final PrintStream err;

    public CliApplication(String version, PrintStream out, PrintStream err) {
        this.version = Objects.requireNonNull(version);
        this.out = Objects.requireNonNull(out);
        this.err = Objects.requireNonNull(err);
    }

    public int run(String[] args) {
        Objects.requireNonNull(args);
        if (args.length == 0) {
            out.println("XhlCLI — local-first terminal coding agent");
            out.println("Phase 00 foundation is ready. Run with --help for options.");
            return 0;
        }
        if (args.length == 1 && "--help".equals(args[0])) {
            printHelp();
            return 0;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            out.printf("XhlCLI %s%n", version);
            return 0;
        }
        err.printf("Unknown option: %s%n", String.join(" ", args));
        err.println("Run with --help for available options.");
        return 2;
    }

    private void printHelp() {
        out.println("Usage: xhlcli [option]");
        out.println("  --help       Show this help message");
        out.println("  --version    Show the current version");
    }
}
```

- [ ] **Step 4: Add the process entrypoint and manifest version fallback**

Create `xhlcli/src/main/java/com/xhlcli/cli/Main.java`:

```java
package com.xhlcli.cli;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = new CliApplication(version(), System.out, System.err).run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static String version() {
        String implementationVersion = Main.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "dev"
                : implementationVersion;
    }
}
```

- [ ] **Step 5: Run the focused tests and full test lifecycle**

Run:

```bash
cd xhlcli
./mvnw -Dtest=CliApplicationTest test
./mvnw test
```

Expected: 4 focused tests pass; full test command reports 0 failures and 0 errors.

- [ ] **Step 6: Package and smoke-test the executable JAR**

Run:

```bash
cd xhlcli
./mvnw package
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
```

Expected:

```text
Usage: xhlcli [option]
  --help       Show this help message
  --version    Show the current version
XhlCLI 0.1.0-SNAPSHOT
```

- [ ] **Step 7: Commit the CLI contract**

```bash
git -C xhlcli add src/main src/test
git -C xhlcli commit -m "feat: add phase zero CLI metadata contract"
```

---

### Task 4: Add Honest User and Contributor Documentation

**Files:**

- Create: `xhlcli/README.md`
- Create: `xhlcli/ROADMAP.md`
- Create: `xhlcli/CHANGELOG.md`
- Create: `xhlcli/CONTRIBUTING.md`
- Create: `xhlcli/CODE_OF_CONDUCT.md`
- Create: `xhlcli/SECURITY.md`
- Modify: `xhlcli/AGENTS.md`

**Interfaces:**

- Consumes: Phase 00 PRD and actual CLI commands.
- Produces: New-user quick start, contribution workflow, security channel, and accurate delivered/planned split.

- [ ] **Step 1: Create README with only Phase 00 delivered features**

`README.md` must contain these sections in this order:

````markdown
# XhlCLI

XhlCLI is a Java 21 terminal coding-agent project developed through small,
verifiable phases.

## Current status

Phase 00 is delivered: the repository has a Java 21 build, executable CLI
metadata commands, automated tests, and project documentation. Model chat and
Agent tools begin in later phases and are not available in this release.

## Requirements

- Java 21
- Git

## Build and verify

```bash
./mvnw test
./mvnw package
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
```

## Documentation

- [Product requirements](PRD.md)
- [Research](RESEARCH.md)
- [Technical design](TECH_DESIGN.md)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)

## Security

Never commit API keys or local XhlCLI state. See [SECURITY.md](SECURITY.md).

## License

See [LICENSE](LICENSE). Any required attribution is included in the repository's
license or notice files.
````

Use English for README v0.1 to make the public entry concise; Chinese learning notes remain in PRD and design documents.

- [ ] **Step 2: Create Roadmap with explicit status categories**

`ROADMAP.md` must list:

- Delivered: Phase 00 only.
- In design: Phase 01.
- Planned: Phase 02–18, each linking to its PRD.
- A warning that planned capabilities are not available in the current build.

- [ ] **Step 3: Create CHANGELOG using Keep a Changelog structure**

The first entry must be:

```markdown
# Changelog

All notable changes to XhlCLI are documented in this file.

## [Unreleased]

## [0.1.0] - 2026-08-25

### Added

- Java 21 Maven project foundation.
- Executable `--help` and `--version` CLI contract.
- Product requirements, technical design, contribution, and security documentation.
- Continuous integration for build and tests.
```

- [ ] **Step 4: Create contributor and governance documents**

`CONTRIBUTING.md` must include Java 21 setup, `./mvnw test`, `./mvnw package`, branch/commit rules, PR checklist, no-real-Key rule, and requirement to update the active Phase PRD/Tech Design when behavior changes.

`CODE_OF_CONDUCT.md` must use the Contributor Covenant text selected by the repository owner and include a real project contact method; do not invent an email address.

`SECURITY.md` must state supported versions, private reporting channel supplied by the repository owner, response expectations, and a warning not to open public issues containing credentials or exploitable details.

- [ ] **Step 5: Update AGENTS current-state commands after the build exists**

Change `AGENTS.md` current state to mark Phase 00 engineering foundation delivered, and retain Phase 01+ as not delivered. Keep the four Phase 00 commands exactly aligned with README.

- [ ] **Step 6: Verify documentation truthfulness and links**

Run:

```bash
cd xhlcli
rg -n 'Phase 00|Java 21|./mvnw test|--help|--version' \
  README.md ROADMAP.md CHANGELOG.md CONTRIBUTING.md AGENTS.md
! rg -n '已支持.*ReAct|已支持.*MCP|已支持.*RAG|currently supports.*Agent' \
  README.md ROADMAP.md
for link in PRD.md RESEARCH.md TECH_DESIGN.md ROADMAP.md CONTRIBUTING.md SECURITY.md LICENSE; do
  test -f "$link"
done
```

Expected: Phase 00 commands and status are present; no later capability is advertised as delivered; every root link exists.

- [ ] **Step 7: Commit project documentation**

```bash
git -C xhlcli add README.md ROADMAP.md CHANGELOG.md CONTRIBUTING.md \
  CODE_OF_CONDUCT.md SECURITY.md AGENTS.md PRD.md RESEARCH.md TECH_DESIGN.md docs
git -C xhlcli commit -m "docs: document phase zero product and contribution workflow"
```

---

### Task 5: Add GitHub Collaboration Templates and CI

**Files:**

- Create: `xhlcli/.github/workflows/ci.yml`
- Create: `xhlcli/.github/ISSUE_TEMPLATE/bug_report.yml`
- Create: `xhlcli/.github/ISSUE_TEMPLATE/feature_request.yml`
- Create: `xhlcli/.github/pull_request_template.md`

**Interfaces:**

- Consumes: Maven Wrapper and Java 21 project.
- Produces: Pull request build gate and structured contribution inputs.

- [ ] **Step 1: Create the CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Verify
        run: ./mvnw --batch-mode verify
      - name: Smoke test executable JAR
        run: |
          java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
          java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
```

- [ ] **Step 2: Create structured issue templates**

`bug_report.yml` must require version, OS, Java version, reproduction steps, expected behavior, actual behavior, and sanitized logs. It must warn users not to include API keys.

`feature_request.yml` must require user problem, proposed outcome, alternatives, target Phase, and scope exclusions. It must not ask contributors to design classes before the requirement is accepted.

- [ ] **Step 3: Create the pull request checklist**

`.github/pull_request_template.md` must require:

```markdown
## Summary

## Related requirement

## Verification

- [ ] Focused tests pass
- [ ] `./mvnw test` passes
- [ ] `./mvnw package` passes
- [ ] Documentation matches delivered behavior
- [ ] No secret, local state, or build artifact is included
- [ ] Existing user changes were preserved
```

- [ ] **Step 4: Validate workflow syntax and run the same command locally**

Run:

```bash
cd xhlcli
./mvnw --batch-mode verify
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
rg -n 'java-version.*21|./mvnw.*verify|Smoke test' .github/workflows/ci.yml
```

Expected: local commands exit 0 and workflow contains the same build and smoke path.

- [ ] **Step 5: Commit CI and collaboration templates**

```bash
git -C xhlcli add .github
git -C xhlcli commit -m "ci: verify Java 21 build and CLI smoke tests"
```

---

### Task 6: Run the Phase 00 Release Gate

**Files:**

- Modify: `xhlcli/CHANGELOG.md` only if verification uncovers a documented difference.
- Modify: `xhlcli/ROADMAP.md` only after every gate passes.

**Interfaces:**

- Consumes: All Phase 00 artifacts.
- Produces: Reproducible evidence that Phase 00 meets its PRD and is ready for a `v0.1.0` tag.

- [ ] **Step 1: Verify repository contents and ignored files**

Run:

```bash
git -C xhlcli status --short --ignored
git -C xhlcli ls-files | sort
```

Expected: no `.env`, `target/`, `.xhlcli/`, real credential, reference `.git`, or unrelated asset is tracked.

- [ ] **Step 2: Run the complete automated gate from a clean working tree**

Run:

```bash
cd xhlcli
./mvnw clean verify
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --version
set +e
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --unknown
exit_code=$?
set -e
test "$exit_code" -eq 2
```

Expected: build and tests exit 0; no-arg/help/version output matches README; unknown option exits 2.

- [ ] **Step 3: Scan for Phase 00 boundary violations**

Run:

```bash
cd xhlcli
! find src/main/java -type f | rg '/(agent|llm|tool|mcp|rag|memory|plan)/'
! rg -n 'com\.paicli|~/.paicli|\.paicli/' src pom.xml README.md
! git ls-files | rg '(^|/)(target|\.env|\.xhlcli|logs|output)/'
```

Expected: no later-phase package, old package/paths, or ignored runtime data exists.

- [ ] **Step 4: Verify documentation structure and status**

Run:

```bash
cd xhlcli
test "$(find docs/prd -name 'phase-*.md' | wc -l | tr -d ' ')" = 19
rg -q 'Phase 00' README.md ROADMAP.md CHANGELOG.md AGENTS.md
rg -q 'Phase 01' ROADMAP.md
! rg -n '\b(TODO|TBD)\b|待补充|占位符' \
  README.md ROADMAP.md CHANGELOG.md CONTRIBUTING.md SECURITY.md AGENTS.md
```

Expected: 19 phase specs exist; Phase 00 is delivered, later phases remain planned; no placeholder language.

- [ ] **Step 5: Perform a clean-directory manual check**

Clone or copy only tracked files to a temporary directory, then run:

```bash
./mvnw test
./mvnw package
java -jar target/xhlcli-0.1.0-SNAPSHOT.jar --help
```

Expected: no dependency on parent workspace, `../paicli`, user configuration, or untracked files.

- [ ] **Step 6: Mark Phase 00 delivered and commit only if every gate passed**

Update `ROADMAP.md` and `AGENTS.md` to say Phase 00 delivered with the exact verification date and commands. Then run:

```bash
git -C xhlcli add ROADMAP.md AGENTS.md
git -C xhlcli commit -m "docs: mark phase zero as verified"
git -C xhlcli status --short
```

Expected: final status is clean except ignored `target/`.

- [ ] **Step 7: Create the first tag only after CI passes on main**

Run after the GitHub Actions CI run is green:

```bash
git -C xhlcli tag -a v0.1.0 -m "XhlCLI Phase 00 project foundation"
git -C xhlcli show --stat --oneline v0.1.0
```

Expected: annotated tag points at the verified Phase 00 commit. Pushing the repository or tag requires explicit repository owner instruction.

---

## Self-Review Checklist

Before execution handoff, verify this plan against the Phase 00 PRD:

- Project docs: Tasks 1 and 4.
- Java 21 Maven skeleton: Tasks 2 and 3.
- `--help` / `--version`: Task 3.
- Test and CI: Tasks 3 and 5.
- Open-source basics: Tasks 1, 4 and 5.
- Secret and local-state protection: Tasks 1 and 6.
- Honest delivered/planned boundary: Tasks 4 and 6.
- Clean-environment reproducibility: Task 6.
- No Phase 01 capability leakage: Tasks 2, 3 and 6.

The exact license/NOTICE text is an external pre-execution input because it must come from the written authorization; this plan deliberately does not invent or paraphrase legal terms.

## Execution Handoff

Plan complete and saved to `docs/plans/2026-08-25-phase-00-project-foundation.md`. Execute it task-by-task only after the exact license and attribution text is available. Use real commits at each verified checkpoint; do not push or publish without explicit owner instruction.

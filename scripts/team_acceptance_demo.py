#!/usr/bin/env python3
"""用本地假模型验收真实终端入口；无需 API Key，不访问远程模型。"""

import argparse
import collections
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def step(identifier, description):
    return {"id": identifier, "description": description, "type": "ANALYSIS", "dependencies": []}


class DemoModel(BaseHTTPRequestHandler):
    counts = collections.Counter()
    problems = []
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            messages = request["messages"]
            system, text = messages[0]["content"], messages[-1]["content"]
            if "执行模式选择器" in system:
                kind = "分类"
                response = {"mode": "TEAM" if "自动团队验收" in text else "REACT", "reason": "离线验收指定模式"}
                assert not request.get("tools"), "分类器不能持有工具"
            elif "Task Planning Specialist" in system:
                assert not request.get("tools"), "规划者不能持有工具"
                if "【失败重规划评估】" in text:
                    kind = "重规划"
                    assert "演示验收缺陷" in text and "工作区文件变化" in text
                    response = {"decision": "REPLAN", "reason": "原计划缺少适配，先补适配再验收",
                                "steps": [step("replacement", "新增适配后完成自动验收")]}
                else:
                    kind = "初始规划"
                    goal = text.split("\n上下文与约束：", 1)[0]
                    response = {"steps": [step("initial", "故意失败步骤" if "自动团队验收" in goal else "手动步骤")]}
            elif "Quality Review Specialist" in system:
                kind = "审查"
                approved = "【待审查步骤任务】\n故意失败步骤" not in text
                response = {"approved": approved, "summary": "通过" if approved else "演示验收缺陷",
                            "issues": [] if approved else ["原接口不可用，须先补适配"]}
            elif "当前任务目标**：" in text:
                kind = "执行"
                if "当前任务目标**：故意失败步骤" in text:
                    response = "原方案执行未满足验收"
                elif "当前任务目标**：新增适配后完成自动验收" in text:
                    response = "替代步骤验收成功"
                else:
                    response = "手动团队验收成功"
            else:
                kind = "普通对话"
                if text == "追问自动团队成果":
                    assert any("[Team COMPLETED]" in str(m.get("content"))
                               and "替代步骤验收成功" in str(m.get("content")) for m in messages)
                response = "普通对话验收成功"
            with self.lock:
                self.counts[kind] += 1
            if not isinstance(response, str):
                response = json.dumps(response, ensure_ascii=False)
            chunks = [
                {"choices": [{"delta": {"content": response}}]},
                {"choices": [{"delta": {}, "finish_reason": "stop"}],
                 "usage": {"prompt_tokens": 1, "completion_tokens": 1}},
            ]
            payload = "".join("data: " + json.dumps(c, ensure_ascii=False) + "\n\n" for c in chunks)
            payload = (payload + "data: [DONE]\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:
            with self.lock:
                self.problems.append(str(error))
            self.send_error(500, "离线验收断言失败")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "target/xhlcli-1.0.1.jar")
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error("请先执行 ./mvnw package -DskipTests")
    with tempfile.TemporaryDirectory(prefix="xhlcli-team-demo-") as directory:
        root = Path(directory)
        home, workspace = root / "home", root / "workspace"
        home.mkdir()
        workspace.mkdir()
        (workspace / "existing.txt").write_text("用户已有内容", encoding="utf-8")
        environment = {k: os.environ[k] for k in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if k in os.environ}
        environment.update(HOME=str(home), TERM="dumb", NO_COLOR="1", DEEPSEEK_API_KEY="local-demo-key")
        server = ThreadingHTTPServer(("127.0.0.1", 0), DemoModel)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        command = ["java", "-Duser.home=" + str(home), "-Dxhlcli.task.dir=" + str(root / "tasks"),
                   "-jar", str(jar), "--base-url", f"http://127.0.0.1:{server.server_port}", "--team-mode", "auto"]

        def run(lines):
            result = subprocess.run(command, input="\n".join(lines) + "\n", text=True,
                                    capture_output=True, cwd=workspace, env=environment, timeout=45)
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)
            if DemoModel.problems:
                raise AssertionError(DemoModel.problems)
            return result.stdout

        try:
            first = run(["/team-mode", "普通问候", "自动团队验收", "/team-mode manual",
                         "追问自动团队成果", "/team 手动团队验收", "/exit"])
            expected = {"分类": 2, "初始规划": 2, "重规划": 1, "执行": 5, "审查": 5, "普通对话": 2}
            assert dict(DemoModel.counts) == expected, (dict(DemoModel.counts), first)
            for marker in ("team-mode=auto", "进入 Team 模式", "重规划 1/2", "替代步骤验收成功",
                           "team-mode=manual", "手动团队验收成功"):
                assert marker in first, (marker, first)
            history = home / ".xhlcli/history"
            assert "自动团队验收" in history.read_text(encoding="utf-8")
            second = run(["/history list 30", "/team-mode", "/exit"])
            assert "自动团队验收" in second and "team-mode=auto" in second, (second, history.read_text(encoding="utf-8"))
            assert "替代步骤验收成功" not in second, "重启只读取输入历史，不恢复完整聊天"
            assert dict(DemoModel.counts) == expected, "重启查看历史不应调用模型"
            assert (workspace / "existing.txt").read_text(encoding="utf-8") == "用户已有内容"
            print("通过：自动普通/Team、三次失败后换计划、关闭自动选择、手动 /team、成果追问。")
            print("通过：重启可查看安全输入历史，会话内开关不覆盖启动配置；已有文件保留。")
            print("模型请求统计：" + json.dumps(expected, ensure_ascii=False))
            print("全部请求使用本地假模型；未验证真实模型的决策质量。")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    main()

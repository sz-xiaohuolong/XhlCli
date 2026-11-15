#!/usr/bin/env python3
"""用真实 JAR、真实本机 Headroom 和模拟模型验收 CLI，不读取用户配置或真实密钥。"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "target/xhlcli-1.0.1.jar")
    parser.add_argument("--headroom-url", default="http://127.0.0.1:8787", help="已启动的本机 Headroom 根地址")
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error("请先在仓库根目录执行 ./mvnw package -DskipTests")
    with urllib.request.urlopen(args.headroom_url.rstrip("/") + "/health", timeout=5) as response:
        health = json.load(response)
    assert health.get("ready"), "Headroom 尚未就绪"

    with tempfile.TemporaryDirectory(prefix="xhlcli-headroom-") as directory:
        root = Path(directory)
        task_home, workspace = root / "user", root / "workspace"
        task_home.mkdir()
        (workspace / ".xhlcli").mkdir(parents=True)
        (workspace / ".xhlcli/rules.md").write_text("项目约束 HEADROOM_RULE_STAYS：保留全部搜索证据。", encoding="utf-8")
        original = "\n".join(f"src/very/long/package/path/Example.java:{i}: return HEADROOM_KEEP_EVIDENCE_{i};" for i in range(40))
        (workspace / "fixture.txt").write_text(original + "\n", encoding="utf-8")
        (workspace / ".env").write_text(
            "XHLCLI_HEADROOM_ENABLED=true\nXHLCLI_HEADROOM_TIMEOUT_MS=30000\n"
            + "XHLCLI_HEADROOM_URL=" + args.headroom_url + "\n", encoding="utf-8")
        calls, problems, observations = [], [], []
        scenario = "enabled"

        class Model(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    calls.append(request)
                    messages = request["messages"]
                    assert any("HEADROOM_RULE_STAYS" in m.get("content", "") for m in messages if m["role"] == "system")
                    tools = [m for m in messages if m["role"] == "tool"]
                    if tools:
                        assert len(tools) == 1 and tools[0]["tool_call_id"] == "acceptance-call"
                        observation = json.loads(tools[0]["content"])
                        assert observation["status"] == "success" and observation["data"] == {}
                        summary = observation["summary"]
                        for i in range(40):
                            assert f"{i}: return HEADROOM_KEEP_EVIDENCE_{i};" in summary, f"缺少搜索条目 {i}"
                        assert "<<ccr:" not in summary and "headroom_retrieve" not in summary
                        baseline = "命令执行完成 (exit code: 0)\n" + original + "\n"
                        if scenario == "enabled":
                            assert len(summary) < len(baseline), "样本未缩短，请检查 Headroom 版本及无损压缩配置"
                        else:
                            assert summary == baseline, "关闭后必须发送原正文"
                        observations.append((len(baseline), len(summary)))
                        delta, finish = {"content": "离线验收完成，40 条证据均已收到。"}, "stop"
                    else:
                        delta = {"tool_calls": [{"index": 0, "id": "acceptance-call", "type": "function",
                                                 "function": {"name": "execute_command", "arguments": json.dumps({"command": "cat fixture.txt"})}}]}
                        finish = "tool_calls"
                    chunk = {"choices": [{"delta": delta, "finish_reason": finish}]}
                    payload = ("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\ndata: [DONE]\n\n").encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except Exception as error:
                    problems.append(str(error))
                    self.send_error(500)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if key in os.environ}
        environment.update(TERM="dumb", NO_COLOR="1", DEEPSEEK_API_KEY="local-demo-key",
                           DEEPSEEK_BASE_URL=f"http://127.0.0.1:{server.server_port}")
        command = ["java", "-Duser.home=" + str(task_home), "-Dxhlcli.task.dir=" + str(root / "tasks"),
                   "-jar", str(jar), "--team-mode", "manual"]
        inputs = "验收工具压缩\ny\n/context\n/exit\n"
        try:
            for scenario in ("enabled", "disabled", "invalid"):
                calls.clear()
                overrides = {}
                if scenario == "disabled":
                    overrides = {"XHLCLI_HEADROOM_ENABLED": "false", "XHLCLI_HEADROOM_URL": "invalid",
                                 "XHLCLI_HEADROOM_TIMEOUT_MS": "invalid"}
                elif scenario == "invalid":
                    overrides = {"XHLCLI_HEADROOM_URL": "https://example.com"}
                result = subprocess.run(command, input=inputs, text=True, capture_output=True,
                                        cwd=workspace, env=environment | overrides, timeout=60)
                assert not problems, problems
                if scenario == "invalid":
                    assert result.returncode == 3 and not calls
                    assert "Headroom 地址无效" in result.stderr
                else:
                    assert result.returncode == 0 and len(calls) == 2, result.stdout + result.stderr
                    assert "离线验收完成" in result.stdout and "不代表账单" in result.stdout
                    assert ("已压缩" if scenario == "enabled" else "Headroom：已关闭") in result.stdout
            before, after = observations[0]
            print(f"通过：真实 Headroom {health.get('version')} 与真实 CLI JAR 联调，工具正文 {before} → {after} 字符，40 条证据保留。")
            print("通过：项目 .env 开启、/context 诊断、进程开关关闭优先、原文恢复、非法配置退出且不请求模型。")
            print("模型由本地 SSE 服务模拟；没有验证真实模型质量、账单节省或桌面 GUI 操作。")
        except Exception:
            if "result" in locals():
                print(result.stdout + result.stderr)
            raise
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    main()

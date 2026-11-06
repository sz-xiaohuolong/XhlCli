#!/usr/bin/env python3
"""使用真实 JAR 和本地假模型验收主对话记忆召回，不读取用户真实数据。"""

import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def entry(identifier, content, scope="project", **optional):
    return dict(id=identifier, content=content, scope=scope,
                timestamp="2026-01-01T00:00:00Z", source="acceptance", **optional)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "target/xhlcli-1.0.1.jar")
    jar = parser.parse_args().jar.resolve()
    if not jar.is_file():
        parser.error("请先执行 ./mvnw verify")
    with tempfile.TemporaryDirectory(prefix="xhlcli-memory-") as directory:
        root = Path(directory)
        home, workspace, other = root / "home", root / "workspace", root / "other"
        for folder in (home / ".xhlcli/memory", workspace / ".xhlcli/memory", other / ".xhlcli/memory"):
            folder.mkdir(parents=True)
        memory_file = workspace / ".xhlcli/memory/long_term.jsonl"
        records = [entry("redis", "Redis 因部署复杂被放弃，标识 A_ONLY。"),
                   entry("java", "Java 21，标识 JAVA_UNRELATED。"),
                   entry("old", "Redis 标识 SUPERSEDED_SHOULD_HIDE。", supersededBy="redis"),
                   entry("expired", "Redis 标识 EXPIRED_SHOULD_HIDE。", pinned=True, expiresAt="2020-01-01T00:00:00Z")]
        memory_file.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n坏 JSON\nnull\n", encoding="utf-8")
        (home / ".xhlcli/memory/long_term.jsonl").write_text(json.dumps(entry("global", "Redis 全局参考 GLOBAL_VISIBLE。", "global")), encoding="utf-8")
        (other / ".xhlcli/memory/long_term.jsonl").write_text(json.dumps(entry("other", "Redis 项目 B_SECRET。")), encoding="utf-8")
        problems, calls = [], []

        class Model(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                    messages = request["messages"]
                    users = [m["content"] for m in messages if m["role"] == "user"]
                    prompt = users[-1]
                    calls.append(prompt)
                    references = [text for text in users if text.startswith("历史参考资料：")]
                    assert len(references) <= 1, "动态资料不应堆积在历史中"
                    for message in messages:
                        if message["role"] == "system":
                            assert "A_ONLY" not in message["content"], "记忆不能提升为系统规则"
                    if len(calls) in (1, 2):
                        assert len(references) == 1
                        reference = references[0]
                        assert "A_ONLY" in reference and "GLOBAL_VISIBLE" in reference
                        assert not any(s in reference for s in ("JAVA_UNRELATED", "B_SECRET", "SUPERSEDED_SHOULD_HIDE", "EXPIRED_SHOULD_HIDE"))
                        assert "时间：2026-01-01" in reference and "执行授权" in reference
                        assert math.ceil(len(reference) / 2.5) + 4 <= 220, "包装后记忆超过配置预算"
                        if len(calls) == 1:
                            assert "NEW_NEXT_TURN" not in reference
                            with memory_file.open("a", encoding="utf-8") as f:
                                f.write(json.dumps(entry("new", "Redis 新增参考 NEW_NEXT_TURN。"), ensure_ascii=False) + "\n")
                        else:
                            assert "NEW_NEXT_TURN" in reference, "下一轮必须刷新记忆"
                    else:
                        assert not references, "零匹配或读取失败必须无动态记忆"
                    if len(calls) == 3:
                        assert "OAuth" in prompt
                        memory_file.unlink()
                        memory_file.mkdir()
                    assert len(calls) <= 4, "召回不应额外调用模型"
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                    self.end_headers()
                    chunk = {"choices": [{"delta": {"content": "离线回答完成"}}]}
                    self.wfile.write(("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\ndata: [DONE]\n\n").encode())
                except Exception as error:
                    problems.append(str(error))
                    self.send_error(500)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        environment = {k: os.environ[k] for k in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if k in os.environ}
        environment.update(HOME=str(home), TERM="dumb", NO_COLOR="1", DEEPSEEK_API_KEY="local-demo-key",
                           XHLCLI_MEMORY_TOKEN_BUDGET="220", XHLCLI_MEMORY_MAX_ENTRIES="3")
        command = ["java", "-Duser.home=" + str(home), "-Dxhlcli.task.dir=" + str(root / "tasks"),
                   "-jar", str(jar), "--base-url", f"http://127.0.0.1:{server.server_port}", "--team-mode", "manual"]
        lines = ["/memory recall Redis", "Redis 为什么没采用？", "/context", "Redis 再解释一次", "/context",
                 "/clear", "OAuth 如何配置？", "Redis 存储失败验收", "/context", "/exit"]
        try:
            result = subprocess.run(command, input="\n".join(lines) + "\n", text=True,
                                    capture_output=True, cwd=workspace, env=environment, timeout=60)
            assert not problems, problems
            assert result.returncode == 0, result.stdout + result.stderr
            assert len(calls) == 4, calls
            assert "记忆召回预览" in result.stdout and "命中" in result.stdout
            assert "主对话最近一轮实际注入" in result.stdout
            assert "记忆读取或召回失败" in result.stdout
            assert "已跳过" in result.stdout
            print("通过：真实 JAR 主对话按需召回、全局/项目隔离、生命周期过滤、旧 JSONL、坏行跳过。")
            print("通过：下一轮刷新、请求内仅一份临时资料、USER 角色注入、包装预算、零召回及读取失败降级。")
            print("通过：/memory recall 与 /context 可观察；4 轮任务恰好 4 次本地模型请求。")
            print("本验收不证明真实模型回答质量；同轮工具循环、图片预算和超限停止见 Java 集成测试。")
        except Exception:
            if 'result' in locals():
                print(result.stdout + result.stderr)
            raise
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    main()

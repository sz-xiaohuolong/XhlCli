#!/usr/bin/env python3
"""用本地模拟模型与真实 JAR 验收后台任务；不读取真实任务库或调用付费模型。"""

import argparse
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class DemoModel(BaseHTTPRequestHandler):
    entered = {name: threading.Event() for name in ("后台完成验收", "后台取消验收")}
    release = {name: threading.Event() for name in entered}
    problems = []
    calls = []
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_POST(self):
        try:
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            users = [m["content"] for m in request["messages"] if m["role"] == "user"]
            prompt = users[-1]
            assert users == [prompt], "每项后台及前台都应有独立历史"
            with self.lock:
                self.calls.append(prompt)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.end_headers()

            def emit(text):
                chunk = {"choices": [{"delta": {"content": text}}]}
                self.wfile.write(("data: " + json.dumps(chunk, ensure_ascii=False) + "\n\n").encode())
                self.wfile.flush()

            if prompt in self.entered:
                names = {tool["function"]["name"] for tool in request.get("tools", [])}
                assert "read_file" in names and not names.intersection({"write_file", "execute_command", "git_diff"})
                emit("部分分析已保存；" * 30)
                self.entered[prompt].set()
                assert self.release[prompt].wait(30), "验收未及时释放模拟模型"
                emit("后台最终结果" if prompt == "后台完成验收" else "不应保存的迟到答案")
            else:
                assert prompt == "前台并行验收"
                assert all(event.is_set() for event in self.entered.values()), "前台调用时两项后台应正在执行"
                emit("前台可继续正常对话")
            self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            with self.lock:
                self.problems.append(str(error))


def await_value(read, predicate, label, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = read()
        if predicate(value):
            return value
        time.sleep(0.05)
    raise AssertionError(label + "：" + str(read()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=Path(__file__).resolve().parents[1] / "target/xhlcli-1.0.1.jar")
    jar = parser.parse_args().jar.resolve()
    if not jar.is_file():
        parser.error("请先执行 ./mvnw verify")
    with tempfile.TemporaryDirectory(prefix="xhlcli-task-demo-") as directory:
        root = Path(directory)
        home, workspace, other = root / "home", root / "workspace", root / "other"
        for path in (home, workspace, other):
            path.mkdir()
        sentinel = workspace / "existing.txt"
        sentinel.write_text("用户已有内容", encoding="utf-8")
        environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME", "TMPDIR", "LANG") if key in os.environ}
        environment.update(HOME=str(home), TERM="dumb", NO_COLOR="1", DEEPSEEK_API_KEY="local-demo-key")
        server = ThreadingHTTPServer(("127.0.0.1", 0), DemoModel)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        command = ["java", "-Duser.home=" + str(home), "-Dxhlcli.task.dir=" + str(root / "tasks"),
                   "-Dxhlcli.task.workers=2", "-jar", str(jar), "--base-url",
                   f"http://127.0.0.1:{server.server_port}", "--team-mode", "manual"]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, cwd=workspace, env=environment, bufsize=1)
        output = []

        def capture():
            while char := process.stdout.read(1):
                output.append(char)

        reader = threading.Thread(target=capture, daemon=True)
        reader.start()

        def send(line):
            process.stdin.write(line + "\n")
            process.stdin.flush()

        def text():
            return "".join(output)

        def tasks():
            db = root / "tasks/tasks.db"
            if not db.exists():
                return []
            with sqlite3.connect(db) as connection:
                try:
                    return connection.execute("SELECT id, prompt, status, result FROM runtime_tasks ORDER BY rowid").fetchall()
                except sqlite3.OperationalError:
                    return []

        try:
            send("/task add 后台完成验收")
            send("/task add 后台取消验收")
            await_value(lambda: all(e.is_set() for e in DemoModel.entered.values()), bool, "后台未并发进入模型")
            rows = await_value(tasks, lambda rows: len(rows) == 2 and all(r[3] for r in rows), "部分输出未持久化")
            complete_id, cancel_id = rows[0][0], rows[1][0]
            send("/task list")
            send("/task log " + complete_id)
            await_value(text, lambda s: "当前输出（尚未完成）" in s, "运行日志不可查")
            send("前台并行验收")
            await_value(text, lambda s: "前台可继续正常对话" in s, "前台被后台阻塞")
            DemoModel.release["后台完成验收"].set()
            await_value(tasks, lambda rows: rows[0][2] == "completed", "后台完成状态未保存")
            send("/task cancel " + cancel_id)
            await_value(tasks, lambda rows: rows[1][2] == "canceled", "取消未确认")
            DemoModel.release["后台取消验收"].set()
            send("/task log " + cancel_id)
            send("/task log " + complete_id)
            send("/exit")
            assert process.wait(timeout=15) == 0, text()
            reader.join(timeout=2)
            assert "已记录取消请求" in text() and "状态: canceled" in text(), text()
            assert "后台最终结果" in text() and "查看结果：/task log" in text(), text()
            assert "不应保存的迟到答案" not in str(tasks())
            expected_calls = list(DemoModel.calls)

            def reopen(cwd, lines):
                result = subprocess.run(command, input="\n".join(lines) + "\n", text=True,
                                        capture_output=True, cwd=cwd, env=environment, timeout=20)
                assert result.returncode == 0, result.stdout + result.stderr
                return result.stdout

            restored = reopen(workspace, ["/task list", "/task log " + complete_id, "/exit"])
            assert complete_id in restored and "后台最终结果" in restored, restored
            hidden = reopen(other, ["/task list", "/task log " + complete_id, "/task cancel " + complete_id, "/exit"])
            assert "暂无后台任务" in hidden and "未找到后台任务" in hidden and "未找到可取消" in hidden, hidden
            assert DemoModel.calls == expected_calls, "重新查询不能触发模型或重放任务"
            assert not DemoModel.problems, DemoModel.problems
            assert sentinel.read_text(encoding="utf-8") == "用户已有内容"
            print("通过：两项后台并发、前台继续对话、当前输出、完成通知、取消与终态查询。")
            print("通过：重启结果可查且不重放；另一工作区不可列出、查询或取消；已有文件保留。")
            print("模型请求：" + json.dumps(expected_calls, ensure_ascii=False))
            print("全部使用本地模拟模型；不代表真实模型质量或远端服务已撤回请求。")
        except Exception:
            print(text())
            raise
        finally:
            for event in DemoModel.release.values():
                event.set()
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    main()

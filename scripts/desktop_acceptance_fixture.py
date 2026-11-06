#!/usr/bin/env python3
"""桌面离线验收：只在指定临时目录准备配置，提供真实 Provider 协议的测试模型。"""
import argparse
import json
import pathlib
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages = body.get('messages', [])
        with self.server.log_lock:
            with self.server.log_file.open('a') as out:
                out.write(json.dumps(body, ensure_ascii=False) + '\n')
        user = next((str(m.get('content', '')) for m in reversed(messages) if m['role'] == 'user'), '')
        last = messages[-1] if messages else {}
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        try:
            if last.get('role') == 'tool':
                self.text('工具已返回结果。\n\n')
                self.text('请展开工具记录检查输出；文件差异可在本轮变更中查看。')
            elif '验收写入' in user:
                self.text('我会创建一个验收文件，写入前请确认这次操作。\n\n')
                self.tool('write_file', {'path': 'desktop-demo.txt', 'content': 'XhlCLI 桌面验收成功\n'})
                return
            elif '验收命令' in user:
                self.text('准备执行项目内的命令。\n\n')
                self.tool('execute_command', {'command': 'pwd'})
                return
            elif '验收停止' in user:
                for i in range(120):
                    self.text(f'进度 {i + 1}。')
                    time.sleep(0.5)
            elif '只回复 OK' in user:
                self.text('OK')
            elif '验收续聊' in user:
                history = json.dumps(messages, ensure_ascii=False)
                self.text('已恢复历史：' + ('找到 maple 标记。' if 'maple' in history else '未找到 maple 标记。'))
            else:
                for chunk in ['已收到你的任务。\n\n', '这是本地验收模型的流式响应，', '实际执行仍使用 XhlCLI 的 Java Agent 与工具。\n\n', '你可以发送「验收写入」「验收命令」「验收停止」检查核心操作。']:
                    self.text(chunk)
                    time.sleep(0.4)
            self.event({}, 'stop')
            self.wfile.write(b'data: [DONE]\n\n')
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def event(self, delta, finish=None):
        event = {'choices': [{'index': 0, 'delta': delta, 'finish_reason': finish}]}
        self.wfile.write(('data: ' + json.dumps(event, ensure_ascii=False) + '\n\n').encode())
        self.wfile.flush()

    def text(self, value):
        self.event({'content': value})

    def tool(self, name, arguments):
        self.event({'tool_calls': [{'index': 0, 'id': 'fixture-' + str(time.time_ns()), 'type': 'function', 'function': {'name': name, 'arguments': json.dumps(arguments, ensure_ascii=False)}}]})
        self.event({}, 'tool_calls')
        self.wfile.write(b'data: [DONE]\n\n')
        self.wfile.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=pathlib.Path, required=True)
    parser.add_argument('--port', type=int, default=18791)
    args = parser.parse_args()
    root = args.root.resolve()
    root.mkdir(parents=True, exist_ok=False)
    home = root / 'home'
    (home / '.xhlcli').mkdir(parents=True)
    project = root / 'project'
    project.mkdir()
    (project / 'README.md').write_text('# 桌面隔离验收项目\n\n用户原有内容：maple。\n')
    config = home / '.xhlcli/config.json'
    config.write_text(json.dumps({'providers': {'deepseek': {'apiKey': 'fixture-local-only', 'baseUrl': f'http://127.0.0.1:{args.port}', 'model': 'deepseek-chat'}}}))
    config.chmod(0o600)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.log_file = root / 'requests.jsonl'
    server.log_lock = threading.Lock()
    print(json.dumps({'home': str(home), 'project': str(project), 'port': args.port}), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()

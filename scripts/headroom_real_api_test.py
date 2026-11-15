#!/usr/bin/env python3
"""使用当前项目的 DeepSeek 配置发起三次真实 API 请求，对比工具正文 Token；会消耗模型额度。"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=repo, help="读取该项目的 .env，保留现有用户配置优先级")
    parser.add_argument("--jar", type=Path, default=repo / "target/xhlcli-1.0.1.jar", help="当前项目构建的 JAR")
    parser.add_argument("--output", type=Path, help="保存无凭据请求和报告；默认使用新的临时目录")
    parser.add_argument("--compile-only", action="store_true", help="只验证探针编译，不调用真实 API")
    args = parser.parse_args()
    project, jar = args.project.resolve(), args.jar.resolve()
    source = repo / "scripts/headroom/HeadroomRealApiProbe.java"
    if not jar.is_file():
        parser.error("缺少 JAR，请先在仓库根目录执行 ./mvnw package -DskipTests")
    if not project.is_dir():
        parser.error("项目目录不存在")
    with tempfile.TemporaryDirectory(prefix="xhlcli-headroom-probe-classes-") as directory:
        classes = Path(directory)
        subprocess.run(["javac", "-cp", str(jar), "-d", str(classes), str(source)], check=True)
        if args.compile_only:
            print("探针编译通过；未调用模型 API。")
            return
        output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix="xhlcli-headroom-real-api-"))
        subprocess.run(["java", "-cp", str(classes) + os.pathsep + str(jar),
                        "com.xhlcli.llm.HeadroomRealApiProbe", str(project), str(output)], cwd=project, check=True)
        print("无凭据请求及实测报告保存在：" + str(output))


if __name__ == "__main__":
    main()

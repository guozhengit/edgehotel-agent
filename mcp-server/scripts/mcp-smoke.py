#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EdgeHotel MCP Server stdio 冒烟验证（MCP 协议 JSON-RPC over stdio）。
用法: python mcp-smoke.py [jar路径]
覆盖: initialize -> initialized -> tools/list -> tools/call(读3+写2+会员) -> 退出
"""
import json
import os
import re
import subprocess
import sys
import time

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "target", "edgehotel-mcp-server.jar")
JAR = os.path.abspath(JAR)
FALLBACK_JDK17 = r"D:\jdk\jdk-17.0.12"


def _resolve_java():
    """优先 JAVA_HOME；但 JDK<17 跑不了本 jar（类文件版本 61），探测版本后回退默认 JDK17。"""
    candidates = [os.environ.get("JAVA_HOME", ""), FALLBACK_JDK17]
    for home in candidates:
        if not home:
            continue
        java = os.path.join(home, "bin", "java.exe")
        if not os.path.isfile(java):
            continue
        try:
            out = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=15)
            ver = (out.stderr or "") + (out.stdout or "")
            m = re.search(r'version "(\d+)', ver)
            if m and int(m.group(1)) >= 17:
                return java
        except Exception:
            pass
    return os.path.join(FALLBACK_JDK17, "bin", "java.exe")


JAVA = _resolve_java()

def main():
    proc = subprocess.Popen(
        [JAVA, "-Dfile.encoding=UTF-8", "-jar", JAR],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        creationflags=0x08000000 if os.name == "nt" else 0,  # CREATE_NO_WINDOW
        bufsize=0)
    time.sleep(1.0)

    def send(obj):
        line = json.dumps(obj, ensure_ascii=False) + "\n"
        proc.stdin.write(line.encode("utf-8"))
        proc.stdin.flush()

    def recv(timeout=10):
        proc.stdout.flush()
        import select
        if os.name != "nt":
            r, _, _ = select.select([proc.stdout], [], [], timeout)
            if not r:
                raise TimeoutError("recv timeout")
            return json.loads(proc.stdout.readline().decode("utf-8"))
        # Windows：无 select 管道支持，用阻塞 readline + 超时线程守护（冒烟用）
        import threading
        result = {}

        def _read():
            try:
                result["line"] = proc.stdout.readline()
            except Exception as e:  # noqa: BLE001
                result["err"] = e

        t = threading.Thread(target=_read, daemon=True)
        t.start()
        t.join(timeout)
        if "line" not in result:
            raise TimeoutError("recv timeout")
        if "err" in result:
            raise result["err"]
        return json.loads(result["line"].decode("utf-8"))

    results = []
    # 1. initialize
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
          "params": {"protocolVersion": "2025-06-18",
                     "capabilities": {}, "clientInfo": {"name": "smoke", "version": "0.1"}}})
    init = recv()
    results.append(("initialize", "OK" if init.get("result") else "FAIL", init.get("result", {}).get("serverInfo")))
    # 2. initialized 通知
    send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
    # 3. tools/list
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    tools = recv()
    names = [t.get("name") for t in tools.get("result", {}).get("tools", [])]
    results.append(("tools/list", "OK" if len(names) >= 5 else "FAIL", ",".join(names)))

    # 4. tools/call：读3 + 写2 + 会员
    calls = [
        ("hotel_room_status", {"date": "2026-10-30", "room_type": "deluxe"}),
        ("hotel_room_status", {"date": "2026-12-25", "room_type": "suite"}),  # 未知日期防幻觉
        ("hotel_price_quote", {"room_type": "standard"}),
        ("hotel_booking_lookup", {"order_id": "B20261001"}),
        ("member_profile_query", {"member_id": "M-1001"}),
        ("complaint_escalate", {"guest_name": "王先生", "room_no": "802", "issue": "空调噪音"}),
        ("marketing_copy_generate", {"scenario": "welcome", "guest_name": "李女士"}),
    ]
    for i, (tool, args) in enumerate(calls):
        send({"jsonrpc": "2.0", "id": 10 + i, "method": "tools/call",
              "params": {"name": tool, "arguments": args}})
        resp = recv()
        if os.environ.get("MCP_SMOKE_DEBUG"):
            print(f"DEBUG {tool}: {json.dumps(resp, ensure_ascii=False)}", file=sys.stderr)
        res = resp.get("result", {})
        text = res.get("content", [{}])[0].get("text", "") if res.get("content") else ""
        results.append((tool, "OK" if not res.get("isError") else "ERR", text.replace("\n", " | ")))

    proc.stdin.close()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()

    for name, status, detail in results:
        print(f"[{status}] {name}: {detail}" if status == "OK" else f"[{status}] {name}: {detail}")
    ok = sum(1 for _, s, _ in results if s == "OK")
    print(f"\n== 冒烟结果: {ok}/{len(results)} PASS ==")
    sys.exit(0 if ok == len(results) else 1)

if __name__ == "__main__":
    main()

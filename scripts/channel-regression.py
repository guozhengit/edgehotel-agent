# -*- coding: utf-8 -*-
"""渠道回归（M5：渠道纳入回归集）：对运行中的应用跑全部渠道的回环自测。
前置：应用以 EH_CHANNEL_ENABLED=true EH_CHANNEL_ECHO=true + 各渠道密钥启动，模型无需加载。
用法: python scripts/channel-regression.py [--base http://localhost:8600]
覆盖：webhook（签名/防重放/缺参）+ 飞书（URL 验证/消息/错签名）+ 企微（URL 验证/消息/错签名/MsgId 幂等）+ 钉钉（单聊/群聊/非文本/错签名）。
"""
import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8600"
if "--base" in sys.argv:
    BASE = sys.argv[sys.argv.index("--base") + 1]

WEBHOOK_SECRET = os.environ.get("EH_CHANNEL_SECRET", "test-secret-123")
FEISHU_KEY = os.environ.get("EH_FEISHU_ENCRYPT_KEY", "test-encrypt-key-123")
FEISHU_TOKEN = os.environ.get("EH_FEISHU_VERIFICATION_TOKEN", "test-vt")
WECOM_TOKEN = os.environ.get("EH_WECOM_TOKEN", "test-wecom-token")
WECOM_AES = os.environ.get("EH_WECOM_ENCODING_AES_KEY", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG")
WECOM_RECEIVE = os.environ.get("EH_WECOM_RECEIVE_ID", "ww-test-corp")
DING_SECRET = os.environ.get("EH_DINGTALK_APP_SECRET", "dingtalk-test-secret")
def _resolve_java():
    """JDK17 探测回退（同 mcp-smoke.py：JAVA_HOME 可能指向 JDK8，JDK8 连 JEP 330 单文件源码启动都不支持）。"""
    for home in (os.environ.get("JAVA_HOME", ""), r"D:\jdk\jdk-17.0.12"):
        if not home:
            continue
        java = os.path.join(home, "bin", "java.exe")
        if not os.path.isfile(java):
            continue
        try:
            out = subprocess.run([java, "-version"], capture_output=True, timeout=15)
            ver = (out.stderr or b"").decode("utf-8", errors="replace") + (out.stdout or b"").decode("utf-8", errors="replace")
            import re as _re
            m = _re.search(r'version "(\d+)', ver)
            if m and int(m.group(1)) >= 17:
                return java
        except Exception:
            pass
    return os.path.join(r"D:\jdk\jdk-17.0.12", "bin", "java.exe")


JAVA = _resolve_java()

PASS = FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name}" + (f" | {detail}" if detail else ""))
    else:
        FAIL += 1
        print(f"  [FAIL] {name} | {detail}")


def post_json(url, obj, headers):
    req = urllib.request.Request(url, data=json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def run_fixture(java_file, *args):
    """调用单文件 Java 夹具生成器，返回 dict（BODY/TS/NONCE/SIG 或 TS/SIGN）。"""
    # Windows 下 java 控制台输出可能为 GBK，按字节捕获后宽松解码
    out = subprocess.run([JAVA, os.path.join(os.path.dirname(os.path.abspath(__file__)), java_file), *args],
                         capture_output=True, timeout=60)
    lines = out.stdout.decode("utf-8", errors="replace").strip().splitlines()
    d = {"BODY": lines[0]}
    for line in lines[1:]:
        k, _, v = line.partition("=")
        d[k] = v
    return d


# ---- 1. webhook ----
print("[webhook] /api/channel/webhook")
url = BASE + "/api/channel/webhook"


def webhook_call(obj, sig_secret=WEBHOOK_SECRET, ts=None):
    raw = json.dumps(obj, ensure_ascii=False)
    ts = str(ts if ts is not None else int(time.time()))
    sig = hmac.new(sig_secret.encode(), f"{ts}.{raw}".encode(), hashlib.sha256).hexdigest()
    return post_json(url, obj if False else raw, {"Content-Type": "application/json",
                       "X-EdgeHotel-Timestamp": ts, "X-EdgeHotel-Signature": sig})


def get_raw(url):
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")[:200]


def post_raw(url, raw, headers):
    req = urllib.request.Request(url, data=raw.encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


def webhook_call_raw(obj, sig_secret=WEBHOOK_SECRET, ts=None):
    raw = json.dumps(obj, ensure_ascii=False)
    ts = str(ts if ts is not None else int(time.time()))
    sig = hmac.new(sig_secret.encode(), f"{ts}.{raw}".encode(), hashlib.sha256).hexdigest()
    return post_raw(url, raw, {"Content-Type": "application/json",
                    "X-EdgeHotel-Timestamp": ts, "X-EdgeHotel-Signature": sig})


s, b = webhook_call_raw({"peer": "reg-webhook", "text": "回归-正常"})
check("webhook 对签名 echo", s == 200 and "ch-webhook-reg-webhook" in b)
s, _ = webhook_call_raw({"peer": "reg-webhook", "text": "x"}, sig_secret="wrong")
check("webhook 错签名 401", s == 401)
s, _ = webhook_call_raw({"peer": "reg-webhook", "text": "x"}, ts=int(time.time()) - 400)
check("webhook 重放 401", s == 401)
s, _ = webhook_call_raw({"peer": "", "text": "x"})
check("webhook 缺参 400", s == 400)

# ---- 2. 飞书 ----
print("[feishu] /api/channel/feishu/callback")
furl = BASE + "/api/channel/feishu/callback"
f = run_fixture("GenFeishuFixture.java", FEISHU_KEY,
                json.dumps({"type": "url_verification", "challenge": "reg-chg-1", "token": FEISHU_TOKEN}))
s, b = post_raw(furl, f["BODY"], {"Content-Type": "application/json",
                 "X-Lark-Timestamp": f["TS"], "X-Lark-Nonce": f["NONCE"], "X-Lark-Signature": f["SIG"]})
check("feishu URL 验证 challenge", s == 200 and "reg-chg-1" in b, f"{s} {b[:80]}")
f = run_fixture("GenFeishuFixture.java", FEISHU_KEY, json.dumps({
    "header": {"event_type": "im.message.receive_v1", "token": FEISHU_TOKEN},
    "event": {"sender": {"sender_id": {"open_id": "ou-reg-1"}, "sender_type": "user"},
              "message": {"message_id": "om-reg-1", "message_type": "text",
                          "content": json.dumps({"text": "渠道回归-飞书"}, ensure_ascii=False)}}}, ensure_ascii=False))
s, b = post_raw(furl, f["BODY"], {"Content-Type": "application/json",
                 "X-Lark-Timestamp": f["TS"], "X-Lark-Nonce": f["NONCE"], "X-Lark-Signature": f["SIG"]})
check("feishu 消息事件 echo", s == 200 and "code" in b, f"{s} {b[:80]}")
s, _ = post_raw(furl, f["BODY"], {"Content-Type": "application/json",
                "X-Lark-Timestamp": f["TS"], "X-Lark-Nonce": f["NONCE"], "X-Lark-Signature": "deadbeef"})
check("feishu 错签名 401", s == 401, f"{s} {b[:80]}")

# ---- 3. 企微 ----
print("[wecom] /api/channel/wecom/callback")
wurl = BASE + "/api/channel/wecom/callback"
w = run_fixture("GenWeComFixture.java", WECOM_TOKEN, WECOM_AES, WECOM_RECEIVE, "echo-reg-9527")
enc = w["BODY"].split("<Encrypt><![CDATA[")[1].split("]]")[0]  # 精确取 Encrypt 段（首个 CDATA 是 ToUserName）
s, b = get_raw(wurl + f"?msg_signature={w['SIG']}&timestamp={w['TS']}&nonce={w['NONCE']}"
               f"&echostr={urllib.parse.quote(enc)}")
check("wecom URL 验证明文回显", s == 200 and "echo-reg-9527" in b, f"{s} {b[:80]}")
w = run_fixture("GenWeComFixture.java", WECOM_TOKEN, WECOM_AES, WECOM_RECEIVE,
                '<xml><ToUserName><![CDATA[%s]]></ToUserName><FromUserName><![CDATA[reg-user-1]]></FromUserName>'
                '<CreateTime>1790000000</CreateTime><MsgType><![CDATA[text]]></MsgType>'
                '<Content><![CDATA[渠道回归-企微]]></Content><MsgId>7700001</MsgId></xml>' % WECOM_RECEIVE)
s, b = post_raw(wurl + f"?msg_signature={w['SIG']}&timestamp={w['TS']}&nonce={w['NONCE']}",
                w["BODY"], {"Content-Type": "application/xml"})
check("wecom 消息事件 success", s == 200 and "success" in b, f"{s} {b[:80]}")
s2, b2 = post_raw(wurl + f"?msg_signature={w['SIG']}&timestamp={w['TS']}&nonce={w['NONCE']}",
                 w["BODY"], {"Content-Type": "application/xml"})
check("wecom MsgId 幂等（重复 200 静默）", s2 == 200 and b2 == "", f"{s2} {b2[:80]}")
s, _ = post_raw(wurl + f"?msg_signature=deadbeef&timestamp={w['TS']}&nonce={w['NONCE']}",
                w["BODY"], {"Content-Type": "application/xml"})
check("wecom 错签名 401", s == 401, f"{s} {b[:80]}")

# ---- 4. 钉钉 ----
print("[dingtalk] /api/channel/dingtalk/callback")
durl = BASE + "/api/channel/dingtalk/callback"


def ding_call(payload, secret=DING_SECRET):
    ts = str(int(time.time() * 1000))
    sign = base64.b64encode(hmac.new(secret.encode(), f"{ts}\n{secret}".encode(), hashlib.sha256).digest()).decode()
    return post_json(durl, payload, {"Content-Type": "application/json", "timestamp": ts, "sign": sign})


s, b = ding_call({"conversationType": "1", "senderStaffId": "reg-ding-1", "msgtype": "text",
                  "text": {"content": "渠道回归-钉钉单聊"}, "sessionWebhook": ""})
check("dingtalk 单聊 200", s == 200, f"{s} {b[:60]}")
s, b = ding_call({"conversationType": "2", "conversationId": "cid-reg-9", "senderStaffId": "w-1",
                  "msgtype": "text", "text": {"content": "渠道回归-钉钉群聊"}, "sessionWebhook": ""})
check("dingtalk 群聊 200", s == 200, f"{s} {b[:60]}")
s, b = ding_call({"conversationType": "1", "senderStaffId": "x", "msgtype": "picture", "sessionWebhook": ""})
check("dingtalk 非文本恒 200", s == 200, f"{s} {b[:60]}")
s, b = ding_call({"conversationType": "1", "senderStaffId": "x", "msgtype": "text",
                  "text": {"content": "x"}}, secret="wrong")
check("dingtalk 错签名 401", s == 401, f"{s} {b[:60]}")

# ---- 5. 审计与计数 ----
print("[audit] /api/channel/messages + stats")
try:
    with urllib.request.urlopen(BASE + "/api/channel/messages", timeout=10) as r:
        msgs = json.loads(r.read().decode())
    channels = {m["channel"] for m in msgs.get("messages", [])}
    check("审计含四渠道", {"webhook", "feishu", "wecom", "dingtalk"} <= channels, str(channels))
    with urllib.request.urlopen(BASE + "/api/channel/messages/stats", timeout=10) as r:
        counters = json.loads(r.read().decode())["counters"]
    check("计数器有 echo 记录", any("echo_reply" in k and v > 0 for k, v in counters.items()))
except Exception as e:
    check("审计与计数可达", False, str(e))

print(f"\n== 渠道回归结果: {PASS} PASS / {FAIL} FAIL ==")
sys.exit(1 if FAIL else 0)

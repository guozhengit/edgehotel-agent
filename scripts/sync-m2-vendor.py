# -*- coding: utf-8 -*-
"""同步 agentscope SNAPSHOT 构件到 m2-vendor/（Docker 构建的离线仓库）。

背景：应用依赖 io.agentscope:2.0.4-SNAPSHOT（来自宿主机 agentscope-java 源码仓 mvn install），
Maven Central 无此构件，容器内构建必须依赖 vendor 目录。agentscope 发布中央仓库正式版后可退役本脚本。

用法：python scripts/sync-m2-vendor.py [--repo D:/repository]
"""
import os
import shutil
import sys

ARTIFACTS = [
    "agentscope-core",
    "agentscope-extensions-channel-common",
    "agentscope-extensions-channel-feishu",
    "agentscope-extensions-channel-wecom",
    "agentscope-extensions-model-openai",
    "agentscope-harness",
    "agentscope-openai-spring-boot-starter",
    "agentscope-spring-boot-starter",
]
VERSION = "2.0.4-SNAPSHOT"
VENDOR_ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "m2-vendor")


def verify():
    """校验 m2-vendor 完整性：7 构件 × (jar+pom+maven-metadata) 齐备。构建前可跑（复盘 P2）。"""
    missing = []
    for artifact in ARTIFACTS:
        d = os.path.join(VENDOR_ROOT, "io", "agentscope", artifact, VERSION)
        for suffix in (".jar", ".pom"):
            hits = [f for f in os.listdir(d) if f.endswith(suffix)] if os.path.isdir(d) else []
            if not hits:
                missing.append(f"{artifact}/{suffix}")
        if not os.path.isfile(os.path.join(d, "maven-metadata.xml")):
            missing.append(f"{artifact}/maven-metadata.xml")
    if missing:
        print("vendor 缺失: " + "; ".join(missing))
        print("修复: python scripts/sync-m2-vendor.py")
        sys.exit(1)
    print(f"vendor 完整 ✓（{len(ARTIFACTS)} 构件）")


def main():
    repo = "D:/repository"
    if "--repo" in sys.argv:
        repo = sys.argv[sys.argv.index("--repo") + 1]
    total = 0
    for artifact in ARTIFACTS:
        src = os.path.join(repo, "io", "agentscope", artifact, VERSION)
        dst = os.path.join(VENDOR_ROOT, "io", "agentscope", artifact, VERSION)
        if not os.path.isdir(src):
            sys.exit(f"缺失构件目录: {src}（先在宿主机 agentscope-java 执行 mvn install）")
        os.makedirs(dst, exist_ok=True)
        for f in os.listdir(src):
            if f.endswith(("-sources.jar", "-javadoc.jar")) or f.startswith("_"):
                continue
            shutil.copy2(os.path.join(src, f), os.path.join(dst, f))
            total += 1
        # file:// 仓库的 SNAPSHOT 解析需要标准 metadata 文件名
        local_meta = os.path.join(dst, "maven-metadata-local.xml")
        if os.path.isfile(local_meta):
            shutil.move(local_meta, os.path.join(dst, "maven-metadata.xml"))
        print(f"  [OK] {artifact}")
    print(f"同步完成：{len(ARTIFACTS)} 构件 / {total} 文件 -> {VENDOR_ROOT}")


if __name__ == "__main__":
    if "--verify" in sys.argv:
        verify()
    else:
        main()

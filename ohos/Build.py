"""Coil OHOS 独立构建，输出只写入新的工作目录。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
GROUP = "io.github.qsxs.cpf.coil"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def path_safe(root, relative):
    path = (root / relative).resolve()
    require(path.is_relative_to(root.resolve()), "路径越界：" + str(relative))
    return path


def check_source(source_sha):
    repository = ROOT.parent
    head = subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()
    require(head == source_sha and len(head) == 40, "源码提交不符")
    result = subprocess.run(["git", "-C", str(repository), "diff", "--quiet", "HEAD", "--", "ohos"], check=False)
    require(result.returncode == 0, "源码目录与提交不一致")
    info = read(ROOT / "source-inputs.json")
    require(len(info["sources"]) == 145 and len(info["archives"]) == 7, "源码数不符")
    require(info["cpfSourceGitCommit"] == "UNKNOWN", "发布源码提交身份不符")
    payloads = {}
    for row in info["sources"]:
        snapshot = path_safe(ROOT, row["path"]).read_bytes()
        if row["buildSource"] == "UPSTREAM_GIT_BLOB":
            require(row["upstreamPath"], "上游源码映射不符")
            require((len(snapshot), sha(snapshot)) == (row["snapshotSize"], row["snapshotSha256"]),
                    "冻结副本摘要不符：" + row["path"])
            data = subprocess.check_output(["git", "-C", str(repository), "show", "HEAD:" + row["upstreamPath"]])
        else:
            require(row["buildSource"] == "FORK_OHOS_OVERLAY", "未知源码来源")
            data = snapshot
        require((len(data), sha(data)) == (row["compiledSize"], row["compiledSha256"]),
                "编译源码已变化，需要重新评估：" + row["path"])
        payloads[(row["module"], row["entry"])] = data
    require(sum(row["buildSource"] == "UPSTREAM_GIT_BLOB" for row in info["sources"]) == 134,
            "上游源码映射数不符")
    license_bytes = (ROOT / "legal/LICENSE").read_bytes()
    require(sha(license_bytes) == "5ade6484903351f6661e791504c50ad1a24cab3a75950c1b2a7be3eb336b6ba9", "许可证摘要不符")
    return info, payloads


def prepare(work, fingerprint, info, cfg, payloads):
    work.mkdir(parents=True, exist_ok=False)
    raw_rows, compiled_rows = [], []
    for row in info["sources"]:
        destination = path_safe(work, "compiled/" + row["module"] + "/" + row["entry"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(payloads[(row["module"], row["entry"])])
        common = {"module": row["module"], "entry": row["entry"], "sourceSet": row["sourceSet"]}
        raw_rows.append(common | {"sha256": row["originalSha256"], "size": row["originalSize"]})
        compiled_rows.append(common | {"path": str(destination), "sha256": row["compiledSha256"],
                                       "size": row["compiledSize"], "originalSha256": row["originalSha256"],
                                       "originalSize": row["originalSize"]})
    write(work / "source-manifest.json", {"fingerprint": fingerprint, "members": raw_rows})
    write(work / "compiled-source-manifest.json", {"fingerprint": fingerprint,
          "sourceVariant": cfg["sourceVariant"], "members": compiled_rows})
    legal = work / "legal/META-INF/LICENSE"
    legal.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "legal/LICENSE", legal)


def transient_network_failure(text):
    retryable = ("connect timed out", "read timed out", "connection reset", "connection refused",
                 "temporary failure in name resolution", "remote host terminated the handshake",
                 "status code 429", "status code 500", "status code 502", "status code 503", "status code 504")
    if "* What went wrong:" not in text:
        return False
    failure = text.rsplit("* What went wrong:", 1)[-1].split("* Try:", 1)[0].lower()
    nonretryable = ("compilation error", "compilation failed", "unresolved reference", "could not find", "checksum mismatch")
    return not any(reason in failure for reason in nonretryable) and any(reason in failure for reason in retryable)


def gradle(work, native, fingerprint, version, task):
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), "-PportWork=" + str(work), "-PportFingerprint=" + fingerprint,
               "-PportVersion=" + version, "-Pkotlin.native.home=" + str(native),
               "--project-cache-dir", str(work / "project-cache"), "--console=plain", "--stacktrace", task]
    env = os.environ.copy()
    env["GRADLE_USER_HOME"] = str(work / "gradle-home")
    env["KONAN_DATA_DIR"] = str(work / "konan")
    attempt = 1
    while True:
        log = work / "logs" / (task + ".attempt-" + str(attempt) + ".log")
        log.parent.mkdir(parents=True, exist_ok=True)
        print("执行 " + task + "，第 " + str(attempt) + " 次", flush=True)
        with log.open("x", encoding="utf-8") as stream:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, check=False)
        print(task + " exit=" + str(result.returncode), flush=True)
        if result.returncode == 0:
            return
        text = log.read_text(encoding="utf-8", errors="replace")
        if not transient_network_failure(text):
            raise RuntimeError("Gradle 阶段失败：" + task + "；日志：" + str(log))
        print("依赖传输暂时失败，保留本轮缓存与日志后重试：" + task, flush=True)
        time.sleep(min(attempt * 2, 30))
        attempt += 1


def verify_resolve(work, fingerprint, version, cfg):
    report = read(work / "evidence/resolve.json")
    require(report["fingerprint"] == fingerprint, "解析证据身份不符")
    pins = read(ROOT / "dependency-pins.json")
    metadata_pins = read(ROOT / "metadata-pins.json")
    modules = cfg["modules"]
    expected = {(name, source + "ResolvableDependenciesMetadata") for name, model in modules.items()
                for source in model["sourceSetParents"] if not source.endswith(("X64Main", "Arm64Main"))}
    expected |= {(name, "ohos" + target + "CompileKlibraries") for name in modules for target in ("X64", "Arm64")}
    configurations = report["configurations"]
    actual = {(c["module"], c["configuration"]) for c in configurations}
    require(len(configurations) == len(expected) and actual == expected, "解析配置集合不符")
    for config in configurations:
        for component in config["components"]:
            if component["kind"] == "module":
                ga = component["group"] + ":" + component["module"]
                expected_version = (metadata_pins.get(ga) if config["scope"] == "metadata" else None) or pins.get(ga)
                if expected_version == "__PORT_VERSION__":
                    expected_version = version
                require(expected_version == component["version"], "解析到未锁定版本：" + ga)
            elif component["kind"] == "project":
                require(component["project"] in modules, "解析到未知子项目")
            else:
                require(component["kind"] == "root", "解析到未知组件类型")
    plugin_modules = set(cfg["poko"]["modules"])
    for item in report["compilerPlugins"]:
        paths = [Path(f["path"]).name for f in item["files"]]
        poko = [x for x in paths if x.startswith("poko-compiler-plugin-")]
        if item["module"] in plugin_modules:
            require(poko == ["poko-compiler-plugin-0.23.2.jar"], "Poko 插件未启用：" + item["task"])
            require("plugin:dev.drewhamilton.poko:pokoAnnotation=coil3/annotation/Poko" in item["options"], "Poko marker 不符")
            require("plugin:dev.drewhamilton.poko:enabled=true" in item["options"], "Poko 未启用")
        else:
            require(not poko, "非 marker 模块不应启用 Poko")
    launchers = report["metadataLaunchers"]
    launcher_tools = read(ROOT / "metadata-toolchain.json")["launcherArtifacts"]
    require({x["module"] for x in launchers} == set(modules), "metadata 启动器集合不符")
    for launcher in launchers:
        artifacts = {x["group"] + ":" + x["module"]: x for x in launcher["artifacts"] if x["kind"] == "module"}
        require(set(artifacts) == set(launcher_tools), "metadata 启动器依赖不完整")
        for ga, expected_file in launcher_tools.items():
            files = artifacts[ga]["files"]
            require(len(files) == 1 and (files[0]["sha256"], files[0]["size"]) ==
                    (expected_file["sha256"], expected_file["size"]), "metadata 启动器摘要不符：" + ga)
    write(work / "evidence/closure-verification.json", {"fingerprint": fingerprint,
          "status": "RESOLVED_VERSIONS_POKO_AND_METADATA_LAUNCHER_VERIFIED", "runtime": "NOT_RUN"})


def verify_producers(work, fingerprint, stage, cfg):
    report = read(work / "evidence" / (stage + ".json"))
    require(report["fingerprint"] == fingerprint and report["stage"] == stage, "生产阶段身份不符")
    producers = report["producers"]
    if stage == "metadata":
        expected = set()
        for module, model in cfg["modules"].items():
            expected.add(":" + module + ":allMetadataJar")
            for source in model["sourceRoots"]:
                cap = source[0].upper() + source[1:]
                expected.add(":" + module + ":" + ("compileCommonMainWithMetadataCli" if source == "commonMain" else "compile" + cap + "KotlinMetadata"))
                expected.add(":" + module + ":transform" + cap + "DependenciesMetadata")
    else:
        target = "OhosX64" if stage == "native-x64" else "OhosArm64"
        expected = {":" + module + ":compileKotlin" + target for module in cfg["modules"]}
    require({p["task"] for p in producers} == expected and len(producers) == len(expected), "编译任务集合不符")
    for producer in producers:
        state = producer["state"]
        require(state["inTaskGraph"] and state["executed"] and not state["failure"] and producer["outputs"],
                "生产任务未成功：" + producer["task"])
        if stage != "metadata":
            require(producer["sourceInputs"], "Native 任务缺少源码：" + producer["task"])
    write(work / "evidence" / (stage + "-verification.json"), {"fingerprint": fingerprint,
          "status": "PRODUCER_TASKS_VERIFIED", "count": len(producers)})


def verify_gmm_files(directory, gmm):
    for variant in gmm["variants"]:
        for file in variant.get("files", []):
            target = path_safe(directory, file["url"])
            data = target.read_bytes()
            require(len(data) == file["size"] and ("sha256" not in file or sha(data) == file["sha256"]), "GMM 文件摘要不符")


def verify_publication(work, fingerprint, version, cfg, info):
    repository = work / "maven"
    group_dir = repository / Path(*GROUP.split("."))
    results = []
    for module, model in cfg["modules"].items():
        for suffix in ("", "-ohosx64", "-ohosarm64"):
            artifact = module + suffix
            directory = group_dir / artifact / version
            prefix = artifact + "-" + version
            gmm = read(directory / (prefix + ".module"))
            component = gmm["component"]
            require(component["group"] == GROUP and component["module"] == module and component["version"] == version,
                    "发布 owner 不符：" + artifact)
            pom = ET.parse(directory / (prefix + ".pom")).getroot()
            ns = {"m": "http://maven.apache.org/POM/4.0.0"}
            require(pom.findtext("m:groupId", namespaces=ns) == GROUP and pom.findtext("m:artifactId", namespaces=ns) == artifact
                    and pom.findtext("m:version", namespaces=ns) == version, "POM 坐标不符：" + artifact)
            require((directory / (prefix + "-license.txt")).read_bytes() == (ROOT / "legal/LICENSE").read_bytes(), "许可证制品不符")
            verify_gmm_files(directory, gmm)
            if not suffix:
                api = next(v for v in gmm["variants"] if v["name"] == "metadataApiElements")
                edges = {d["module"] for d in api.get("dependencies", []) if d["group"] == GROUP}
                require(edges == set(model["projectApiDependencies"]), "模块 API 依赖不符：" + module)
                for target in ("ohosx64", "ohosarm64"):
                    require(any(v.get("available-at", {}).get("module") == module + "-" + target and
                                v["available-at"].get("version") == version for v in gmm["variants"]), "缺少 ABI 发布映射")
                with zipfile.ZipFile(directory / (prefix + "-sources.jar")) as archive:
                    names = set(archive.namelist())
                    require("META-INF/LICENSE" in names and "META-INF/upstream/source-inputs.json" in names, "源码许可证或溯源缺失")
                    for row in info["sources"]:
                        if row["module"] != module:
                            continue
                        entry = row["entry"]
                        if entry.startswith("ohosArm64Main/"):
                            entry = "ohosMain/" + entry.split("/", 1)[1]
                        require(entry in names and sha(archive.read(entry)) == row["compiledSha256"], "发布源码不符：" + module + "/" + entry)
            else:
                target = "ohos_x64" if suffix == "-ohosx64" else "ohos_arm64"
                with zipfile.ZipFile(directory / (prefix + ".klib")) as archive:
                    names = archive.namelist()
                    manifest = archive.read("default/manifest").decode()
                    require("native_targets=" + target in manifest and "compiler_version=2.4.20-0.1.0-08" in manifest,
                            "KLIB 目标或编译器不符：" + artifact)
                    require(any(n.startswith("default/ir/") and not n.endswith("/") for n in names) and
                            any(n.startswith("default/linkdata/") and not n.endswith("/") for n in names), "KLIB 缺少 IR/metadata")
            results.append({"artifact": artifact, "moduleSha256": sha((directory / (prefix + ".module")).read_bytes())})
    require(len(results) == 21, "发布模块数不符")
    write(work / "evidence/publication-verification.json", {"fingerprint": fingerprint, "version": version,
          "status": "SEVEN_MODULES_METADATA_AND_BOTH_ABIS_VERIFIED", "publications": results,
          "link": "NOT_RUN", "runtime": "NOT_RUN"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--native-home", type=Path, required=True)
    args = parser.parse_args()
    require(args.run_id.isdecimal() and args.run_attempt.isdecimal(), "CI 标识不符")
    info, payloads = check_source(args.source_sha)
    cfg = read(ROOT / "port-config.json")
    version = "3.3.0-cpf11-preview-" + args.source_sha[:12] + "-" + args.run_id + "-" + args.run_attempt
    fingerprint = sha((args.source_sha + ":" + version + ":" + sha((ROOT / "source-inputs.json").read_bytes())).encode())
    work = args.work.resolve()
    native = args.native_home.resolve()
    require(native.is_dir() and not work.exists(), "Native 工具缺失或工作目录已存在")
    prepare(work, fingerprint, info, cfg, payloads)
    write(work / "identity.json", {"sourceSha": args.source_sha, "version": version, "fingerprint": fingerprint})
    gradle(work, native, fingerprint, version, "recordResolvedDependencies")
    verify_resolve(work, fingerprint, version, cfg)
    for task, stage in (("recordMetadataProducers", "metadata"), ("recordX64Producers", "native-x64"),
                        ("recordArm64Producers", "native-arm64")):
        gradle(work, native, fingerprint, version, task)
        verify_producers(work, fingerprint, stage, cfg)
    gradle(work, native, fingerprint, version, "publishAllPublicationsToPortRepository")
    verify_publication(work, fingerprint, version, cfg, info)
    print("独立 Coil 构建和发布核验通过：" + version, flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Coil 构建失败：" + str(error), file=sys.stderr, flush=True)
        raise

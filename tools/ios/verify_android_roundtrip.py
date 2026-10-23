#!/usr/bin/env python3
"""Restore synthetic Swift JSON/ZIP exports with the unchanged Android data layer."""
from pathlib import Path
import hashlib, json, os, subprocess, sys, urllib.request

root = Path(__file__).resolve().parents[2]
evidence = root / "ios-evidence"
workspace = Path(os.environ["RUNNER_TEMP"]) / "organizer-android-interop"
jars = workspace / "jars"
jars.mkdir(parents=True, exist_ok=True)
artifacts = [
    ("org/jetbrains/kotlin", "kotlin-compiler-embeddable", "2.1.21"),
    ("org/jetbrains/kotlin", "kotlin-stdlib", "2.1.21"),
    ("org/jetbrains/kotlin", "kotlin-script-runtime", "2.1.21"),
    ("org/jetbrains/kotlin", "kotlin-reflect", "1.6.10"),
    ("org/jetbrains/kotlin", "kotlin-daemon-embeddable", "2.1.21"),
    ("org/jetbrains/intellij/deps", "trove4j", "1.0.20200330"),
    ("org/jetbrains", "annotations", "13.0"),
    ("org/jetbrains/kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ("org/json", "json", "20250107"),
    ("org/xerial", "sqlite-jdbc", "3.49.1.0"),
    ("org/slf4j", "slf4j-api", "2.0.17"),
    ("org/slf4j", "slf4j-simple", "2.0.17"),
]
for group, name, version in artifacts:
    dest = jars / f"{name}-{version}.jar"
    urllib.request.urlretrieve(f"https://repo.maven.apache.org/maven2/{group}/{name}/{version}/{dest.name}", dest)
(evidence / "interop-dependency-sha256.json").write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(jars.glob("*.jar"))}, indent=2) + "\n")
out = evidence / "android-interop"
subprocess.run([sys.executable, str(root / "tools/ios/android_bridge.py"), "--jars", str(jars), "--output", str(out), "verify", str(evidence / "ios-roundtrip.json"), str(root / "mobile/ios/OrganizerCore/Tests/OrganizerCoreTests/Fixtures/android-v6.json")], check=True)
cp = os.pathsep.join([str(out / "classes"), *[str(p) for p in sorted(jars.glob("*.jar"))]])
subprocess.run(["java", "-cp", cp, "kz.kairat.organizer.AndroidBridgeMainKt", "verify", str(evidence / "ios-roundtrip.zip"), str(root / "mobile/ios/OrganizerCore/Tests/OrganizerCoreTests/Fixtures/android-v6.json")], check=True)
(evidence / "android-interop-result.json").write_text(json.dumps({"json": "passed", "zip": "passed", "checks": ["records", "amounts", "links", "preferences", "attachment bytes"], "syntheticOnly": True, "androidSourceModified": False}, indent=2) + "\n")

#!/usr/bin/env python3
"""Build with an installed SDK/JDK; no Gradle and no build-time downloads.

Works on Windows, native Linux and WSL. In WSL the SDK may live on the Windows
side (/mnt/c/...); Windows-only tools (aapt, zipalign) are then invoked through
interop with converted paths, while javac/jar/d8/apksigner stay on the Linux JVM.
"""
import hashlib
import os
import platform
import re
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parent
is_windows = os.name == "nt"
is_wsl = ("microsoft" in platform.release().lower()) or ("WSL_DISTRO_NAME" in os.environ)


def find_sdk():
    candidates = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(key):
            candidates.append(Path(os.environ[key]))
    home = Path.home()
    candidates += [home / "Android/Sdk", Path("/opt/android-sdk"), Path("/usr/lib/android-sdk")]
    if is_windows:
        local = Path(os.environ.get("LOCALAPPDATA", home / "AppData/Local"))
        candidates += [local / "Android/Sdk", Path("C:/Android/Sdk"), Path("C:/other/Android/sdk")]
    elif is_wsl:
        candidates.append(Path("/mnt/c/other/Android/sdk"))
        users = Path("/mnt/c/Users")
        if users.exists():
            for user in users.iterdir():
                candidates.append(user / "AppData/Local/Android/Sdk")
    for candidate in candidates:
        if (candidate / "platforms").is_dir() and (candidate / "build-tools").is_dir():
            return candidate
    raise SystemExit("Android SDK not found; set ANDROID_HOME to a folder with platforms/ and build-tools/.")


def newest(base):
    versions = []
    for entry in base.iterdir():
        if entry.is_dir():
            match = re.search(r"(\d+)", entry.name)
            if match:
                versions.append((int(match.group(1)), entry.name, entry))
    if not versions:
        raise SystemExit("No usable version under " + str(base))
    versions.sort(key=lambda item: (item[0], item[1]))
    return versions[-1][2]


def find_jdk():
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin/javac").exists():
        return Path(home)
    javac = shutil.which("javac")
    if javac:
        return Path(javac).resolve().parent.parent
    raise SystemExit("JDK not found; set JAVA_HOME.")


sdk = find_sdk()
platform_dir = newest(sdk / "platforms")
build_tools = newest(sdk / "build-tools")
jdk = find_jdk()
android = platform_dir / "android.jar"
windows_tools = is_windows or (is_wsl and str(sdk).startswith("/mnt/"))

print("SDK:", sdk)
print("Platform:", platform_dir.name)
print("Build tools:", build_tools.name)
print("JDK:", jdk)


def native_path(path):
    """Windows binaries invoked from WSL need Windows-style absolute paths."""
    value = str(path)
    if is_windows:
        return value
    if is_wsl and value.startswith("/mnt/"):
        return subprocess.check_output(["wslpath", "-w", value], text=True).strip()
    return value


def sdk_tool(name, external=False):
    """external=True forces a Windows executable (aapt/zipalign have no JVM entry point)."""
    if windows_tools and external:
        exe = build_tools / (name + ".exe")
        if not exe.exists():
            raise SystemExit("Missing " + str(exe))
        return exe
    return build_tools / name


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=str(root), check=True)


build_root = root / "build"
build_root.mkdir(parents=True, exist_ok=True)
build = Path(tempfile.mkdtemp(prefix="v3-", dir=build_root))
dist = root / "dist"
for directory in (build / "classes", build / "dex", dist):
    directory.mkdir(parents=True, exist_ok=True)

libraries = []
for aar in sorted((root / "libs").glob("*-13.1.5.aar")):
    jar = build / (aar.stem + ".jar")
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    libraries.append(jar)
if len(libraries) != 4:
    raise SystemExit("Expected pinned Shizuku api/provider/aidl/shared 13.1.5 AARs in libs/")

javac = jdk / "bin/javac"
jar_tool = jdk / "bin/jar"
keytool = jdk / "bin/keytool"
java = jdk / "bin/java"
aapt = sdk_tool("aapt", external=True)

# aapt must generate R.java before javac; the final package step runs after dexing.
gen = build / "gen"
gen.mkdir(parents=True, exist_ok=True)
run(aapt, "package", "-f", "-m",
    "-J", native_path(gen),
    "-M", native_path(root / "AndroidManifest.xml"),
    "-S", native_path(root / "res"),
    "-I", native_path(android))
r_java = gen / "org/nfcresearch/qpolllab/R.java"
if not r_java.exists():
    raise SystemExit("aapt did not generate R.java")

sources = sorted((root / "src").rglob("*.java")) + [r_java]
run(javac, "-encoding", "UTF-8", "--release", "8",
    "-classpath", os.pathsep.join(str(p) for p in [android, *libraries]),
    "-d", build / "classes", *sources)
run(jar_tool, "cf", build / "classes.jar", "-C", build / "classes", ".")

d8_jar = build_tools / "lib/d8.jar"
if d8_jar.exists():
    run(java, "-Xmx2g", "-cp", d8_jar, "com.android.tools.r8.D8", "--min-api", "26",
        "--lib", android, "--output", build / "dex", build / "classes.jar", *libraries)
else:
    run(sdk_tool("d8"), "--min-api", "26", "--lib", android, "--output", build / "dex",
        build / "classes.jar", *libraries)

run(aapt, "package", "-f",
    "-M", native_path(root / "AndroidManifest.xml"),
    "-S", native_path(root / "res"),
    "-A", native_path(root / "assets"),
    "-I", native_path(android),
    "-F", native_path(build / "unsigned.apk"))
with zipfile.ZipFile(build / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(build / "dex/classes.dex", "classes.dex")

zipalign = sdk_tool("zipalign", external=True)
run(zipalign, "-f", "4", native_path(build / "unsigned.apk"), native_path(build / "aligned.apk"))

keystore = build_root / "debug.keystore"
if not keystore.exists():
    run(keytool, "-genkeypair", "-keystore", keystore, "-storepass", "android", "-keypass", "android",
        "-alias", "qpolllab", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=QPoll Lab Local Debug", "-noprompt")

version = ET.parse(root / "AndroidManifest.xml").getroot().attrib["{http://schemas.android.com/apk/res/android}versionName"]
apk = dist / ("QPollLab-" + version + ".apk")
apksigner_jar = build_tools / "lib/apksigner.jar"
signer = [java, "-jar", apksigner_jar] if apksigner_jar.exists() else [sdk_tool("apksigner")]
run(*signer, "sign", "--ks", keystore, "--ks-key-alias", "qpolllab",
    "--ks-pass", "pass:android", "--key-pass", "pass:android",
    "--out", apk, build / "aligned.apk")
run(*signer, "verify", "--verbose", apk)
run(aapt, "dump", "badging", native_path(apk))

digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(dist / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n", encoding="utf-8")
print("APK:", apk)
print("SHA256:", digest)

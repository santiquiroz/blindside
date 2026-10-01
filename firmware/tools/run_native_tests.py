# Native Unity suites without an installed compiler (python -m pip install --user ziglang); CI stays authoritative.
import pathlib
import subprocess
import sys
import urllib.request

FIRMWARE = pathlib.Path(__file__).resolve().parents[1]
BUILD = FIRMWARE / ".pio" / "native-runner"
UNITY_VERSION = "v2.6.0"
UNITY_FILES = ("unity.c", "unity.h", "unity_internals.h")
UNITY_URL = "https://raw.githubusercontent.com/ThrowTheSwitch/Unity/{version}/src/{name}"


def zig(*args):
    return [sys.executable, "-m", "ziglang", *[str(a) for a in args]]


def fetch_unity():
    target = BUILD / f"unity-{UNITY_VERSION}"
    target.mkdir(parents=True, exist_ok=True)
    for name in UNITY_FILES:
        path = target / name
        if not path.exists():
            urllib.request.urlretrieve(UNITY_URL.format(version=UNITY_VERSION, name=name), path)
    return target


def library_dirs():
    libraries = FIRMWARE / "lib"
    return sorted(path for path in libraries.iterdir() if path.is_dir()) if libraries.exists() else []


def library_sources():
    return sorted((FIRMWARE / "lib").rglob("*.cpp"))


def selected_suites(names):
    suites = sorted(path for path in (FIRMWARE / "test").iterdir() if (path / "test_main.cpp").exists())
    return [suite for suite in suites if not names or suite.name in names]


def compile_unity(unity):
    obj = BUILD / "unity.o"
    subprocess.run(zig("cc", "-c", unity / "unity.c", "-I", unity, "-o", obj), check=True)
    return obj


def include_flags(unity):
    folders = [unity, FIRMWARE / "include", FIRMWARE / "test", *library_dirs()]
    return [flag for folder in folders for flag in ("-I", folder)]


def build_suite(suite, unity, unity_obj):
    exe = BUILD / f"{suite.name}.exe"
    # Zig's bundled libc++ headers trip clang's nullability warning on Windows; it is noise, not our code.
    command = zig("c++", "-std=gnu++17", "-Wall", "-Wextra", "-Wno-nullability-completeness", *include_flags(unity),
                  suite / "test_main.cpp", *library_sources(), unity_obj, "-o", exe)
    return exe if subprocess.run(command).returncode == 0 else None


def run_suite(suite, exe):
    print(f"== {suite.name}", flush=True)
    if exe is None:
        print("build failed")
        return False
    return subprocess.run([str(exe)]).returncode == 0


def main(names):
    unity = fetch_unity()
    unity_obj = compile_unity(unity)
    results = [run_suite(suite, build_suite(suite, unity, unity_obj)) for suite in selected_suites(names)]
    print(f"{results.count(True)} suites passed, {results.count(False)} failed")
    return 0 if results and all(results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

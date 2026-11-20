"""Download pinned upstream artifacts for the APK; no downloads happen inside the app."""
from pathlib import Path
import hashlib
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".build-cache" / "offline-voice"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
LIBS = ROOT / "app" / "libs"
ARTIFACTS = [
    (
        "sherpa-onnx-2.0.2.aar",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar",
        "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e",
    ),
    (
        "kokoro-int8-multi-lang-v1_0.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
    ),
    (
        "kitten-nano-en-v0_8-int8.tar.bz2",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_8-int8.tar.bz2",
        "6fa5be852612ce761094ba74ee6123b4fc4acfefa79bf64dc63acae4a83af2fd",
    ),
    (
        "lexicon-en-us.txt",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/lexicon-en-us.txt",
        "8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a",
    ),
]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(name, url, expected):
    target = CACHE / name
    if not target.exists() or digest(target) != expected:
        print(f"Downloading {name} ...", flush=True)
        part = target.with_suffix(target.suffix + ".part")
        request = urllib.request.Request(url, headers={"User-Agent": "BetterNarrator-build"})
        with urllib.request.urlopen(request, timeout=120) as response, part.open("wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if digest(part) != expected:
            part.unlink()
            raise RuntimeError(f"Checksum mismatch: {name}")
        part.replace(target)
    print(f"Verified {name}", flush=True)
    return target


def extract_model(model, folder, wanted):
    destination = ASSETS / folder
    destination.mkdir(parents=True, exist_ok=True)
    found = set()
    # Copy only named regular files; do not extract links or upstream directory paths.
    with tarfile.open(model, "r:bz2") as archive:
        for member in archive:
            name = Path(member.name).name
            if name not in wanted or not member.isfile():
                continue
            if name in found:
                raise RuntimeError(f"Duplicate asset in archive: {name}")
            target = destination / name
            if folder == "kitten" and name == "LICENSE":
                target = ASSETS / "licenses" / "Kitten-Apache-2.0.txt"
            with archive.extractfile(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
            found.add(name)
    if found != wanted:
        raise RuntimeError(f"Missing model assets: {wanted - found}")
    for path in sorted(destination.iterdir()):
        if path.is_file():
            print(f"{folder}/{path.name}: {path.stat().st_size / 1024 / 1024:.1f} MiB", flush=True)


def main():
    for directory in (CACHE, ASSETS, ASSETS / "licenses", LIBS):
        directory.mkdir(parents=True, exist_ok=True)
    aar, kokoro, kitten, lexicon = [download(*artifact) for artifact in ARTIFACTS]
    shutil.copyfile(aar, LIBS / aar.name)
    extract_model(kokoro, "kokoro", {"model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt"})
    extract_model(kitten, "kitten", {"model.int8.onnx", "voices.bin", "tokens.txt", "LICENSE"})
    shutil.copyfile(lexicon, ASSETS / "kitten" / "lexicon-en-us.txt")
    print("Offline voice assets ready. Build the app with Gradle.", flush=True)


if __name__ == "__main__":
    main()

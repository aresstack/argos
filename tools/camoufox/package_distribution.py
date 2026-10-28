"""Package a Camoufox folder patched by playwright4j's hotpatch_omni.py as an Argos Camoufox distribution.

    python package_distribution.py --dir <patched camoufox> --target lin.x86_64|win.x86_64
        --release 156.0.1-beta.36 --revision argos.1 --hotpatch <commit> [--addon NAME=<xpi> ...] --out <zip>

The zip holds the browser files at its root (without hotpatch's omni.ja.orig backup), add-ons unpacked under
addons/<NAME>/ and argos-distribution.json, which lists the files Argos must mark executable on POSIX. Entries are
sorted and carry a fixed timestamp, so the same input gives the same archive.
"""
import argparse
import hashlib
import json
import os
import stat
import sys
import zipfile

FIXED_TIME = (1980, 1, 1, 0, 0, 0)
SKIP = {"omni.ja.orig"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ascii_name(rel):
    """Font files with non-ASCII names get an escaped ASCII name: a JVM started under a C/POSIX locale cannot create
    them, and fontconfig identifies fonts by content, not by file name. Anything else must already be ASCII."""
    if rel.isascii():
        return rel
    if not rel.startswith("fonts/"):
        sys.exit(f"non-ASCII path outside fonts/: {rel!r}")
    folder, _, name = rel.rpartition("/")
    escaped = "".join(c if c.isascii() else f"_u{ord(c):04x}" for c in name)
    return f"{folder}/{escaped}"


def entry(name, executable):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3  # unix, so external_attr carries the mode
    info.external_attr = ((0o100755 if executable else 0o100644) << 16)
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", required=True)
    parser.add_argument("--target", required=True, choices=["lin.x86_64", "win.x86_64"])
    parser.add_argument("--release", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--hotpatch", required=True, help="playwright4j commit of hotpatch_omni.py")
    parser.add_argument("--addon", action="append", default=[], help="NAME=path/to/addon.xpi")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    root = os.path.abspath(args.dir)
    executable = "camoufox.exe" if args.target.startswith("win") else "camoufox"
    if not os.path.isfile(os.path.join(root, executable)) or not os.path.isfile(os.path.join(root, "omni.ja")):
        sys.exit(f"{root} is not an unpacked Camoufox for {args.target}")
    if os.path.exists(os.path.join(root, "addons")):
        sys.exit("the Camoufox folder already has addons/; refusing to mix")

    files = []
    for folder, dirs, names in os.walk(root):
        dirs.sort()
        for name in sorted(names):
            path = os.path.join(folder, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            if rel in SKIP or os.path.islink(path):
                continue
            files.append((ascii_name(rel), path))
    files.sort()

    executables = []
    if args.target.startswith("lin"):
        executables = [rel for rel, path in files if os.stat(path).st_mode & stat.S_IXUSR]
        if executable not in executables:
            sys.exit(f"{executable} is not executable in {root}")

    addons = {}
    for spec in args.addon:
        name, _, xpi = spec.partition("=")
        if not name or not os.path.isfile(xpi):
            sys.exit(f"bad --addon {spec}")
        addons[name] = {"sha256": sha256(xpi), "file": os.path.basename(xpi)}

    manifest = {
        "release": args.release,
        "revision": args.revision,
        "target": args.target,
        "executable": executable,
        "bidiFixes": f"aresstack/playwright4j {args.hotpatch} docs/camoufox/hotpatch_omni.py",
        "addons": addons,
        "executables": executables,
    }

    with zipfile.ZipFile(args.out, "w", allowZip64=True) as out:
        for rel, path in files:
            with open(path, "rb") as f, out.open(entry(rel, rel in executables), "w", force_zip64=True) as dst:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    dst.write(chunk)
        for name in sorted(addons):
            xpi = dict(a.split("=", 1) for a in args.addon)[name]
            with zipfile.ZipFile(xpi) as src:
                for info in sorted(src.infolist(), key=lambda i: i.filename):
                    if info.is_dir():
                        continue
                    target = f"addons/{name}/{info.filename}"
                    if ".." in target.split("/"):
                        sys.exit(f"unsafe add-on entry {info.filename}")
                    out.writestr(entry(target, False), src.read(info))
        out.writestr(entry("argos-distribution.json", False), json.dumps(manifest, indent=1, sort_keys=True))
    print(f"{args.out}: {sha256(args.out)}")


if __name__ == "__main__":
    main()

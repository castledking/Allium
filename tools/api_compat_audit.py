#!/usr/bin/env python3
"""Check that everything Allium's bytecode calls from the server API still exists on an
older server.

Allium compiles against a newer Paper API than the oldest server it supports, so a member
it names can compile cleanly and then throw NoSuchMethodError / NoClassDefFoundError at
runtime. Those land in whatever code path happens to touch them first, which is how a
`/cards give` on a 1.21 server died inside a card frame.

This compares descriptors, not just names. That distinction is the whole point: Adventure
5 re-bounded ComponentBuilder's type parameter, so `build()` exists on both versions with
the same name and two different return descriptors. A name-only audit reports it as fine.

usage:
  api_compat_audit.py --classes target/classes --baseline jars.csv \\
                      --target "Paper 1.21.11:jars.csv" [--target ...]

jars.csv is a comma-separated classpath. Exits non-zero if anything is missing.
"""

import argparse
import glob
import os
import re
import subprocess
import sys

# Only the API the server itself provides. Plugin APIs are softdepends that Allium already
# reaches reflectively, and a missing one of those is a feature being absent, not a crash.
SERVER_PREFIXES = (
    "org.bukkit",
    "io.papermc",
    "com.destroystokyo",
    "net.md_5",
    "org.spigotmc",
    "net.kyori",
)

CLASS_DECL = re.compile(
    r"^(?:public |abstract |final |static |strictfp |)*(?:class|interface|enum|record)\s+([\w.$]+)")
DESCRIPTOR = re.compile(r"\s*descriptor:\s*(\S+)")
MEMBER_REF = re.compile(r"//\s+(InterfaceMethod|Method|Field)\s+([\w/$]+)\.([\w$]+):(\S+)")
CLASS_REF = re.compile(r"(?://\s+class |//\s+checkcast\s+#\d+\s+//\s+class )([\w/$]+)")


def _run_javap(classpath, names, index):
    """javap a batch of classes; halve on failure so one bad name cannot drop the batch."""
    if not names:
        return
    proc = subprocess.run(["javap", "-p", "-s", "-cp", classpath] + names,
                          capture_output=True, text=True)
    if proc.returncode != 0 and len(names) > 1:
        middle = len(names) // 2
        _run_javap(classpath, names[:middle], index)
        _run_javap(classpath, names[middle:], index)
        return
    current = None
    pending = None
    for line in proc.stdout.splitlines():
        header = CLASS_DECL.match(line)
        if header:
            current = header.group(1)
            index.setdefault(current, {"members": set(), "supertypes": set()})
            extends = re.search(r"\bextends\s+([\w.$<>, ]+?)(?:\s+implements|\s*\{|$)", line)
            if extends:
                index[current]["supertypes"].update(re.findall(r"[\w.$]+", extends.group(1)))
            continue
        if current is None:
            continue
        descriptor = DESCRIPTOR.match(line)
        if descriptor and pending:
            index[current]["members"].add((pending[0], pending[1], descriptor.group(1)))
            pending = None
            continue
        body = line.strip().rstrip(";")
        if "(" in body:
            parts = body.split("(")[0].split()
            if parts:
                pending = ("method", parts[-1])
        elif body and not body.startswith("}") and "descriptor:" not in line:
            name = body.split()[-1]
            if re.match(r"^[A-Za-z_$][\w$]*$", name or ""):
                index[current]["members"].add(("field", name, None))


def expand(jars):
    if not isinstance(jars, str):
        jars = ",".join(jars)
    """A comma-separated classpath, where an entry may be a glob.

    Globbing matters because a server's Adventure arrives as several artifacts — api, key,
    minimessage, the serializers, examination — and a target that lists only adventure-api
    reports every minimessage type as missing, which buries the real findings.
    """
    out = []
    for entry in jars.split(","):
        entry = entry.strip()
        if not entry:
            continue
        if any(ch in entry for ch in "*?["):
            out.extend(sorted(glob.glob(entry)))
        else:
            out.append(entry)
    return out


def build_index(jars):
    """-> {class: {"members": {(kind, name, descriptor)}, "supertypes": {name}}}"""
    jars = expand(jars)
    classpath = ":".join(jars)
    available = set()
    for jar in jars:
        listing = subprocess.run(["unzip", "-Z1", jar], capture_output=True, text=True).stdout
        available.update(listing.split())
    classes = sorted({n[:-6].replace("/", ".") for n in available
                      if n.endswith(".class") and "module-info" not in n})
    index = {}
    for start in range(0, len(classes), 120):
        _run_javap(classpath, classes[start:start + 120], index)
    return index


def has_member(index, cls, kind, name, descriptor, seen=None):
    """Is the member reachable from cls, including inherited and default methods?"""
    seen = seen if seen is not None else set()
    if cls in seen or cls not in index:
        return False
    seen.add(cls)
    for member_kind, member_name, member_descriptor in index[cls]["members"]:
        if member_kind != kind or member_name != name:
            continue
        # A field's descriptor is not always printed; accept any descriptor for those.
        if member_descriptor is None or member_descriptor == descriptor or kind == "field":
            return True
    return any(has_member(index, s, kind, name, descriptor, seen)
               for s in index[cls]["supertypes"])


def scan_allium(classdir):
    """-> ({(class, name, descriptor, kind)}, {class}) for the server API only."""
    members, types = set(), set()
    for root, _, files in os.walk(classdir):
        for filename in files:
            if not filename.endswith(".class"):
                continue
            proc = subprocess.run(["javap", "-p", "-c", "-cp", classdir,
                                   os.path.join(root, filename)],
                                  capture_output=True, text=True)
            for line in proc.stdout.splitlines():
                match = MEMBER_REF.search(line)
                if match:
                    name = match.group(2).replace("/", ".")
                    if name.startswith(SERVER_PREFIXES):
                        kind = "field" if match.group(1) == "Field" else "method"
                        members.add((name, match.group(3), match.group(4), kind))
                match = CLASS_REF.search(line)
                if match:
                    name = match.group(1).replace("/", ".")
                    if name.startswith(SERVER_PREFIXES):
                        types.add(name)
    return members, types


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--classes", required=True, help="compiled Allium classes directory")
    parser.add_argument("--baseline", required=True,
                        help="jars of the API Allium was compiled against; entries may be globs")
    parser.add_argument("--target", action="append", default=[], metavar="NAME:JARS",
                        help="NAME:JARS of a supported server to check against, repeatable; JARS entries may be globs")
    args = parser.parse_args()

    print("scanning Allium's bytecode ...", flush=True)
    members, types = scan_allium(args.classes)
    print(f"  {len(members)} server-API member calls, {len(types)} server-API types\n")

    print("indexing the API Allium compiles against ...", flush=True)
    baseline = build_index(args.baseline.split(","))

    failed = False
    for entry in args.target:
        name, _, jars = entry.partition(":")
        print(f"--- {name} ---", flush=True)
        print("  indexing ...", flush=True)
        target = build_index(jars.split(","))

        # Both checks are gated on the baseline. Anything the compiled-against API did not
        # provide either is not ours to call (a shaded library's reference, say) or is provided
        # by the server outside paper-api, and neither is a compatibility finding.
        missing_types = sorted(t for t in types
                               if t not in target and t in baseline)
        broken = sorted(
            (cls, member, descriptor, kind)
            for cls, member, descriptor, kind in members
            if not has_member(target, cls, kind, member, descriptor)
            and has_member(baseline, cls, kind, member, descriptor))

        for cls in missing_types:
            failed = True
            print(f"  MISSING CLASS  {cls}")
        for cls, member, descriptor, kind in broken:
            failed = True
            print(f"  MISSING        {cls}.{member}{descriptor}  [{kind}]")

        if not missing_types and not broken:
            print("  OK - every server-API member and type Allium uses is present")
        print()

    if failed:
        print("FAIL - the jar would throw at runtime on one of the supported servers.")
        print("      Route the call through codes.castled.allium.util.ApiCompat or")
        print("      ComponentCompat rather than naming the member directly.")
        return 1
    print("PASS - links on every supported server checked.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

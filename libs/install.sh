#!/bin/sh
# Install every jar listed in libs/dependencies.txt into the local Maven
# repository, so a build resolves dependencies that no repository serves.
#
# Used by .github/workflows/build.yml, and worth running by hand after
# changing a version so a stale jar in ~/.m2 is caught locally first.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")

[ -f "$here/dependencies.txt" ] || {
    echo "libs/dependencies.txt not found" >&2
    exit 1
}

installed=0
# `|| [ -n "$file" ]` because read returns non-zero on a final line with no
# trailing newline, which would otherwise skip that entry silently.
while read -r file group artifact version rest || [ -n "$file" ]; do
    case "$file" in ''|'#'*) continue ;; esac
    case "$file" in libs/*) ;; *)
        echo "skipping unrecognised line: $file" >&2
        continue ;;
    esac
    if [ ! -f "$root/$file" ]; then
        echo "missing jar: $file" >&2
        exit 1
    fi
    mvn -B -q install:install-file \
        -Dfile="$root/$file" \
        -DgroupId="$group" \
        -DartifactId="$artifact" \
        -Dversion="$version" \
        -Dpackaging=jar
    echo "installed $group:$artifact:$version"
    installed=$((installed + 1))
done < "$here/dependencies.txt"

[ "$installed" -gt 0 ] || {
    echo "no jars listed in libs/dependencies.txt" >&2
    exit 1
}
echo "$installed vendored dependency jar(s) installed"
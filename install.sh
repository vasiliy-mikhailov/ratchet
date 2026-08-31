#!/bin/sh
# INSTALL ONE TAGGED VERSION INTO A LOCAL MAVEN REPOSITORY.
#
# The README has always said "it is not on Maven Central: install it from its own source tree."
# That was a sentence, not a step: a reader had to infer the checkout, the skip-tests, the repo
# flag, and that the tag existed at all. ratchet#3 was filed because a consumer could not get a
# version, and ratchet#1 because five versions had no tag to check out. Both are closed, and this
# is what closes the gap between them.
#
#   ./install.sh                      the newest tag, into ~/.m2
#   ./install.sh v0.5.0               a specific one
#   ./install.sh v0.22.0 -r ~/.m2-fitness/repository   into a repository another build reads
#
# `-r` becomes -Dmaven.repo.local, which wants the REPOSITORY directory, not the `.m2` above it.
# The example in 0.11.1 said `~/.m2-fitness` and would have installed a directory level too high.
#
# It puts the checkout back where it found it, including when the build fails.

set -eu

version=""
repo=""

while [ $# -gt 0 ]; do
    case "$1" in
        -r|--repo) [ $# -ge 2 ] || { echo "install.sh: $1 wants a directory" >&2; exit 2; }
                   repo="$2"; shift 2 ;;
        -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*)        echo "install.sh: unknown option $1" >&2; exit 2 ;;
        *)         version="$1"; shift ;;
    esac
done

cd "$(dirname "$0")"

# NEWEST BY VERSION ORDER, NOT BY DATE. Tags were added retroactively for 0.1.0 through 0.4.1, so
# creation order says nothing about which release is newest.
if [ -z "$version" ]; then
    version=$(git tag --list 'v*' | sort -t. -k1,1V -k2,2n -k3,3n | tail -1)
    [ -n "$version" ] || { echo "install.sh: this checkout has no tags" >&2; exit 1; }
fi

if ! git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
    echo "install.sh: no tag $version. This checkout has:" >&2
    git tag --list 'v*' | sort -t. -k1,1V -k2,2n -k3,3n | sed 's/^/  /' >&2
    echo "  (if one is missing, 'git fetch --tags' first)" >&2
    exit 1
fi

# REFUSE EARLY ON A DIRTY TREE, in this script's own words. Without this the run gets as far as
# printing "installing", then git says "Please commit your changes or stash them before you switch
# branches" about a checkout the reader did not ask for and cannot see. Saying it first, and saying
# which files, costs one command.
dirty=$(git status --porcelain --untracked-files=no)
if [ -n "$dirty" ]; then
    echo "install.sh: this checkout has uncommitted changes, so it cannot switch to a tag:" >&2
    echo "$dirty" | sed 's/^/  /' >&2
    echo "install.sh: commit or stash them, or run this from a fresh clone." >&2
    exit 1
fi

# WHERE TO PUT IT BACK. A detached HEAD left behind by a failed build is a worse outcome than the
# failure, because the next person's `git pull` reports something baffling.
was=$(git symbolic-ref -q --short HEAD || git rev-parse HEAD)
# A FAILED RESTORE MUST BE LOUD. This was `git checkout -q "$was" 2>/dev/null || true`, which is
# the silent-failure shape this repository exists to argue against, sitting in the recovery path
# where it is least visible: if the checkout back fails for any reason, the reader is left on a
# detached HEAD with no message, and finds out later when a commit lands somewhere unexpected.
# That happened here, to the author, on this repository, and cost an amended release commit.
restore() {
    if ! git checkout -q "$was" 2>/dev/null; then
        echo "install.sh: COULD NOT RETURN THIS CHECKOUT TO $was — it is on a detached HEAD." >&2
        echo "install.sh: run 'git checkout $was' before committing anything." >&2
    fi
}
trap restore EXIT INT TERM

echo "ratchet: installing $version${repo:+ into $repo}"
git checkout -q "$version"

set -- -B -DskipTests install
[ -n "$repo" ] && set -- "$@" "-Dmaven.repo.local=$repo"

# QUIET WHEN IT WORKS, LOUD WHEN IT DOES NOT. A first draft of this sent maven to /dev/null
# unconditionally, so a build that failed printed the word "installing" and then nothing at all —
# which is the silent-failure shape this whole repository exists to argue against, reproduced in
# the script that closes the issue about it.
# NOT `mktemp -t ratchet-install`. BSD appends the X's itself; GNU coreutils requires at least
# three trailing X in the template and exits 1 without them — so the one-command route added in
# 0.11.1 died on Debian and Ubuntu, which is where most consumers are, at the point where the
# checkout had already happened. Written and tested on a Mac, reported from Linux the same day.
log=$(mktemp "${TMPDIR:-/tmp}/ratchet-install.XXXXXX")
if ! mvn "$@" >"$log" 2>&1; then
    echo "install.sh: the build failed. The last of it:" >&2
    tail -25 "$log" >&2
    echo "install.sh: full output in $log" >&2
    exit 1
fi
rm -f "$log"

# CHECK THE JARS LANDED. "It built" and "a consumer can resolve it" are different claims, and the
# difference between them is the whole of ratchet#3.
into=${repo:-$HOME/.m2/repository}
number=${version#v}
missing=""
for module in ratchet-core ratchet-llm; do
    jar="$into/tech/mikhailov/ratchet/$module/$number/$module-$number.jar"
    [ -f "$jar" ] || missing="$missing $module"
done
if [ -n "$missing" ]; then
    echo "install.sh: the build succeeded but these are not visible at $into:$missing" >&2
    echo "install.sh: if 'mvn' here is a wrapper (docker, toolbox) that remaps the repository," >&2
    echo "install.sh: they may have landed where IT can see rather than where this script looked." >&2
    echo "install.sh: pass -r as the path that wrapper expects, and check inside it." >&2
    exit 1
fi

echo "ratchet: $number is installed. Depend on it with:"
echo
echo "  <dependency>"
echo "    <groupId>tech.mikhailov.ratchet</groupId>"
echo "    <artifactId>ratchet-core</artifactId>"
echo "    <version>$number</version>"
echo "  </dependency>"

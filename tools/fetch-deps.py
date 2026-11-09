#!/usr/bin/env python3
"""Resolve, download and unpack Maven dependencies without Gradle (python3 stdlib only).

Reads a roots file (one "group:artifact[:version]" per line, '#' comments; no version =
latest stable release), resolves the full transitive runtime closure from Gradle module
metadata (.module) when the POM says it was published with it, otherwise from the POM
(parents, properties, dependencyManagement, BOM imports, exclusions). One version per
module, highest wins, dependency constraints included -- the same rules Gradle uses.

Outputs into --out (default libs/):
  deps.lock        the closure, dependencies before dependents:
                   "group:artifact:version  file  sha256"  (file "-" = no artifact,
                   e.g. a KMP umbrella module or a platform/BOM)
  <g>.<a>-<v>.aar|jar   the downloaded artifacts
  exploded/<g>.<a>-<v>/ each AAR unpacked: classes.jar, libs/*.jar, res/, assets/,
                   AndroidManifest.xml, R.txt, proguard.txt, aar-metadata.properties
                   (jni/ is skipped)
  deps.env         shell variables for build.sh (see write_env)
  cache/           POM / .module cache (Maven repository layout)

Modes:
  (default)   resolve from the roots file, rewrite the lock, download, unpack
  --locked    skip resolution, fetch exactly what deps.lock lists
  --ensure    fast no-op when the lock matches the roots file and everything is
              present; uses the lock when only files are missing; re-resolves when the
              roots file changed (build.sh calls this)
  --env jvm   pick plain-JVM variants (for tools run on the Mac) instead of Android ones
"""
import argparse
import functools
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

GOOGLE = "https://dl.google.com/android/maven2/"
CENTRAL = "https://repo1.maven.org/maven2/"
GOOGLE_GROUPS = ("androidx.", "com.android.", "com.google.android.", "com.google.firebase",
                 "android.arch.", "com.google.testing.platform", "com.google.gms")


# Modules that must share one version even though their metadata does not say so. The
# Kotlin Gradle plugin adds this rule in a normal build; without it kotlin-stdlib-jdk7/jdk8
# 1.6.x (pulled by kotlinx-coroutines) duplicate classes that moved into kotlin-stdlib 1.8.
ALIGN = [("org.jetbrains.kotlin", ("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8",
                                   "kotlin-stdlib-common"))]


def log(*a):
    print("fetch-deps:", *a, file=sys.stderr)


def die(msg):
    log("ERROR:", msg)
    sys.exit(1)


# ---------------------------------------------------------------- versions (Gradle order)

_SPECIAL = {"dev": 0, "rc": 2, "snapshot": 3, "final": 4, "ga": 5, "release": 6, "sp": 7}


def _vparts(v):
    out = []
    for tok in re.split(r"[.\-_+]", v):
        out += re.findall(r"\d+|\D+", tok)
    return out


def vcmp(a, b):
    pa, pb = _vparts(a), _vparts(b)
    for x, y in zip(pa, pb):
        if x == y:
            continue
        xn, yn = x.isdigit(), y.isdigit()
        if xn and yn:
            if int(x) != int(y):
                return 1 if int(x) > int(y) else -1
            continue
        if xn:
            return 1
        if yn:
            return -1
        rx, ry = _SPECIAL.get(x.lower(), 1), _SPECIAL.get(y.lower(), 1)
        if rx != ry:
            return 1 if rx > ry else -1
        return 1 if x > y else -1
    if len(pa) == len(pb):
        return 0
    longer, sign = (pa, 1) if len(pa) > len(pb) else (pb, -1)
    extra = longer[min(len(pa), len(pb))]
    return sign if extra.isdigit() else -sign


vkey = functools.cmp_to_key(vcmp)


def vmax(vs):
    vs = [v for v in vs if v]
    return max(vs, key=vkey) if vs else None


def is_stable(v):
    return not re.search(r"(?i)(alpha|beta|rc\d*$|-rc|dev|snapshot|eap|preview|milestone|-m\d)", v)


# ---------------------------------------------------------------- repository access

class Repo:
    def __init__(self, cache):
        self.cache = cache
        self.meta = {}

    def _bases(self, group):
        g = (GOOGLE, CENTRAL)
        return g if group.startswith(GOOGLE_GROUPS) else g[::-1]

    def _fetch(self, group, rel):
        for base in self._bases(group):
            try:
                with urllib.request.urlopen(base + rel, timeout=60) as r:
                    return r.read()
            except urllib.error.HTTPError as e:
                if e.code not in (403, 404):
                    raise
        return None

    def get(self, group, rel, cache=True):
        path = os.path.join(self.cache, rel)
        if cache and os.path.exists(path):
            with open(path, "rb") as f:
                return f.read()
        data = self._fetch(group, rel)
        if data is not None and cache:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
        return data

    @staticmethod
    def rel(g, a, v, name):
        return f"{g.replace('.', '/')}/{a}/{v}/{name}"

    def versions(self, g, a):
        if (g, a) not in self.meta:
            data = self.get(g, f"{g.replace('.', '/')}/{a}/maven-metadata.xml", cache=False)
            if data is None:
                die(f"no maven-metadata.xml for {g}:{a}")
            root = ET.fromstring(data)
            self.meta[(g, a)] = [e.text.strip() for e in root.iter("version")]
        return self.meta[(g, a)]

    def latest_stable(self, g, a):
        v = vmax([v for v in self.versions(g, a) if is_stable(v)])
        if not v:
            die(f"no stable version of {g}:{a}")
        return v

    def download(self, g, a, v, fname, dest, sha256=None):
        """Download an artifact to dest and verify it (sha256 from .module, else .sha1)."""
        if os.path.exists(dest) and sha256 and file_sha256(dest) == sha256:
            return
        data = self._fetch(g, self.rel(g, a, v, fname))
        if data is None:
            die(f"cannot download {g}:{a}:{v} {fname}")
        if sha256:
            if hashlib.sha256(data).hexdigest() != sha256:
                die(f"sha256 mismatch for {fname}")
        else:
            sha1 = self._fetch(g, self.rel(g, a, v, fname + ".sha1"))
            if sha1 is not None:
                want = sha1.decode("ascii", "replace").split()[0].strip().lower()
                if hashlib.sha1(data).hexdigest() != want:
                    die(f"sha1 mismatch for {fname}")
        with open(dest + ".part", "wb") as f:
            f.write(data)
        os.replace(dest + ".part", dest)


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- metadata model

class Dep:
    __slots__ = ("g", "a", "v", "excl", "platform")

    def __init__(self, g, a, v, excl=(), platform=False):
        self.g, self.a, self.v = g, a, v
        self.excl = frozenset(excl)
        self.platform = platform


class Meta:
    """What one g:a:v contributes: dependencies, constraints and its artifact file."""

    def __init__(self):
        self.deps = []          # [Dep]
        self.constraints = []   # [(g, a, v)]
        self.file = None        # (filename, sha256 or None)


def _q(tag):
    return tag.split("}", 1)[-1]


def _child(el, name):
    for c in el:
        if _q(c.tag) == name:
            return c
    return None


def _text(el, name, default=None):
    c = _child(el, name)
    return c.text.strip() if c is not None and c.text else default


class Resolver:
    def __init__(self, repo, env):
        self.repo = repo
        self.env = env  # "android" or "jvm"
        self.metas = {}
        self.poms = {}

    # ---- POM (with parents, properties, dependencyManagement, imports)

    def raw_pom(self, g, a, v):
        data = self.repo.get(g, Repo.rel(g, a, v, f"{a}-{v}.pom"))
        if data is None:
            die(f"POM not found: {g}:{a}:{v}")
        return data

    def effective_pom(self, g, a, v):
        key = (g, a, v)
        if key in self.poms:
            return self.poms[key]
        data = self.raw_pom(g, a, v)
        root = ET.fromstring(data)
        parent = _child(root, "parent")
        props, mgmt, deps = {}, {}, []
        pgroup = pversion = None
        if parent is not None:
            pg, pa, pv = _text(parent, "groupId"), _text(parent, "artifactId"), _text(parent, "version")
            pp = self.effective_pom(pg, pa, pv)
            props.update(pp["props"])
            mgmt.update(pp["mgmt"])
            deps += pp["deps"]
            pgroup, pversion = pg, pv
        group = _text(root, "groupId", pgroup)
        version = _text(root, "version", pversion)
        packaging = _text(root, "packaging", "jar")
        pe = _child(root, "properties")
        if pe is not None:
            for c in pe:
                props[_q(c.tag)] = (c.text or "").strip()
        for k, val in (("project.version", version), ("version", version), ("pom.version", version),
                       ("project.groupId", group), ("groupId", group), ("project.artifactId", a),
                       ("project.parent.version", pversion), ("parent.version", pversion),
                       ("project.parent.groupId", pgroup)):
            if val:
                props[k] = val

        def interp(s):
            if s is None:
                return None
            for _ in range(10):
                n = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), s)
                if n == s:
                    break
                s = n
            return s

        def read_deps(container):
            out = []
            if container is None:
                return out
            for d in container:
                if _q(d.tag) != "dependency":
                    continue
                excl = set()
                ex = _child(d, "exclusions")
                if ex is not None:
                    for e in ex:
                        excl.add((interp(_text(e, "groupId", "*")), interp(_text(e, "artifactId", "*"))))
                out.append({
                    "g": interp(_text(d, "groupId")), "a": interp(_text(d, "artifactId")),
                    "v": interp(_text(d, "version")), "scope": interp(_text(d, "scope")),
                    "type": interp(_text(d, "type", "jar")), "classifier": interp(_text(d, "classifier")),
                    "optional": interp(_text(d, "optional", "false")) == "true", "excl": excl,
                })
            return out

        dm = _child(root, "dependencyManagement")
        if dm is not None:
            for d in read_deps(_child(dm, "dependencies")):
                if d["scope"] == "import" and d["type"] == "pom":
                    bom = self.effective_pom(d["g"], d["a"], d["v"])
                    for k, val in bom["mgmt"].items():
                        mgmt.setdefault(k, val)
                else:
                    mgmt[(d["g"], d["a"])] = d
        own = read_deps(_child(root, "dependencies"))
        # child declarations override inherited ones with the same key
        keys = {(d["g"], d["a"]) for d in own}
        deps = [d for d in deps if (d["g"], d["a"]) not in keys] + own
        res = {"group": group, "version": version, "packaging": packaging, "props": props,
               "mgmt": mgmt, "deps": deps, "text": data}
        self.poms[key] = res
        return res

    def meta_from_pom(self, g, a, v, platform):
        pom = self.effective_pom(g, a, v)
        m = Meta()
        if platform:
            for (mg, ma), d in pom["mgmt"].items():
                if d.get("v") and d.get("scope") != "import":
                    m.constraints.append((mg, ma, d["v"]))
        for d in pom["deps"]:
            managed = pom["mgmt"].get((d["g"], d["a"]), {})
            scope = d["scope"] or managed.get("scope") or "compile"
            if scope not in ("compile", "runtime") or d["optional"]:
                continue
            if d["classifier"]:
                log(f"note: ignoring classifier dependency {d['g']}:{d['a']}:{d['classifier']}")
                continue
            ver = d["v"] or managed.get("v")
            excl = set(d["excl"]) | set(managed.get("excl", ()))
            m.deps.append(Dep(d["g"], d["a"], ver, excl))
        # dependencyManagement of a normal module also acts as constraints for its own
        # deps only (Maven semantics); applied above through "managed".
        if not platform:
            ext = {"aar": "aar", "pom": None}.get(pom["packaging"], "jar")
            if ext:
                m.file = (f"{a}-{v}.{ext}", None)
        return m

    # ---- Gradle module metadata

    def _score(self, attrs, platform):
        cat = attrs.get("org.gradle.category", "library")
        if platform:
            return 10 if cat in ("platform", "enforced-platform") else -1
        if cat != "library" or "org.gradle.docstype" in attrs:
            return -1
        usage = attrs.get("org.gradle.usage", "")
        if usage not in ("java-runtime", "java-runtime-jars", "kotlin-runtime"):
            return -1
        s = 10
        pt = attrs.get("org.jetbrains.kotlin.platform.type")
        if pt is not None:
            if pt == "androidJvm":
                s += 4 if self.env == "android" else -100
            elif pt == "jvm":
                s += 2
            else:
                return -1
        je = attrs.get("org.gradle.jvm.environment")
        if je == "android":
            s += 3 if self.env == "android" else -100
        elif je == "standard-jvm":
            s += 1 if self.env == "android" else 3
        bt = attrs.get("com.android.build.api.attributes.BuildTypeAttr")
        if bt is not None and bt != "release":
            s -= 5
        return s

    def meta_from_module(self, g, a, v, mod, platform):
        best, bscore = None, -1
        for var in mod.get("variants", []):
            sc = self._score(var.get("attributes", {}), platform)
            if sc > bscore:
                best, bscore = var, sc
        if best is None:
            return None
        m = Meta()
        av = best.get("available-at")
        if av:
            m.deps.append(Dep(av["group"], av["module"], av["version"]))
            return m

        def ver(d):
            vv = d.get("version") or {}
            return vv.get("strictly") or vv.get("requires") or vv.get("prefers")

        for d in best.get("dependencies", []):
            cat = (d.get("attributes") or {}).get("org.gradle.category")
            excl = {(e.get("group", "*"), e.get("module", "*")) for e in d.get("excludes", [])}
            m.deps.append(Dep(d["group"], d["module"], ver(d), excl,
                              platform=cat in ("platform", "enforced-platform")))
        for c in best.get("dependencyConstraints", []):
            if ver(c):
                m.constraints.append((c["group"], c["module"], ver(c)))
        if not platform:
            files = [f for f in best.get("files", []) if f["name"].endswith((".aar", ".jar"))]
            if files:
                f = files[0]
                m.file = (f["url"], f.get("sha256"))
        return m

    def meta(self, g, a, v, platform=False):
        key = (g, a, v, platform)
        if key in self.metas:
            return self.metas[key]
        pom = self.raw_pom(g, a, v)
        m = None
        if b"published-with-gradle-metadata" in pom:
            data = self.repo.get(g, Repo.rel(g, a, v, f"{a}-{v}.module"))
            if data is not None:
                m = self.meta_from_module(g, a, v, json.loads(data), platform)
        if m is None:
            m = self.meta_from_pom(g, a, v, platform)
        self.metas[key] = m
        return m

    # ---- version ranges

    def pick(self, g, a, spec):
        """Resolve a Maven range like [1.0,2.0) to the highest matching version."""
        if not spec or spec[0] not in "[(":
            return spec
        if "," not in spec:
            return spec.strip("[]()")
        lo, hi = [s.strip() for s in spec[1:-1].split(",", 1)]
        ok = []
        for v in self.repo.versions(g, a):
            if lo and (vcmp(v, lo) < 0 or (spec[0] == "(" and vcmp(v, lo) == 0)):
                continue
            if hi and (vcmp(v, hi) > 0 or (spec[-1] == ")" and vcmp(v, hi) == 0)):
                continue
            ok.append(v)
        stable = [v for v in ok if is_stable(v)]
        r = vmax(stable or ok)
        if not r:
            die(f"no version of {g}:{a} matches {spec}")
        return r

    # ---- graph resolution

    def resolve(self, roots):
        """roots: [(g, a, v)]. Returns (selected {(g,a): v}, edges {(g,a): set}, platforms)."""
        selected = {(g, a): v for g, a, v in roots}
        for it in range(60):
            req = defaultdict(set)
            for g, a, v in roots:
                req[(g, a)].add(v)
            cons = defaultdict(set)
            edges = defaultdict(set)
            platforms = set()
            seen = set()
            visited = []
            queue = [((g, a), frozenset(), False) for g, a, _ in roots]
            while queue:
                key, excl, plat = queue.pop(0)
                state = (key, excl, plat)
                if state in seen:
                    continue
                seen.add(state)
                if key not in visited:
                    visited.append(key)
                v = selected.get(key) or vmax(req[key] | cons[key])
                if not v:
                    die(f"no version for {key[0]}:{key[1]} (versionless dependency, no constraint)")
                if plat:
                    platforms.add(key)
                m = self.meta(key[0], key[1], v, plat)
                for cg, ca, cv in m.constraints:
                    cons[(cg, ca)].add(self.pick(cg, ca, cv))
                for d in m.deps:
                    if any((eg in ("*", d.g)) and (ea in ("*", d.a)) for eg, ea in excl):
                        continue
                    dk = (d.g, d.a)
                    if d.v:
                        req[dk].add(self.pick(d.g, d.a, d.v))
                    edges[key].add(dk)
                    queue.append((dk, excl | d.excl, d.platform))
            new = {}
            for key in visited:
                vv = vmax(req[key] | cons[key])
                if not vv:
                    die(f"no version for {key[0]}:{key[1]}")
                new[key] = vv
            for group, members in ALIGN:
                present = [(group, m) for m in members if (group, m) in new]
                top = vmax([new[k] for k in present])
                for k in present:
                    new[k] = top
            if new == selected:
                return selected, edges, platforms
            if it > 30:  # guard against oscillation: only move upwards
                new = {k: vmax([vv, selected.get(k)]) for k, vv in new.items()}
            selected = new
        die("resolution did not converge")


def topo_order(selected, edges, roots):
    """Dependencies before dependents (stable, DFS in declaration order)."""
    order, done, active = [], set(), set()

    def visit(k):
        if k in done or k in active:
            return
        active.add(k)
        for d in sorted(edges.get(k, ())):
            if d in selected:
                visit(d)
        active.discard(k)
        done.add(k)
        order.append(k)

    for g, a, _ in roots:
        visit((g, a))
    for k in sorted(selected):
        visit(k)
    return order


# ---------------------------------------------------------------- roots and lockfile

def read_roots(path, repo):
    roots = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            p = line.split(":")
            if len(p) == 2:
                p.append(repo.latest_stable(p[0], p[1]))
                log(f"{p[0]}:{p[1]} -> latest stable {p[2]}")
            elif len(p) != 3:
                die(f"bad line in {path}: {line}")
            roots.append(tuple(p))
    return roots


def roots_hash(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:16]


def read_lock(path):
    entries, rh = [], None
    with open(path) as f:
        for line in f:
            if line.startswith("# roots "):
                rh = line.split()[2]
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            gav, fname, sha = line.split()
            g, a, v = gav.split(":")
            entries.append((g, a, v, None if fname == "-" else fname, None if sha == "-" else sha))
    return entries, rh


def write_lock(path, entries, rh, env):
    with open(path, "w") as f:
        f.write("# Generated by tools/fetch-deps.py -- do not edit; change the roots file and re-run.\n")
        f.write(f"# roots {rh}\n# env {env}\n")
        f.write("# group:artifact:version  file  sha256   (dependencies before dependents)\n")
        for g, a, v, fname, sha in entries:
            f.write(f"{g}:{a}:{v}  {fname or '-'}  {sha or '-'}\n")


# ---------------------------------------------------------------- unpacking and env

def local_name(g, a, v, fname):
    return f"{g}.{a}-{v}{os.path.splitext(fname)[1]}"


def explode(aar, dest):
    """Unpack the parts of an AAR the build uses (zip-slip safe, jni/ skipped)."""
    if os.path.isdir(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    keep_top = ("classes.jar", "AndroidManifest.xml", "R.txt", "proguard.txt", "public.txt")
    with zipfile.ZipFile(aar) as z:
        for info in z.infolist():
            n = info.filename
            if info.is_dir() or n.startswith("/") or ".." in n.split("/"):
                continue
            if n in keep_top or n.startswith(("res/", "assets/", "libs/")):
                out = n
            elif n.endswith("aar-metadata.properties"):
                out = "aar-metadata.properties"
            else:
                continue
            path = os.path.join(dest, out)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with z.open(info) as src, open(path, "wb") as dst:
                shutil.copyfileobj(src, dst)
    with open(os.path.join(dest, ".ok"), "w") as f:
        f.write(os.path.basename(aar) + "\n")


ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def aar_info(d):
    info = {"package": None, "min_sdk": 1, "min_compile_sdk": 0, "has_res": False, "has_r": False}
    mf = os.path.join(d, "AndroidManifest.xml")
    if os.path.exists(mf):
        root = ET.parse(mf).getroot()
        info["package"] = root.get("package")
        us = _child(root, "uses-sdk")
        if us is not None and us.get(ANDROID_NS + "minSdkVersion", "").isdigit():
            info["min_sdk"] = int(us.get(ANDROID_NS + "minSdkVersion"))
    res = os.path.join(d, "res")
    info["has_res"] = os.path.isdir(res) and any(os.scandir(res))
    rtxt = os.path.join(d, "R.txt")
    info["has_r"] = os.path.exists(rtxt) and os.path.getsize(rtxt) > 0
    md = os.path.join(d, "aar-metadata.properties")
    if os.path.exists(md):
        with open(md) as f:
            for line in f:
                k, _, val = line.strip().partition("=")
                if k == "minCompileSdk" and val.isdigit():
                    info["min_compile_sdk"] = int(val)
    return info


def write_env(out, entries):
    """deps.env, sourced by build.sh. Paths are relative to the project root.
    LIB_JARS        every jar that goes on the javac classpath and into d8 (deps first)
    LIB_RES_DIRS    AAR res/ dirs, lowest priority first (dependencies before dependents)
    LIB_MANIFESTS   AAR manifests, highest priority first (for the manifest merger)
    LIB_ASSETS      AAR assets/ dirs
    LIB_R_PACKAGES  colon-separated packages that need an R class (AARs with a non-empty R.txt)
    LIB_MIN_SDK / LIB_MIN_COMPILE_SDK   highest minSdk / minCompileSdk among the AARs
    """
    jars, res, mfs, assets, rpk = [], [], [], [], []
    min_sdk, min_csdk = 1, 0
    rel_out = os.path.relpath(out, ROOT)
    for g, a, v, fname, _ in entries:
        if not fname:
            continue
        ln = local_name(g, a, v, fname)
        if ln.endswith(".jar"):
            jars.append(f"{rel_out}/{ln}")
            continue
        d = f"{rel_out}/exploded/{g}.{a}-{v}"
        ad = os.path.join(ROOT, d)
        if os.path.exists(os.path.join(ad, "classes.jar")):
            jars.append(f"{d}/classes.jar")
        if os.path.isdir(os.path.join(ad, "libs")):
            for j in sorted(os.listdir(os.path.join(ad, "libs"))):
                if j.endswith(".jar"):
                    jars.append(f"{d}/libs/{j}")
        info = aar_info(ad)
        if info["has_res"]:
            res.append(f"{d}/res")
        if os.path.exists(os.path.join(ad, "AndroidManifest.xml")):
            mfs.append(f"{d}/AndroidManifest.xml")
        if os.path.isdir(os.path.join(ad, "assets")):
            assets.append(f"{d}/assets")
        if info["has_r"] and info["package"] and info["package"] not in rpk:
            rpk.append(info["package"])
        min_sdk = max(min_sdk, info["min_sdk"])
        min_csdk = max(min_csdk, info["min_compile_sdk"])
    mfs.reverse()
    with open(os.path.join(out, "deps.env"), "w") as f:
        f.write("# Generated by tools/fetch-deps.py from deps.lock -- sourced by build.sh\n")
        f.write(f'LIB_JARS="{" ".join(jars)}"\n')
        f.write(f'LIB_RES_DIRS="{" ".join(res)}"\n')
        f.write(f'LIB_MANIFESTS="{" ".join(mfs)}"\n')
        f.write(f'LIB_ASSETS="{" ".join(assets)}"\n')
        f.write(f'LIB_R_PACKAGES="{":".join(rpk)}"\n')
        f.write(f"LIB_MIN_SDK={min_sdk}\nLIB_MIN_COMPILE_SDK={min_csdk}\n")


def materialize(repo, out, entries):
    """Download every artifact in entries, unpack AARs, drop stale files, write deps.env."""
    os.makedirs(os.path.join(out, "exploded"), exist_ok=True)
    wanted, wanted_dirs = set(), set()
    jobs = []
    for g, a, v, fname, sha in entries:
        if not fname:
            continue
        ln = local_name(g, a, v, fname)
        wanted.add(ln)
        if ln.endswith(".aar"):
            wanted_dirs.add(f"{g}.{a}-{v}")
        jobs.append((g, a, v, fname, sha, ln))

    def one(job):
        g, a, v, fname, sha, ln = job
        dest = os.path.join(out, ln)
        if not (os.path.exists(dest) and (not sha or file_sha256(dest) == sha)):
            repo.download(g, a, v, fname, dest, sha)
        if ln.endswith(".aar"):
            ed = os.path.join(out, "exploded", f"{g}.{a}-{v}")
            ok = os.path.join(ed, ".ok")
            if not (os.path.exists(ok) and open(ok).read().strip() == ln):
                explode(dest, ed)

    with ThreadPoolExecutor(8) as ex:
        list(ex.map(one, jobs))
    for n in os.listdir(out):
        if n.endswith((".aar", ".jar")) and n not in wanted:
            os.remove(os.path.join(out, n))
    for n in os.listdir(os.path.join(out, "exploded")):
        if n not in wanted_dirs:
            shutil.rmtree(os.path.join(out, "exploded", n))
    write_env(out, entries)


def complete(out, entries):
    if not os.path.exists(os.path.join(out, "deps.env")):
        return False
    for g, a, v, fname, _ in entries:
        if not fname:
            continue
        ln = local_name(g, a, v, fname)
        if not os.path.exists(os.path.join(out, ln)):
            return False
        if ln.endswith(".aar") and not os.path.exists(os.path.join(out, "exploded", f"{g}.{a}-{v}", ".ok")):
            return False
    return True


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--roots", default=os.path.join(ROOT, "tools", "deps.txt"))
    ap.add_argument("--out", default=os.path.join(ROOT, "libs"))
    ap.add_argument("--env", choices=("android", "jvm"), default="android")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--locked", action="store_true")
    mode.add_argument("--ensure", action="store_true")
    ap.add_argument("--tree", action="store_true", help="print who requests what")
    ap.add_argument("--dry-run", action="store_true", help="resolve and print the closure; write nothing")
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    lock = os.path.join(out, "deps.lock")
    repo = Repo(os.path.join(out, "cache"))
    rh = roots_hash(args.roots)

    if args.locked or args.ensure:
        if os.path.exists(lock):
            entries, lrh = read_lock(lock)
            if args.locked or lrh == rh:
                if lrh != rh:
                    log(f"warning: {lock} was made from a different {args.roots}")
                if args.ensure and complete(out, entries):
                    return
                materialize(repo, out, entries)
                log(f"fetched {sum(1 for e in entries if e[3])} artifacts from {lock}")
                return
            log(f"{args.roots} changed since {lock} was written; re-resolving")
        elif args.locked:
            die(f"no {lock}")

    roots = read_roots(args.roots, repo)
    res = Resolver(repo, args.env)
    selected, edges, platforms = res.resolve(roots)
    order = topo_order(selected, edges, roots)
    entries = []
    for g, a in order:
        v = selected[(g, a)]
        m = res.meta(g, a, v, (g, a) in platforms and (g, a) not in {(r[0], r[1]) for r in roots})
        fname, sha = m.file if m.file else (None, None)
        entries.append((g, a, v, fname, sha))
    if args.tree:
        for k in order:
            print(f"{k[0]}:{k[1]}:{selected[k]}")
            for d in sorted(edges.get(k, ())):
                print(f"    -> {d[0]}:{d[1]}:{selected.get(d)}")
    if args.dry_run:
        for g, a, v, fname, _ in entries:
            print(f"{g}:{a}:{v}  {fname or '-'}")
        return
    materialize(repo, out, entries)
    # record the hash of every file actually downloaded (POM-only artifacts carry none)
    entries = [(g, a, v, f, sha or (file_sha256(os.path.join(out, local_name(g, a, v, f))) if f else None))
               for g, a, v, f, sha in entries]
    write_lock(lock, entries, rh, args.env)
    log(f"resolved {len(entries)} modules ({sum(1 for e in entries if e[3])} artifacts) -> {lock}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Export every archify diagram to a standalone SVG pair and a Markdown page.

Why this exists: GitHub renders a checked-in .html file as plain text, so the
interactive viewers under docs/ are invisible to anyone browsing the repository.
This writes, beside each <name>.html:

    <name>-light.svg / <name>-dark.svg   the diagram alone, one per theme
    <name>.md                            a GitHub-viewable page embedding them

Nothing is redrawn. The SVG is the one archify already rendered into the HTML,
lifted out of a real browser so that the page CSS which styles it (classes like
c-backend, t-primary) can be resolved to computed values and written back as
inline styles. The Markdown restates the diagram as text — stages, labelled
flows, conclusion cards — from the same JSON spec archify rendered from.

    python3 docs/export-diagrams.py            # everything under docs/
    python3 docs/export-diagrams.py A.html B.html

Requires Firefox and geckodriver (the snap ships both) and nothing else; run it
from the repository root. geckodriver is started and stopped for you.

    NOTE Firefox from a snap cannot read outside $HOME, so the repository must
    live under the user's home directory for the file:// loads to succeed.
"""
import atexit
import glob
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

PORT = int(os.environ.get("ARCHIFY_EXPORT_PORT", "4444"))
BASE = f"http://127.0.0.1:{PORT}"



def rq(method, path, body=None, timeout=180):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=timeout) as f:
        return json.loads(f.read())

# Presentation properties that decide how the diagram looks. Layout-affecting
# geometry stays in the markup; these are the painted ones.
PROPS = ["fill","fill-opacity","fill-rule","stroke","stroke-opacity","stroke-width",
         "stroke-linecap","stroke-linejoin","stroke-dasharray","stroke-dashoffset",
         "opacity","color","font-family","font-size","font-weight","font-style",
         "letter-spacing","text-anchor","dominant-baseline","paint-order",
         "mix-blend-mode","display","visibility","filter","mask","clip-path",
         "text-transform","white-space"]

SCRIPT = """
const PROPS = arguments[0];
const svg = document.querySelector('svg');
if (!svg) return null;

// Drop anything the viewer added for interaction; keep the authored drawing.
const clone = svg.cloneNode(true);
const originals = [svg, ...svg.querySelectorAll('*')];
const clones = [clone, ...clone.querySelectorAll('*')];

for (let i = 0; i < originals.length; i++) {
  const cs = getComputedStyle(originals[i]);
  const parent = originals[i].parentElement;
  const ps = parent && parent.namespaceURI === 'http://www.w3.org/2000/svg'
      ? getComputedStyle(parent) : null;
  const decls = [];
  for (const p of PROPS) {
    const v = cs.getPropertyValue(p);
    if (!v) continue;
    // Only what differs from the parent: inheritance then does the rest.
    if (ps && ps.getPropertyValue(p) === v) continue;
    decls.push(p + ':' + v);
  }
  if (decls.length) clones[i].setAttribute('style', decls.join(';'));
  clones[i].removeAttribute('class');
}

const vb = svg.getAttribute('viewBox') || '';
const parts = vb.split(/[ ,]+/).map(Number);
const page = getComputedStyle(document.body);
// The diagram panel's own surface, not the page margin behind it.
const panel = svg.closest('[class*="panel"], figure, main, section') || document.body;
return {
  svg: new XMLSerializer().serializeToString(clone),
  viewBox: vb,
  width: parts[2] || 0,
  height: parts[3] || 0,
  pageBg: page.backgroundColor,
  panelBg: getComputedStyle(panel).backgroundColor,
  title: (document.querySelector('title') || {}).textContent || ''
};
"""

def new_session():
    s = rq("POST", "/session", {"capabilities": {"alwaysMatch": {
        "browserName": "firefox",
        "moz:firefoxOptions": {"args": ["-headless"]}}}})
    return s["value"]["sessionId"]

# The legend's geometry is measured from real text at runtime, so a snapshot
# taken before the embedded webfont loads is laid out with fallback metrics —
# which also made the export differ by fractions of a pixel between runs.
SETTLE = """
const done = arguments[arguments.length - 1];
const ready = document.fonts ? document.fonts.ready : Promise.resolve();
ready.then(() => requestAnimationFrame(
        () => requestAnimationFrame(() => done(true))))
     .catch(() => done(false));
"""


def grab(sid, html_path, theme):
    url = "file://" + os.path.abspath(html_path) + "?theme=" + theme
    rq("POST", f"/session/{sid}/url", {"url": url})
    rq("POST", f"/session/{sid}/execute/async", {"script": SETTLE, "args": []})
    out = rq("POST", f"/session/{sid}/execute/sync", {"script": SCRIPT, "args": [PROPS]})
    return out["value"]

def build(data, theme):
    svg = data["svg"]
    # A standalone file needs the namespace and an intrinsic size; the page's
    # <svg> carries neither (the document supplied them).
    if "xmlns=" not in svg.split(">", 1)[0]:
        svg = svg.replace("<svg", '<svg xmlns="http://www.w3.org/2000/svg"', 1)
    if "xmlns:xlink=" not in svg.split(">", 1)[0] and "xlink:" in svg:
        svg = svg.replace("<svg", '<svg xmlns:xlink="http://www.w3.org/1999/xlink"', 1)
    head, rest = svg.split(">", 1)
    if " width=" not in head:
        head += f' width="{data["width"]}" height="{data["height"]}"'
    svg = head + ">" + rest
    # Paint the surface: an <img>-embedded SVG is transparent by default, which
    # would put light-theme text on GitHub's dark page.
    bg = data["panelBg"]
    if not bg or bg in ("transparent", "rgba(0, 0, 0, 0)"):
        bg = data["pageBg"]
    rect = (f'<rect x="0" y="0" width="{data["width"]}" height="{data["height"]}" '
            f'fill="{bg}"/>')
    i = svg.index(">") + 1
    svg = svg[:i] + rect + svg[i:]
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + svg + "\n"

def export_svgs(pairs):
    sid = new_session()
    try:
        for html_path, out_stem in pairs:
            for theme in ("light", "dark"):
                data = grab(sid, html_path, theme)
                if data is None:
                    print("NO SVG", html_path); continue
                out = f"{out_stem}-{theme}.svg"
                os.makedirs(os.path.dirname(out), exist_ok=True)
                with open(out, "w", encoding="utf-8") as f:
                    f.write(build(data, theme))
                print(f"{os.path.getsize(out):>7}  {out}")
    finally:
        rq("DELETE", f"/session/{sid}")


# ---------------------------------------------------------------------------
# Markdown pages
# ---------------------------------------------------------------------------

def esc(s):
    """Diagram labels legitimately contain <mapName>, &, backticks."""
    return html.escape(str(s), quote=False)

def spec_for(html_path):
    stem = html_path[:-5]
    for suffix in (".spec.json", ".architecture.json", ".dataflow.json",
                   ".workflow.json", ".sequence.json", ".lifecycle.json"):
        if os.path.exists(stem + suffix):
            return stem + suffix
    return None

def rel(frm, to):
    return os.path.relpath(to, os.path.dirname(frm)).replace(os.sep, "/")

def picture(md_path, stem, alt):
    light, dark = rel(md_path, stem + "-light.svg"), rel(md_path, stem + "-dark.svg")
    return (
        "<picture>\n"
        f'  <source media="(prefers-color-scheme: dark)" srcset="{dark}">\n'
        f'  <img alt="{esc(alt)}" src="{light}">\n'
        "</picture>"
    )

def node_table(spec):
    """The diagram's boxes, as a table: label, kind, and the note under it."""
    nodes = spec.get("nodes") or spec.get("components") or []
    if not nodes: return ""
    rows = ["| Element | Kind | Note |", "|---|---|---|"]
    for n in nodes:
        rows.append("| **{}** | {} | {} |".format(
            esc(n.get("label", n.get("id", ""))),
            esc(n.get("type", "")),
            esc(n.get("sublabel", "") or "")))
    return "\n".join(rows)

def flow_table(spec):
    """The labelled arrows — the part of a diagram that carries the verbs."""
    flows = spec.get("flows") or spec.get("connections") or []
    nodes = spec.get("nodes") or spec.get("components") or []
    name = {n.get("id"): n.get("label", n.get("id")) for n in nodes}
    rows = [r for r in flows if r.get("label")]
    if not rows: return ""
    out = ["| From | | To | Carries |", "|---|---|---|---|"]
    for f in rows:
        out.append("| {} | → | {} | {} |".format(
            esc(name.get(f.get("from"), f.get("from"))),
            esc(name.get(f.get("to"), f.get("to"))),
            esc(f.get("label", ""))))
    return "\n".join(out)

def stages_line(spec):
    st = spec.get("stages") or []
    if not st: return ""
    return " → ".join(esc(s.get("label", "")) for s in st)

def boundaries_list(spec):
    bs = spec.get("boundaries") or []
    nodes = spec.get("nodes") or spec.get("components") or []
    name = {n.get("id"): n.get("label", n.get("id")) for n in nodes}
    if not bs: return ""
    out = []
    for b in bs:
        members = ", ".join(esc(name.get(w, w)) for w in b.get("wraps", []))
        out.append(f"- **{esc(b.get('label',''))}** — {members}")
    return "\n".join(out)

def cards_md(spec):
    out = []
    for c in spec.get("cards", []):
        out.append("### " + esc(c.get("title", "")))
        out.append("")
        for item in c.get("items", []):
            out.append("- " + esc(item))
        out.append("")
    return "\n".join(out)

def build_markdown(html_path):
    stem = html_path[:-5]
    md_path = stem + ".md"
    spec_path = spec_for(html_path)
    spec = json.load(open(spec_path, encoding="utf-8")) if spec_path else {}
    meta = spec.get("meta", {})
    title = meta.get("title", os.path.basename(stem))
    kind = spec.get("diagram_type", "diagram")

    L = [f"# {title}", ""]
    L.append(picture(md_path, stem, f"{title} ({kind} diagram)"))
    L.append("")
    L.append(f"*The image above is a static export. The interactive version — "
             f"pan, zoom, search, relationship tracing and its own light/dark "
             f"toggle — is [`{os.path.basename(html_path)}`]({rel(md_path, html_path)}); "
             f"download it and open it in a browser, no server or network needed.*")
    L.append("")

    st = stages_line(spec)
    if st:
        L += ["**Stages:** " + st, ""]

    bl = boundaries_list(spec)
    if bl:
        L += ["## Boundaries", "", bl, ""]

    nt = node_table(spec)
    if nt:
        L += ["## Elements", "", nt, ""]

    ft = flow_table(spec)
    if ft:
        L += ["## Flows", "", ft, ""]

    cm = cards_md(spec)
    if cm:
        L += ["## What it shows", "", cm]

    L += ["---", ""]
    src = f"[`{os.path.basename(spec_path)}`]({rel(md_path, spec_path)})" if spec_path else "n/a"
    L.append(f"Source: {src} · rendered with the `archify` skill at its "
             f"`showcase` quality profile. The SVGs beside this page are exported "
             f"from that same HTML, so the three formats cannot drift — "
             f"regenerate them together with "
             f"[`docs/export-diagrams.py`]({rel(md_path, 'docs/export-diagrams.py')}); "
             f"see [`docs/diagrams.md`]({rel(md_path, 'docs/diagrams.md')}).")
    return md_path, "\n".join(L) + "\n"


# ---------------------------------------------------------------------------
# geckodriver lifecycle
# ---------------------------------------------------------------------------

def find_geckodriver():
    for c in ("geckodriver", "/snap/bin/firefox.geckodriver",
              "/usr/local/bin/geckodriver", "/usr/bin/geckodriver"):
        p = shutil.which(c) or (c if os.path.exists(c) else None)
        if p:
            return p
    return None


def start_driver():
    """Returns a stopper, or None when one is already listening on the port."""
    try:
        urllib.request.urlopen(BASE + "/status", timeout=2).read()
        return None                       # someone else's driver; leave it alone
    except Exception:
        pass
    exe = find_geckodriver()
    if not exe:
        sys.exit("geckodriver not found. Install Firefox (the snap ships "
                 "firefox.geckodriver) or put geckodriver on PATH.")
    proc = subprocess.Popen([exe, "--port", str(PORT), "--host", "127.0.0.1"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    atexit.register(proc.terminate)
    for _ in range(40):
        time.sleep(0.25)
        try:
            urllib.request.urlopen(BASE + "/status", timeout=2).read()
            return proc.terminate
        except Exception:
            continue
    proc.terminate()
    sys.exit(f"geckodriver did not come up on {BASE}")


def main(argv):
    targets = argv[1:] or sorted(glob.glob("docs/**/*.html", recursive=True))
    if not targets:
        sys.exit("no diagram HTML found under docs/")
    stop = start_driver()
    try:
        export_svgs([(t, t[:-5]) for t in targets])
    finally:
        if stop:
            stop()
    for t in targets:
        path, text = build_markdown(t)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"{len(text):>7}  {path}")


if __name__ == "__main__":
    main(sys.argv)

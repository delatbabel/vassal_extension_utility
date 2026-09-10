#!/usr/bin/env python3
r"""Upload a directory of scenario files to one release of a vassalengine.org package.

The library website uploads one file at a time, through a file picker, with no
way to queue a directory: publishing a release of the WiF scenarios means 47
trips through the same dialog, and a single mis-click leaves a release that is
missing a file nobody notices until a player reports it. This does the whole
directory in one command, against the same REST API the website itself uses
(`https://vassalengine.org/api/gls/v1` — see `model/GameLibrary`):

    POST /projects/{proj}/packages/{pkg}/{version}            create the release
    POST /projects/{proj}/packages/{pkg}/{version}/{filename}  upload one file

Both need an `Authorization: Bearer <token>` for a user who owns the project.

## Usage

    tools/upload_scenarios.py DIR [DIR...] --project=PROJ --package=PKG
                              [--release=X.Y.Z] [--apply]
                              [--token=JWT | --token-file=PATH] [--skip-bad]
                              [--api=URL] [--ums-api=URL] [--list]

    tools/upload_scenarios.py --project=PROJ --list      # packages and releases

`--project` takes the module page URL or the bare project name, so a link
copied out of the browser works as-is. `--package` matches a package by slug,
by name, or by any unambiguous fragment of either — `--package=Scenarios`
finds "Module Version 2.x Scenarios".

A directory contributes its `.vsav` files (minus any `*-backup*.vsav`, which is
what Refresh Counters leaves behind). A file named directly is uploaded whatever
its type, checked by the same rules the library applies to that type.

Reports by default and uploads only with `--apply`, like the other tools here.
The report lists every file with its size and what would happen to it, so the
release contents can be checked before a byte leaves the machine.

`--release` names the release to upload into, creating it if it does not exist.
Omit it and the release version is taken from the files themselves (see below),
which is what makes `--release` unnecessary for a normal scenario batch.

## What the library checks — mirrored here, before anything is uploaded

The service validates an uploaded `.vsav` (`prod_core.rs::add_file`) and a
rejection costs the whole upload of that file, so every check it makes is made
locally first:

- the file must be a ZIP holding a `moduledata` entry (a save written without
  one — some hand-built or very old files — is rejected);
- `moduledata`'s `<version>` must parse as **semver**, so `2.1.3` is fine but
  `2.1` and `1.63` are not: the server parses with the `semver` crate, which
  requires all three components;
- that version must **equal the release version**. This is why a batch of
  scenarios can only go into the release matching the module they were saved
  from, and why the default release version is the one the files carry;
- the filename must be acceptable to the object store (`upload.rs::safe_filename`):
  no control characters, none of `"'*/:<>?|\`, no `..`, no trailing period, no
  leading or trailing space, at most 255 characters, and not a reserved Windows
  name.

A file already in the release is **skipped**: the service holds
`UNIQUE(release_id, filename)`, so re-uploading one fails, which also means an
interrupted run can simply be run again. If a file of that name is already
published but its SHA-256 differs from the local copy, that is reported as a
conflict rather than skipped quietly — the API has no way to replace a file, so
the release has to be deleted (or a new release version used) from the website.

Files are uploaded in name order and each is verified afterwards against the
project JSON the service returns: same filename, same size, same SHA-256.

## Getting a token

The library issues short-lived JWTs to the website, so the token comes from a
browser session:

1. log in at https://vassalengine.org/library with the account that owns the
   project (`delatbabel` for the WiF project);
2. open the browser's cookie inspector for `vassalengine.org` (in Firefox:
   Storage → Cookies; in Chrome: Application → Cookies);
3. copy the value of the **`refresh`** cookie — that is the long-lived one —
   and, optionally, of `token` (the access token, good for an hour or so).

Then either pass `--token=<value>`, or save it (recommended, so it stays out of
your shell history and out of `ps`) as
`~/.vassal-extension-utility/library-token`, `chmod 600`:

    refresh=eyJhbGciOi...
    token=eyJhbGciOi...

A `refresh` value is enough on its own: the tool exchanges it for an access
token at `POST {ums}/refresh`, the same call the website makes, and does so
again whenever the access token is within a minute of expiring — so a
multi-gigabyte batch that outlasts one token keeps going. The file may also
just hold a bare token on a single line. Its contents are credentials and are
never printed.

`VASSAL_LIBRARY_TOKEN` / `VASSAL_LIBRARY_REFRESH_TOKEN` are honoured too, and
`--token-file` points at a different file.
"""
import base64, hashlib, json, os, re, sys, time, zipfile
import urllib.error, urllib.parse, urllib.request
import xml.etree.ElementTree as ET

GLS_API = 'https://vassalengine.org/api/gls/v1'
UMS_API = 'https://vassalengine.org/api/ums/v1'
TOKEN_FILE = os.path.expanduser('~/.vassal-extension-utility/library-token')
AGENT = 'VASSAL-Extension-Utility'

JSON_TIMEOUT = 60           # seconds, for the small JSON calls
UPLOAD_TIMEOUT = 1800       # seconds, for one file (the service allows 300s of
                            # request time by default, but its own proxy timeout
                            # is what actually bites; be generous and let the
                            # server say no)
RETRIES = 3                 # attempts per upload, for 5xx / dropped connections
BLOCK = 1 << 16             # progress-reporting granularity

# semver.org's own regex: what the server's `semver` crate accepts.
SEMVER = re.compile(
    r'^(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)'
    r'(?:-(?P<prerelease>(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)'
    r'(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?'
    r'(?:\+(?P<buildmetadata>[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$')

# upload.rs::safe_filename
BAD_CHARS = re.compile(r'[\x00-\x1F\x7F-\x9F"\'*/:<>?|\\]')
WIN_RESERVED = re.compile(r'^(?:CON|PRN|AUX|NUL|(?:COM|LPT)[1-9])($|\.)', re.I)


class ApiError(Exception):
    """An HTTP error from the library, carrying its own `{"error": ...}` text."""

    def __init__(self, status, message, url):
        super().__init__('HTTP %s: %s' % (status, message))
        self.status, self.message, self.url = status, message, url


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def _request(url, method='GET', data=None, headers=None, timeout=JSON_TIMEOUT):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('User-Agent', AGENT)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            message = json.loads(body.decode('utf-8', 'replace'))['error']
        except Exception:
            message = (body.decode('utf-8', 'replace').strip()
                       or e.reason or 'no message')
        raise ApiError(e.code, message, url) from None


def get_json(url):
    return json.loads(_request(url)[1].decode('utf-8'))


def quote(segment):
    """Percent-encode one path segment (filenames carry spaces and '#')."""
    return urllib.parse.quote(str(segment), safe='')


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------

def jwt_expiry(token):
    """-> the token's `exp` (epoch seconds), or None if it cannot be read."""
    try:
        payload = token.split('.')[1]
        payload += '=' * (-len(payload) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload))['exp'])
    except Exception:
        return None


def read_token_file(path):
    """-> (access token, refresh token) from `key=value` lines or a bare token."""
    access = refresh = None
    try:
        with open(path, encoding='utf-8') as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                if line.startswith('token='):
                    access = line[6:].strip()
                elif line.startswith('refresh='):
                    refresh = line[8:].strip()
                elif '=' not in line:
                    access = line
    except FileNotFoundError:
        pass
    return access, refresh


class Auth:
    """Holds the tokens and keeps the access token fresh.

    The website's own client refreshes whenever the access token has expired
    (`client.js::refreshTokenIfExpired`); a batch upload can outlast a token
    mid-run, so this refreshes a minute early too.
    """

    def __init__(self, access, refresh, ums_api):
        self.access, self.refresh, self.ums_api = access, refresh, ums_api

    def header(self):
        if self.refresh and self._expiring():
            self._refresh_access()
        if not self.access:
            raise SystemExit(
                'no library token: pass --token=..., or write one to %s '
                '(see the header of this script)' % TOKEN_FILE)
        return {'Authorization': 'Bearer ' + self.access}

    def _expiring(self):
        if not self.access:
            return True
        exp = jwt_expiry(self.access)
        return exp is None or time.time() + 60 >= exp

    def _refresh_access(self):
        url = self.ums_api + '/refresh'
        try:
            _, body = _request(url, method='POST',
                               headers={'Authorization': 'Bearer ' + self.refresh})
        except ApiError as e:
            raise SystemExit(
                'could not refresh the access token (%s).\nThe `refresh` cookie '
                'has probably expired — log in at vassalengine.org and copy it '
                'again.' % e) from None
        try:
            self.access = json.loads(body.decode('utf-8'))['token']
        except Exception:
            raise SystemExit('%s returned no token: %r'
                             % (url, body[:200])) from None

    def describe(self):
        """A one-line account of the credentials, with no secrets in it."""
        who = []
        if self.access:
            exp = jwt_expiry(self.access)
            who.append('access token' + (
                ' (%s %s)' % ('expired' if exp < time.time() else 'expires',
                              time.strftime('%H:%M:%S', time.localtime(exp)))
                if exp else ''))
        if self.refresh:
            who.append('refresh token')
        return ', '.join(who) if who else 'no token'


def explain(error, what):
    """The API's message, plus what it usually means for an uploader."""
    hint = {
        401: 'the token is missing, expired or not a valid JWT — see "Getting a '
             'token" in the header of this script',
        403: 'this account does not own the project',
        404: 'no such project, package or release',
        413: 'the file is over the library\'s size limit',
        415: 'the library would not accept the file type',
    }.get(error.status)
    return '%s: HTTP %s %s%s' % (what, error.status, error.message,
                                 '\n(%s)' % hint if hint else '')


def load_auth(args):
    access = args.get('--token') or os.environ.get('VASSAL_LIBRARY_TOKEN')
    refresh = os.environ.get('VASSAL_LIBRARY_REFRESH_TOKEN')
    if not access and not refresh:
        access, refresh = read_token_file(args.get('--token-file') or TOKEN_FILE)
    return Auth(access, refresh, args.get('--ums-api') or UMS_API)


# ---------------------------------------------------------------------------
# Local pre-flight, mirroring the service's own checks
# ---------------------------------------------------------------------------

# The extensions the service opens and version-checks (prod_core.rs::add_file);
# a `.vmdx` needs only a valid version, the rest must match the release.
CHECKED_EXTS = ('vsav', 'vmod', 'vlog', 'vmdx')
FREE_VERSION_EXTS = ('vmdx', )


def module_version(path):
    """-> (version string, problem). Exactly what the service reads."""
    if path.rsplit('.', 1)[-1].lower() not in CHECKED_EXTS:
        return None, None                  # not a type the library looks inside
    if not zipfile.is_zipfile(path):
        return None, 'not a ZIP archive'
    try:
        with zipfile.ZipFile(path) as z:
            if 'moduledata' not in z.namelist():
                return None, 'no "moduledata" entry — the library rejects it'
            md = z.read('moduledata')
    except Exception as e:
        return None, 'unreadable: %s' % e
    try:
        version = ET.fromstring(md).findtext('version') or ''
    except ET.ParseError as e:
        return None, 'malformed moduledata: %s' % e
    if not version:
        return None, 'moduledata has no <version>'
    if not SEMVER.match(version):
        return version, ('module version %r is not semver, so the library '
                         'cannot accept the file' % version)
    return version, None


def filename_problem(name):
    if not name or len(name) > 255:
        return 'filename is empty or over 255 characters'
    if name != name.strip():
        return 'filename has leading or trailing whitespace'
    if name.endswith('.'):
        return 'filename ends with a period'
    if '..' in name:
        return 'filename contains ".."'
    if BAD_CHARS.search(name):
        return 'filename contains a character the library rejects'
    if WIN_RESERVED.match(name):
        return 'filename is a reserved Windows name'
    return None


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for block in iter(lambda: fh.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def human(n):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return '%.1f %s' % (n, unit) if unit != 'B' else '%d B' % n
        n /= 1024.0


# ---------------------------------------------------------------------------
# The library project
# ---------------------------------------------------------------------------

def project_name_from(value):
    """The project name from a pasted page URL or the bare name.

    Mirrors `GameLibrary.projectNameFrom`.
    """
    s = (value or '').strip()
    for cut in ('#', '?'):
        if cut in s:
            s = s[:s.index(cut)]
    s = s.rstrip('/')
    return s.rsplit('/', 1)[-1]


def find_package(project, wanted):
    """The package matching `wanted` by slug, name, or an unambiguous fragment."""
    packages = project.get('packages') or []
    for pkg in packages:                                   # exact slug or name
        if wanted in (pkg.get('slug'), pkg.get('name')):
            return pkg
    low = wanted.lower()
    hits = [p for p in packages
            if low in (p.get('slug') or '').lower()
            or low in (p.get('name') or '').lower()]
    if len(hits) == 1:
        return hits[0]
    if not hits:
        raise SystemExit('no package matching %r in %s. It has:\n%s'
                         % (wanted, project.get('slug'), list_packages(project)))
    raise SystemExit('%r matches %d packages; be more specific:\n%s'
                     % (wanted, len(hits),
                        '\n'.join('    %s' % p.get('name') for p in hits)))


def list_packages(project):
    out = []
    for pkg in project.get('packages') or []:
        versions = [r.get('version') for r in pkg.get('releases') or []]
        out.append('    %s\n        slug %s\n        releases: %s'
                   % (pkg.get('name'), pkg.get('slug'),
                      ', '.join(versions) if versions else '(none)'))
    return '\n'.join(out)


def find_release(pkg, version):
    for rel in pkg.get('releases') or []:
        if rel.get('version') == version:
            return rel
    return None


# ---------------------------------------------------------------------------
# Uploading
# ---------------------------------------------------------------------------

class ProgressReader:
    """Wraps the file so `http.client` streams it while we report progress.

    The percentage is drawn in place with a carriage return, which only makes
    sense on a terminal — a run redirected to a log (a 1 GB batch under
    `nohup`) gets one line per file instead.
    """

    def __init__(self, path, size, label):
        self.fh = open(path, 'rb')
        self.size, self.label = size, label
        self.done, self.reported, self.start = 0, 0, time.time()
        self.tty = sys.stdout.isatty()

    def read(self, n=-1):
        block = self.fh.read(n)
        self.done += len(block)
        if self.tty and (not block or self.done - self.reported >= BLOCK):
            self.reported = self.done
            self._report()
        return block

    def _report(self):
        elapsed = max(time.time() - self.start, 1e-6)
        sys.stdout.write('\r        %-52s %3d%%  %s/s   '
                         % (self.label[:52], 100 * self.done // max(self.size, 1),
                            human(self.done / elapsed)))
        sys.stdout.flush()

    def close(self):
        self.fh.close()


def create_release(api, proj, pkg_slug, version, auth):
    url = '%s/projects/%s/packages/%s/%s' % (api, quote(proj), quote(pkg_slug),
                                             quote(version))
    # b'' rather than None so the request carries a Content-Length: 0, as the
    # website's own fetch() does.
    _request(url, method='POST', data=b'', headers=auth.header())


def upload_file(api, proj, pkg_slug, version, path, size, auth):
    """POSTs one file, retrying a server-side or network failure."""
    url = '%s/projects/%s/packages/%s/%s/%s' % (
        api, quote(proj), quote(pkg_slug), quote(version),
        quote(os.path.basename(path)))
    label = os.path.basename(path)
    for attempt in range(1, RETRIES + 1):
        body = ProgressReader(path, size, label)
        try:
            headers = dict(auth.header())          # may refresh the token first
            headers['Content-Type'] = 'application/octet-stream'
            headers['Content-Length'] = str(size)
            _request(url, method='POST', data=body, headers=headers,
                     timeout=UPLOAD_TIMEOUT)
            return
        except ApiError as e:
            # A duplicate filename surfaces as a 500 from the UNIQUE(release_id,
            # filename) constraint — the file is already there, so retrying it
            # can only fail again.
            if 'UNIQUE' in e.message or e.status < 500 or attempt == RETRIES:
                raise
            print('\n        HTTP %s: %s — retrying (%d/%d)'
                  % (e.status, e.message, attempt, RETRIES))
        except OSError as e:                       # timeout, reset, DNS, TLS
            if attempt == RETRIES:
                raise
            print('\n        %s — retrying (%d/%d)' % (e, attempt, RETRIES))
        finally:
            body.close()
        time.sleep(5 * attempt)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

USAGE = """usage: upload_scenarios.py DIR [DIR...] --project=PROJ --package=PKG
                           [--release=X.Y.Z] [--apply] [--skip-bad]
                           [--token=JWT | --token-file=PATH]
                           [--api=URL] [--ums-api=URL]
       upload_scenarios.py --project=PROJ --list

See the header of this script for the full description and how to get a token."""


def main(argv):
    args, flags, dirs = {}, set(), []
    for a in argv:
        if a.startswith('--') and '=' in a:
            k, v = a.split('=', 1)
            args[k] = v
        elif a.startswith('--'):
            flags.add(a)
        else:
            dirs.append(a)

    if ({'--help', '-h'} & set(argv)) or (not dirs and '--list' not in flags):
        raise SystemExit(USAGE)
    if not args.get('--project'):
        raise SystemExit('--project is required\n\n' + USAGE)

    api = (args.get('--api') or GLS_API).rstrip('/')
    proj = project_name_from(args['--project'])

    project = get_json('%s/projects/%s' % (api, quote(proj)))
    print('project %s — owners: %s'
          % (project.get('slug'), ', '.join(project.get('owners') or [])))

    if '--list' in flags:
        print(list_packages(project))
        return 0

    if not args.get('--package'):
        raise SystemExit('--package is required. %s has:\n%s'
                         % (project.get('slug'), list_packages(project)))
    pkg = find_package(project, args['--package'])
    print('package %r (slug %s)' % (pkg.get('name'), pkg.get('slug')))

    # ---- collect the files ------------------------------------------------
    files = []
    for d in dirs:
        if os.path.isfile(d):
            files.append(d)
            continue
        if not os.path.isdir(d):
            raise SystemExit('%s: no such file or directory' % d)
        files += [os.path.join(d, n) for n in sorted(os.listdir(d))
                  if n.lower().endswith('.vsav')
                  and '-backup' not in n.lower()      # never a Refresh backup
                  and os.path.isfile(os.path.join(d, n))]
    if not files:
        raise SystemExit('no .vsav files found in: %s' % ', '.join(dirs))
    files.sort(key=lambda p: os.path.basename(p).lower())

    # ---- pre-flight, and the release version ------------------------------
    checked = []          # (path, size, module version, problem)
    versions = {}
    for path in files:
        size = os.path.getsize(path)
        version, problem = module_version(path)
        problem = problem or filename_problem(os.path.basename(path))
        checked.append([path, size, version, problem])
        if version and not problem and (
                path.rsplit('.', 1)[-1].lower() not in FREE_VERSION_EXTS):
            versions.setdefault(version, []).append(os.path.basename(path))

    release_version = args.get('--release')
    if not release_version:
        if len(versions) == 1:
            release_version = next(iter(versions))
            print('release %s — taken from the files\' module version'
                  % release_version)
        elif not versions:
            raise SystemExit('no file has a usable module version; '
                             'pass --release=X.Y.Z to name the release')
        else:
            raise SystemExit(
                'the files carry %d different module versions, so the release '
                'cannot be inferred — pass --release=X.Y.Z:\n%s'
                % (len(versions), '\n'.join(
                    '    %-10s %d file(s): %s' % (v, len(n), ', '.join(n[:3])
                                                  + (', …' if len(n) > 3 else ''))
                    for v, n in sorted(versions.items()))))
    elif not SEMVER.match(release_version):
        raise SystemExit('--release=%s is not a semver version (X.Y.Z), which '
                         'is all the library accepts' % release_version)

    # every file's module version must equal the release version — except an
    # extension's, which the service accepts at any valid version
    for row in checked:
        free = row[0].rsplit('.', 1)[-1].lower() in FREE_VERSION_EXTS
        if not row[3] and row[2] is not None and not free \
                and row[2] != release_version:
            row[3] = ('module version %s does not match release %s'
                      % (row[2], release_version))

    release = find_release(pkg, release_version)
    published = {f['filename']: f for f in (release or {}).get('files') or []}
    print('release %s — %s' % (release_version,
                               '%d file(s) already published' % len(published)
                               if release else 'does not exist yet, will be created'))

    # ---- what would happen to each file -----------------------------------
    todo, skip, bad, conflict = [], [], [], []
    for path, size, version, problem in checked:
        name = os.path.basename(path)
        if problem:
            bad.append((name, size, problem))
            continue
        remote = published.get(name)
        if remote is None:
            todo.append((path, size))
            continue
        digest = sha256(path)
        if remote.get('sha256') == digest:
            skip.append((name, size))
        else:
            conflict.append((name, size, remote, digest))

    print('\n%d file(s), %s' % (len(checked), human(sum(r[1] for r in checked))))
    for path, size in todo:
        print('    %-52s %10s  upload' % (os.path.basename(path), human(size)))
    for name, size in skip:
        print('    %-52s %10s  already published, identical — skip' % (name, human(size)))
    for name, size, remote, digest in conflict:
        print('    %-52s %10s  CONFLICT: differs from the published copy'
              % (name, human(size)))
        print('        published %s bytes sha256 %s… — local %s bytes sha256 %s…'
              % (format(remote.get('size') or 0, ','), (remote.get('sha256') or '')[:8],
                 format(size, ','), digest[:8]))
    for name, size, problem in bad:
        print('    %-52s %10s  PROBLEM: %s' % (name, human(size), problem))

    if conflict:
        print('\nA published file cannot be replaced through the API. Delete the '
              'release on the website, or upload into a new release version.')

    print('\n%d to upload (%s), %d skipped, %d conflict(s), %d problem(s)'
          % (len(todo), human(sum(s for _, s in todo)), len(skip),
             len(conflict), len(bad)))

    if '--apply' not in flags:
        print('REPORT ONLY — pass --apply to create the release and upload')
        return 1 if (bad or conflict) else 0

    if (bad or conflict) and '--skip-bad' not in flags:
        raise SystemExit(
            'refusing to upload with %d problem(s) and %d conflict(s) '
            'outstanding: fix them, or pass --skip-bad to upload the rest'
            % (len(bad), len(conflict)))
    if not todo:
        print('nothing to upload')
        return 1 if (bad or conflict) else 0

    # ---- upload -----------------------------------------------------------
    auth = load_auth(args)
    print('\nauthenticating with: %s' % auth.describe())
    if not auth.refresh and auth.access and auth._expiring():
        print('WARNING: that access token has expired. Add the `refresh` cookie '
              'to %s so the tool can mint its own.' % TOKEN_FILE)

    if not release:
        try:
            create_release(api, proj, pkg['slug'], release_version, auth)
        except ApiError as e:
            raise SystemExit(explain(e, 'could not create release %s'
                                     % release_version)) from None
        print('created release %s' % release_version)

    started, uploaded, failed = time.time(), [], []
    for i, (path, size) in enumerate(todo, 1):
        print('    [%d/%d] %s (%s)' % (i, len(todo), os.path.basename(path),
                                       human(size)))
        began = time.time()
        try:
            upload_file(api, proj, pkg['slug'], release_version, path, size, auth)
            took = max(time.time() - began, 1e-6)
            print('\r        %-52s done in %ds (%s/s)%10s'
                  % (os.path.basename(path)[:52], took, human(size / took), ''))
            uploaded.append(path)
        except (ApiError, OSError) as e:
            why = explain(e, 'rejected') if isinstance(e, ApiError) else str(e)
            print('\r        %-52s FAILED: %s'
                  % (os.path.basename(path)[:52], why))
            failed.append((os.path.basename(path), why))

    elapsed = time.time() - started
    print('\nuploaded %d file(s), %s in %d:%02d'
          % (len(uploaded), human(sum(os.path.getsize(p) for p in uploaded)),
             elapsed // 60, elapsed % 60))

    # ---- verify against what the library now reports -----------------------
    project = get_json('%s/projects/%s' % (api, quote(proj)))
    pkg = find_package(project, pkg['slug'])
    release = find_release(pkg, release_version)
    now = {f['filename']: f for f in (release or {}).get('files') or []}
    missing = []
    for path in uploaded:
        name = os.path.basename(path)
        remote = now.get(name)
        if remote is None:
            missing.append('%s: not listed in the release' % name)
        elif remote.get('size') != os.path.getsize(path):
            missing.append('%s: size %s there, %s here'
                           % (name, remote.get('size'), os.path.getsize(path)))
        elif remote.get('sha256') != sha256(path):
            missing.append('%s: SHA-256 differs' % name)
    print('release %s now holds %d file(s); %d of ours verified byte-for-byte'
          % (release_version, len(now), len(uploaded) - len(missing)))
    for m in missing:
        print('    UNVERIFIED %s' % m)
    for name, err in failed:
        print('    FAILED     %s: %s' % (name, err))
    if failed:
        print('\nRun the same command again to retry the failures: files already '
              'published are skipped.')
    # Non-zero whenever anything was left undone, including the files skipped
    # by --skip-bad, so a scripted run notices.
    return 1 if (failed or missing or bad or conflict) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

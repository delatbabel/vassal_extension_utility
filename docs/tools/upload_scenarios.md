# upload_scenarios.py — publish a directory of scenarios to the library

The library website uploads one file at a time, through a file picker, with no
way to queue a directory: publishing a release of the WiF scenarios means 47
trips through the same dialog, and a single mis-click leaves a release quietly
missing a file nobody notices until a player reports it. This does the whole
directory in one command, against the same REST API the website itself uses.

```
POST /projects/{proj}/packages/{pkg}/{version}             create the release
POST /projects/{proj}/packages/{pkg}/{version}/{filename}  upload one file
```

Both need an `Authorization: Bearer <token>` for a user who owns the project.
The API base is `https://vassalengine.org/api/gls/v1` — the same one
[`model/GameLibrary`](../../src/main/java/org/vassalengine/extutil/model/GameLibrary.java)
reads.

## Synopsis

```
tools/upload_scenarios.py DIR [DIR...] --project=PROJ --package=PKG
                          [--release=X.Y.Z] [--apply] [--skip-bad]
                          [--token=JWT | --token-file=PATH]
                          [--api=URL] [--ums-api=URL]

tools/upload_scenarios.py --project=PROJ --list
```

| Option | Effect |
|---|---|
| `--project=PROJ` | Module page URL or bare project name — a link copied out of the browser works as-is. |
| `--package=PKG` | Matches a package by slug, by name, or by any unambiguous fragment of either: `--package=Scenarios` finds "Module Version 2.x Scenarios". |
| `--release=X.Y.Z` | Release to upload into, created if absent. Omit it and the version is taken from the files themselves. |
| `--list` | Print the project's packages and releases. Needs no credentials. |
| `--apply` | Actually create the release and upload. **Without it the tool only reports.** |
| `--skip-bad` | Continue past files that fail the local checks instead of stopping. |
| `--token=JWT` / `--token-file=PATH` | Supply credentials explicitly. |
| `--api=URL` / `--ums-api=URL` | Point at a different service — a local instance, for example. |

A directory contributes its `.vsav` files, minus any `*-backup*.vsav` (what
Refresh Counters leaves behind). A file named directly is uploaded whatever its
type, checked by the rules the library applies to that type.

## The report is the point

```
$ tools/upload_scenarios.py ~/WiFScenarios/2.1.3_Scenarios \
      --project=World_in_Flames_Official_CE_and_Extensions_DonHarris \
      --package=Scenarios
project World_in_Flames_Official_CE_and_Extensions_DonHarris — owners: delatbabel, palad0n
package 'Module Version 2.x Scenarios' (slug Module-Version-2.x-Scenarios)
release 2.1.3 — taken from the files' module version
release 2.1.3 — 46 file(s) already published

47 file(s), 915.8 MB
    118-presetup-ce-maps-fascist-tide-deluxe-fif.vsav       19.4 MB  upload
    003-presetup-ce-maps-everything.vsav                    31.9 MB  already published, identical — skip
    …
    404-aif-maps-empty.vsav                                246.3 KB  CONFLICT: differs from the published copy
        published 252,189 bytes sha256 d118b113… — local 252,175 bytes sha256 05f149e4…

1 to upload (19.4 MB), 45 skipped, 1 conflict(s), 0 problem(s)
REPORT ONLY — pass --apply to create the release and upload
```

## Every server check is made locally first

Open [diagrams/upload_scenarios.html](diagrams/upload_scenarios.html).

The service validates every uploaded save (`prod_core.rs::add_file`) and a
rejection costs the whole upload of that file, so each of its checks is made
locally before a byte leaves the machine:

- the file must be a ZIP holding a `moduledata` entry (a save written without one
  — some hand-built or very old files — is rejected);
- `moduledata`'s `<version>` must parse as **semver**, so `2.1.3` is fine but
  `2.1` and `1.63` are not: the service parses with the `semver` crate, which
  requires all three components;
- that version must **equal the release version** (an extension is the one
  exception: a `.vmdx` needs only a valid version);
- the filename must satisfy `upload.rs::safe_filename` — no control characters,
  none of `"'*/:<>?|\`, no `..`, no trailing period, no leading or trailing
  space, at most 255 characters, and not a reserved Windows name.

So a batch of scenarios can only go into the release matching the module they
were saved from, which is why `--release` can be omitted: the default is the
version the files themselves carry, and mixed versions are refused rather than
half-uploaded.

## Interrupted runs, and files already published

A file already in the release is **skipped**, because the service holds
`UNIQUE(release_id, filename)` and re-uploading one fails — so an interrupted or
partly-failed run is resumed by running the same command again.

A local file whose name is published but whose SHA-256 differs is reported as a
**conflict**, never overwritten silently: the API has no way to replace a file,
so either delete the release on the website or publish into a new release
version.

Files are uploaded in name order. Every upload is verified afterwards against the
project JSON the service returns — same filename, same size, same SHA-256 — and a
5xx or dropped connection is retried up to three times. The exit status is
non-zero if anything was left undone.

## Credentials

The library issues short-lived JWTs to the website, so the token comes from a
browser session:

1. log in at <https://vassalengine.org/library> with the account that owns the
   project;
2. open the browser's cookie inspector for `vassalengine.org` — Firefox: Storage
   → Cookies; Chrome: Application → Cookies;
3. copy the value of the **`refresh`** cookie (the long-lived one) and,
   optionally, of `token` (the access token, good for an hour or so).

Save them as `~/.vassal-extension-utility/library-token`, `chmod 600`:

```
refresh=eyJhbGciOi...
token=eyJhbGciOi...
```

The `refresh` value alone is enough: the tool exchanges it for an access token at
`POST https://vassalengine.org/api/ums/v1/refresh` — the same call the website
makes — and does so again whenever the access token is within a minute of
expiring, so a multi-gigabyte batch that outlasts one token keeps going. The file
may also just hold a bare token on a single line. Its contents are credentials
and are never printed.

`--token=`, `--token-file=`, `VASSAL_LIBRARY_TOKEN` and
`VASSAL_LIBRARY_REFRESH_TOKEN` are all honoured.

## See also

- [docs/architecture/architecture.html](../architecture/architecture.html) — where `GameLibrary` sits in the application
- [tools/README.md](../../tools/README.md#upload_scenariospy--publish-a-directory-of-scenarios-to-the-library)

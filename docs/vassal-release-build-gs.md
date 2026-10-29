# `make release` dies on Ghostscript — `(EBADF) Bad file descriptor - /usr/bin/gs`

A fix in `../vassal`. Ghostscript was never the problem; it is never reached.

---

## 1. The symptom

```
[ERROR] Failed to execute goal org.asciidoctor:asciidoctor-maven-plugin:3.2.0:process-asciidoc
        (output-userguide) on project vassal-doc: Execution output-userguide of goal
        org.asciidoctor:asciidoctor-maven-plugin:3.2.0:process-asciidoc failed:
        org.jruby.exceptions.SystemCallError: (EBADF) Bad file descriptor - /usr/bin/gs
```

The obvious reading — Ghostscript missing or broken — is wrong. It is installed and healthy:

```
$ dpkg -l ghostscript
ii  ghostscript  10.06.0~dfsg-3ubuntu1.2  amd64  interpreter for the PostScript language and for PDF
$ /usr/bin/gs --version
10.06.0
```

`EBADF` is also the wrong errno for a missing executable; that would be `ENOENT`. The bad file
descriptor belongs to the *caller*, not to `gs`.

## 2. Why only `make release`

Two Make targets build the engine jar and they treat the documentation differently:

| | asciidoctor | Result |
|---|---|---|
| `make jar` | `SKIPS` includes `-Dasciidoctor.skip=true` | never runs, never fails |
| `make release` | `SKIPS` empty, and `-Dasciidoctor.attributes=optimize` | runs **with PDF optimization** |

That `optimize` attribute (in the `$(LIBDIR)/Vengine.jar` recipe) is what makes
`asciidoctor-pdf` shrink the generated PDFs through Ghostscript. It is worth having — the user
guide halves, 2 008 686 → 993 480 bytes — but it is the only thing in the build that shells out
to an external program, which is why nothing else trips over this.

Note that `vassal-doc/pom.xml` already sets `<optimize>false</optimize>` on two of the three
executions, with a comment citing
[asciidoctorj-pdf#108](https://github.com/asciidoctor/asciidoctorj-pdf/issues/108) ("bug in Ruby
with Java 17 and up"). The Makefile's `-Dasciidoctor.attributes=optimize` overrides that, so the
release build walks straight into the bug the pom was trying to avoid.

## 3. The actual cause

The Ruby stack trace says it plainly:

```
org.jruby.RubyProcess.spawn        (org/jruby/RubyProcess.java:1779)
RUBY.popen_run                     (stdlib/open3.rb:222)
RUBY.capture3                      (stdlib/open3.rb:290)
RUBY.run                           (gems/asciidoctor-pdf-2.3.27/lib/asciidoctor/pdf/optimizer.rb:22)
RUBY.render                        (gems/rghost-0.9.7/lib/rghost/ruby_ghost_engine.rb:83)
```

`asciidoctor-pdf`'s optimizer calls Ghostscript through `Open3.capture3`, which needs three
pipes wired to the child's stdin/stdout/stderr. To build those with `posix_spawn`, JRuby has to
turn Java stream objects into real operating-system file descriptors, and it does that by
reflecting into `java.io.FileDescriptor` and `sun.nio.ch`.

Both are strongly encapsulated from Java 17 on, and this machine runs **Java 25** (no older JDK
is installed). The reflection fails, JRuby spawns with invalid descriptors, and the kernel
rejects the call with `EBADF`. The path in the message is just the command it was trying to run.

## 4. The fix

Open the two packages JRuby reflects into, in `../vassal/.mvn/jvm.config`:

```
--add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

`.mvn/jvm.config` is read automatically by `./mvnw` for every invocation in the project, so
there is nothing to remember and nothing to set in the environment. It takes raw JVM arguments
only — **a `#` comment in that file is passed to the JVM as a main class name** and the build
fails with `Could not find or load main class #` — so the explanation lives as a comment above
the `$(LIBDIR)/Vengine.jar` recipe in the `Makefile`, which is where the `optimize` flag is set.

Both options are needed; neither alone is enough, and no other `--add-opens` substitutes:

| Flags | Result |
|---|---|
| `java.io` alone | fails |
| `sun.nio.ch` alone | fails |
| `java.lang` alone | fails |
| `java.io` + `java.lang` | fails |
| `sun.nio.ch` + `java.lang` | fails |
| **`java.io` + `sun.nio.ch`** | **succeeds** |

### What was tried and rejected

- **`--enable-native-access=ALL-UNNAMED`.** JRuby warns about restricted native access on Java
  25, so this looks like the fix. It is not: still `EBADF`.
- **`-Djruby.native.enabled=false`**, to make JRuby use its pure-Java process launcher. This
  replaces the failure rather than removing it — `NotImplementedError: waitpid unsupported or
  native support failed to load`.
- **Dropping `-Dasciidoctor.attributes=optimize`** from the Makefile. The build would pass, but
  every released PDF would be twice its necessary size, and the flag is deliberate.

## 5. Verifying

```bash
cd ../vassal
./mvnw -pl vassal-doc generate-resources -Dasciidoctor.attributes=optimize
```

Should report `BUILD SUCCESS` for all three `process-asciidoc` executions, and leave an
optimized PDF:

```
vassal-doc/target/classes/userguide/userguide.pdf          993 480   (2 008 686 unoptimized)
vassal-doc/target/classes/designerguide/designerguide.pdf  2 780 681
```

The halved size is the proof that Ghostscript really ran, rather than the optimization being
silently skipped.

## 6. Building Windows afterwards — the jlink/jmods version trap

With the spawn fixed, `make release` gets through the Maven build, `release-other` and
`release-linux`, then stops at Windows because the cross-build tooling is not present:

```
make: *** No rule to make target 'tmp/VASSAL-...-windows-x86_64.exe', needed by 'release-windows-x86_64'.
```

`./bootstrap.sh` fetches it, but **its default JDK will not work**. The Makefile links the
bundled runtime with the *host* `jlink` against the *target* JDK's `jmods`, and jlink refuses a
feature-version mismatch. bootstrap defaults to Temurin 26 while this machine's JDK — and so its
jlink — is 25, so the default download produces a runtime that cannot be linked.

Ask for the version you actually have:

```bash
java -version                       # e.g. 25.0.4.1
./bootstrap.sh --no-dmg --no-wrapper -t 25.0.4.1+1 windows-x86_64
make release-windows
```

Temurin publishes `jdk-25.0.4.1+1`, an exact match for the host, which is the safest choice —
list what is available with:

```bash
curl -s 'https://api.adoptium.net/v3/info/release_names?architecture=x64&image_type=jdk&os=windows&release_type=ga&version=%5B25%2C26%29&page_size=5&sort_order=DESC'
```

`--no-dmg` and `--no-wrapper` skip building libdmg-hfsplus (macOS only) and regenerating the
Maven wrapper. Everything lands under `dist/` and `make release-windows` then produces the NSIS
installer. `nsis`, `dos2unix`, `cmake` and `genisoimage` need to be installed; `jinja2-cli` is
only needed for `release-announcements`.

## 7. Files changed

| File | Change |
|---|---|
| `.mvn/jvm.config` | new — the two `--add-opens` |
| `Makefile` | comment above the `Vengine.jar` recipe explaining the flag, the failure and where the fix lives |

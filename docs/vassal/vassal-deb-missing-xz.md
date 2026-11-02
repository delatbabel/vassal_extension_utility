# `NoClassDefFoundError: org/tukaani/xz/FilterOptions` on the 3.8.0 `.deb`

Diagnosis of [vassalengine/vassal#15256](https://github.com/vassalengine/vassal/issues/15256).

**Short version:** the package is built correctly. The new `libxz-java` dependency was not
installed, because the `.deb` was installed in a way that does not resolve dependencies. The
cure is `sudo apt install libxz-java`; the way to avoid it is to install the `.deb` with `apt`
rather than `dpkg -i`.

---

## 1. The report

Installing VASSAL 3.8.0 from the `.deb` and opening any module:

```
java.lang.RuntimeException: java.util.concurrent.ExecutionException:
java.lang.NoClassDefFoundError: org/tukaani/xz/FilterOptions
    at VASSAL.launch.Launcher.<init>(Launcher.java:164)
    at VASSAL.launch.Player.<init>(Player.java:61)
Caused by: java.lang.NoClassDefFoundError: org/tukaani/xz/FilterOptions
    at VASSAL.build.GameModule.initGameState(GameModule.java:912)
```

The reporter saw it with *World War I 1.2.3* and "whenever opening or editing other modules",
so it is not module-specific.

## 2. Why it fires on *opening* a module

3.8.0 took on one new third-party dependency, `org.tukaani:xz`, for the XZ-compressed saved-game
format. `GameState` uses `LZMA2Options`, and `GameModule.initGameState()` runs when **any**
module opens — so the class is needed on open, long before anything is saved. The class named in
the error is `FilterOptions` rather than anything obviously XZ-ish because it is
`LZMA2Options`'s superclass, which the verifier must resolve to check the constructor call.

## 3. The package is not at fault

Unpacking the released `vassal_3.8.0-1_all.deb` shows all three places that matter are correct:

| What | Value |
|---|---|
| `Depends:` | includes `libxz-java (>= 1.9)` |
| payload | `/usr/share/vassal/lib/xz.jar -> ../../java/xz.jar` |
| `Vengine.jar` manifest | `Class-Path` has 47 entries, `xz.jar` among them |

The manifest entry is worth a note because it *looks* broken on inspection — the wrap falls
mid-token, `xz.ja` on one line and ` r` on the next — but that is ordinary manifest continuation
syntax. Parsed with `java.util.jar.Manifest` it yields exactly `xz.jar`.

## 4. What actually happens

The Module Manager launches the Player as a child JVM with **one jar on the classpath**:

```
/usr/lib/jvm/.../bin/java ... -cp /usr/share/vassal/lib/Vengine.jar VASSAL.launch.Player
```

Everything else is reached through that jar's manifest `Class-Path`, whose entries are relative
symlinks into `/usr/share/java`. So:

1. `libxz-java` is not installed →
2. `/usr/share/java/xz.jar` does not exist →
3. `/usr/share/vassal/lib/xz.jar` is a **dangling symlink** →
4. the JVM **silently ignores** a `Class-Path` entry it cannot resolve →
5. nothing complains until the class is first needed, i.e. opening a module.

Step 4 is what makes this hard to read: there is no warning at startup, no "jar not found", just
a `NoClassDefFoundError` deep in a stack trace with no hint that a *package* is missing.

## 5. Why it is `xz` and nothing else

`libxz-java` is the **only new dependency in 3.8.0**. Every other Java library the package needs
was already required by 3.7.x, so on any machine upgrading from 3.7 they are all present
already and only this one is absent.

Measured on a machine with `vassal 3.7.30` installed, against the 3.8.0 package's 22
`lib*-java` dependencies:

```
libasm-java ... libxmlgraphics-commons-java      21 installed
libxz-java                                        *** MISSING ***
```

Reproduced by taking a VASSAL built from source and removing only `xz` from the classpath:

```
WITH xz     : module opened OK (Europa Nomonhan)
WITHOUT xz  : ClassNotFoundException: org.tukaani.xz.FilterOptions
              first VASSAL frame: VASSAL.build.GameModule.initGameState
```

— the same class, from the same method, as the reported stack.

## 6. The cure, and how to avoid it

**If you already hit it:**

```bash
sudo apt install libxz-java
```

or, equivalently, let apt repair the half-satisfied install:

```bash
sudo apt --fix-broken install
```

Nothing needs reinstalling or reconfiguring afterwards — the symlink the package already placed
starts resolving as soon as the library is there.

**To avoid it in the first place: install a downloaded `.deb` with `apt`, not `dpkg`.**

```bash
sudo apt install ./vassal_3.8.0-1_all.deb      # resolves and installs dependencies
```

rather than

```bash
sudo dpkg -i vassal_3.8.0-1_all.deb            # does NOT fetch dependencies
```

`dpkg -i` installs only the file you hand it. It will report unmet dependencies, but it leaves
the package on the system in that state, and the result starts and looks healthy until the
missing class is touched. Note the path matters: `apt install vassal_3.8.0-1_all.deb` is read as
a *package name*; the leading `./` is what tells apt it is a file.

Graphical installers vary — some wrap `apt` and resolve dependencies, some wrap `dpkg` and do
not. If a module fails to open after a graphical install, running the `apt --fix-broken` line
above costs nothing and rules this out.

This will catch anyone upgrading 3.7 → 3.8 with `dpkg -i`, which is a habit a lot of people
have precisely because it has always worked for VASSAL before: until 3.8.0 the dependency list
had not gained anything new in a long time, so a `dpkg -i` upgrade happened to be safe.

## 7. Suggestions for upstream

None of this is a code defect, but the failure is needlessly opaque. Cheap improvements, in
rough order of value:

- **Say so in the release notes and in `debian/README.Debian`** — that 3.8.0 adds a dependency
  on `libxz-java`, and that a downloaded `.deb` should be installed with `apt install ./…`.
  Neither `CHANGES` nor `README.Debian` currently mentions it.
- **Fail legibly.** A check at launch for the classes the engine cannot run without, reporting
  "the XZ for Java library is missing — install libxz-java" instead of letting it surface as a
  `NoClassDefFoundError`, would turn a bug report into a one-line fix by the user.
- A startup warning for any `Class-Path` entry that does not resolve would catch this and the
  next one of its kind.

#
# Makefile for the VASSAL Extension Utility.
#
# For use on Linux systems. Modelled on ../vassal/Makefile.
#
# Native Linux packages (deb, rpm) are built with the system JDK's jpackage,
# which bundles a jlink runtime automatically. Cross-building Windows (.exe via
# Launch4j) and macOS (.dmg via libdmg-hfsplus) packages needs extra tools and
# per-platform JDKs; run `make bootstrap` (or dist/bootstrap.sh) once to fetch
# them. See docs/packaging.md for the full details and tool requirements.
#

SHELL:=/bin/bash

# =======================================================================
# Version numbering  (mirrors ../vassal/Makefile)
# =======================================================================

# The numeric version — the single source of truth. Bump this for a release.
VNUM:=1.0.1
# major.minor part
V_MAJ_MIN:=$(shell echo "$(VNUM)" | cut -f1,2 -d'.')
# four-part form required by the Windows .exe version resource
NUMVERSION4:=$(VNUM).0

# The Maven/pom version. Use -SNAPSHOT between releases; plain VNUM to release.
MAVEN_VERSION:=$(VNUM)
#MAVEN_VERSION:=$(VNUM)-SNAPSHOT

# Full, unique build version derived from git (used in artifact filenames):
#   - on a release tag matching MAVEN_VERSION -> just the version
#   - on a release-* branch                   -> version + commit
#   - anywhere else                           -> version + commit + branch
GITBRANCH:=$(subst /,_,$(shell git rev-parse --abbrev-ref HEAD 2>/dev/null))
GITCOMMIT:=$(shell git rev-parse --short HEAD 2>/dev/null)
ifeq ($(shell git describe --tags 2>/dev/null),$(MAVEN_VERSION))
  VERSION:=$(MAVEN_VERSION)
else ifeq ($(patsubst release-%,release,$(GITBRANCH)),release)
  VERSION:=$(MAVEN_VERSION)-$(GITCOMMIT)
else
  VERSION:=$(MAVEN_VERSION)-$(GITCOMMIT)-$(GITBRANCH)
endif

YEAR:=$(shell date +%Y)

# =======================================================================
# Project identity / paths
# =======================================================================

ARTIFACT:=extension-utility
JARNAME:=$(ARTIFACT)-$(MAVEN_VERSION)
MAINCLASS:=org.vassalengine.extutil.Main

# Names used in the built packages
APPNAME:=VASSAL Extension Utility
PKGNAME:=vassal-extension-utility
VENDOR:=VASSAL Engine
DESCRIPTION:=Move and copy components between VASSAL modules and extensions

# Linux launcher/binary name — no spaces, so it is convenient to run and to put
# on PATH. jpackage's --name sets the launcher executable name (and the .desktop
# entry); the deb/rpm package + /opt subdir stay $(PKGNAME).
LAUNCHER:=vassal_extension_utility
# jpackage installs the app under /opt/<package-name>; the launcher is bin/<name>.
LINK_SRC:=/opt/$(PKGNAME)/bin/$(LAUNCHER)
LINK_DST:=/usr/bin/$(LAUNCHER)

MVN:=./mvnw
DISTJAR:=target/$(JARNAME)-jar-with-dependencies.jar

DISTDIR:=dist
TMPDIR:=tmp
TOOLDIR:=$(DISTDIR)/tools
JDKDIR:=$(DISTDIR)/jdks

# JVM modules the runtime must contain (Swing, XML/DOM, logging for logback).
APP_MODULES:=java.base,java.desktop,java.xml,java.naming,java.logging,java.sql

# JDK used for native packaging: must ship jmods AND jpackage, so jpackage's
# internal jlink can build a runtime image. Auto-detect the first such JDK;
# override with `make JPACKAGE_JDK=/path/to/jdk`.
JPACKAGE_JDK?=$(shell for d in /usr/lib/jvm/*/ ; do \
  [ -d "$${d}jmods" ] && [ -x "$${d}bin/jpackage" ] && echo "$${d%/}"; done | sort -V | tail -1)
JPACKAGE:=$(JPACKAGE_JDK)/bin/jpackage
# Cross-linking a Windows/macOS runtime requires a host jlink whose Java feature
# version EXACTLY matches the target JDK's jmods (jlink refuses a mismatch, e.g.
# "jlink version 21.0 does not match target java.base version 17.0"). Targets use
# different versions — Windows 32-bit tops out at Java 17 (no newer 32-bit build)
# while everything else uses 21 — so we resolve a jlink per version. These must
# match the versions bootstrap downloads (see JDK_MAIN / JDK_WIN32 in bootstrap.sh).
JDK_MAIN_VER:=21
JDK_WIN32_VER:=17

# Resolve a Linux host jlink of a given Java feature version: prefer one
# bootstrapped under dist/jdks/linux-x86_64-<ver>, else a system JDK of that
# version. Empty if none is available (the build rule then errors with guidance).
find_host_jlink=$(or $(wildcard $(JDKDIR)/linux-x86_64-$(1)/bin/jlink),$(shell \
  for d in /usr/lib/jvm/*/ ; do [ -x "$${d}bin/jlink" ] && \
    [ "$$($${d}bin/jlink --version 2>/dev/null | cut -d. -f1)" = "$(1)" ] && \
    { echo "$${d}bin/jlink"; break; }; done))

JLINK_MAIN:=$(call find_host_jlink,$(JDK_MAIN_VER))
JLINK_WIN32:=$(call find_host_jlink,$(JDK_WIN32_VER))

# Cross-build tools (populated by `make bootstrap`)
LAUNCH4J_JAR:=$(TOOLDIR)/launch4j/launch4j.jar
LAUNCH4J:=java -jar $(LAUNCH4J_JAR)
DMG:=$(TOOLDIR)/libdmg-hfsplus/build/dmg/dmg
GENISOIMAGE:=genisoimage

# Note: no --strip-debug — when cross-linking, its native-symbol stripping runs
# the host objcopy against target-platform binaries (e.g. macOS Mach-O), which
# fails. --compress=2 (not zip-6) is used because it is accepted by every jlink
# version we use as a host: the newer zip-N form is JDK 21+ only, whereas the
# 32-bit Windows target requires a Java 17 host jlink.
JLINK_OPTS:=--no-header-files --no-man-pages --compress=2 \
            --add-modules $(APP_MODULES)

# =======================================================================
# Common targets
# =======================================================================

.DEFAULT_GOAL:=help

help:
	@echo "VASSAL Extension Utility — available targets:"
	@echo ""
	@echo "  Build:"
	@echo "    compile / build   Compile Java sources"
	@echo "    test              Run unit tests"
	@echo "    jar               Build the executable fat JAR"
	@echo "    run               Run the application"
	@echo "    javadoc           Generate Javadoc"
	@echo "    clean             Remove build artefacts"
	@echo ""
	@echo "  Version:"
	@echo "    version-print     Print the full build version ($(VERSION))"
	@echo "    version-set       Set the Maven/pom version to $(MAVEN_VERSION)"
	@echo "    version-bump      Bump the patch version by 0.0.1 (Makefile + pom)"
	@echo "    post-release      Re-apply the Maven version after a release"
	@echo ""
	@echo "  Packages (output in $(TMPDIR)/):"
	@echo "    bootstrap             Fetch Windows/macOS cross-build tools + JDKs"
	@echo "    release-linux-deb     Linux .deb        (jpackage)"
	@echo "    release-linux-rpm     Linux .rpm        (jpackage; needs rpmbuild)"
	@echo "    release-windows       Windows .exe, all three architectures (Launch4j)"
	@echo "    release-windows-x86_64 / -aarch64 / -x86_32"
	@echo "    release-macos         macOS .dmg, both architectures (libdmg-hfsplus)"
	@echo "    release-macos-x86_64 / -aarch64"
	@echo "    release-sha256        SHA-256 checksums of all packages"
	@echo "    release               Everything above (deb, rpm, windows, macos)"
	@echo "    clean-release         Remove built packages"
	@echo ""
	@echo "  See docs/packaging.md for prerequisites and details."

build: compile

compile:
	$(MVN) compile

test:
	$(MVN) test

jar: $(DISTJAR)

$(DISTJAR):
	$(MVN) package

run: $(DISTJAR)
	java -jar $(DISTJAR)

javadoc:
	$(MVN) javadoc:javadoc

$(TMPDIR):
	mkdir -p $@

# =======================================================================
# Version management
# =======================================================================

version-print:
	@echo $(VERSION)

# alias kept for backwards compatibility
version: version-print

version-set:
	$(MVN) versions:set -DnewVersion=$(MAVEN_VERSION) -DgenerateBackupPoms=false

# Bump the patch component of VNUM (e.g. 1.0.0 -> 1.0.1), rewriting VNUM in this
# Makefile and setting the pom version to match so the build stays consistent.
# (The new value is computed in the shell because make expands $(VNUM) once, at
# parse time — a plain dependency on version-set would use the old value.)
version-bump:
	@new=$$(echo "$(VNUM)" | awk -F. 'BEGIN{OFS="."} {$$NF=$$NF+1; print}') ; \
	echo "Bumping version: $(VNUM) -> $$new" ; \
	sed -i -E "s/^VNUM:=.*/VNUM:=$$new/" Makefile ; \
	$(MVN) -q versions:set -DnewVersion=$$new -DgenerateBackupPoms=false ; \
	echo "Updated Makefile VNUM and pom.xml to $$new"

post-release: version-set

# =======================================================================
# Cross-build tooling
# =======================================================================

bootstrap:
	$(DISTDIR)/bootstrap.sh

# =======================================================================
# Linux — deb / rpm  (jpackage bundles a runtime automatically)
# =======================================================================

# Common jpackage arguments for the Linux installers.
JPACKAGE_COMMON=--input $(TMPDIR)/jpackage-input \
                --main-jar $(notdir $(DISTJAR)) \
                --main-class $(MAINCLASS) \
                --name $(LAUNCHER) \
                --app-version $(VNUM) \
                --vendor "$(VENDOR)" \
                --description "$(DESCRIPTION)" \
                --dest $(TMPDIR) \
                --resource-dir $(TMPDIR)/jpackage-res \
                --linux-package-name $(PKGNAME) \
                --linux-app-category utils \
                --linux-menu-group "Game;Utility"

$(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)): $(DISTJAR) | $(TMPDIR)
	rm -rf $(TMPDIR)/jpackage-input
	mkdir -p $(TMPDIR)/jpackage-input
	cp $(DISTJAR) $(TMPDIR)/jpackage-input/

# Package maintainer scripts that symlink the launcher into /usr/bin so it is on
# the user's PATH. We take jpackage's OWN templates (from the packaging JDK) and
# inject the symlink after the desktop-install/uninstall markers, so the result
# stays correct across JDK versions and keeps jpackage's default behaviour. The
# symlink is removed on uninstall only if it still points at our launcher.
$(TMPDIR)/jpackage-res: | $(TMPDIR)
	@command -v unzip >/dev/null || { echo "unzip is required to build the Linux package scripts"; exit 1; }
	rm -rf $@ && mkdir -p $@/tpl
	@# a .jmod has a 4-byte magic prefix before the zip, so unzip extracts fine
	@# but exits non-zero with a warning — tolerate it, then verify the files.
	cd $@/tpl && unzip -o -q -j "$(JPACKAGE_JDK)/jmods/jdk.jpackage.jmod" \
	    'classes/jdk/jpackage/internal/resources/template.postinst' \
	    'classes/jdk/jpackage/internal/resources/template.prerm' \
	    'classes/jdk/jpackage/internal/resources/template.spec' >/dev/null 2>&1 || true
	@for f in template.postinst template.prerm template.spec ; do \
	    [ -f $@/tpl/$$f ] || { echo "Failed to extract $$f from jdk.jpackage.jmod"; exit 1; }; done
	sed '/DESKTOP_COMMANDS_INSTALL/a ln -sf "$(LINK_SRC)" "$(LINK_DST)"' \
	    $@/tpl/template.postinst > $@/postinst
	sed '/DESKTOP_COMMANDS_UNINSTALL/a [ "$$(readlink "$(LINK_DST)" 2>/dev/null)" = "$(LINK_SRC)" ] && rm -f "$(LINK_DST)" || true' \
	    $@/tpl/template.prerm > $@/prerm
	@# jpackage names the .spec resource after the PACKAGE name (--linux-package-name),
	@# not the launcher/app name.
	sed -e '/DESKTOP_COMMANDS_INSTALL/a ln -sf "$(LINK_SRC)" "$(LINK_DST)"' \
	    -e '/DESKTOP_COMMANDS_UNINSTALL/a [ "$$(readlink "$(LINK_DST)" 2>/dev/null)" = "$(LINK_SRC)" ] && rm -f "$(LINK_DST)" || true' \
	    $@/tpl/template.spec > $@/$(PKGNAME).spec
	rm -rf $@/tpl

release-linux-deb: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)) $(TMPDIR)/jpackage-res
	@[ -x "$(JPACKAGE)" ] || { echo "jpackage not found (set JPACKAGE_JDK); see docs/packaging.md"; exit 1; }
	rm -f $(TMPDIR)/$(PKGNAME)_*.deb
	"$(JPACKAGE)" --type deb $(JPACKAGE_COMMON)
	@ls -1 $(TMPDIR)/*.deb

release-linux-rpm: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)) $(TMPDIR)/jpackage-res
	@[ -x "$(JPACKAGE)" ] || { echo "jpackage not found (set JPACKAGE_JDK); see docs/packaging.md"; exit 1; }
	@command -v rpmbuild >/dev/null || { echo "rpmbuild not found — install the 'rpm' package (see docs/packaging.md)"; exit 1; }
	rm -f $(TMPDIR)/$(PKGNAME)-*.rpm
	"$(JPACKAGE)" --type rpm $(JPACKAGE_COMMON)
	@ls -1 $(TMPDIR)/*.rpm

release-linux: release-linux-deb release-linux-rpm

# =======================================================================
# Windows — .exe via Launch4j, one per architecture
# =======================================================================
# Each build directory holds the wrapped VASSAL-Extension-Utility.exe plus a
# jlink runtime (jre/) built from that architecture's Windows JDK, then zipped.

# jlink a Windows runtime for the given arch from its bootstrapped JDK, using a
# host jlink whose version matches that arch's JDK (32-bit = Java 17, else 21).
$(TMPDIR)/windows-%-build/jre: | $(TMPDIR)
	@[ -d $(JDKDIR)/windows-$* ] || { echo "Missing $(JDKDIR)/windows-$* — run 'make bootstrap'"; exit 1; }
	@jlink="$(JLINK_MAIN)"; ver=$(JDK_MAIN_VER); \
	  [ "$*" = "x86_32" ] && { jlink="$(JLINK_WIN32)"; ver=$(JDK_WIN32_VER); }; \
	  [ -n "$$jlink" ] && [ -x "$$jlink" ] || { \
	    echo "No host jlink for Java $$ver (needed to link windows-$*). Run 'make bootstrap'."; exit 1; }; \
	  rm -rf $@; mkdir -p $(TMPDIR)/windows-$*-build; \
	  echo "$$jlink --module-path $(JDKDIR)/windows-$*/jmods --add-modules $(APP_MODULES) --output $@"; \
	  "$$jlink" --module-path $(JDKDIR)/windows-$*/jmods $(JLINK_OPTS) --output $@

# generate the Launch4j config and wrap the JAR into VASSAL-Extension-Utility.exe
$(TMPDIR)/windows-%-build/VASSAL-Extension-Utility.exe: $(DISTJAR) $(DISTDIR)/windows/launch4j.xml.in
	mkdir -p $(TMPDIR)/windows-$*-build
	cp $(DISTJAR) $(TMPDIR)/windows-$*-build/
	sed -e 's|@JAR@|$(CURDIR)/$(TMPDIR)/windows-$*-build/$(notdir $(DISTJAR))|g' \
	    -e 's|@OUTFILE@|$(CURDIR)/$@|g' \
	    -e 's|@OUTNAME@|VASSAL-Extension-Utility.exe|g' \
	    -e 's|@JREPATH@|jre|g' \
	    -e 's|@NUMVERSION4@|$(NUMVERSION4)|g' \
	    -e 's|@VERSION@|$(VERSION)|g' \
	    $(DISTDIR)/windows/launch4j.xml.in > $(TMPDIR)/windows-$*-build/launch4j.xml
	$(LAUNCH4J) $(CURDIR)/$(TMPDIR)/windows-$*-build/launch4j.xml

$(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-windows-%.zip: \
		$(TMPDIR)/windows-%-build/VASSAL-Extension-Utility.exe \
		$(TMPDIR)/windows-%-build/jre
	cp -a CHANGES.md LICENSE README.md $(TMPDIR)/windows-$*-build/ 2>/dev/null || true
	rm -f $@
	pushd $(TMPDIR)/windows-$*-build >/dev/null ; \
	  zip -9rq $(CURDIR)/$@ VASSAL-Extension-Utility.exe jre $(notdir $(DISTJAR)) \
	           README.md LICENSE CHANGES.md 2>/dev/null ; \
	  popd >/dev/null
	@echo "built $@"

release-windows-x86_64:  $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-windows-x86_64.zip
release-windows-aarch64: $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-windows-aarch64.zip
release-windows-x86_32:  $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-windows-x86_32.zip

release-windows: release-windows-x86_64 release-windows-aarch64 release-windows-x86_32

# =======================================================================
# macOS — .dmg via genisoimage + libdmg-hfsplus, one per architecture
# =======================================================================

# Lay out the disk-image staging dir for the given arch: a "VASSAL Extension
# Utility.app" bundle (with a jlink runtime) plus an /Applications symlink.
# The bundle name contains spaces, so it is only ever created inside the recipe
# — the Make target itself (the space-free "image" directory) must not.
APPDIRNAME:=VASSAL Extension Utility.app

$(TMPDIR)/macos-%-build/image: $(DISTJAR) \
		$(DISTDIR)/macos/Info.plist.in $(DISTDIR)/macos/run.sh.in $(DISTDIR)/macos/PkgInfo
	@[ -d $(JDKDIR)/macos-$* ] || { echo "Missing $(JDKDIR)/macos-$* — run 'make bootstrap'"; exit 1; }
	@[ -n "$(JLINK_MAIN)" ] && [ -x "$(JLINK_MAIN)" ] || { \
	    echo "No host jlink for Java $(JDK_MAIN_VER) (needed to link macos-$*). Run 'make bootstrap'."; exit 1; }
	rm -rf "$@"
	mkdir -p "$@/$(APPDIRNAME)/Contents/MacOS" "$@/$(APPDIRNAME)/Contents/Resources/Java"
	sed -e 's|@NUMVERSION@|$(VNUM)|g' $(DISTDIR)/macos/Info.plist.in \
	    > "$@/$(APPDIRNAME)/Contents/Info.plist"
	cp $(DISTDIR)/macos/PkgInfo "$@/$(APPDIRNAME)/Contents/PkgInfo"
	sed -e 's|@JARFILE@|$(notdir $(DISTJAR))|g' $(DISTDIR)/macos/run.sh.in \
	    > "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	chmod 755 "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	cp $(DISTJAR) "$@/$(APPDIRNAME)/Contents/Resources/Java/"
	"$(JLINK_MAIN)" --module-path $(JDKDIR)/macos-$*/jmods $(JLINK_OPTS) \
	    --output "$@/$(APPDIRNAME)/Contents/MacOS/jre"
	ln -sf /Applications "$@/Applications"

$(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-macos-%-uncompressed.iso: $(TMPDIR)/macos-%-build/image
	$(GENISOIMAGE) -V "VASSAL Ext Util" -D -R -apple -no-pad -quiet -o $@ "$<"

$(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-macos-%.dmg: \
		$(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-macos-%-uncompressed.iso
	@[ -x "$(DMG)" ] || { echo "dmg tool missing — run 'make bootstrap'"; exit 1; }
	rm -f $@
	$(DMG) $< $@
	@echo "built $@"

release-macos-x86_64:  $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-macos-x86_64.dmg
release-macos-aarch64: $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION)-macos-aarch64.dmg

release-macos: release-macos-x86_64 release-macos-aarch64

# =======================================================================
# Aggregate / checksums / clean
# =======================================================================

release: release-linux release-windows release-macos

release-sha256: | $(TMPDIR)
	pushd $(TMPDIR) >/dev/null ; \
	  sha256sum *.deb *.rpm *-windows-*.zip *-macos-*.dmg 2>/dev/null \
	    > VASSAL-Extension-Utility-$(VERSION).sha256 || true ; \
	  popd >/dev/null
	@echo "wrote $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION).sha256"
	@cat $(TMPDIR)/VASSAL-Extension-Utility-$(VERSION).sha256 2>/dev/null || true

clean-release:
	$(RM) -r $(TMPDIR)

clean: clean-release
	$(MVN) clean

# prevents make from deleting intermediate files (jre/, .app, .iso)
.SECONDARY:

.PHONY: help build compile test jar run javadoc clean clean-release \
        version version-print version-set version-bump post-release bootstrap \
        release release-linux release-linux-deb release-linux-rpm \
        release-windows release-windows-x86_64 release-windows-aarch64 release-windows-x86_32 \
        release-macos release-macos-x86_64 release-macos-aarch64 release-sha256

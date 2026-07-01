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
VNUM:=1.0.0
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
# jlink for cross-linking Windows/macOS runtimes from the target platform's
# jmods. Must be at least as new as the bootstrapped JDKs (21), so pick the
# highest-version host jlink available. Override with `make JLINK_JDK=...`.
JLINK_JDK?=$(shell for d in /usr/lib/jvm/*/ ; do \
  [ -x "$${d}bin/jlink" ] && echo "$${d%/}"; done | sort -V | tail -1)
JLINK:=$(JLINK_JDK)/bin/jlink

# Cross-build tools (populated by `make bootstrap`)
LAUNCH4J_JAR:=$(TOOLDIR)/launch4j/launch4j.jar
LAUNCH4J:=java -jar $(LAUNCH4J_JAR)
DMG:=$(TOOLDIR)/libdmg-hfsplus/build/dmg/dmg
GENISOIMAGE:=genisoimage

# Note: no --strip-debug — when cross-linking, its native-symbol stripping runs
# the host objcopy against target-platform binaries (e.g. macOS Mach-O), which
# fails. --compress keeps the runtime small instead.
JLINK_OPTS:=--no-header-files --no-man-pages --compress=zip-6 \
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
                --name "$(APPNAME)" \
                --app-version $(VNUM) \
                --vendor "$(VENDOR)" \
                --description "$(DESCRIPTION)" \
                --dest $(TMPDIR) \
                --linux-package-name $(PKGNAME) \
                --linux-app-category utils \
                --linux-menu-group "Game;Utility"

$(TMPDIR)/jpackage-input/$(notdir $(DISTJAR)): $(DISTJAR) | $(TMPDIR)
	rm -rf $(TMPDIR)/jpackage-input
	mkdir -p $(TMPDIR)/jpackage-input
	cp $(DISTJAR) $(TMPDIR)/jpackage-input/

release-linux-deb: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR))
	@[ -x "$(JPACKAGE)" ] || { echo "jpackage not found (set JPACKAGE_JDK); see docs/packaging.md"; exit 1; }
	rm -f $(TMPDIR)/$(PKGNAME)_*.deb
	"$(JPACKAGE)" --type deb $(JPACKAGE_COMMON)
	@ls -1 $(TMPDIR)/*.deb

release-linux-rpm: $(TMPDIR)/jpackage-input/$(notdir $(DISTJAR))
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

# jlink a Windows runtime for the given arch from its bootstrapped JDK
$(TMPDIR)/windows-%-build/jre: | $(TMPDIR)
	@[ -d $(JDKDIR)/windows-$* ] || { echo "Missing $(JDKDIR)/windows-$* — run 'make bootstrap'"; exit 1; }
	rm -rf $@
	mkdir -p $(TMPDIR)/windows-$*-build
	"$(JLINK)" --module-path $(JDKDIR)/windows-$*/jmods $(JLINK_OPTS) --output $@

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
	rm -rf "$@"
	mkdir -p "$@/$(APPDIRNAME)/Contents/MacOS" "$@/$(APPDIRNAME)/Contents/Resources/Java"
	sed -e 's|@NUMVERSION@|$(VNUM)|g' $(DISTDIR)/macos/Info.plist.in \
	    > "$@/$(APPDIRNAME)/Contents/Info.plist"
	cp $(DISTDIR)/macos/PkgInfo "$@/$(APPDIRNAME)/Contents/PkgInfo"
	sed -e 's|@JARFILE@|$(notdir $(DISTJAR))|g' $(DISTDIR)/macos/run.sh.in \
	    > "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	chmod 755 "$@/$(APPDIRNAME)/Contents/MacOS/run.sh"
	cp $(DISTJAR) "$@/$(APPDIRNAME)/Contents/Resources/Java/"
	"$(JLINK)" --module-path $(JDKDIR)/macos-$*/jmods $(JLINK_OPTS) \
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
        version version-print version-set post-release bootstrap \
        release release-linux release-linux-deb release-linux-rpm \
        release-windows release-windows-x86_64 release-windows-aarch64 release-windows-x86_32 \
        release-macos release-macos-x86_64 release-macos-aarch64 release-sha256

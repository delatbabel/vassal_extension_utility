#
# Makefile for VASSAL Extension Utility
# For use on Linux systems.
#

SHELL:=/bin/bash

VERSION:=1.0
ARTIFACT:=extension-utility
JARNAME:=$(ARTIFACT)-$(VERSION)

MVN:=./mvnw

DISTJAR:=target/$(JARNAME)-jar-with-dependencies.jar

# -----------------------------------------------------------------------
# Common targets
# -----------------------------------------------------------------------

.DEFAULT_GOAL:=help

help:
	@echo "VASSAL Extension Utility — available targets:"
	@echo ""
	@echo "  compile    Compile Java sources"
	@echo "  build      Compile Java sources"
	@echo "  test       Run unit tests"
	@echo "  jar        Build executable fat JAR ($(DISTJAR))"
	@echo "  run        Run the application"
	@echo "  javadoc    Generate Javadoc in target/site/apidocs/"
	@echo "  clean      Remove all build artefacts"
	@echo ""
	@echo "  version    Print current version"

build:  compile

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

clean:
	$(MVN) clean

version:
	@echo $(VERSION)

# -----------------------------------------------------------------------

.PHONY: help compile test jar run javadoc clean version

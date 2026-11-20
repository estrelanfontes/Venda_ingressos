#!/usr/bin/env bash
set -e
CP="out:$(find lib -name "*.jar" | tr '\n' ':')"
java -cp "$CP" ingressos.Main "$@"
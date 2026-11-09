#!/usr/bin/env bash
# Compila o projeto (Java 17+) para a pasta ./out
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir out
javac --release 17 -Xlint:all -d out $(find src -name '*.java')
echo "Compilado em ./out"

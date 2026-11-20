#!/usr/bin/env bash
set -e
CP=$(find lib -name "*.jar" | tr '\n' ':')
rm -rf out && mkdir -p out
javac -cp "$CP" -d out $(find src -name "*.java")
echo "Compilado em ./out"
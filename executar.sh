#!/usr/bin/env bash
# Menu interativo:            ./executar.sh
# Simulação sem menu:         ./executar.sh --simular 50 200
cd "$(dirname "$0")"
exec java -cp out ingressos.Main "$@"

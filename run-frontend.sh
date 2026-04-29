#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
if command -v ./mvnw &> /dev/null; then
    ./mvnw javafx:run
else
    mvn javafx:run
fi

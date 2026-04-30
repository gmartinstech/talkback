#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

if [[ ! -f "pom.xml" ]]; then
    echo "Error: pom.xml not found. Please run from the repository root."
    exit 1
fi

echo "Stopping any running Copiloto instances..."
pkill -f "Copiloto" 2>/dev/null || true

echo "Building and launching Copiloto..."
mvn compile javafx:run -q

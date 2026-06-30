#!/bin/bash

# Navigate to the directory where this script resides
cd "$(dirname "$0")"

# Explicitly assign the Homebrew OpenJDK 21 path
export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home"

if [ ! -d "$JAVA_HOME" ]; then
    echo "❌ Error: Java 21 path not found at the expected Homebrew location."
    read -p "Press Enter to exit..."
    exit 1
fi

# Locate the fat/shaded jar file
JAR=$(ls target/*-jar-with-dependencies.jar *.jar 2>/dev/null | head -1)

if [ -z "$JAR" ]; then
    echo "❌ Error: No executable JAR file found."
    read -p "Press Enter to exit..."
    exit 1
fi

echo "🚀 Starting Email Audit Engine..."
echo "Forcing Java 21 Runtime from: $JAVA_HOME"
echo "Targeting JAR: $JAR"
echo "----------------------------------------"

# Run the app normally. The proxy launcher bypasses the module check entirely.
"$JAVA_HOME/bin/java" -Dglass.platform=Mac -jar "$JAR"
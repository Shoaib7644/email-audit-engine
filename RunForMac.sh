#!/bin/bash

cd "$(dirname "$0")"

JAR=$(ls *.jar | head -1)

echo "Starting Email Audit Engine..."
echo "Using JAR: $JAR"

java -jar "$JAR"
#!/usr/bin/env sh
MVNW_PATH=".mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$MVNW_PATH" ]; then
  echo "Downloading Maven Wrapper..."
  mkdir -p .mvn/wrapper
  curl -fsSL -o "$MVNW_PATH" https://repo.maven.apache.org/maven2/io/takari/maven-wrapper/0.5.6/maven-wrapper-0.5.6.jar || true
fi
java -cp "$MVNW_PATH" -Dmaven.multiModuleProjectDirectory=.   org.apache.maven.wrapper.MavenWrapperMain "$@"

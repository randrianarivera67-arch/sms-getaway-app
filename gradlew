#!/bin/sh
cd "$(dirname "$0")"
exec java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"

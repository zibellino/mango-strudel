#!/usr/bin/env sh
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME=`pwd -P`
MAX_FD="maximum"
warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }
if [ "$1" = "--stop" ] ; then exit 0; fi
GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
if $darwin && [ "$TERM" = "dumb" ] ; then GRADLE_OPTS="$GRADLE_OPTS \"-Dorg.gradle.native=false\""; fi
exec "$JAVACMD" "$@"

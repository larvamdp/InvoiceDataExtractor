#!/usr/bin/env bash
# Ejecuta Maven dentro de Docker (no requiere JDK local).
# MVN_DOCKER_ARGS opcional: argumentos extra para `docker run` (p. ej. "--network host").
mkdir -p "$HOME/.m2"
# shellcheck disable=SC2086
exec docker run --rm -u "$(id -u):$(id -g)" ${MVN_DOCKER_ARGS:-} \
  -v "$PWD":/app -v "$HOME/.m2":/var/maven/.m2 -e MAVEN_CONFIG=/var/maven/.m2 \
  -w /app maven:3.9-eclipse-temurin-21 mvn -Duser.home=/var/maven "$@"

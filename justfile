set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

engine := "analysis-engine"
renderer := "renderer"

default: list

list:
    @just --list

build-engine:
    cd {{engine}} && ./gradlew build

test-engine:
    cd {{engine}} && ./gradlew test

build-renderer:
    cd {{renderer}} && ./gradlew build :web:jsBrowserDistribution

test-renderer:
    cd {{renderer}} && ./gradlew :shared:jvmTest :server:test

test: test-engine test-renderer

test-lsp:
    cd {{engine}} && ./gradlew :lsp:test

lint:
    cd {{engine}} && ./gradlew ktlintCheck
    cd {{renderer}} && ./gradlew ktlintCheck

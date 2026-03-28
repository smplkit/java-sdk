.PHONY: install generate

install:
	# Gradle manages its own dependencies; ensure wrapper is executable
	chmod +x gradlew

generate:
	./scripts/generate.sh

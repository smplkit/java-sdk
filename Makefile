.PHONY: install generate

install:
	chmod +x gradlew
	npm install -g @openapitools/openapi-generator-cli

generate:
	./scripts/generate.sh

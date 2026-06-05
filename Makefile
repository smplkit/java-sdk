.PHONY: install generate \
	config_runtime_showcase config_management_showcase \
	flags_runtime_showcase flags_management_showcase \
	logging_runtime_showcase logging_management_showcase \
	audit_runtime_showcase audit_management_showcase \
	jobs_management_showcase

install:
	chmod +x gradlew
	npm install -g @openapitools/openapi-generator-cli

generate:
	./scripts/generate.sh

config_runtime_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigRuntimeShowcase

config_management_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.ConfigManagementShowcase

flags_runtime_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsRuntimeShowcase

flags_management_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.FlagsManagementShowcase

logging_runtime_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingRuntimeShowcase

logging_management_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.LoggingManagementShowcase

audit_runtime_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.AuditRuntimeShowcase

audit_management_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.AuditManagementShowcase

jobs_management_showcase:
	./gradlew :examples:run -PmainClass=com.smplkit.examples.JobsManagementShowcase

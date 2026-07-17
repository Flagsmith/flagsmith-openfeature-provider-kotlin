.PHONY: install
install: ## Set up the local development environment
	@if [ ! -f local.properties ]; then \
		if [ -z "$(ANDROID_HOME)" ]; then echo "ANDROID_HOME is not set"; exit 1; fi; \
		echo "sdk.dir=$(ANDROID_HOME)" > local.properties; \
		echo "local.properties created from ANDROID_HOME."; \
	fi

.PHONY: build
build: ## Build the library
	./gradlew :provider:assembleRelease

.PHONY: test
test: ## Run unit tests
	./gradlew check -P excludeIntegrationTests $(opts)

.PHONY: test-integration
test-integration: ## Run all tests including integration
	./gradlew check $(opts)

.PHONY: coverage
coverage: ## Verify 100% line coverage
	./gradlew koverVerify koverHtmlReport

.PHONY: publish-local
publish-local: ## Publish to the local Maven repository
	./gradlew :provider:publishToMavenLocal

.PHONY: clean
clean: ## Remove build outputs
	./gradlew clean

help:
	@echo "Usage: make [target]"
	@echo ""
	@echo "Available targets:"
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  \033[36m%-30s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

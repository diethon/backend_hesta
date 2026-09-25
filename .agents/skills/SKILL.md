Create a repository-local Codex agent skill for this Java Spring Boot backend.

Create:

.agents/skills/hesta-backend/SKILL.md

The skill name is:

hesta-backend

Purpose:

Guide Codex when implementing backend features for the HESTA smart-home platform.

Before writing the skill, inspect the repository and identify:

* Java version
* Spring Boot version
* Maven or Gradle
* package structure
* entity conventions
* repository conventions
* service conventions
* controller conventions
* DTO conventions
* exception handling
* Spring Security configuration
* database technology
* migration technology such as Flyway or Liquibase
* test framework and existing test patterns

Do not modify application code for this task.

The SKILL.md must contain repository-compatible instructions covering the following rules.

# Stack and scope

* This is a Java Spring Boot backend.
* Existing Java, Spring Boot, dependency, database, build-tool, API, and repository conventions are authoritative.
* Do not introduce unrelated libraries, frameworks, architecture changes, or dependency upgrades.
* Preserve existing API contracts unless the task explicitly changes them.
* Keep modifications scoped to the requested feature.
* Preserve unrelated user changes.


# Architecture

Follow the repository's existing architecture.

When compatible with the repository, use these responsibility boundaries:

* Controller: HTTP transport only.
* Service/application layer: business logic and orchestration.
* Repository: persistence operations.
* Entity/domain: persistent/domain state.
* DTO: request and response API contracts.
* Mapper: DTO/entity conversion when the project already uses mappers.

Rules:
* Do not create a trigger for database
* Do not put significant business logic in controllers.
* Do not put business orchestration in repositories.
* Prefer constructor injection.
* Do not use field injection.
* Put transactional boundaries in the service/application layer.
* Avoid circular service dependencies.

# API behavior

* Preserve existing endpoints, HTTP methods, payloads, response formats, and status codes unless explicitly requested.
* Do not expose JPA entities directly if the existing project uses DTOs.
* Use Bean Validation for request validation where appropriate.
* Use business validation in services for cross-entity rules.
* Use the existing global exception-handling mechanism.
* Do not leak stack traces, database errors, secrets, or internal implementation details.

# Persistence

If Spring Data JPA is used:

* Follow existing JpaRepository patterns.
* Prefer derived queries or JPQL when sufficient.
* Avoid unnecessary native SQL.
* Avoid accidental N+1 queries.
* Avoid unnecessary EAGER relationships.
* Do not serialize lazy JPA proxies into API responses.

Database changes:

* Use the migration tool already configured in the repository.
* Do not rely on Hibernate schema auto-generation as a replacement for migrations unless the repository explicitly does so.
* Add foreign keys, unique constraints, indexes, and nullability intentionally.
* Do not rewrite old production migrations unless repository policy allows it.

# Home-scoped domain integrity

HESTА resources frequently belong to a Home.

For Home-scoped operations:

* Never trust client-provided homeId or ownership relationships without checking persisted data.
* Referenced resources must exist.
* A referenced Device must belong to the expected Home.
* Resource existence alone is not enough.
* Reject cross-Home references explicitly.
* Apply backend authorization independently from frontend role checks.

Example invariant:

scene.home.id must equal device.home.id

Do not validate this by comparing untrusted request values only.

# Scene domain

A Scene belongs to one Home.

Scene contains at minimum:

* id
* homeId / Home relationship
* name
* description
* enabled or status
* timestamps according to repository convention

A Scene can contain multiple SceneAction records.

SceneAction contains at minimum:

* id
* sceneId / Scene relationship
* targetDeviceId / Device relationship
* action
* value when required
* order
* timestamps according to repository convention

Business rules:

* One Scene can contain many actions.
* Actions must have deterministic order.
* Prefer uniqueness of sceneId + order unless requirements state otherwise.
* A SceneAction cannot reference a Device from another Home.
* Creating or updating SceneAction must validate:

  * Scene exists.
  * Device exists.
  * Device belongs to the same Home as Scene.
  * action configuration is valid.
  * order is valid.
* Deleting a Scene must not leave orphan SceneAction records.
* Scene persistence must not depend on MQTT.
* Scene entities must not reference MQTT clients, topics, brokers, or transport classes.
* SceneAction represents an intended device command independent of transport.
* Future Sprint 2 execution should be able to map SceneAction to DeviceCommandService.

For Sprint 1 Scene tasks, do not implement real MQTT/device execution unless explicitly requested.

# Automation boundaries

Automation and Scene domain logic must remain independent from MQTT transport.

When a feature integrates with device execution:

* Use the existing DeviceCommandService contract.
* Do not duplicate DeviceCommandService responsibilities.
* Use mocks or fakes in tests when real device execution is outside task scope.
* Do not require a live MQTT broker for ordinary unit tests.

# Security

* Preserve existing Spring Security behavior.
* Do not weaken authorization rules to make tests or development easier.
* Backend authorization is authoritative.
* Never rely solely on frontend roles.
* Distinguish platform roles and Home roles if both exist.
* Do not log passwords, access tokens, refresh tokens, authorization headers, secrets, or private keys.
* Do not hardcode secrets, database credentials, MQTT credentials, client IDs, or environment-specific URLs.

# Transactions

Use @Transactional where multiple persistence operations form one business operation.

Examples:

* Creating Scene and multiple SceneActions should be atomic.
* Replacing/reordering actions should not leave partial state after an error.
* Do not put transaction boundaries in controllers.

# Repository rules

Repository classes/interfaces are for persistence.

For Scene functionality, repository behavior may include:

* findById
* findAllByHomeId
* find SceneAction by scene ordered by order ascending
* CRUD operations

Use project naming conventions instead of forcing these exact method names.

Avoid repository APIs that bypass Home scope without a business need.

# Validation

Reject invalid input clearly.

Validate where applicable:

* required fields
* enum values
* order values
* duplicated order
* referenced entities
* Home ownership
* action/value compatibility
* update conflicts

Use domain-specific exceptions consistent with the repository.

Do not catch Exception broadly unless translating it at an application boundary.

# Logging

Use the repository's existing logging framework.

Do not use System.out.println.

Useful identifiers can be logged when safe:

* sceneId
* homeId
* deviceId
* executionId

Never log sensitive credentials or tokens.

# Testing

Use the test stack already configured by the repository.

Do not introduce another test framework unnecessarily.

Prefer the narrowest meaningful test level.

For Scene work, cover at minimum:

* create Scene
* read Scene
* update Scene
* delete Scene
* create multiple SceneActions
* actions are returned in deterministic order
* valid Device from same Home is accepted
* Device from another Home is rejected
* nonexistent Device is rejected
* invalid action/order is rejected
* delete behavior does not leave unintended orphan actions

If Testcontainers or repository integration-test infrastructure already exists, follow it.

Do not claim tests were executed unless they were actually executed.

# Verification

Use the repository's existing build wrapper.

For Maven, prefer ./mvnw when present.

Possible commands:

./mvnw test
./mvnw verify

For Gradle, prefer ./gradlew when present.

Possible commands:

./gradlew test
./gradlew build

Determine the actual command from the repository.

Run focused tests first when useful.

Report pre-existing failures separately.

Do not disable:

* tests
* validation
* compiler checks
* Checkstyle
* SpotBugs
* PMD
* quality gates

just to make the build pass.

# Agent workflow

For every implementation task:

1. Read applicable AGENTS.md files.
2. Read this skill.
3. Inspect nearby implementation before creating new patterns.
4. Identify existing conventions.
5. Check API/database contracts before changing them.
6. Make the smallest coherent change.
7. Add or update relevant tests.
8. Run permitted verification commands.
9. Review git diff.
10. Report:

* files changed
* behavior implemented
* tests/commands actually run
* failures
* anything not verified

For proposal-only, analysis-only, or read-only tasks:

* do not modify files
* do not run artifact-producing commands
* do not generate migrations
* do not run formatters that modify files

# Code quality

* Use clear Java naming.
* Prefer small focused methods.
* Prefer final injected dependencies.
* Avoid static mutable state.
* Avoid unnecessary abstraction.
* Avoid speculative refactoring.
* Follow SOLID principles pragmatically.
* Reuse existing utilities and patterns.
* Do not overengineer Sprint 1 features for hypothetical future requirements.

After creating the file:

1. Print the resulting SKILL.md.
2. Explain which repository conventions were discovered and incorporated.
3. Run git diff -- .agents/skills/hesta-backend/SKILL.md.
4. Do not modify any other file.

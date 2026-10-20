# HESTA Backend

## Collaboration and responses

- Respond in Vietnamese by default and keep technical names, code, file names, and API identifiers in English where appropriate.
- Lead with the result or direct answer. Be concise, relevant, and avoid repeating the request.
- If the request is clear, proceed without asking for confirmation. State any small assumption you make.
- After code changes, report the outcome, key files changed, checks actually run, and any remaining issue. Never claim an unrun test passed.
- For review-only or explanation-only requests, do not modify files.

## Project baseline

- Use the Java and Spring Boot versions declared in `pom.xml` (currently Java 21 and Spring Boot 3.4.3).
- Keep dependencies managed by the Spring Boot parent unless the library is not covered by its BOM.
- Follow applicable instructions in `.agents/skills/`; this file contains only HESTA-specific constraints.

## Architecture and API contracts

- Preserve the package-by-layer structure rooted at `com.hesta.backend`.
- Keep domain service interfaces in `service` and implementations in `service.impl`. Do not introduce a new architectural layer during unrelated work.
- Keep APIs under `/api/v1`. Put typed input and output models in `dto.request` and `dto.response`; do not extend the existing `Map<String, Object>` or raw `Object` response patterns.
- Preserve the `ApiResponse<T>` contract (`code`, `message`, `result`) and success code `1000`. Do not introduce another response envelope or RFC 9457 without an explicit API migration request.
- Represent expected failures with `AppException` and `ErrorCode`. New validation message keys must match an `ErrorCode` constant so `GlobalExceptionHandler` can resolve them.

## Security

- Preserve stateless Bearer JWT authentication and obtain the current user through `CustomUserDetails`; never trust a caller-supplied user identity or role.
- Keep platform roles (`ADMIN`, `USER`) separate from per-home roles (`OWNER`, `MEMBER`) and enforce the relevant boundary for every protected operation.
- Never add, log, or expose passwords, tokens, API keys, OAuth credentials, or credential-bearing defaults. Configuration changes must use environment-backed values.

## Persistence and database

- PostgreSQL schema changes belong exclusively in timestamped files under `supabase/migrations/`. Do not add Flyway migrations or enable Flyway.
- Never edit a migration already applied outside an isolated local environment; add a forward-only migration instead.
- Treat `DATABASE_MIGRATION_GUIDE.md` and the actual Supabase migrations as authoritative where older documentation conflicts.
- Keep migrations and JPA mappings aligned. Preserve Hibernate schema validation and disabled Open Session in View.
- Never change Hibernate `ddl-auto` to `create`, `create-drop`, or `update` as a substitute for a proper Supabase migration.
- Do not run `supabase db push` against Supabase Cloud; that is reserved for the release owner. Automated tests must not use the shared Cloud database.

## Verification and naming

- Follow the existing type suffixes and Java naming conventions; match neighboring Lombok style rather than performing unrelated repository-wide refactors.
- Add or update JUnit 5 tests for changed behavior and ensure service unit tests provide every constructor dependency.
- Run relevant targeted tests first, then the Maven test suite when a safe local/test datasource is configured. Report any verification that could not be run.

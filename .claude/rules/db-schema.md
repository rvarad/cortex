---
paths:
  - "cortex-ingestion/src/main/resources/db/migration/**"
  - "cortex-common/src/main/java/**/model/*.java"
---

# Schema changes

`cortex-ingestion` owns every Flyway migration — `src/main/resources/db/migration/`,
currently `V1`–`V9`. It owns them even for tables it never reads. There is no
second migration directory anywhere in the repo.

All three JPA services run `spring.jpa.hibernate.ddl-auto=validate`. Nothing
auto-creates or auto-alters a column, in any environment.

## What that means in practice

Adding or changing a field on an entity in `cortex-common/**/model/` without a
matching migration doesn't fail where you made the change. It fails at **startup**,
in `cortex-ingestion`, `cortex-media-processing-service` and
`cortex-rag-orchestration` — all three, including the two you didn't touch, with a
schema-validation error naming a column you've never heard of.

So: entity change and migration are one change. Same commit.

## The trap `mvn verify` will not catch

`cortex-media-processing-service/src/test/resources/application.properties` sets
`ddl-auto=create-drop`, so its tests build a schema from the entities and pass
regardless of whether a migration exists. A green `mvn -B verify` is **not**
evidence that the migration is there.

The check is starting the service against a real database:
`docker compose up -d postgres` then run it.

## Rules for the migrations themselves

- **Applied migrations are immutable.** Never edit `V1`–`V9` or renumber them.
  Flyway checksums them; an edited file fails on the next startup against any
  database that already ran it. A change to an existing table is a new version.
- Next version number is `max + 1`, no gaps.
- Name them `V<n>__snake_case_description.sql` — two underscores after the number.
- Production runs `baseline-on-migrate=true` with `baseline-version=1`, so a
  database that predates Flyway is assumed to be at V1. Don't assume a fresh
  database has run V1's contents.
